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

import com.customnpcmodels.cache.skeletal.SkeletalAnimation;
import com.customnpcmodels.cache.skeletal.SkeletalPoser;
import com.customnpcmodels.cache.skeletal.Skeleton;
import com.customnpcmodels.inject.Clip;
import com.customnpcmodels.inject.Mesh;
import com.customnpcmodels.inject.Rig;
import com.customnpcmodels.inject.RsColor;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes a mesh, and optionally its rig and clips, as a binary glTF.
 *
 * <p>This is the half of the round trip we control completely, which is why it came first: it is
 * what builds real test input for the reader, and it is how an author seeds Blender work from an
 * asset that already animates correctly.
 *
 * <h2>Layout</h2>
 *
 * <ul>
 *   <li><b>Geometry.</b> glTF colors are per vertex and the engine's are per face, so every face
 *       gets three vertices of its own. Two custom attributes carry what that split and the color
 *       conversion would otherwise lose: {@code _RS_VERTEX}, the original vertex index, so the reader
 *       can weld exactly, and {@code _RS_HSL}, the packed color, so an unedited color comes back
 *       bit-for-bit. The reader trusts neither blindly - see {@link GltfToMeshConverter}.</li>
 *   <li><b>Face data with no glTF equivalent</b> - render types, render priorities, the model
 *       priority - rides in the mesh's {@code extras}. Transparency is {@code COLOR_0} alpha.</li>
 *   <li><b>Parts.</b> A mesh merged from several models is written as one glTF mesh per model, each
 *       with its own slice of the per-face extras and an {@code rsPart} index that keeps the face
 *       order when an editor writes the parts back in another order. A single part is written as
 *       one mesh with no index.</li>
 *   <li><b>The rig</b> is a skin with one joint per vertex group, arranged by {@link JointTree}, and
 *       every vertex bound to its group's joint with a weight of 1.</li>
 *   <li><b>Each clip</b> is an animation named after its sequence id, keyed at the live frame start
 *       times with STEP interpolation, because a frame is a held pose rather than a waypoint. Every
 *       joint's transform comes from {@link RigPoser}, so the animation is exact for any rig shape.</li>
 * </ul>
 */
final class GlbWriter
{
	static final String RS_VERTEX = "_RS_VERTEX";
	static final String RS_HSL = "_RS_HSL";
	static final String JOINT_PREFIX = "group_";

	static final String EXTRA_PRIORITY = "rsPriority";
	static final String EXTRA_RENDER_TYPES = "rsRenderTypes";
	static final String EXTRA_RENDER_PRIORITIES = "rsRenderPriorities";
	static final String EXTRA_TRANSPARENCIES = "rsTransparencies";
	static final String EXTRA_PART = "rsPart";

	private GlbWriter()
	{
	}

	/** A static mesh, rigged or not, with no animation. */
	static byte[] write(Mesh mesh, List<String> report)
	{
		return write(mesh, null, new ArrayList<>(), new LinkedHashMap<>(), report);
	}

	/** The whole mesh as a single part. */
	static byte[] write(Mesh mesh, Map<Integer, Rig> rigs, List<Clip> clips, Map<Integer, SequenceTiming> timings,
		List<String> report)
	{
		return write(mesh, MeshPart.whole(mesh.getFaceCount()), rigs, clips, timings, report);
	}

	/**
	 * @param parts   contiguous runs of the mesh's faces covering all of it, in face order; each
	 *                becomes a glTF mesh of its own when there is more than one
	 * @param rigs    the rigs the clips name, by id
	 * @param timings per clip sequence id, how the live sequence plays; a clip without one is keyed
	 *                one cycle per frame and reported
	 */
	static byte[] write(Mesh mesh, List<MeshPart> parts, Map<Integer, Rig> rigs, List<Clip> clips,
		Map<Integer, SequenceTiming> timings, List<String> report)
	{
		Gltf gltf = new Gltf();
		gltf.asset.generator = "Custom NPC Models authoring pipeline";
		Glb.BinBuilder bin = new Glb.BinBuilder(gltf);
		checkParts(mesh, parts, report);

		boolean rigged = mesh.isRigged();
		Rig rig = null;
		if (rigged && rigs != null && !clips.isEmpty())
		{
			rig = rigs.get(clips.get(0).getRigId());
			for (Clip clip : clips)
			{
				if (clip.getRigId() != clips.get(0).getRigId())
				{
					report.add("Clips use more than one rig; the armature is arranged from rig "
						+ clips.get(0).getRigId() + ", which only changes how it looks in Blender");
					break;
				}
			}
		}
		JointTree tree = rigged ? JointTree.build(mesh, rig, report) : null;
		if (tree != null && rigs != null)
		{
			tree = detachFromSingularParents(tree, mesh, rigs, clips, report);
		}

		int[][] vertexJoints = null;
		double[][] vertexWeights = null;
		if (rigged)
		{
			vertexJoints = new int[mesh.getVerticesCount()][];
			vertexWeights = new double[mesh.getVerticesCount()][];
			for (int group = 0; group < mesh.getVertexGroups().length; group++)
			{
				int joint = tree.jointOfGroup(group);
				for (int vertex : mesh.getVertexGroup(group))
				{
					vertexJoints[vertex] = joint == -1 ? null : new int[]{joint};
					vertexWeights[vertex] = joint == -1 ? null : new double[]{1};
				}
			}
		}

		Gltf.Scene scene = new Gltf.Scene();
		scene.nodes = new ArrayList<>();
		gltf.scenes.add(scene);
		gltf.scene = 0;
		List<Gltf.Node> meshNodes = writeMeshes(gltf, bin, scene, mesh, parts, vertexJoints, vertexWeights, report);

		if (rigged)
		{
			writeSkin(gltf, bin, meshNodes, scene, tree);
			writeAnimations(gltf, bin, mesh, tree, rigs, clips, timings, report);
		}

		byte[] data = bin.finish();
		return Glb.write(gltf, data);
	}

	/** Checks the parts cover the mesh's faces in order, and reports textures, which glTF export drops. */
	private static void checkParts(Mesh mesh, List<MeshPart> parts, List<String> report)
	{
		int faces = mesh.getFaceCount();
		int covered = 0;
		for (MeshPart part : parts)
		{
			if (part.firstFace != covered)
			{
				throw new IllegalArgumentException("Part " + part + " does not follow on from face " + covered);
			}
			covered += part.faceCount;
		}
		if (covered != faces)
		{
			throw new IllegalArgumentException("Parts cover " + covered + " of the mesh's " + faces + " faces");
		}
		if (mesh.getFaceTextures() != null)
		{
			long textured = 0;
			for (short texture : mesh.getFaceTextures())
			{
				textured += texture != -1 ? 1 : 0;
			}
			if (textured > 0)
			{
				report.add("Mesh " + mesh.getId() + " has " + textured + " textured faces; glTF export "
					+ "drops the textures and keeps their base colors");
			}
		}
	}

	/**
	 * One glTF mesh per part, so Blender imports each as an object of its own and the painter can
	 * hide it. A single part keeps the layout files had before parts existed.
	 *
	 * @param vertexJoints  per vertex, the joints it is skinned to, or null for a vertex in no group;
	 *                      null throughout for an unrigged mesh
	 * @param vertexWeights per vertex, its joints' weights
	 */
	private static List<Gltf.Node> writeMeshes(Gltf gltf, Glb.BinBuilder bin, Gltf.Scene scene, Mesh mesh,
		List<MeshPart> parts, int[][] vertexJoints, double[][] vertexWeights, List<String> report)
	{
		// Vertices no face uses still belong to the mesh - they keep its vertex numbering, and a group
		// centroid counts them - so they ride along after the last part's face corners, referenced by
		// no triangle
		boolean[] referenced = new boolean[mesh.getVerticesCount()];
		for (int face = 0; face < mesh.getFaceCount(); face++)
		{
			referenced[mesh.getFaceIndices1()[face]] = true;
			referenced[mesh.getFaceIndices2()[face]] = true;
			referenced[mesh.getFaceIndices3()[face]] = true;
		}
		List<Integer> loose = new ArrayList<>();
		for (int vertex = 0; vertex < referenced.length; vertex++)
		{
			if (!referenced[vertex])
			{
				loose.add(vertex);
			}
		}

		boolean multiPart = parts.size() > 1;
		List<Gltf.Node> meshNodes = new ArrayList<>();
		int[] unbound = new int[1];
		for (int p = 0; p < parts.size(); p++)
		{
			MeshPart part = parts.get(p);
			boolean last = p == parts.size() - 1;
			Gltf.Primitive primitive = writePrimitive(bin, mesh, part, last ? loose : Collections.emptyList(),
				vertexJoints, vertexWeights, unbound);

			// On the mesh, not the primitive: Blender keeps a mesh's extras as custom properties and
			// exports them again, but drops a primitive's
			Gltf.MeshDef meshDef = new Gltf.MeshDef();
			meshDef.name = multiPart ? part.name : "mesh_" + mesh.getId();
			meshDef.primitives.add(primitive);
			meshDef.extras = extras(mesh, part, multiPart ? p : -1);
			gltf.meshes.add(meshDef);

			Gltf.Node meshNode = new Gltf.Node();
			meshNode.name = meshDef.name;
			meshNode.mesh = gltf.meshes.size() - 1;
			gltf.nodes.add(meshNode);
			scene.nodes.add(gltf.nodes.size() - 1);
			meshNodes.add(meshNode);
		}
		if (unbound[0] > 0)
		{
			report.add(unbound[0] + " vertex copies sit on vertices in no group; bound to the first joint");
		}
		return meshNodes;
	}

	/**
	 * One part's faces as a primitive: three vertices per face, then any loose vertices, which no
	 * triangle names.
	 */
	private static Gltf.Primitive writePrimitive(Glb.BinBuilder bin, Mesh mesh, MeshPart part, List<Integer> loose,
		int[][] vertexJoints, double[][] vertexWeights, int[] unbound)
	{
		boolean rigged = vertexJoints != null;

		// Three vertices per face, corners in reversed order: the Y flip into glTF space is a
		// reflection, and without the reversal every face would point inward
		int corners = part.faceCount * 3;
		int splits = corners + loose.size();
		double[] positions = new double[splits * 3];
		double[] colors = new double[splits * 4];
		int[] rsVertex = new int[splits];
		int[] rsHsl = new int[splits];
		int[] joints = rigged ? new int[splits * 4] : null;
		double[] weights = rigged ? new double[splits * 4] : null;

		for (int split = 0; split < splits; split++)
		{
			int vertex;
			int hsl = 0;
			int transparency = 0;
			if (split < corners)
			{
				int face = part.firstFace + split / 3;
				int[] order = {mesh.getFaceIndices1()[face], mesh.getFaceIndices3()[face], mesh.getFaceIndices2()[face]};
				vertex = order[split % 3];
				hsl = mesh.getFaceColors()[face] & 0xFFFF;
				transparency = mesh.getFaceTransparencies() == null ? 0 : mesh.getFaceTransparencies()[face] & 0xFF;
			}
			else
			{
				vertex = loose.get(split - corners);
			}

			double[] p = Mat4.transformPoint(Mat4.GLTF_FROM_RS,
				mesh.getVerticesX()[vertex], mesh.getVerticesY()[vertex], mesh.getVerticesZ()[vertex]);
			System.arraycopy(p, 0, positions, split * 3, 3);

			System.arraycopy(RsColor.hslToLinear(hsl), 0, colors, split * 4, 3);
			colors[split * 4 + 3] = 1 - transparency / 255.0;

			rsVertex[split] = vertex;
			rsHsl[split] = hsl;

			if (rigged)
			{
				int[] vertexJoint = vertexJoints[vertex];
				if (vertexJoint == null)
				{
					unbound[0]++;
					joints[split * 4] = 0;
					weights[split * 4] = 1;
				}
				else
				{
					for (int i = 0; i < vertexJoint.length; i++)
					{
						joints[split * 4 + i] = vertexJoint[i];
						weights[split * 4 + i] = vertexWeights[vertex][i];
					}
				}
			}
		}

		Gltf.Primitive primitive = new Gltf.Primitive();
		primitive.mode = Gltf.TRIANGLES;
		primitive.attributes = new LinkedHashMap<>();
		primitive.attributes.put("POSITION", bin.floats(positions, "VEC3", Gltf.ARRAY_BUFFER, true));
		primitive.attributes.put("COLOR_0", bin.floats(colors, "VEC4", Gltf.ARRAY_BUFFER, false));
		// Floats, not shorts: Blender's importer only keeps scalar custom attributes stored as float
		// or unsigned byte, and both values fit a float's 24-bit mantissa exactly
		primitive.attributes.put(RS_VERTEX, bin.floats(toDoubles(rsVertex), "SCALAR", Gltf.ARRAY_BUFFER, false));
		primitive.attributes.put(RS_HSL, bin.floats(toDoubles(rsHsl), "SCALAR", Gltf.ARRAY_BUFFER, false));
		if (rigged)
		{
			primitive.attributes.put("JOINTS_0", bin.unsignedShorts(joints, "VEC4", Gltf.ARRAY_BUFFER));
			primitive.attributes.put("WEIGHTS_0", bin.floats(weights, "VEC4", Gltf.ARRAY_BUFFER, false));
		}

		int[] indices = new int[corners];
		for (int i = 0; i < corners; i++)
		{
			indices[i] = i;
		}
		primitive.indices = corners <= 0xFFFF
			? bin.unsignedShorts(indices, "SCALAR", Gltf.ELEMENT_ARRAY_BUFFER)
			: bin.unsignedInts(indices, "SCALAR", Gltf.ELEMENT_ARRAY_BUFFER);
		return primitive;
	}

	/**
	 * @param partIndex the part's place in the mesh, recorded so the reader can restore face order
	 *                  whatever order an editor writes the parts back in; -1 for a single part
	 */
	private static JsonObject extras(Mesh mesh, MeshPart part, int partIndex)
	{
		JsonObject extras = new JsonObject();
		extras.addProperty(EXTRA_PRIORITY, mesh.getPriority());
		extras.addProperty(EXTRA_TRANSPARENCIES, mesh.getFaceTransparencies() != null);
		if (mesh.getFaceRenderTypes() != null)
		{
			extras.add(EXTRA_RENDER_TYPES, jsonBytes(mesh.getFaceRenderTypes(), part));
		}
		if (mesh.getFaceRenderPriorities() != null)
		{
			extras.add(EXTRA_RENDER_PRIORITIES, jsonBytes(mesh.getFaceRenderPriorities(), part));
		}
		if (partIndex >= 0)
		{
			extras.addProperty(EXTRA_PART, partIndex);
		}
		return extras;
	}

	/** A part's per-face bytes as a JSON array, in face order. */
	private static JsonArray jsonBytes(byte[] values, MeshPart part)
	{
		JsonArray array = new JsonArray();
		for (int face = part.firstFace; face < part.firstFace + part.faceCount; face++)
		{
			array.add(values[face]);
		}
		return array;
	}

	private static double[] toDoubles(int[] values)
	{
		double[] doubles = new double[values.length];
		for (int i = 0; i < values.length; i++)
		{
			doubles[i] = values[i];
		}
		return doubles;
	}

	/** One skeletal sequence to export: its animation and how the live sequence plays. */
	static final class SkeletalClip
	{
		final int sequenceId;
		final SkeletalAnimation animation;
		final SequenceTiming timing;

		SkeletalClip(int sequenceId, SkeletalAnimation animation, SequenceTiming timing)
		{
			this.sequenceId = sequenceId;
			this.animation = animation;
			this.timing = timing;
		}
	}

	/**
	 * A mesh skinned to a skeletal (animaya) rig, which the client poses with bones and weights
	 * rather than vertex groups: one joint per bone, named {@code group_N} after its index and
	 * arranged as the bones are, with each vertex weighted to up to four of them. A vertex with no
	 * bones, which the client never moves, rides a joint named {@code static} that no clip animates.
	 * Each clip is keyed a frame per cycle, every key the bones' pose at that frame.
	 * <p>
	 * The bones work in the engine's model space with y and z negated, so a bone's matrix M is
	 * C * M * inverse(C) in glTF, with C the scale from that space to glTF's.
	 *
	 * @param pose          the bind pose the joints rest in
	 * @param vertexBones   per vertex, the bones it is weighted to, or null for none
	 * @param vertexWeights per vertex, each of its bones' weights, summing to 1
	 */
	static byte[] writeSkeletal(Mesh mesh, List<MeshPart> parts, Skeleton skeleton, int pose, int[][] vertexBones,
		double[][] vertexWeights, List<SkeletalClip> clips, List<String> report)
	{
		Gltf gltf = new Gltf();
		gltf.asset.generator = "Custom NPC Models authoring pipeline";
		Glb.BinBuilder bin = new Glb.BinBuilder(gltf);
		checkParts(mesh, parts, report);

		int bones = skeleton.getBoneCount();
		int[][] vertexJoints = new int[mesh.getVerticesCount()][];
		double[][] weights = new double[mesh.getVerticesCount()][];
		boolean anyStatic = false;
		int trimmed = 0;
		for (int vertex = 0; vertex < vertexJoints.length; vertex++)
		{
			if (vertexBones[vertex] == null || vertexBones[vertex].length == 0)
			{
				vertexJoints[vertex] = new int[]{bones};
				weights[vertex] = new double[]{1};
				anyStatic = true;
				continue;
			}
			int[] joints = vertexBones[vertex];
			double[] w = vertexWeights[vertex];
			if (joints.length > 4)
			{
				// glTF takes four joints a vertex: keep the heaviest four, scaled back up to a whole
				trimmed++;
				Integer[] order = new Integer[joints.length];
				for (int i = 0; i < order.length; i++)
				{
					order[i] = i;
				}
				double[] all = w;
				Arrays.sort(order, (a, b) -> Double.compare(all[b], all[a]));
				int[] keptJoints = new int[4];
				double[] keptWeights = new double[4];
				double sum = 0;
				for (int i = 0; i < 4; i++)
				{
					keptJoints[i] = joints[order[i]];
					keptWeights[i] = w[order[i]];
					sum += keptWeights[i];
				}
				for (int i = 0; i < 4; i++)
				{
					keptWeights[i] /= sum;
				}
				joints = keptJoints;
				w = keptWeights;
			}
			vertexJoints[vertex] = joints;
			weights[vertex] = w;
		}
		if (trimmed > 0)
		{
			report.add(trimmed + " vertices are weighted to more than four bones; each keeps its heaviest four");
		}

		Gltf.Scene scene = new Gltf.Scene();
		scene.nodes = new ArrayList<>();
		gltf.scenes.add(scene);
		gltf.scene = 0;
		List<Gltf.Node> meshNodes = writeMeshes(gltf, bin, scene, mesh, parts, vertexJoints, weights, report);

		int firstJoint = writeSkeletalSkin(gltf, bin, meshNodes, scene, skeleton, pose, anyStatic);
		writeSkeletalAnimations(gltf, bin, skeleton, firstJoint, clips, report);

		byte[] data = bin.finish();
		return Glb.write(gltf, data);
	}

	/** The engine's model space with y and z negated, which bones work in, to glTF's. */
	private static final double[] GLTF_FROM_SKELETAL = Mat4.scale(1 / Mat4.UNITS_PER_METRE,
		1 / Mat4.UNITS_PER_METRE, -1 / Mat4.UNITS_PER_METRE);
	private static final double[] SKELETAL_FROM_GLTF = Mat4.scale(Mat4.UNITS_PER_METRE, Mat4.UNITS_PER_METRE,
		-Mat4.UNITS_PER_METRE);

	private static double[] toGltf(double[] skeletal)
	{
		return Mat4.multiply(GLTF_FROM_SKELETAL, skeletal, SKELETAL_FROM_GLTF);
	}

	/**
	 * An armature root, then a joint per bone resting in the bind pose, then the static joint when
	 * some vertex needs it. Returns the node index of the first bone's joint.
	 */
	private static int writeSkeletalSkin(Gltf gltf, Glb.BinBuilder bin, List<Gltf.Node> meshNodes, Gltf.Scene scene,
		Skeleton skeleton, int pose, boolean withStatic)
	{
		Gltf.Node armature = new Gltf.Node();
		armature.name = "armature";
		armature.children = new ArrayList<>();
		gltf.nodes.add(armature);
		int armatureIndex = gltf.nodes.size() - 1;
		scene.nodes.add(armatureIndex);

		int bones = skeleton.getBoneCount();
		int joints = bones + (withStatic ? 1 : 0);
		int firstJoint = gltf.nodes.size();
		double[] inverseBinds = new double[joints * 16];
		List<Integer> jointNodes = new ArrayList<>();
		for (int bone = 0; bone < bones; bone++)
		{
			double[][] trs = Mat4.toTrs(toGltf(skeleton.getLocal(bone, pose)));
			Gltf.Node node = new Gltf.Node();
			node.name = JOINT_PREFIX + bone;
			node.translation = trs[0];
			node.rotation = trs[1];
			node.scale = trs[2];
			gltf.nodes.add(node);
			jointNodes.add(firstJoint + bone);
			System.arraycopy(Mat4.invert(toGltf(skeleton.getModel(bone, pose))), 0, inverseBinds, bone * 16, 16);
		}
		if (withStatic)
		{
			Gltf.Node node = new Gltf.Node();
			node.name = "static";
			gltf.nodes.add(node);
			jointNodes.add(firstJoint + bones);
			armature.children.add(firstJoint + bones);
			System.arraycopy(Mat4.identity(), 0, inverseBinds, bones * 16, 16);
		}
		for (int bone = 0; bone < bones; bone++)
		{
			int parent = skeleton.getParent(bone);
			Gltf.Node parentNode = parent < 0 ? armature : gltf.nodes.get(firstJoint + parent);
			if (parentNode.children == null)
			{
				parentNode.children = new ArrayList<>();
			}
			parentNode.children.add(firstJoint + bone);
		}

		Gltf.Skin skin = new Gltf.Skin();
		skin.name = "rig";
		skin.joints = jointNodes;
		skin.skeleton = armatureIndex;
		skin.inverseBindMatrices = bin.floats(inverseBinds, "MAT4", null, false);
		gltf.skins = new ArrayList<>();
		gltf.skins.add(skin);
		for (Gltf.Node meshNode : meshNodes)
		{
			meshNode.skin = 0;
		}
		return firstJoint;
	}

	private static void writeSkeletalAnimations(Gltf gltf, Glb.BinBuilder bin, Skeleton skeleton, int firstJoint,
		List<SkeletalClip> clips, List<String> report)
	{
		if (clips.isEmpty())
		{
			return;
		}
		gltf.animations = new ArrayList<>();
		int bones = skeleton.getBoneCount();
		for (SkeletalClip clip : clips)
		{
			int frames = clip.timing.frameCount();

			// One extra key at the end repeating the last pose, so the animation lasts as long as the
			// sequence does in Blender rather than stopping when the last frame starts
			double[] times = new double[frames + 1];
			for (int frame = 0; frame <= frames; frame++)
			{
				times[frame] = clip.timing.startTime(frame);
			}

			double[][] translations = new double[bones][(frames + 1) * 3];
			double[][] rotations = new double[bones][(frames + 1) * 4];
			double[][] scales = new double[bones][(frames + 1) * 3];
			double worstShear = 0;
			for (int frame = 0; frame <= frames; frame++)
			{
				double[][] locals = SkeletalPoser.locals(skeleton, clip.animation, Math.min(frame, frames - 1));
				for (int bone = 0; bone < bones; bone++)
				{
					double[] local = toGltf(locals[bone]);
					worstShear = Math.max(worstShear, Mat4.shear(local));
					double[][] trs = Mat4.toTrs(local);
					System.arraycopy(trs[0], 0, translations[bone], frame * 3, 3);
					double[] q = trs[1];
					if (frame > 0)
					{
						// Keep consecutive keys on the same hemisphere so any interpolation is the short way
						int prev = (frame - 1) * 4;
						double dot = q[0] * rotations[bone][prev] + q[1] * rotations[bone][prev + 1]
							+ q[2] * rotations[bone][prev + 2] + q[3] * rotations[bone][prev + 3];
						if (dot < 0)
						{
							q = new double[]{-q[0], -q[1], -q[2], -q[3]};
						}
					}
					System.arraycopy(q, 0, rotations[bone], frame * 4, 4);
					System.arraycopy(trs[2], 0, scales[bone], frame * 3, 3);
				}
			}
			if (worstShear > 1e-3)
			{
				report.add("Clip " + clip.sequenceId + " shears a bone (" + worstShear
					+ "); glTF joints cannot express shear, so that pose is approximate");
			}

			Gltf.Animation animation = new Gltf.Animation();
			animation.name = String.valueOf(clip.sequenceId);
			int input = bin.floats(times, "SCALAR", null, true);
			for (int bone = 0; bone < bones; bone++)
			{
				int node = firstJoint + bone;
				channel(animation, input, bin.floats(translations[bone], "VEC3", null, false), node, "translation");
				channel(animation, input, bin.floats(rotations[bone], "VEC4", null, false), node, "rotation");
				channel(animation, input, bin.floats(scales[bone], "VEC3", null, false), node, "scale");
			}
			gltf.animations.add(animation);
		}
	}

	/**
	 * Node layout: first one node per part mesh, then an armature root with no transform, then one
	 * node per joint named {@code group_N} after the vertex group it carries. Every part mesh is
	 * skinned to the one rig.
	 */
	private static void writeSkin(Gltf gltf, Glb.BinBuilder bin, List<Gltf.Node> meshNodes, Gltf.Scene scene,
		JointTree tree)
	{
		Gltf.Node armature = new Gltf.Node();
		armature.name = "armature";
		armature.children = new ArrayList<>();
		gltf.nodes.add(armature);
		int armatureIndex = gltf.nodes.size() - 1;
		scene.nodes.add(armatureIndex);

		int firstJoint = gltf.nodes.size();
		double[] inverseBinds = new double[tree.groups.length * 16];
		List<Integer> jointNodes = new ArrayList<>();

		for (int joint = 0; joint < tree.groups.length; joint++)
		{
			double[] rest = restGltf(tree, joint);
			double[] parentRest = tree.parents[joint] == -1 ? new double[3] : restGltf(tree, tree.parents[joint]);

			Gltf.Node node = new Gltf.Node();
			node.name = JOINT_PREFIX + tree.groups[joint];
			node.translation = new double[]{rest[0] - parentRest[0], rest[1] - parentRest[1], rest[2] - parentRest[2]};
			gltf.nodes.add(node);
			jointNodes.add(firstJoint + joint);

			System.arraycopy(Mat4.translation(-rest[0], -rest[1], -rest[2]), 0, inverseBinds, joint * 16, 16);
		}

		for (int joint = 0; joint < tree.groups.length; joint++)
		{
			Gltf.Node parent = tree.parents[joint] == -1 ? armature : gltf.nodes.get(firstJoint + tree.parents[joint]);
			if (parent.children == null)
			{
				parent.children = new ArrayList<>();
			}
			parent.children.add(firstJoint + joint);
		}

		Gltf.Skin skin = new Gltf.Skin();
		skin.name = "rig";
		skin.joints = jointNodes;
		skin.skeleton = armatureIndex;
		skin.inverseBindMatrices = bin.floats(inverseBinds, "MAT4", null, false);
		gltf.skins = new ArrayList<>();
		gltf.skins.add(skin);
		for (Gltf.Node meshNode : meshNodes)
		{
			meshNode.skin = 0;
		}
	}

	private static double[] restGltf(JointTree tree, int joint)
	{
		double[] rest = tree.restPositions[joint];
		return Mat4.transformPoint(Mat4.GLTF_FROM_RS, rest[0], rest[1], rest[2]);
	}

	private static void writeAnimations(Gltf gltf, Glb.BinBuilder bin, Mesh mesh, JointTree tree,
		Map<Integer, Rig> rigs, List<Clip> clips, Map<Integer, SequenceTiming> timings, List<String> report)
	{
		if (clips.isEmpty())
		{
			return;
		}

		gltf.animations = new ArrayList<>();
		int firstJoint = gltf.nodes.size() - tree.groups.length;

		// Joints every exported frame scales to nothing: geometry that only shows in the rest pose, or
		// in a sequence that was not exported, which is otherwise a mystery once it is in Blender
		boolean[] alwaysCollapsed = new boolean[tree.groups.length];
		Arrays.fill(alwaysCollapsed, true);
		boolean anyClip = false;

		for (Clip clip : clips)
		{
			Rig rig = rigs.get(clip.getRigId());
			if (rig == null)
			{
				report.add("Clip " + clip.getSequenceId() + " names rig " + clip.getRigId() + ", which was not supplied; skipped");
				continue;
			}

			int frames = clip.getFrameCount();
			SequenceTiming timing = timings.get(clip.getSequenceId());
			if (timing == null || timing.frameCount() != frames)
			{
				report.add("Clip " + clip.getSequenceId() + " has no matching live timing; keyed one cycle per frame");
				int[] lengths = new int[frames];
				Arrays.fill(lengths, 1);
				timing = new SequenceTiming(clip.getSequenceId(), lengths);
			}

			// One extra key at the end repeating the last pose, so the animation lasts as long as the
			// sequence does in Blender rather than stopping when the last frame starts
			double[] times = new double[frames + 1];
			for (int frame = 0; frame <= frames; frame++)
			{
				times[frame] = timing.startTime(frame);
			}

			int joints = tree.groups.length;
			double[][] translations = new double[joints][(frames + 1) * 3];
			double[][] rotations = new double[joints][(frames + 1) * 4];
			double[][] scales = new double[joints][(frames + 1) * 3];
			boolean anyScale = false;
			double worstShear = 0;

			for (int frame = 0; frame <= frames; frame++)
			{
				double[][] world = worldPose(mesh, tree, rig, clip, Math.min(frame, frames - 1));
				for (int joint = 0; joint < joints; joint++)
				{
					alwaysCollapsed[joint] &= Mat4.isCollapsed(world[joint]);

					double[] local = tree.parents[joint] == -1
						? world[joint]
						: Mat4.multiply(Mat4.invert(world[tree.parents[joint]]), world[joint]);
					worstShear = Math.max(worstShear, Mat4.shear(local));

					double[][] trs = Mat4.toTrs(local);
					System.arraycopy(trs[0], 0, translations[joint], frame * 3, 3);
					double[] q = trs[1];
					if (frame > 0)
					{
						// Keep consecutive keys on the same hemisphere so any interpolation is the short way
						int prev = (frame - 1) * 4;
						double dot = q[0] * rotations[joint][prev] + q[1] * rotations[joint][prev + 1]
							+ q[2] * rotations[joint][prev + 2] + q[3] * rotations[joint][prev + 3];
						if (dot < 0)
						{
							q = new double[]{-q[0], -q[1], -q[2], -q[3]};
						}
					}
					System.arraycopy(q, 0, rotations[joint], frame * 4, 4);
					System.arraycopy(trs[2], 0, scales[joint], frame * 3, 3);
					for (double s : trs[2])
					{
						anyScale |= Math.abs(s - 1) > 1e-3;
					}
				}
			}

			if (worstShear > 1e-3)
			{
				report.add("Clip " + clip.getSequenceId() + " shears a vertex group (" + worstShear
					+ "); glTF joints cannot express shear, so that pose is approximate");
			}

			Gltf.Animation animation = new Gltf.Animation();
			animation.name = String.valueOf(clip.getSequenceId());
			int input = bin.floats(times, "SCALAR", null, true);
			for (int joint = 0; joint < joints; joint++)
			{
				int node = firstJoint + joint;
				channel(animation, input, bin.floats(translations[joint], "VEC3", null, false), node, "translation");
				channel(animation, input, bin.floats(rotations[joint], "VEC4", null, false), node, "rotation");
				if (anyScale)
				{
					channel(animation, input, bin.floats(scales[joint], "VEC3", null, false), node, "scale");
				}
			}
			gltf.animations.add(animation);
			anyClip = true;
		}

		for (int joint = 0; anyClip && joint < tree.groups.length; joint++)
		{
			if (alwaysCollapsed[joint])
			{
				report.add("Vertex group " + tree.groups[joint] + " (" + facesTouching(mesh, tree.groups[joint])
					+ " faces) is scaled to zero in every exported clip; it is only visible in the rest pose, "
					+ "or in sequences not exported (try -Pseqs)");
			}
		}
	}

	/** Each joint's glTF world transform at one frame of a clip. */
	private static double[][] worldPose(Mesh mesh, JointTree tree, Rig rig, Clip clip, int frame)
	{
		double[][] rsGroups = RigPoser.pose(mesh, rig, clip, frame, mesh.getVertexGroups().length);
		double[][] world = new double[tree.groups.length][];
		for (int joint = 0; joint < world.length; joint++)
		{
			double[] rest = restGltf(tree, joint);
			double[] moved = Mat4.multiply(Mat4.GLTF_FROM_RS, rsGroups[tree.groups[joint]], Mat4.RS_FROM_GLTF);
			world[joint] = Mat4.multiply(moved, Mat4.translation(rest[0], rest[1], rest[2]));
		}
		return world;
	}

	/**
	 * The tree, with any joint whose parent some exported frame flattens hung from a steadier
	 * ancestor. A child's key is its transform relative to its parent's, which a flattened parent
	 * has no inverse to give - and a joint scaled to nothing, as death clips often do, still
	 * leaves children that animate on their own.
	 */
	private static JointTree detachFromSingularParents(JointTree tree, Mesh mesh, Map<Integer, Rig> rigs,
		List<Clip> clips, List<String> report)
	{
		boolean[] singular = new boolean[tree.groups.length];
		for (Clip clip : clips)
		{
			Rig rig = rigs.get(clip.getRigId());
			for (int frame = 0; rig != null && frame < clip.getFrameCount(); frame++)
			{
				double[][] world = worldPose(mesh, tree, rig, clip, frame);
				for (int joint = 0; joint < world.length; joint++)
				{
					singular[joint] |= !Mat4.isInvertible(world[joint]);
				}
			}
		}

		List<Integer> detached = new ArrayList<>();
		for (int joint = 0; joint < tree.groups.length; joint++)
		{
			if (tree.parents[joint] != -1 && singular[tree.parents[joint]])
			{
				detached.add(tree.groups[joint]);
			}
		}
		if (detached.isEmpty())
		{
			return tree;
		}
		report.add("Vertex groups " + detached + " hang from a joint some clip flattens; each is parented to "
			+ "the nearest ancestor that stays whole instead");
		return tree.reparentedAround(singular);
	}

	/** The faces with at least one corner in the group. */
	private static int facesTouching(Mesh mesh, int group)
	{
		boolean[] member = new boolean[mesh.getVerticesCount()];
		for (int vertex : mesh.getVertexGroup(group))
		{
			member[vertex] = true;
		}
		int faces = 0;
		for (int face = 0; face < mesh.getFaceCount(); face++)
		{
			if (member[mesh.getFaceIndices1()[face]] || member[mesh.getFaceIndices2()[face]]
				|| member[mesh.getFaceIndices3()[face]])
			{
				faces++;
			}
		}
		return faces;
	}

	private static void channel(Gltf.Animation animation, int input, int output, int node, String path)
	{
		Gltf.Sampler sampler = new Gltf.Sampler();
		sampler.input = input;
		sampler.output = output;
		sampler.interpolation = "STEP";
		animation.samplers.add(sampler);

		Gltf.Channel channel = new Gltf.Channel();
		channel.sampler = animation.samplers.size() - 1;
		channel.target = new Gltf.Target();
		channel.target.node = node;
		channel.target.path = path;
		animation.channels.add(channel);
	}
}
