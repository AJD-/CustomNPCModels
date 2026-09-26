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

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The slice of the glTF 2.0 document model the pipeline reads and writes, shaped for Gson.
 *
 * <p>Field names are the glTF property names. Optional properties are boxed so an absent one stays
 * absent on the way out - Gson skips nulls - and is distinguishable on the way in.
 */
final class Gltf
{
	static final int BYTE = 5120;
	static final int UNSIGNED_BYTE = 5121;
	static final int SHORT = 5122;
	static final int UNSIGNED_SHORT = 5123;
	static final int UNSIGNED_INT = 5125;
	static final int FLOAT = 5126;

	static final int ARRAY_BUFFER = 34962;
	static final int ELEMENT_ARRAY_BUFFER = 34963;

	static final int TRIANGLES = 4;

	Asset asset = new Asset();
	Integer scene;
	List<Scene> scenes = new ArrayList<>();
	List<Node> nodes = new ArrayList<>();
	List<MeshDef> meshes = new ArrayList<>();
	List<Skin> skins;
	List<Animation> animations;
	List<Accessor> accessors = new ArrayList<>();
	List<BufferView> bufferViews = new ArrayList<>();
	List<Buffer> buffers = new ArrayList<>();
	List<String> extensionsUsed;
	List<String> extensionsRequired;

	static final class Asset
	{
		String version = "2.0";
		String generator;
	}

	static final class Scene
	{
		String name;
		List<Integer> nodes;
	}

	static final class Node
	{
		String name;
		List<Integer> children;
		Integer mesh;
		Integer skin;
		double[] translation;
		double[] rotation;
		double[] scale;
		double[] matrix;
	}

	static final class MeshDef
	{
		String name;
		List<Primitive> primitives = new ArrayList<>();
		JsonObject extras;
	}

	static final class Primitive
	{
		Map<String, Integer> attributes;
		Integer indices;
		Integer mode;
		Integer material;
		JsonObject extras;
	}

	static final class Skin
	{
		String name;
		Integer inverseBindMatrices;
		Integer skeleton;
		List<Integer> joints;
	}

	static final class Animation
	{
		String name;
		List<Channel> channels = new ArrayList<>();
		List<Sampler> samplers = new ArrayList<>();
	}

	static final class Channel
	{
		int sampler;
		Target target;
	}

	static final class Target
	{
		Integer node;
		String path;
	}

	static final class Sampler
	{
		int input;
		int output;
		String interpolation;
	}

	static final class Accessor
	{
		Integer bufferView;
		Integer byteOffset;
		int componentType;
		Boolean normalized;
		int count;
		String type;
		double[] min;
		double[] max;
		JsonObject sparse;
	}

	static final class BufferView
	{
		int buffer;
		Integer byteOffset;
		int byteLength;
		Integer byteStride;
		Integer target;
	}

	static final class Buffer
	{
		int byteLength;
		String uri;
	}

	/** Components per element for an accessor type. */
	static int components(String type)
	{
		switch (type)
		{
			case "SCALAR":
				return 1;
			case "VEC2":
				return 2;
			case "VEC3":
				return 3;
			case "VEC4":
				return 4;
			case "MAT4":
				return 16;
			default:
				throw new GltfException("Unsupported accessor type " + type);
		}
	}

	/** Bytes per component, or -1 for a component type the pipeline does not read. */
	static int componentSize(int componentType)
	{
		switch (componentType)
		{
			case BYTE:
			case UNSIGNED_BYTE:
				return 1;
			case SHORT:
			case UNSIGNED_SHORT:
				return 2;
			case UNSIGNED_INT:
			case FLOAT:
				return 4;
			default:
				return -1;
		}
	}
}
