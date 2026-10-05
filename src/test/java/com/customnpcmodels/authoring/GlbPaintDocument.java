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

import com.customnpcmodels.inject.Mesh;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A {@code .glb} open for face painting: one engine HSL color per face, and a save that changes
 * nothing but those colors.
 *
 * <h2>Faces</h2>
 *
 * Face {@code n} is the converter's face {@code n} - the file is walked in
 * {@link GltfToMeshConverter#meshNodes} order - and its starting color is the one the converter
 * gives it, so what the painter shows is exactly what the game would draw. Each mesh node's faces
 * are one run of that numbering, listed by {@link #parts} so the painter can hide them.
 *
 * <h2>Saving</h2>
 *
 * The document is edited as a JSON tree rather than through {@link Gltf}, so materials, extras and
 * anything else Blender wrote survive. Only primitives with a repainted face are rewritten:
 * <ul>
 *   <li>Every attribute is unwelded to three vertices per face, copied element by element in its
 *       own encoding, with any vertex no face uses kept after the corners. Blender merges identical
 *       corners, so without this a face could share a vertex with its neighbor and painting it would
 *       bleed across.</li>
 *   <li>A repainted face's corners get the new {@code COLOR_0} in that accessor's encoding, alpha
 *       untouched, and the matching {@code _RS_HSL}, so the converter takes the color exactly.</li>
 *   <li>Rewritten accessors keep their indices, so the skin and animations still point at the right
 *       data, and the BIN chunk is rebuilt with every buffer view that is still used.</li>
 * </ul>
 * A save is converted back before it is returned, and refused if any face comes back a different
 * color, so a bad save never reaches the disk.
 */
final class GlbPaintDocument
{
	private static final String COLOR_0 = "COLOR_0";

	/** One primitive's triangles and where its faces start in the document's numbering. */
	private static final class Slot
	{
		int mesh;
		int primitive;
		int firstFace;
		int faces;
		int vertexCount;
		/** Per corner, the vertex it names, in file winding. */
		int[] indices;
	}

	private final Glb glb;
	private final JsonObject json;
	private final List<Slot> slots = new ArrayList<>();
	private final List<MeshPart> parts = new ArrayList<>();
	private final Mesh mesh;
	private final short[] originalColors;
	private final short[] colors;

	private GlbPaintDocument(byte[] data)
	{
		glb = Glb.read(data);
		json = Glb.readJson(data);
		mesh = GltfToMeshConverter.convert(data, 0, 0, Collections.emptyMap()).mesh;
		originalColors = mesh.getFaceColors().clone();
		colors = originalColors.clone();

		Gltf gltf = glb.gltf;
		Set<Integer> meshesSeen = new HashSet<>();
		int face = 0;
		for (int node : GltfToMeshConverter.meshNodes(gltf))
		{
			int meshIndex = gltf.nodes.get(node).mesh;
			if (!meshesSeen.add(meshIndex))
			{
				throw new GltfException("Mesh " + meshIndex + " is used by more than one node, so painting a face "
					+ "would paint every copy; make the copies single-user in Blender");
			}

			int partFirstFace = face;
			JsonArray primitives = meshJson(meshIndex).getAsJsonArray("primitives");
			for (int p = 0; p < primitives.size(); p++)
			{
				Slot slot = readSlot(meshIndex, p, primitives.get(p).getAsJsonObject());
				slot.firstFace = face;
				face += slot.faces;
				slots.add(slot);
			}

			String name = gltf.nodes.get(node).name != null ? gltf.nodes.get(node).name
				: gltf.meshes.get(meshIndex).name != null ? gltf.meshes.get(meshIndex).name
				: "Part " + (parts.size() + 1);
			parts.add(new MeshPart(name, partFirstFace, face - partFirstFace));
		}

		if (face != mesh.getFaceCount())
		{
			throw new GltfException("The file has " + face + " triangles but converts to " + mesh.getFaceCount()
				+ " faces; the painter cannot tell which face is which");
		}
		checkAccessorsUnshared();
	}

	/** Opens a file for painting, refusing anything whose save could not keep every face in place. */
	static GlbPaintDocument load(byte[] data)
	{
		return new GlbPaintDocument(data);
	}

	int faceCount()
	{
		return colors.length;
	}

	/**
	 * The file's meshes, one per node in face order, each named after its node - for an exported
	 * multi-model NPC, one per model.
	 */
	List<MeshPart> parts()
	{
		return Collections.unmodifiableList(parts);
	}

	short color(int face)
	{
		return colors[face];
	}

	void paint(int face, short hsl)
	{
		colors[face] = hsl;
	}

	/** Whether any face differs from the file as loaded. */
	boolean isDirty()
	{
		return !Arrays.equals(colors, originalColors);
	}

	/**
	 * The geometry as the plugin sees it: engine units, welded, one face per document face. Its face
	 * colors are the file's as loaded; the current ones are {@link #color}.
	 */
	Mesh mesh()
	{
		return mesh;
	}

	/** The file with the current colors, verified to convert back to exactly them. */
	byte[] save()
	{
		JsonObject out = json.deepCopy();
		JsonArray accessors = out.getAsJsonArray("accessors");

		// Accessor index to its new element bytes; indices past the current end are appended
		Map<Integer, byte[]> newData = new LinkedHashMap<>();
		Map<Integer, Integer> newTargets = new HashMap<>();

		for (Slot slot : slots)
		{
			if (!repainted(slot))
			{
				continue;
			}
			rewrite(out, slot, accessors, newData, newTargets);
		}

		byte[] bin = rebuildBin(out, newData, newTargets);
		byte[] saved = Glb.write(out, bin);

		short[] back = GltfToMeshConverter.convert(saved, 0, 0, Collections.emptyMap()).mesh.getFaceColors();
		for (int face = 0; face < colors.length; face++)
		{
			if (back[face] != colors[face])
			{
				throw new GltfException("Save check failed: face " + face + " would come back as color "
					+ (back[face] & 0xFFFF) + " instead of " + (colors[face] & 0xFFFF) + "; nothing was saved");
			}
		}
		return saved;
	}

	// --- Loading --------------------------------------------------------------------------------

	private JsonObject meshJson(int meshIndex)
	{
		return json.getAsJsonArray("meshes").get(meshIndex).getAsJsonObject();
	}

	private Slot readSlot(int meshIndex, int primitiveIndex, JsonObject primitive)
	{
		if (primitive.has("targets"))
		{
			throw new GltfException("Mesh " + meshIndex + " has shape keys (morph targets); the painter "
				+ "cannot keep them in step - apply or delete them in Blender");
		}
		if (primitive.has("mode") && primitive.get("mode").getAsInt() != Gltf.TRIANGLES)
		{
			throw new GltfException("A primitive uses draw mode " + primitive.get("mode").getAsInt()
				+ "; only triangles are painted");
		}

		JsonObject attributes = primitive.getAsJsonObject("attributes");
		for (Map.Entry<String, JsonElement> attribute : attributes.entrySet())
		{
			int accessor = attribute.getValue().getAsInt();
			// Decoding it runs Glb's checks: no sparse data, no interleaving, known component types
			glb.readDoubles(accessor);
			if (attribute.getKey().equals(COLOR_0))
			{
				Gltf.Accessor color = glb.accessor(accessor);
				boolean normalized = Boolean.TRUE.equals(color.normalized);
				if (!(color.componentType == Gltf.FLOAT
					|| normalized && (color.componentType == Gltf.UNSIGNED_BYTE || color.componentType == Gltf.UNSIGNED_SHORT)))
				{
					throw new GltfException("COLOR_0 is stored as component type " + color.componentType
						+ ", which glTF does not allow for colors");
				}
			}
		}

		Slot slot = new Slot();
		slot.mesh = meshIndex;
		slot.primitive = primitiveIndex;
		slot.vertexCount = glb.accessor(attributes.get("POSITION").getAsInt()).count;
		if (primitive.has("indices"))
		{
			slot.indices = glb.readInts(primitive.get("indices").getAsInt());
		}
		else
		{
			slot.indices = new int[slot.vertexCount];
			for (int i = 0; i < slot.indices.length; i++)
			{
				slot.indices[i] = i;
			}
		}
		slot.faces = slot.indices.length / 3;
		return slot;
	}

	/**
	 * Refuses a file where an accessor this document may rewrite is also read from anywhere else,
	 * since rewriting it in place would change that other user too.
	 */
	private void checkAccessorsUnshared()
	{
		Map<Integer, Integer> uses = new HashMap<>();
		for (JsonElement meshElement : json.getAsJsonArray("meshes"))
		{
			for (JsonElement primitiveElement : meshElement.getAsJsonObject().getAsJsonArray("primitives"))
			{
				JsonObject primitive = primitiveElement.getAsJsonObject();
				for (Map.Entry<String, JsonElement> attribute : primitive.getAsJsonObject("attributes").entrySet())
				{
					uses.merge(attribute.getValue().getAsInt(), 1, Integer::sum);
				}
				if (primitive.has("indices"))
				{
					uses.merge(primitive.get("indices").getAsInt(), 1, Integer::sum);
				}
			}
		}
		if (json.has("skins"))
		{
			for (JsonElement skin : json.getAsJsonArray("skins"))
			{
				if (skin.getAsJsonObject().has("inverseBindMatrices"))
				{
					uses.merge(skin.getAsJsonObject().get("inverseBindMatrices").getAsInt(), 1, Integer::sum);
				}
			}
		}
		if (json.has("animations"))
		{
			for (JsonElement animation : json.getAsJsonArray("animations"))
			{
				for (JsonElement sampler : animation.getAsJsonObject().getAsJsonArray("samplers"))
				{
					uses.merge(sampler.getAsJsonObject().get("input").getAsInt(), 1, Integer::sum);
					uses.merge(sampler.getAsJsonObject().get("output").getAsInt(), 1, Integer::sum);
				}
			}
		}

		for (Slot slot : slots)
		{
			JsonObject primitive = primitiveJson(json, slot);
			List<Integer> own = new ArrayList<>();
			for (Map.Entry<String, JsonElement> attribute : primitive.getAsJsonObject("attributes").entrySet())
			{
				own.add(attribute.getValue().getAsInt());
			}
			if (primitive.has("indices"))
			{
				own.add(primitive.get("indices").getAsInt());
			}
			for (int accessor : own)
			{
				if (uses.getOrDefault(accessor, 0) > 1)
				{
					throw new GltfException("Accessor " + accessor + " is shared between several uses, which the "
						+ "painter cannot rewrite safely; re-export the file from Blender");
				}
			}
		}
	}

	private static JsonObject primitiveJson(JsonObject root, Slot slot)
	{
		return root.getAsJsonArray("meshes").get(slot.mesh).getAsJsonObject()
			.getAsJsonArray("primitives").get(slot.primitive).getAsJsonObject();
	}

	// --- Saving ---------------------------------------------------------------------------------

	private boolean repainted(Slot slot)
	{
		for (int face = slot.firstFace; face < slot.firstFace + slot.faces; face++)
		{
			if (colors[face] != originalColors[face])
			{
				return true;
			}
		}
		return false;
	}

	/** Unwelds the slot's primitive and writes its new colors, recording each new accessor's bytes. */
	private void rewrite(JsonObject out, Slot slot, JsonArray accessors, Map<Integer, byte[]> newData,
		Map<Integer, Integer> newTargets)
	{
		// The corners in triangle order, then every vertex no triangle uses
		int corners = slot.faces * 3;
		boolean[] used = new boolean[slot.vertexCount];
		for (int i = 0; i < corners; i++)
		{
			used[slot.indices[i]] = true;
		}
		List<Integer> order = new ArrayList<>(corners);
		for (int i = 0; i < corners; i++)
		{
			order.add(slot.indices[i]);
		}
		for (int vertex = 0; vertex < slot.vertexCount; vertex++)
		{
			if (!used[vertex])
			{
				order.add(vertex);
			}
		}
		int count = order.size();

		JsonObject primitive = primitiveJson(out, slot);
		JsonObject attributes = primitive.getAsJsonObject("attributes");

		for (Map.Entry<String, JsonElement> attribute : new ArrayList<>(attributes.entrySet()))
		{
			String name = attribute.getKey();
			int index = attribute.getValue().getAsInt();
			if (name.equals(GlbWriter.RS_HSL))
			{
				continue;
			}

			Gltf.Accessor accessor = glb.accessor(index);
			byte[] data = unweld(accessor, order);
			if (name.equals(COLOR_0))
			{
				writeColors(data, accessor, slot);
			}

			JsonObject accessorJson = accessors.get(index).getAsJsonObject();
			accessorJson.addProperty("count", count);
			accessorJson.remove("byteOffset");
			if (name.equals(COLOR_0))
			{
				// Optional for colors, and stale once any has changed
				accessorJson.remove("min");
				accessorJson.remove("max");
			}
			newData.put(index, data);
			newTargets.put(index, Gltf.ARRAY_BUFFER);
		}

		if (!attributes.has(COLOR_0))
		{
			attributes.addProperty(COLOR_0, append(accessors, newData, newTargets,
				floatAccessor(count, "VEC4"), freshColors(slot, count)));
		}

		// _RS_HSL is always rewritten as floats: it must agree with every repainted corner
		double[] hsl = new double[count];
		int[] oldHsl = attributes.has(GlbWriter.RS_HSL) ? glb.readInts(attributes.get(GlbWriter.RS_HSL).getAsInt()) : null;
		for (int i = 0; i < count; i++)
		{
			int face = i < corners ? slot.firstFace + i / 3 : -1;
			if (face != -1 && (oldHsl == null || colors[face] != originalColors[face]))
			{
				hsl[i] = colors[face] & 0xFFFF;
			}
			else
			{
				hsl[i] = oldHsl == null ? 0 : oldHsl[order.get(i)];
			}
		}
		byte[] hslData = floats(hsl);
		if (attributes.has(GlbWriter.RS_HSL))
		{
			int index = attributes.get(GlbWriter.RS_HSL).getAsInt();
			accessors.set(index, floatAccessor(count, "SCALAR"));
			newData.put(index, hslData);
			newTargets.put(index, Gltf.ARRAY_BUFFER);
		}
		else
		{
			attributes.addProperty(GlbWriter.RS_HSL, append(accessors, newData, newTargets,
				floatAccessor(count, "SCALAR"), hslData));
		}

		// Sequential indices over the corners; the loose vertices stay unreferenced
		boolean shorts = corners <= 0xFFFF;
		ByteBuffer indexData = ByteBuffer.allocate(corners * (shorts ? 2 : 4)).order(ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < corners; i++)
		{
			if (shorts)
			{
				indexData.putShort((short) i);
			}
			else
			{
				indexData.putInt(i);
			}
		}
		JsonObject indexAccessor = new JsonObject();
		indexAccessor.addProperty("componentType", shorts ? Gltf.UNSIGNED_SHORT : Gltf.UNSIGNED_INT);
		indexAccessor.addProperty("count", corners);
		indexAccessor.addProperty("type", "SCALAR");
		if (primitive.has("indices"))
		{
			int index = primitive.get("indices").getAsInt();
			accessors.set(index, indexAccessor);
			newData.put(index, indexData.array());
			newTargets.put(index, Gltf.ELEMENT_ARRAY_BUFFER);
		}
		else
		{
			primitive.addProperty("indices", append(accessors, newData, newTargets, indexAccessor, indexData.array()));
			newTargets.put(primitive.get("indices").getAsInt(), Gltf.ELEMENT_ARRAY_BUFFER);
		}
	}

	/** The accessor's elements in {@code order}, byte for byte. */
	private byte[] unweld(Gltf.Accessor accessor, List<Integer> order)
	{
		int elementSize = Gltf.componentSize(accessor.componentType) * Gltf.components(accessor.type);
		Gltf.BufferView view = glb.gltf.bufferViews.get(accessor.bufferView);
		int start = (view.byteOffset == null ? 0 : view.byteOffset) + (accessor.byteOffset == null ? 0 : accessor.byteOffset);

		byte[] source = glb.binBytes();
		byte[] data = new byte[order.size() * elementSize];
		for (int i = 0; i < order.size(); i++)
		{
			System.arraycopy(source, start + order.get(i) * elementSize, data, i * elementSize, elementSize);
		}
		return data;
	}

	/** Overwrites RGB on every corner of every repainted face, in the accessor's own encoding. */
	private void writeColors(byte[] data, Gltf.Accessor accessor, Slot slot)
	{
		int componentSize = Gltf.componentSize(accessor.componentType);
		int components = Gltf.components(accessor.type);
		ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

		for (int f = 0; f < slot.faces; f++)
		{
			int face = slot.firstFace + f;
			if (colors[face] == originalColors[face])
			{
				continue;
			}
			double[] linear = linear(colors[face]);
			for (int k = 0; k < 3; k++)
			{
				int element = f * 3 + k;
				for (int c = 0; c < 3; c++)
				{
					int at = (element * components + c) * componentSize;
					switch (accessor.componentType)
					{
						case Gltf.UNSIGNED_BYTE:
							buffer.put(at, (byte) Math.round(linear[c] * 255));
							break;
						case Gltf.UNSIGNED_SHORT:
							buffer.putShort(at, (short) Math.round(linear[c] * 65535));
							break;
						default:
							buffer.putFloat(at, (float) linear[c]);
							break;
					}
				}
			}
		}
	}

	/** A COLOR_0 for a primitive that had none: every face its current color, fully opaque. */
	private byte[] freshColors(Slot slot, int count)
	{
		double[] values = new double[count * 4];
		for (int i = 0; i < count; i++)
		{
			short hsl = i < slot.faces * 3 ? colors[slot.firstFace + i / 3] : RsColor.rgbToHsl(0x808080);
			double[] linear = linear(hsl);
			System.arraycopy(linear, 0, values, i * 4, 3);
			values[i * 4 + 3] = 1;
		}
		return floats(values);
	}

	private static double[] linear(short hsl)
	{
		return RsColor.hslToLinear(hsl & 0xFFFF);
	}

	private static byte[] floats(double[] values)
	{
		return Glb.floatBytes(values);
	}

	private static JsonObject floatAccessor(int count, String type)
	{
		JsonObject accessor = new JsonObject();
		accessor.addProperty("componentType", Gltf.FLOAT);
		accessor.addProperty("count", count);
		accessor.addProperty("type", type);
		return accessor;
	}

	private static int append(JsonArray accessors, Map<Integer, byte[]> newData, Map<Integer, Integer> newTargets,
		JsonObject accessor, byte[] data)
	{
		accessors.add(accessor);
		int index = accessors.size() - 1;
		newData.put(index, data);
		newTargets.put(index, Gltf.ARRAY_BUFFER);
		return index;
	}

	/**
	 * Lays out the BIN chunk again: every buffer view still in use, byte for byte and at its old
	 * alignment, then one new view per rewritten accessor.
	 */
	private byte[] rebuildBin(JsonObject out, Map<Integer, byte[]> newData, Map<Integer, Integer> newTargets)
	{
		JsonArray accessors = out.getAsJsonArray("accessors");
		JsonArray oldViews = out.has("bufferViews") ? out.getAsJsonArray("bufferViews") : new JsonArray();
		JsonArray images = out.has("images") ? out.getAsJsonArray("images") : new JsonArray();

		Set<Integer> keep = new HashSet<>();
		for (int a = 0; a < accessors.size(); a++)
		{
			JsonObject accessor = accessors.get(a).getAsJsonObject();
			if (!newData.containsKey(a) && accessor.has("bufferView"))
			{
				keep.add(accessor.get("bufferView").getAsInt());
			}
		}
		for (JsonElement image : images)
		{
			if (image.getAsJsonObject().has("bufferView"))
			{
				keep.add(image.getAsJsonObject().get("bufferView").getAsInt());
			}
		}

		byte[] source = glb.binBytes();
		ByteArrayOutputStream bin = new ByteArrayOutputStream();
		JsonArray views = new JsonArray();
		Map<Integer, Integer> viewMap = new HashMap<>();
		for (int v = 0; v < oldViews.size(); v++)
		{
			if (!keep.contains(v))
			{
				continue;
			}
			JsonObject view = oldViews.get(v).getAsJsonObject().deepCopy();
			int offset = view.has("byteOffset") ? view.get("byteOffset").getAsInt() : 0;
			int length = view.get("byteLength").getAsInt();

			// Same position modulo 4 as before, which keeps every accessor inside it aligned
			Glb.align(bin, offset % 4);
			view.addProperty("byteOffset", bin.size());
			bin.write(source, offset, length);
			viewMap.put(v, views.size());
			views.add(view);
		}

		for (Map.Entry<Integer, byte[]> entry : newData.entrySet())
		{
			Glb.align(bin, 0);
			JsonObject view = new JsonObject();
			view.addProperty("buffer", 0);
			view.addProperty("byteOffset", bin.size());
			view.addProperty("byteLength", entry.getValue().length);
			view.addProperty("target", newTargets.get(entry.getKey()));
			bin.write(entry.getValue(), 0, entry.getValue().length);

			accessors.get(entry.getKey()).getAsJsonObject().addProperty("bufferView", views.size());
			views.add(view);
		}
		Glb.align(bin, 0);

		for (int a = 0; a < accessors.size(); a++)
		{
			JsonObject accessor = accessors.get(a).getAsJsonObject();
			if (!newData.containsKey(a) && accessor.has("bufferView"))
			{
				accessor.addProperty("bufferView", viewMap.get(accessor.get("bufferView").getAsInt()));
			}
		}
		for (JsonElement image : images)
		{
			JsonObject imageJson = image.getAsJsonObject();
			if (imageJson.has("bufferView"))
			{
				imageJson.addProperty("bufferView", viewMap.get(imageJson.get("bufferView").getAsInt()));
			}
		}
		out.add("bufferViews", views);

		JsonArray buffers = out.has("buffers") ? out.getAsJsonArray("buffers") : new JsonArray();
		if (buffers.size() == 0)
		{
			buffers.add(new JsonObject());
		}
		buffers.get(0).getAsJsonObject().addProperty("byteLength", bin.size());
		out.add("buffers", buffers);
		return bin.toByteArray();
	}
}
