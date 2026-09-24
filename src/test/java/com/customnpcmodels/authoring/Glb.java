/*
 * Copyright (c) 2026, AJD
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.customnpcmodels.authoring;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * The binary glTF container: a 12-byte header, a JSON chunk and a BIN chunk, all little-endian.
 *
 * <p>Also the one place accessors are decoded, because that is where every constraint on how the
 * bytes are laid out has to be enforced. Anything the reader would otherwise misread - a sparse
 * accessor, an interleaved view, a component type it does not handle, a second buffer - is refused
 * by name.
 */
final class Glb
{
	private static final int MAGIC = 0x46546C67;       // "glTF"
	private static final int VERSION = 2;
	private static final int CHUNK_JSON = 0x4E4F534A;  // "JSON"
	private static final int CHUNK_BIN = 0x004E4942;   // "BIN\0"

	static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	final Gltf gltf;
	private final ByteBuffer bin;

	Glb(Gltf gltf, byte[] bin)
	{
		this.gltf = gltf;
		this.bin = ByteBuffer.wrap(bin == null ? new byte[0] : bin).order(ByteOrder.LITTLE_ENDIAN);
	}

	/** The BIN chunk's bytes, for re-writing a document after editing its JSON. */
	byte[] binBytes()
	{
		return bin.array();
	}

	static byte[] write(Gltf gltf, byte[] bin)
	{
		byte[] json = pad(GSON.toJson(gltf).getBytes(StandardCharsets.UTF_8), (byte) ' ');
		byte[] body = pad(bin, (byte) 0);

		int length = 12 + 8 + json.length + (body.length > 0 ? 8 + body.length : 0);
		ByteBuffer out = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
		out.putInt(MAGIC).putInt(VERSION).putInt(length);
		out.putInt(json.length).putInt(CHUNK_JSON).put(json);
		if (body.length > 0)
		{
			out.putInt(body.length).putInt(CHUNK_BIN).put(body);
		}
		return out.array();
	}

	static Glb read(byte[] data)
	{
		ByteBuffer in = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
		if (data.length < 20 || in.getInt() != MAGIC)
		{
			throw new GltfException("Not a binary glTF (.glb) file - export with the glTF Binary format");
		}

		int version = in.getInt();
		if (version != VERSION)
		{
			throw new GltfException("glTF container version " + version + "; only 2 is read");
		}

		int length = in.getInt();
		if (length > data.length)
		{
			throw new GltfException("The .glb says it is " + length + " bytes but the file is " + data.length);
		}

		int jsonLength = in.getInt();
		if (in.getInt() != CHUNK_JSON)
		{
			throw new GltfException("The .glb does not start with a JSON chunk");
		}
		String json = new String(data, in.position(), jsonLength, StandardCharsets.UTF_8);
		in.position(in.position() + jsonLength);

		byte[] bin = null;
		if (in.position() + 8 <= length)
		{
			int binLength = in.getInt();
			if (in.getInt() == CHUNK_BIN)
			{
				bin = Arrays.copyOfRange(data, in.position(), in.position() + binLength);
			}
		}

		Gltf gltf = GSON.fromJson(json, Gltf.class);
		if (gltf == null || gltf.asset == null || gltf.asset.version == null || !gltf.asset.version.startsWith("2."))
		{
			throw new GltfException("Not a glTF 2.0 document");
		}
		if (gltf.extensionsRequired != null && !gltf.extensionsRequired.isEmpty())
		{
			throw new GltfException("The file requires extensions " + gltf.extensionsRequired
				+ ", which this pipeline does not read - export without compression or quantization");
		}
		if (gltf.buffers != null)
		{
			if (gltf.buffers.size() > 1)
			{
				throw new GltfException("The file has " + gltf.buffers.size() + " buffers; only the .glb's own is read");
			}
			if (!gltf.buffers.isEmpty() && gltf.buffers.get(0).uri != null)
			{
				throw new GltfException("The file's buffer is external (" + gltf.buffers.get(0).uri
					+ "); export as a single .glb");
			}
		}
		return new Glb(gltf, bin);
	}

	/** The accessor flattened to doubles, integer components normalized when the accessor says so. */
	double[] readDoubles(int accessorIndex)
	{
		Gltf.Accessor accessor = accessor(accessorIndex);
		int components = Gltf.components(accessor.type);
		double[] values = new double[accessor.count * components];
		boolean normalized = Boolean.TRUE.equals(accessor.normalized);
		visit(accessorIndex, (i, raw) -> values[i] = normalized ? normalize(accessor.componentType, raw) : raw);
		return values;
	}

	/** The accessor flattened to ints. Refuses floats that are not whole numbers. */
	int[] readInts(int accessorIndex)
	{
		Gltf.Accessor accessor = accessor(accessorIndex);
		int[] values = new int[accessor.count * Gltf.components(accessor.type)];
		visit(accessorIndex, (i, raw) ->
		{
			if (raw != Math.rint(raw))
			{
				throw new GltfException("Accessor " + accessorIndex + " holds " + raw + " where an integer was expected");
			}
			values[i] = (int) raw;
		});
		return values;
	}

	Gltf.Accessor accessor(int index)
	{
		if (gltf.accessors == null || index < 0 || index >= gltf.accessors.size())
		{
			throw new GltfException("Accessor " + index + " does not exist");
		}
		return gltf.accessors.get(index);
	}

	private interface Visitor
	{
		void accept(int index, double raw);
	}

	private void visit(int accessorIndex, Visitor visitor)
	{
		Gltf.Accessor accessor = accessor(accessorIndex);
		if (accessor.sparse != null)
		{
			throw new GltfException("Accessor " + accessorIndex + " is sparse; export without sparse accessors");
		}
		if (accessor.bufferView == null)
		{
			throw new GltfException("Accessor " + accessorIndex + " has no buffer view");
		}

		int componentSize = Gltf.componentSize(accessor.componentType);
		if (componentSize < 0)
		{
			throw new GltfException("Accessor " + accessorIndex + " has unsupported component type "
				+ accessor.componentType);
		}

		int components = Gltf.components(accessor.type);
		int elementSize = componentSize * components;

		Gltf.BufferView view = gltf.bufferViews.get(accessor.bufferView);
		if (view.byteStride != null && view.byteStride != elementSize)
		{
			throw new GltfException("Buffer view " + accessor.bufferView + " is interleaved (stride "
				+ view.byteStride + " for " + elementSize + "-byte elements); export without interleaving");
		}

		int start = (view.byteOffset == null ? 0 : view.byteOffset)
			+ (accessor.byteOffset == null ? 0 : accessor.byteOffset);
		int end = start + accessor.count * elementSize;
		if (end > (view.byteOffset == null ? 0 : view.byteOffset) + view.byteLength || end > bin.capacity())
		{
			throw new GltfException("Accessor " + accessorIndex + " runs past the end of its buffer view");
		}

		int n = accessor.count * components;
		for (int i = 0; i < n; i++)
		{
			int at = start + i * componentSize;
			double raw;
			switch (accessor.componentType)
			{
				case Gltf.BYTE:
					raw = bin.get(at);
					break;
				case Gltf.UNSIGNED_BYTE:
					raw = bin.get(at) & 0xFF;
					break;
				case Gltf.SHORT:
					raw = bin.getShort(at);
					break;
				case Gltf.UNSIGNED_SHORT:
					raw = bin.getShort(at) & 0xFFFF;
					break;
				case Gltf.UNSIGNED_INT:
					raw = bin.getInt(at) & 0xFFFFFFFFL;
					break;
				default:
					raw = bin.getFloat(at);
					break;
			}
			visitor.accept(i, raw);
		}
	}

	private static double normalize(int componentType, double raw)
	{
		switch (componentType)
		{
			case Gltf.BYTE:
				return Math.max(raw / 127.0, -1.0);
			case Gltf.UNSIGNED_BYTE:
				return raw / 255.0;
			case Gltf.SHORT:
				return Math.max(raw / 32767.0, -1.0);
			case Gltf.UNSIGNED_SHORT:
				return raw / 65535.0;
			default:
				return raw;
		}
	}

	private static byte[] pad(byte[] bytes, byte filler)
	{
		if (bytes == null)
		{
			return new byte[0];
		}
		int padded = (bytes.length + 3) & ~3;
		if (padded == bytes.length)
		{
			return bytes;
		}
		byte[] out = Arrays.copyOf(bytes, padded);
		Arrays.fill(out, bytes.length, padded, filler);
		return out;
	}

	/**
	 * Accumulates the BIN chunk while a document is written, one buffer view and accessor per call.
	 */
	static final class BinBuilder
	{
		private final Gltf gltf;
		private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		BinBuilder(Gltf gltf)
		{
			this.gltf = gltf;
		}

		int floats(double[] values, String type, Integer target, boolean bounds)
		{
			ByteBuffer buffer = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
			for (double value : values)
			{
				buffer.putFloat((float) value);
			}

			Gltf.Accessor accessor = accessor(buffer.array(), Gltf.FLOAT, values.length, type, target);
			if (bounds)
			{
				int components = Gltf.components(type);
				accessor.min = new double[components];
				accessor.max = new double[components];
				Arrays.fill(accessor.min, Double.POSITIVE_INFINITY);
				Arrays.fill(accessor.max, Double.NEGATIVE_INFINITY);
				for (int i = 0; i < values.length; i++)
				{
					// Bounds of the float actually stored, not the double it came from
					double stored = (float) values[i];
					accessor.min[i % components] = Math.min(accessor.min[i % components], stored);
					accessor.max[i % components] = Math.max(accessor.max[i % components], stored);
				}
			}
			return gltf.accessors.size() - 1;
		}

		int unsignedShorts(int[] values, String type, Integer target)
		{
			ByteBuffer buffer = ByteBuffer.allocate(values.length * 2).order(ByteOrder.LITTLE_ENDIAN);
			for (int value : values)
			{
				if (value < 0 || value > 0xFFFF)
				{
					throw new GltfException("Value " + value + " does not fit an unsigned short");
				}
				buffer.putShort((short) value);
			}
			accessor(buffer.array(), Gltf.UNSIGNED_SHORT, values.length, type, target);
			return gltf.accessors.size() - 1;
		}

		int unsignedInts(int[] values, String type, Integer target)
		{
			ByteBuffer buffer = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
			for (int value : values)
			{
				buffer.putInt(value);
			}
			accessor(buffer.array(), Gltf.UNSIGNED_INT, values.length, type, target);
			return gltf.accessors.size() - 1;
		}

		private Gltf.Accessor accessor(byte[] data, int componentType, int values, String type, Integer target)
		{
			// Every view starts 4-aligned, which covers every component size used here
			while (bytes.size() % 4 != 0)
			{
				bytes.write(0);
			}

			Gltf.BufferView view = new Gltf.BufferView();
			view.buffer = 0;
			view.byteOffset = bytes.size();
			view.byteLength = data.length;
			view.target = target;
			gltf.bufferViews.add(view);
			bytes.write(data, 0, data.length);

			Gltf.Accessor accessor = new Gltf.Accessor();
			accessor.bufferView = gltf.bufferViews.size() - 1;
			accessor.componentType = componentType;
			accessor.count = values / Gltf.components(type);
			accessor.type = type;
			gltf.accessors.add(accessor);
			return accessor;
		}

		byte[] finish()
		{
			while (bytes.size() % 4 != 0)
			{
				bytes.write(0);
			}
			byte[] bin = bytes.toByteArray();
			Gltf.Buffer buffer = new Gltf.Buffer();
			buffer.byteLength = bin.length;
			gltf.buffers.clear();
			gltf.buffers.add(buffer);
			return bin;
		}
	}
}
