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

import com.customnpcmodels.inject.Clip;
import com.customnpcmodels.inject.Mesh;
import com.customnpcmodels.inject.Rig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts a binary glTF into the engine's terms: a mesh, and for a skinned file a rig and one clip
 * per mapped animation.
 *
 * <h2>Geometry</h2>
 *
 * Positions come back into engine units with Y negated and triangle winding reversed to undo the
 * reflection. Split vertices are welded by {@code _RS_VERTEX} when the file still carries it and it
 * is still consistent - every copy of a vertex in the same place and on the same joint - and by
 * position and joint otherwise. Face colors come from {@code _RS_HSL} only while it still agrees
 * with {@code COLOR_0}, so recoloring in Blender wins over a stale hint. Optional face columns come
 * back null when the file has nothing for them; face textures always do, because the engine cannot
 * express real texturing from a glTF.
 *
 * <h2>Rig and animation</h2>
 *
 * The engine's rig is flat and its vertices single-bound, so each skin joint becomes a vertex group
 * - its heaviest influence per vertex, with anything material that is discarded reported - and the
 * hierarchy is expressed the engine's way: per joint, parent before child, a translate, a rotate and
 * a scale naming the joint's whole subtree, turning about a pivot naming its own group.
 *
 * <p>Each animation is sampled at the start of every frame of the live sequence it is mapped to, and
 * for each joint the transform its group must end at is computed from the glTF skinning equation.
 * The ops that get it there are derived against what the preceding ops <em>actually</em> did once
 * rounded and quantised to 1/256 of a turn, not against the ideal, so the error never compounds down
 * a limb: every group lands within one quantisation step of where the file puts it. Masks and values
 * go through {@link FrameOps}, so the clips take the shape cache clips do.
 */
final class GltfToMeshConverter
{
	private static final Pattern JOINT_NAME = Pattern.compile(Pattern.quote(GlbWriter.JOINT_PREFIX) + "(\\d+)");

	/** Weight discarded from a vertex's non-dominant joints worth reporting. */
	private static final double MATERIAL_WEIGHT = 0.01;

	/** Translate, pivot, rotate and scale, in that order, for every joint. */
	static final int TRANSFORMS_PER_JOINT = 4;

	/** How far a {@code _RS_HSL} hint's color may sit from COLOR_0 and still be trusted. */
	private static final double COLOR_TOLERANCE = 1.5 / 255;

	static final class Result
	{
		Mesh mesh;
		Rig rig;
		final List<Clip> clips = new ArrayList<>();
		final List<String> report = new ArrayList<>();
		/** Whether vertices were welded by {@code _RS_VERTEX}, so they keep the exported mesh's numbering. */
		boolean keptVertexNumbering;
	}

	private final Glb glb;
	private final Gltf gltf;
	private final Result result = new Result();

	/** Per node, its parent node, or -1. */
	private int[] nodeParents;

	private GltfToMeshConverter(Glb glb)
	{
		this.glb = glb;
		this.gltf = glb.gltf;
	}

	/**
	 * @param animations glTF animation name to the live sequence it stands in for; animations the
	 *                   file has that are not named here are ignored and reported
	 */
	static Result convert(byte[] glbBytes, int meshId, int rigId, Map<String, SequenceTiming> animations)
	{
		GltfToMeshConverter converter = new GltfToMeshConverter(Glb.read(glbBytes));
		converter.run(meshId, rigId, animations);
		return converter.result;
	}

	private void run(int meshId, int rigId, Map<String, SequenceTiming> animations)
	{
		nodeParents = nodeParents(gltf);
		List<Integer> meshNodes = meshNodes(gltf);
		if (meshNodes.isEmpty())
		{
			throw new GltfException("The file has no mesh");
		}

		if (gltf.skins != null && gltf.skins.size() > 1)
		{
			throw new GltfException("The file has " + gltf.skins.size() + " skins; the engine has one rig per "
				+ "model, so join the armatures before exporting");
		}

		Set<Integer> skins = new HashSet<>();
		for (int node : meshNodes)
		{
			skins.add(gltf.nodes.get(node).skin == null ? -1 : gltf.nodes.get(node).skin);
		}
		if (skins.size() > 1)
		{
			throw new GltfException("Some meshes are skinned and some are not; parent every mesh to the armature");
		}
		Gltf.Skin skin = skins.contains(-1) ? null : gltf.skins.get(skins.iterator().next());

		Geometry geometry = readGeometry(meshNodes, skin);
		result.mesh = buildMesh(meshId, geometry, skin);

		if (skin != null)
		{
			buildRigAndClips(rigId, skin, animations);
		}
		else if (!animations.isEmpty())
		{
			result.report.add("The file has no skin, so its animations cannot drive anything and were ignored");
		}
	}

	/** Per node, its parent node, or -1. */
	private static int[] nodeParents(Gltf gltf)
	{
		int[] nodeParents = new int[gltf.nodes.size()];
		Arrays.fill(nodeParents, -1);
		for (int node = 0; node < gltf.nodes.size(); node++)
		{
			List<Integer> children = gltf.nodes.get(node).children;
			if (children != null)
			{
				for (int child : children)
				{
					nodeParents[child] = node;
				}
			}
		}
		return nodeParents;
	}

	/**
	 * The nodes whose meshes are converted, in the order their triangles become faces. Anything that
	 * edits faces by index, such as the painter, must walk the file in this same order.
	 */
	static List<Integer> meshNodes(Gltf gltf)
	{
		List<Integer> meshNodes = new ArrayList<>();
		for (int node : sceneNodes(gltf, nodeParents(gltf)))
		{
			if (gltf.nodes.get(node).mesh != null)
			{
				meshNodes.add(node);
			}
		}
		return meshNodes;
	}

	/** Every node reachable from the default scene, or from every root when there is no scene. */
	private static List<Integer> sceneNodes(Gltf gltf, int[] nodeParents)
	{
		List<Integer> roots = new ArrayList<>();
		if (gltf.scenes != null && !gltf.scenes.isEmpty())
		{
			Gltf.Scene scene = gltf.scenes.get(gltf.scene == null ? 0 : gltf.scene);
			if (scene.nodes != null)
			{
				roots.addAll(scene.nodes);
			}
		}
		else
		{
			for (int node = 0; node < gltf.nodes.size(); node++)
			{
				if (nodeParents[node] == -1)
				{
					roots.add(node);
				}
			}
		}

		List<Integer> all = new ArrayList<>();
		List<Integer> stack = new ArrayList<>(roots);
		while (!stack.isEmpty())
		{
			int node = stack.remove(0);
			all.add(node);
			List<Integer> children = gltf.nodes.get(node).children;
			if (children != null)
			{
				stack.addAll(children);
			}
		}
		return all;
	}

	// --- Geometry -------------------------------------------------------------------------------

	/** Split (per-corner) vertex data gathered across every primitive. */
	private static final class Geometry
	{
		final List<double[]> positions = new ArrayList<>();   // engine units
		final List<double[]> colors = new ArrayList<>();      // linear RGBA, or null
		final List<Integer> rsVertex = new ArrayList<>();     // or null
		final List<Integer> rsHsl = new ArrayList<>();        // or null
		final List<Integer> groups = new ArrayList<>();       // -1 when unskinned
		final List<int[]> triangles = new ArrayList<>();      // split vertex indices, glTF winding
		final List<Integer> renderTypes = new ArrayList<>();  // per face, null entries when absent
		final List<Integer> renderPriorities = new ArrayList<>();
		boolean anyRenderTypes;
		boolean anyRenderPriorities;
		boolean transparenciesDeclared;
		Integer priority;
		int materialWeights;
		double worstDiscarded;
		boolean missingColor;
	}

	private Geometry readGeometry(List<Integer> meshNodes, Gltf.Skin skin)
	{
		Geometry geometry = new Geometry();
		int[] groupOfSlot = skin == null ? null : groupIds(skin);

		for (int node : meshNodes)
		{
			double[] world = worldAtRest(node);
			Gltf.MeshDef meshDef = gltf.meshes.get(gltf.nodes.get(node).mesh);
			for (Gltf.Primitive primitive : meshDef.primitives)
			{
				// The writer puts them on the mesh, which Blender preserves; older files had them per primitive
				JsonObject extras = primitive.extras != null ? primitive.extras : meshDef.extras;
				readPrimitive(geometry, primitive, extras, skin == null ? world : null, groupOfSlot);
			}
		}

		if (geometry.materialWeights > 0)
		{
			result.report.add(geometry.materialWeights + " vertices are weighted to more than one joint; each "
				+ "keeps only its heaviest, discarding up to " + Math.round(geometry.worstDiscarded * 100)
				+ "% of its weight - the engine binds a vertex to exactly one group");
		}
		if (geometry.missingColor)
		{
			result.report.add("Some faces have no COLOR_0 and were given a neutral grey");
		}
		return geometry;
	}

	private void readPrimitive(Geometry geometry, Gltf.Primitive primitive, JsonObject extras, double[] staticWorld,
		int[] groupOfSlot)
	{
		if (primitive.mode != null && primitive.mode != Gltf.TRIANGLES)
		{
			throw new GltfException("A primitive uses draw mode " + primitive.mode + "; only triangles are read");
		}

		Map<String, Integer> attributes = primitive.attributes;
		Integer positionAccessor = attributes.get("POSITION");
		if (positionAccessor == null)
		{
			throw new GltfException("A primitive has no POSITION");
		}

		double[] positions = glb.readDoubles(positionAccessor);
		int count = positions.length / 3;
		int base = geometry.positions.size();

		double[] colors = null;
		int colorComponents = 0;
		if (attributes.containsKey("COLOR_0"))
		{
			colors = glb.readDoubles(attributes.get("COLOR_0"));
			colorComponents = Gltf.components(glb.accessor(attributes.get("COLOR_0")).type);
		}
		int[] rsVertex = attributes.containsKey(GlbWriter.RS_VERTEX) ? glb.readInts(attributes.get(GlbWriter.RS_VERTEX)) : null;
		int[] rsHsl = attributes.containsKey(GlbWriter.RS_HSL) ? glb.readInts(attributes.get(GlbWriter.RS_HSL)) : null;

		int[] groups = null;
		if (groupOfSlot != null)
		{
			groups = dominantGroups(geometry, attributes, count, groupOfSlot);
		}

		for (int v = 0; v < count; v++)
		{
			double x = positions[v * 3];
			double y = positions[v * 3 + 1];
			double z = positions[v * 3 + 2];
			double[] gltfPoint = staticWorld == null ? new double[]{x, y, z} : Mat4.transformPoint(staticWorld, x, y, z);
			geometry.positions.add(Mat4.transformPoint(Mat4.RS_FROM_GLTF, gltfPoint[0], gltfPoint[1], gltfPoint[2]));

			if (colors != null)
			{
				double[] rgba = {colors[v * colorComponents], colors[v * colorComponents + 1],
					colors[v * colorComponents + 2], colorComponents == 4 ? colors[v * colorComponents + 3] : 1};
				geometry.colors.add(rgba);
			}
			else
			{
				geometry.colors.add(null);
			}
			geometry.rsVertex.add(rsVertex == null ? null : rsVertex[v]);
			geometry.rsHsl.add(rsHsl == null ? null : rsHsl[v]);
			geometry.groups.add(groups == null ? -1 : groups[v]);
		}

		int[] indices;
		if (primitive.indices != null)
		{
			indices = glb.readInts(primitive.indices);
		}
		else
		{
			indices = new int[count];
			for (int i = 0; i < count; i++)
			{
				indices[i] = i;
			}
		}
		if (indices.length % 3 != 0)
		{
			throw new GltfException("A primitive has " + indices.length + " indices, not a whole number of triangles");
		}

		int faces = indices.length / 3;
		for (int face = 0; face < faces; face++)
		{
			for (int k = 0; k < 3; k++)
			{
				if (indices[face * 3 + k] < 0 || indices[face * 3 + k] >= count)
				{
					throw new GltfException("A primitive index names vertex " + indices[face * 3 + k] + " of " + count);
				}
			}
			geometry.triangles.add(new int[]{base + indices[face * 3], base + indices[face * 3 + 1], base + indices[face * 3 + 2]});
		}

		readExtras(geometry, extras, faces);
	}

	/** Per vertex, the group of its heaviest joint across JOINTS_0/WEIGHTS_0 and JOINTS_1/WEIGHTS_1. */
	private int[] dominantGroups(Geometry geometry, Map<String, Integer> attributes, int count, int[] groupOfSlot)
	{
		if (!attributes.containsKey("JOINTS_0") || !attributes.containsKey("WEIGHTS_0"))
		{
			throw new GltfException("A skinned primitive has no JOINTS_0/WEIGHTS_0; every vertex needs a joint");
		}

		int[] bestSlot = new int[count];
		double[] bestWeight = new double[count];
		double[] total = new double[count];
		Arrays.fill(bestSlot, -1);

		for (int set = 0; attributes.containsKey("JOINTS_" + set); set++)
		{
			int[] joints = glb.readInts(attributes.get("JOINTS_" + set));
			double[] weights = glb.readDoubles(attributes.get("WEIGHTS_" + set));
			for (int v = 0; v < count; v++)
			{
				for (int k = 0; k < 4; k++)
				{
					double weight = weights[v * 4 + k];
					total[v] += weight;
					if (weight > bestWeight[v])
					{
						bestWeight[v] = weight;
						bestSlot[v] = joints[v * 4 + k];
					}
				}
			}
		}

		int[] groups = new int[count];
		for (int v = 0; v < count; v++)
		{
			if (bestSlot[v] < 0 || bestSlot[v] >= groupOfSlot.length)
			{
				throw new GltfException("Vertex " + v + " has no joint with any weight");
			}
			groups[v] = groupOfSlot[bestSlot[v]];

			double discarded = total[v] <= 0 ? 0 : (total[v] - bestWeight[v]) / total[v];
			if (discarded > MATERIAL_WEIGHT)
			{
				geometry.materialWeights++;
				geometry.worstDiscarded = Math.max(geometry.worstDiscarded, discarded);
			}
		}
		return groups;
	}

	private void readExtras(Geometry geometry, JsonObject extras, int faces)
	{
		int[] renderTypes = null;
		int[] renderPriorities = null;
		if (extras != null)
		{
			if (extras.has(GlbWriter.EXTRA_PRIORITY) && geometry.priority == null)
			{
				geometry.priority = extras.get(GlbWriter.EXTRA_PRIORITY).getAsInt();
			}
			if (extras.has(GlbWriter.EXTRA_TRANSPARENCIES) && extras.get(GlbWriter.EXTRA_TRANSPARENCIES).getAsBoolean())
			{
				geometry.transparenciesDeclared = true;
			}
			renderTypes = faceArray(extras, GlbWriter.EXTRA_RENDER_TYPES, faces);
			renderPriorities = faceArray(extras, GlbWriter.EXTRA_RENDER_PRIORITIES, faces);
		}

		geometry.anyRenderTypes |= renderTypes != null;
		geometry.anyRenderPriorities |= renderPriorities != null;
		for (int face = 0; face < faces; face++)
		{
			geometry.renderTypes.add(renderTypes == null ? null : renderTypes[face]);
			geometry.renderPriorities.add(renderPriorities == null ? null : renderPriorities[face]);
		}
	}

	private int[] faceArray(JsonObject extras, String key, int faces)
	{
		if (!extras.has(key))
		{
			return null;
		}

		JsonArray array = extras.getAsJsonArray(key);
		if (array.size() != faces)
		{
			result.report.add("Ignored " + key + ": it lists " + array.size() + " faces for a primitive of "
				+ faces + " - the mesh was edited after export, so the per-face values no longer line up");
			return null;
		}

		int[] values = new int[faces];
		int i = 0;
		for (JsonElement element : array)
		{
			values[i++] = element.getAsInt();
		}
		return values;
	}

	private Mesh buildMesh(int meshId, Geometry geometry, Gltf.Skin skin)
	{
		int splits = geometry.positions.size();
		int[] welded = weldByRsVertex(geometry);
		result.keptVertexNumbering = welded != null;
		if (welded == null)
		{
			welded = weldByPosition(geometry);
		}

		int vertices = 0;
		for (int v : welded)
		{
			vertices = Math.max(vertices, v + 1);
		}

		float[] vx = new float[vertices];
		float[] vy = new float[vertices];
		float[] vz = new float[vertices];
		int[] groupOf = new int[vertices];
		for (int split = 0; split < splits; split++)
		{
			double[] p = geometry.positions.get(split);
			int v = welded[split];
			vx[v] = (float) p[0];
			vy[v] = (float) p[1];
			vz[v] = (float) p[2];
			groupOf[v] = geometry.groups.get(split);
		}

		int faces = geometry.triangles.size();
		int[] i1 = new int[faces];
		int[] i2 = new int[faces];
		int[] i3 = new int[faces];
		short[] colors = new short[faces];
		byte[] transparencies = new byte[faces];
		boolean anyTransparency = geometry.transparenciesDeclared;
		int degenerate = 0;

		for (int face = 0; face < faces; face++)
		{
			int[] corners = geometry.triangles.get(face);

			// Reversed back: glTF corners (a, b, c) are the engine's (a, c, b)
			i1[face] = welded[corners[0]];
			i2[face] = welded[corners[2]];
			i3[face] = welded[corners[1]];
			if (i1[face] == i2[face] || i2[face] == i3[face] || i1[face] == i3[face])
			{
				degenerate++;
			}

			colors[face] = faceColor(geometry, corners);

			double alpha = 0;
			for (int corner : corners)
			{
				double[] rgba = geometry.colors.get(corner);
				alpha += rgba == null ? 1 : rgba[3];
			}
			int transparency = (int) Math.round((1 - alpha / 3) * 255);
			transparencies[face] = (byte) Math.max(0, Math.min(255, transparency));
			anyTransparency |= transparency != 0;
		}
		if (degenerate > 0)
		{
			result.report.add(degenerate + " faces collapsed to a line or point when their vertices were welded");
		}

		int priority = geometry.priority == null ? 0 : geometry.priority;
		byte[] renderTypes = geometry.anyRenderTypes ? faceBytes(geometry.renderTypes, 0) : null;
		byte[] renderPriorities = geometry.anyRenderPriorities ? faceBytes(geometry.renderPriorities, priority) : null;

		int[][] vertexGroups = null;
		if (skin != null)
		{
			int groupCount = 0;
			for (int group : groupOf)
			{
				groupCount = Math.max(groupCount, group + 1);
			}
			List<List<Integer>> members = new ArrayList<>();
			for (int g = 0; g < groupCount; g++)
			{
				members.add(new ArrayList<>());
			}
			for (int v = 0; v < vertices; v++)
			{
				members.get(groupOf[v]).add(v);
			}
			vertexGroups = new int[groupCount][];
			for (int g = 0; g < groupCount; g++)
			{
				vertexGroups[g] = members.get(g).stream().mapToInt(Integer::intValue).toArray();
			}
		}

		return new Mesh(meshId, priority, vx, vy, vz, i1, i2, i3, colors, renderTypes,
			anyTransparency ? transparencies : null, renderPriorities, null, null, null, null, null, vertexGroups);
	}

	/**
	 * Welds by the original vertex index the writer stored, or returns null - and says why - when the
	 * file no longer carries it faithfully: missing on some vertex, split copies that were moved apart
	 * or rebound, or indices with gaps.
	 */
	private int[] weldByRsVertex(Geometry geometry)
	{
		int splits = geometry.positions.size();
		if (splits == 0)
		{
			return null;
		}
		if (geometry.rsVertex.contains(null))
		{
			result.report.add("The file has no " + GlbWriter.RS_VERTEX + " on some vertices, so vertices were welded "
				+ "by position instead - in Blender, export with Data > Mesh > Attributes on");
			return null;
		}

		Map<Integer, Integer> firstSplit = new HashMap<>();
		int[] welded = new int[splits];
		int max = -1;
		for (int split = 0; split < splits; split++)
		{
			int vertex = geometry.rsVertex.get(split);
			welded[split] = vertex;
			max = Math.max(max, vertex);

			Integer first = firstSplit.putIfAbsent(vertex, split);
			if (first != null)
			{
				if (!Arrays.equals(geometry.positions.get(first), geometry.positions.get(split))
					|| !geometry.groups.get(first).equals(geometry.groups.get(split)))
				{
					result.report.add("Copies of original vertex " + vertex + " no longer agree on position or "
						+ "joint - the mesh was edited - so vertices were welded by position instead");
					return null;
				}
			}
		}

		if (firstSplit.size() != max + 1)
		{
			result.report.add("The original vertex indices have gaps - vertices were deleted - so vertices "
				+ "were welded by position instead");
			return null;
		}
		return welded;
	}

	private int[] weldByPosition(Geometry geometry)
	{
		Map<List<Object>, Integer> keys = new LinkedHashMap<>();
		int[] welded = new int[geometry.positions.size()];
		for (int split = 0; split < welded.length; split++)
		{
			double[] p = geometry.positions.get(split);
			List<Object> key = Arrays.asList(p[0], p[1], p[2], geometry.groups.get(split));
			Integer vertex = keys.get(key);
			if (vertex == null)
			{
				vertex = keys.size();
				keys.put(key, vertex);
			}
			welded[split] = vertex;
		}
		return welded;
	}

	private short faceColor(Geometry geometry, int[] corners)
	{
		Integer hint = geometry.rsHsl.get(corners[0]);
		boolean hintHolds = hint != null;
		for (int corner : corners)
		{
			hintHolds &= hint != null && hint.equals(geometry.rsHsl.get(corner));
			double[] rgba = geometry.colors.get(corner);
			if (hintHolds && rgba != null)
			{
				int rgb = RsColor.hslToRgb(hint);
				hintHolds = Math.abs(rgba[0] - RsColor.srgbToLinear(rgb >> 16 & 255)) <= COLOR_TOLERANCE
					&& Math.abs(rgba[1] - RsColor.srgbToLinear(rgb >> 8 & 255)) <= COLOR_TOLERANCE
					&& Math.abs(rgba[2] - RsColor.srgbToLinear(rgb & 255)) <= COLOR_TOLERANCE;
			}
		}
		if (hintHolds)
		{
			return (short) (int) hint;
		}

		double r = 0;
		double g = 0;
		double b = 0;
		int colored = 0;
		for (int corner : corners)
		{
			double[] rgba = geometry.colors.get(corner);
			if (rgba != null)
			{
				r += rgba[0];
				g += rgba[1];
				b += rgba[2];
				colored++;
			}
		}
		if (colored == 0)
		{
			geometry.missingColor = true;
			return RsColor.rgbToHsl(0x808080);
		}

		int rgb = RsColor.linearToSrgb(r / colored) << 16 | RsColor.linearToSrgb(g / colored) << 8
			| RsColor.linearToSrgb(b / colored);
		return RsColor.rgbToHsl(rgb);
	}

	private static byte[] faceBytes(List<Integer> values, int fallback)
	{
		byte[] bytes = new byte[values.size()];
		for (int i = 0; i < bytes.length; i++)
		{
			Integer value = values.get(i);
			bytes[i] = (byte) (int) (value == null ? fallback : value);
		}
		return bytes;
	}

	/**
	 * The vertex group each skin joint slot becomes: the N of a {@code group_N} joint name when every
	 * joint has one, so a round trip keeps the original numbering, else the slot index.
	 */
	private int[] groupIds(Gltf.Skin skin)
	{
		int[] ids = new int[skin.joints.size()];
		Set<Integer> seen = new HashSet<>();
		for (int slot = 0; slot < ids.length; slot++)
		{
			String name = gltf.nodes.get(skin.joints.get(slot)).name;
			Matcher matcher = name == null ? null : JOINT_NAME.matcher(name);
			if (matcher == null || !matcher.matches() || !seen.add(Integer.parseInt(matcher.group(1))))
			{
				for (int i = 0; i < ids.length; i++)
				{
					ids[i] = i;
				}
				return ids;
			}
			ids[slot] = Integer.parseInt(matcher.group(1));
		}
		return ids;
	}

	// --- Rig and animation ----------------------------------------------------------------------

	private void buildRigAndClips(int rigId, Gltf.Skin skin, Map<String, SequenceTiming> animations)
	{
		int slots = skin.joints.size();
		int[] groupOfSlot = groupIds(skin);

		Map<Integer, Integer> slotOfNode = new HashMap<>();
		for (int slot = 0; slot < slots; slot++)
		{
			slotOfNode.put(skin.joints.get(slot), slot);
		}

		int[] parentSlot = new int[slots];
		for (int slot = 0; slot < slots; slot++)
		{
			parentSlot[slot] = -1;
			for (int node = nodeParents[skin.joints.get(slot)]; node != -1; node = nodeParents[node])
			{
				Integer parent = slotOfNode.get(node);
				if (parent != null)
				{
					parentSlot[slot] = parent;
					break;
				}
			}
		}

		// Parent before child, siblings in slot order
		List<Integer> order = new ArrayList<>();
		for (int slot = 0; slot < slots; slot++)
		{
			if (parentSlot[slot] == -1)
			{
				addSubtree(order, slot, parentSlot);
			}
		}

		double[][] inverseBinds = new double[slots][];
		double[] ibm = skin.inverseBindMatrices == null ? null : glb.readDoubles(skin.inverseBindMatrices);
		double[][] restJoints = new double[slots][];
		for (int slot = 0; slot < slots; slot++)
		{
			inverseBinds[slot] = ibm == null ? Mat4.identity() : Arrays.copyOfRange(ibm, slot * 16, slot * 16 + 16);
			double[] bind = Mat4.invert(inverseBinds[slot]);
			restJoints[slot] = Mat4.transformPoint(Mat4.RS_FROM_GLTF, bind[12], bind[13], bind[14]);
		}

		Mesh mesh = result.mesh;
		double[][] centroids = new double[slots][];
		for (int slot = 0; slot < slots; slot++)
		{
			int[] members = mesh.getVertexGroup(groupOfSlot[slot]);
			if (members.length > 0)
			{
				double[] c = new double[3];
				for (int v : members)
				{
					c[0] += mesh.getVerticesX()[v];
					c[1] += mesh.getVerticesY()[v];
					c[2] += mesh.getVerticesZ()[v];
				}
				centroids[slot] = new double[]{c[0] / members.length, c[1] / members.length, c[2] / members.length};
			}
		}
		reportDistantJoints(restJoints, centroids, groupOfSlot);

		// The rig: per joint in order, translate subtree, pivot own group, rotate subtree, scale subtree
		int joints = order.size();
		int[] types = new int[joints * TRANSFORMS_PER_JOINT];
		int[][] rigGroups = new int[joints * TRANSFORMS_PER_JOINT][];
		for (int k = 0; k < joints; k++)
		{
			int slot = order.get(k);
			TreeSet<Integer> subtree = new TreeSet<>();
			collectGroups(subtree, slot, parentSlot, groupOfSlot);
			int[] subtreeGroups = subtree.stream().mapToInt(Integer::intValue).toArray();

			int base = k * TRANSFORMS_PER_JOINT;
			types[base] = 1;
			rigGroups[base] = subtreeGroups;
			types[base + 1] = 0;
			rigGroups[base + 1] = new int[]{groupOfSlot[slot]};
			types[base + 2] = 2;
			rigGroups[base + 2] = subtreeGroups.clone();
			types[base + 3] = 3;
			rigGroups[base + 3] = subtreeGroups.clone();
		}
		result.rig = new Rig(rigId, types, rigGroups);

		Map<String, Gltf.Animation> byName = new LinkedHashMap<>();
		if (gltf.animations != null)
		{
			for (Gltf.Animation animation : gltf.animations)
			{
				byName.put(animation.name, animation);
			}
		}
		for (String name : byName.keySet())
		{
			if (!animations.containsKey(name))
			{
				result.report.add("Animation '" + name + "' is not mapped to a live sequence and was ignored");
			}
		}

		for (Map.Entry<String, SequenceTiming> entry : animations.entrySet())
		{
			Gltf.Animation animation = byName.get(entry.getKey());
			if (animation == null)
			{
				throw new GltfException("The manifest maps animation '" + entry.getKey() + "', which the file does not have "
					+ "(it has " + byName.keySet() + ")");
			}
			result.clips.add(buildClip(rigId, entry.getValue(), animation, skin, order, parentSlot, inverseBinds,
				restJoints, centroids, types));
		}
	}

	private static void addSubtree(List<Integer> order, int slot, int[] parentSlot)
	{
		order.add(slot);
		for (int child = 0; child < parentSlot.length; child++)
		{
			if (parentSlot[child] == slot)
			{
				addSubtree(order, child, parentSlot);
			}
		}
	}

	private static void collectGroups(Set<Integer> groups, int slot, int[] parentSlot, int[] groupOfSlot)
	{
		groups.add(groupOfSlot[slot]);
		for (int child = 0; child < parentSlot.length; child++)
		{
			if (parentSlot[child] == slot)
			{
				collectGroups(groups, child, parentSlot, groupOfSlot);
			}
		}
	}

	/**
	 * A joint far from the vertices bound to it is the usual sign of a badly weighted asset. It still
	 * converts exactly - the pivot carries the offset - but it is worth seeing.
	 */
	private void reportDistantJoints(double[][] restJoints, double[][] centroids, int[] groupOfSlot)
	{
		double worst = 0;
		int worstSlot = -1;
		for (int slot = 0; slot < restJoints.length; slot++)
		{
			if (centroids[slot] == null)
			{
				continue;
			}
			double d = distance(restJoints[slot], centroids[slot]);
			if (d > worst)
			{
				worst = d;
				worstSlot = slot;
			}
		}
		if (worstSlot != -1 && worst > Mat4.UNITS_PER_METRE / 2)
		{
			result.report.add("Joint for group " + groupOfSlot[worstSlot] + " sits " + Math.round(worst)
				+ " units (" + String.format("%.2f", worst / Mat4.UNITS_PER_METRE) + " tiles) from the centroid of "
				+ "the vertices bound to it - check the weighting");
		}
	}

	private Clip buildClip(int rigId, SequenceTiming timing, Gltf.Animation animation, Gltf.Skin skin,
		List<Integer> order, int[] parentSlot, double[][] inverseBinds, double[][] restJoints,
		double[][] centroids, int[] types)
	{
		Channels channels = new Channels(animation);
		int frames = timing.frameCount();

		double lastStart = timing.startTime(frames - 1);
		if (channels.end + 1e-6 < lastStart)
		{
			result.report.add("Animation '" + animation.name + "' is " + String.format("%.2f", channels.end)
				+ "s long but sequence " + timing.sequenceId + " runs " + String.format("%.2f", timing.duration())
				+ "s; its last pose is held for the rest");
		}
		else if (channels.end > timing.duration() + SequenceTiming.SECONDS_PER_CYCLE + 1e-6)
		{
			result.report.add("Animation '" + animation.name + "' is " + String.format("%.2f", channels.end)
				+ "s long but sequence " + timing.sequenceId + " runs " + String.format("%.2f", timing.duration())
				+ "s; the rest is cut off");
		}

		int slots = skin.joints.size();
		int[][] transforms = new int[frames][];
		int[][] dx = new int[frames][];
		int[][] dy = new int[frames][];
		int[][] dz = new int[frames][];
		double worstSkew = 0;

		for (int frame = 0; frame < frames; frame++)
		{
			double time = timing.startTime(frame);
			Map<Integer, double[]> worlds = new HashMap<>();

			// Where every group has to end up: the glTF skinning transform, in engine space
			double[][] target = new double[slots][];
			for (int slot = 0; slot < slots; slot++)
			{
				double[] world = world(skin.joints.get(slot), channels, time, worlds);
				double[] skinning = Mat4.multiply(world, inverseBinds[slot]);
				target[slot] = Mat4.multiply(Mat4.RS_FROM_GLTF, skinning, Mat4.GLTF_FROM_RS);
			}

			int[] masks = new int[types.length];
			List<Integer> values = new ArrayList<>();
			double[][] realized = new double[slots][];

			for (int k = 0; k < order.size(); k++)
			{
				int slot = order.get(k);
				double[] parent = parentSlot[slot] == -1 ? Mat4.identity() : realized[parentSlot[slot]];
				double[] joint = restJoints[slot];

				// Translate the subtree so the joint lands where the file puts it
				double[] goal = Mat4.transformPoint(target[slot], joint[0], joint[1], joint[2]);
				double[] current = Mat4.transformPoint(parent, joint[0], joint[1], joint[2]);
				int[] t = {rint(goal[0] - current[0]), rint(goal[1] - current[1]), rint(goal[2] - current[2])};
				double[] after = Mat4.multiply(Mat4.translation(t[0], t[1], t[2]), parent);
				double[] pivot = Mat4.transformPoint(after, joint[0], joint[1], joint[2]);

				// Whatever linear change is left after the ancestors, split into the rotate op and then
				// the scale op the engine applies after it about the same pivot: remaining = S * R,
				// with S along the model axes. That is exact whenever the rows of what remains are
				// orthogonal - always, for a joint that only rotates - and approximate otherwise.
				double[] remaining = Mat4.multiply(target[slot], Mat4.invert(after));
				double[] scale = new double[3];
				double[] rotation = Mat4.identity();
				for (int row = 0; row < 3; row++)
				{
					double r0 = Mat4.get(remaining, row, 0);
					double r1 = Mat4.get(remaining, row, 1);
					double r2 = Mat4.get(remaining, row, 2);
					scale[row] = Math.sqrt(r0 * r0 + r1 * r1 + r2 * r2);
					Mat4.set(rotation, row, 0, r0 / scale[row]);
					Mat4.set(rotation, row, 1, r1 / scale[row]);
					Mat4.set(rotation, row, 2, r2 / scale[row]);
				}
				worstSkew = Math.max(worstSkew, Mat4.shear(transpose(rotation)));

				double[] euler = Mat4.rsEuler(Mat4.rotationOnly(rotation));
				int ax = Mat4.quantizeAngle(euler[0]);
				int ay = Mat4.quantizeAngle(euler[1]);
				int az = Mat4.quantizeAngle(euler[2]);
				int sx = rint(scale[0] * 128);
				int sy = rint(scale[1] * 128);
				int sz = rint(scale[2] * 128);
				boolean rotates = ax != 0 || ay != 0 || az != 0;
				boolean scales = sx != 128 || sy != 128 || sz != 128;

				// The pivot the engine will compute is the centroid of the joint's own group plus the
				// op's delta, so the delta is whatever closes the gap to the joint - or, for a joint
				// with no vertices of its own, the joint's absolute position
				int[] delta = {0, 0, 0};
				double[] realizedPivot = pivot;
				if (rotates || scales)
				{
					double[] centroid = centroids[slot] == null ? new double[3]
						: Mat4.transformPoint(after, centroids[slot][0], centroids[slot][1], centroids[slot][2]);
					delta = new int[]{rint(pivot[0] - centroid[0]), rint(pivot[1] - centroid[1]), rint(pivot[2] - centroid[2])};
					realizedPivot = new double[]{centroid[0] + delta[0], centroid[1] + delta[1], centroid[2] + delta[2]};
				}

				// What the engine will actually do, rounding and quantisation included - the next joint
				// down is solved against this, so errors never compound along a limb
				double[] linear = Mat4.multiply(
					Mat4.scale(sx / 128.0, sy / 128.0, sz / 128.0),
					Mat4.rsRotation(ax, ay, az));
				realized[slot] = rotates || scales
					? Mat4.multiply(
						Mat4.translation(realizedPivot[0], realizedPivot[1], realizedPivot[2]),
						linear,
						Mat4.translation(-realizedPivot[0], -realizedPivot[1], -realizedPivot[2]),
						after)
					: after;

				int base = k * TRANSFORMS_PER_JOINT;
				masks[base] = mask(t[0], t[1], t[2], 0, values);
				masks[base + 1] = rotates || scales ? mask(delta[0], delta[1], delta[2], 0, values) : 0;
				masks[base + 2] = rotates ? mask(ax, ay, az, 0, values) : 0;
				masks[base + 3] = scales ? mask(sx, sy, sz, 128, values) : 0;
			}

			int[][] ops = FrameOps.build(types, masks, values.stream().mapToInt(Integer::intValue).toArray());
			transforms[frame] = ops[0];
			dx[frame] = ops[1];
			dy[frame] = ops[2];
			dz[frame] = ops[3];
		}

		if (worstSkew > 1e-3)
		{
			result.report.add("Animation '" + animation.name + "' scales a joint along axes of its own rather than "
				+ "the model's (skew " + String.format("%.3f", worstSkew) + "); the engine scales along the model "
				+ "axes only, so that joint's pose is approximate");
		}

		return new Clip(timing.sequenceId, rigId, transforms, dx, dy, dz);
	}

	/**
	 * Sets the mask bits for the axes that differ from the op type's default - 0 for most, 128 for
	 * scale - and appends their values, in x, y, z order.
	 */
	private static int mask(int x, int y, int z, int unset, List<Integer> values)
	{
		int mask = 0;
		if (x != unset)
		{
			mask |= 1;
			values.add(x);
		}
		if (y != unset)
		{
			mask |= 2;
			values.add(y);
		}
		if (z != unset)
		{
			mask |= 4;
			values.add(z);
		}
		return mask;
	}

	private static int rint(double value)
	{
		return (int) Math.round(value);
	}

	private static double[] transpose(double[] m)
	{
		double[] t = Mat4.identity();
		for (int row = 0; row < 3; row++)
		{
			for (int col = 0; col < 3; col++)
			{
				Mat4.set(t, row, col, Mat4.get(m, col, row));
			}
		}
		return t;
	}

	private double[] worldAtRest(int node)
	{
		return world(node, null, 0, new HashMap<>());
	}

	private double[] world(int node, Channels channels, double time, Map<Integer, double[]> memo)
	{
		double[] cached = memo.get(node);
		if (cached != null)
		{
			return cached;
		}

		double[] local = local(node, channels, time);
		double[] world = nodeParents[node] == -1 ? local : Mat4.multiply(world(nodeParents[node], channels, time, memo), local);
		memo.put(node, world);
		return world;
	}

	private double[] local(int node, Channels channels, double time)
	{
		Gltf.Node def = gltf.nodes.get(node);
		boolean animated = channels != null && channels.animates(node);
		if (def.matrix != null)
		{
			if (animated)
			{
				throw new GltfException("Node '" + def.name + "' is animated but stores a matrix; glTF forbids that");
			}
			return def.matrix.clone();
		}

		double[] t = def.translation;
		double[] r = def.rotation;
		double[] s = def.scale;
		if (animated)
		{
			t = channels.sample(node, "translation", time, t);
			r = channels.sample(node, "rotation", time, r);
			s = channels.sample(node, "scale", time, s);
		}
		return Mat4.fromTrs(t, r, s);
	}

	/** One animation's channels, decoded once and sampled per frame. */
	private final class Channels
	{
		private final Map<String, double[]> times = new HashMap<>();
		private final Map<String, double[]> values = new HashMap<>();
		private final Map<String, Boolean> step = new HashMap<>();
		private final Set<Integer> nodes = new HashSet<>();
		private double end;

		Channels(Gltf.Animation animation)
		{
			for (Gltf.Channel channel : animation.channels)
			{
				if (channel.target == null || channel.target.node == null)
				{
					continue;
				}
				String path = channel.target.path;
				if (!"translation".equals(path) && !"rotation".equals(path) && !"scale".equals(path))
				{
					result.report.add("Animation '" + animation.name + "' animates " + path + ", which was ignored");
					continue;
				}

				Gltf.Sampler sampler = animation.samplers.get(channel.sampler);
				String interpolation = sampler.interpolation == null ? "LINEAR" : sampler.interpolation;
				if ("CUBICSPLINE".equals(interpolation))
				{
					throw new GltfException("Animation '" + animation.name + "' uses CUBICSPLINE interpolation; "
						+ "export with linear or step interpolation");
				}

				String key = channel.target.node + "/" + path;
				double[] input = glb.readDoubles(sampler.input);
				times.put(key, input);
				values.put(key, glb.readDoubles(sampler.output));
				step.put(key, "STEP".equals(interpolation));
				nodes.add(channel.target.node);
				if (input.length > 0)
				{
					end = Math.max(end, input[input.length - 1]);
				}
			}
		}

		boolean animates(int node)
		{
			return nodes.contains(node);
		}

		double[] sample(int node, String path, double time, double[] rest)
		{
			String key = node + "/" + path;
			double[] input = times.get(key);
			if (input == null || input.length == 0)
			{
				return rest;
			}

			int width = values.get(key).length / input.length;
			double[] output = values.get(key);

			// Keyed times are compared with a little slack, because they were written as floats
			double t = time + 1e-6;
			if (t <= input[0])
			{
				return Arrays.copyOfRange(output, 0, width);
			}
			int last = input.length - 1;
			if (t >= input[last])
			{
				return Arrays.copyOfRange(output, last * width, last * width + width);
			}

			int before = 0;
			while (before + 1 < input.length && input[before + 1] <= t)
			{
				before++;
			}
			double[] a = Arrays.copyOfRange(output, before * width, before * width + width);
			if (step.get(key))
			{
				return a;
			}

			double[] b = Arrays.copyOfRange(output, (before + 1) * width, (before + 1) * width + width);
			double f = (time - input[before]) / (input[before + 1] - input[before]);
			f = Math.max(0, Math.min(1, f));
			if ("rotation".equals(path))
			{
				return Mat4.slerp(a, b, f);
			}
			double[] out = new double[width];
			for (int i = 0; i < width; i++)
			{
				out[i] = a[i] + (b[i] - a[i]) * f;
			}
			return out;
		}
	}

	private static double distance(double[] a, double[] b)
	{
		double dx = a[0] - b[0];
		double dy = a[1] - b[1];
		double dz = a[2] - b[2];
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}
}
