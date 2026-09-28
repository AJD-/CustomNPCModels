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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import com.customnpcmodels.inject.Mesh;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import org.junit.Test;

/**
 * Stage A: static geometry through glTF and back.
 * <p>
 * The round trip alone cannot catch a writer and reader that are wrong in compensating ways, so
 * the winding and color are also checked against things computed without the converter: face
 * normals straight from the file's own bytes, and a color pair worked out by hand.
 */
public class GltfStaticTest
{
	static Mesh roundTrip(Mesh mesh, List<String> report)
	{
		byte[] glb = GlbWriter.write(mesh, report);
		GltfToMeshConverter.Result result = GltfToMeshConverter.convert(glb, mesh.getId(), 1, Collections.emptyMap());
		report.addAll(result.report);
		return result.mesh;
	}

	static void assertSameMesh(Mesh expected, Mesh actual)
	{
		assertEquals("vertices", expected.getVerticesCount(), actual.getVerticesCount());
		assertArrayEquals("x", expected.getVerticesX(), actual.getVerticesX(), 1e-4f);
		assertArrayEquals("y", expected.getVerticesY(), actual.getVerticesY(), 1e-4f);
		assertArrayEquals("z", expected.getVerticesZ(), actual.getVerticesZ(), 1e-4f);
		assertArrayEquals("faces 1", expected.getFaceIndices1(), actual.getFaceIndices1());
		assertArrayEquals("faces 2", expected.getFaceIndices2(), actual.getFaceIndices2());
		assertArrayEquals("faces 3", expected.getFaceIndices3(), actual.getFaceIndices3());
		assertArrayEquals("colors", expected.getFaceColors(), actual.getFaceColors());
		assertArrayEquals("render types", expected.getFaceRenderTypes(), actual.getFaceRenderTypes());
		assertArrayEquals("transparencies", expected.getFaceTransparencies(), actual.getFaceTransparencies());
		assertArrayEquals("render priorities", expected.getFaceRenderPriorities(), actual.getFaceRenderPriorities());
		assertEquals("priority", expected.getPriority(), actual.getPriority());
		assertNull("face textures never come back", actual.getFaceTextures());
		assertSameGroups(expected.getVertexGroups(), actual.getVertexGroups());
	}

	/** Group tables compare by membership; trailing empty groups carry no information. */
	private static void assertSameGroups(int[][] expected, int[][] actual)
	{
		if (expected == null)
		{
			assertNull("an unrigged mesh stays unrigged", actual);
			return;
		}
		int groups = Math.max(expected.length, actual.length);
		for (int g = 0; g < groups; g++)
		{
			int[] e = g < expected.length && expected[g] != null ? expected[g].clone() : new int[0];
			int[] a = g < actual.length && actual[g] != null ? actual[g].clone() : new int[0];
			java.util.Arrays.sort(e);
			java.util.Arrays.sort(a);
			assertArrayEquals("group " + g, e, a);
		}
	}

	@Test
	public void testHandComputedColorPair()
	{
		// hue 0, saturation 7, luminance 64, worked through the client's palette arithmetic by hand:
		// hue 1/128, saturation 15/16, luminance 1/2 gives q = 31/32, p = 1/32, so red is q, green
		// is p + (q - p) * 6 * (1/128) and blue is p - scaled by 256 and truncated
		int hsl = 7 << 7 | 64;
		assertEquals(0xF81308, RsColor.hslToRgb(hsl));
		assertEquals(hsl, RsColor.rgbToHsl(0xF81308));
	}

	@Test
	public void testEveryColorSurvivesAsWhatIsSeen()
	{
		// Different packed values can draw identically, so the check is on the drawn color
		for (int hue = 0; hue < 64; hue++)
		{
			for (int sat = 0; sat < 8; sat++)
			{
				for (int lum = RsColor.MIN_LUMINANCE; lum <= RsColor.MAX_LUMINANCE; lum++)
				{
					int hsl = hue << 10 | sat << 7 | lum;
					int rgb = RsColor.hslToRgb(hsl);
					assertEquals("hsl " + hsl, rgb, RsColor.hslToRgb(RsColor.rgbToHsl(rgb)));
				}
			}
		}
	}

	@Test
	public void testLuminanceIsClampedAwayFromBlack()
	{
		int hsl = RsColor.rgbToHsl(0x000000);
		assertTrue("luminance " + (hsl & 127), (hsl & 127) >= RsColor.MIN_LUMINANCE);
		assertTrue((RsColor.rgbToHsl(0xFFFFFF) & 127) <= RsColor.MAX_LUMINANCE);
	}

	@Test
	public void testSyntheticMeshRoundTripsExactly()
	{
		Mesh mesh = new TestMesh()
			.priority(3)
			.renderTypes(new byte[]{0, 1})
			.transparencies(new byte[]{0, (byte) 200})
			.priorities(new byte[]{5, 9})
			.build();
		List<String> report = new ArrayList<>();
		assertSameMesh(mesh, roundTrip(mesh, report));
		assertEquals(Collections.emptyList(), report);
	}

	@Test
	public void testAbsentColumnsComeBackNull()
	{
		Mesh mesh = new TestMesh().groups(null).build();
		Mesh back = roundTrip(mesh, new ArrayList<>());
		assertSameMesh(mesh, back);
		assertNull(back.getFaceTransparencies());
		assertNull(back.getFaceRenderTypes());
		assertNull(back.getFaceRenderPriorities());
		assertNull(back.getVertexGroups());
	}

	@Test
	public void testAllZeroTransparenciesStayPresent()
	{
		// Present-but-zero is not the same as absent: a null array puts a model on the opaque path
		Mesh mesh = new TestMesh().transparencies(new byte[]{0, 0}).build();
		assertArrayEquals(new byte[]{0, 0}, roundTrip(mesh, new ArrayList<>()).getFaceTransparencies());
	}

	@Test
	public void testLiveMeshesRoundTrip() throws Exception
	{
		List<Mesh> meshes = new ArrayList<>();
		for (int part : LiveFixtures.MOLE_PARTS)
		{
			meshes.add(LiveFixtures.mesh(part));
		}
		meshes.add(LiveFixtures.mole());
		meshes.add(LiveFixtures.mesh(LiveFixtures.SKELETON_MESH));

		for (Mesh mesh : meshes)
		{
			List<String> report = new ArrayList<>();
			assertSameMesh(mesh, roundTrip(mesh, report));
			assertEquals("mesh " + mesh.getId(), Collections.emptyList(), report);
		}
	}

	/** The Giant Mole's faces as its four models, the way the exporter splits it. */
	static List<MeshPart> moleParts() throws Exception
	{
		List<MeshPart> parts = new ArrayList<>();
		int firstFace = 0;
		for (int i = 0; i < LiveFixtures.MOLE_PARTS.length; i++)
		{
			int faces = LiveFixtures.mesh(LiveFixtures.MOLE_PARTS[i]).getFaceCount();
			parts.add(new MeshPart(MeshPart.name(i, LiveFixtures.MOLE_PARTS[i]), firstFace, faces));
			firstFace += faces;
		}
		return parts;
	}

	/**
	 * A merged mesh written as one glTF mesh per model comes back exactly as the single-mesh file
	 * does: each part carries its own slice of the per-face extras, and seam vertices shared between
	 * parts weld back into one by their original index.
	 */
	@Test
	public void testAMeshWrittenAsPartsRoundTripsExactly() throws Exception
	{
		Mesh mesh = LiveFixtures.mole();
		List<MeshPart> parts = moleParts();
		List<String> report = new ArrayList<>();
		byte[] glb = GlbWriter.write(mesh, parts, null, new ArrayList<>(), Collections.emptyMap(), report);

		Gltf gltf = Glb.read(glb).gltf;
		assertEquals(parts.size(), gltf.meshes.size());
		for (int p = 0; p < parts.size(); p++)
		{
			assertEquals(parts.get(p).name, gltf.meshes.get(p).name);
			assertEquals(p, gltf.meshes.get(p).extras.get(GlbWriter.EXTRA_PART).getAsInt());
		}

		GltfToMeshConverter.Result result = GltfToMeshConverter.convert(glb, mesh.getId(), 1, Collections.emptyMap());
		report.addAll(result.report);
		assertSameMesh(mesh, result.mesh);
		assertEquals(Collections.emptyList(), report);
	}

	/**
	 * An editor may write the parts back in another order. The part index restores the original face
	 * order, which is what render order and anything painted by face index depend on.
	 */
	@Test
	public void testPartsComeBackInTheirOwnOrderWhateverOrderTheFileListsThem() throws Exception
	{
		Mesh mesh = LiveFixtures.mole();
		byte[] glb = GlbWriter.write(mesh, moleParts(), null, new ArrayList<>(), Collections.emptyMap(), new ArrayList<>());
		byte[] reordered = edit(glb, gltf -> Collections.reverse(gltf.scenes.get(0).nodes));

		// The writer puts part N in mesh N, so the meshes a node order visits spell out the part order
		Gltf gltf = Glb.read(reordered).gltf;
		List<Integer> fileOrder = new ArrayList<>();
		for (int node : gltf.scenes.get(0).nodes)
		{
			if (gltf.nodes.get(node).mesh != null)
			{
				fileOrder.add(gltf.nodes.get(node).mesh);
			}
		}
		List<Integer> readOrder = new ArrayList<>();
		for (int node : GltfToMeshConverter.meshNodes(gltf))
		{
			readOrder.add(gltf.nodes.get(node).mesh);
		}
		assertEquals(java.util.Arrays.asList(3, 2, 1, 0), fileOrder);
		assertEquals(java.util.Arrays.asList(0, 1, 2, 3), readOrder);

		assertSameMesh(mesh, GltfToMeshConverter.convert(reordered, mesh.getId(), 1, Collections.emptyMap()).mesh);
	}

	/**
	 * The winding check that does not go through the reader: every triangle in the file, taken in the
	 * file's own corner order, must face the same way as the engine face it came from - which glTF
	 * defines as counter-clockwise, so the normal of the file's order must be the mirror of the
	 * engine's outward normal.
	 */
	@Test
	public void testExportedFacesFaceOutward() throws Exception
	{
		for (Mesh mesh : new Mesh[]{LiveFixtures.mole(), LiveFixtures.mesh(LiveFixtures.SKELETON_MESH), new TestMesh().build()})
		{
			Glb glb = Glb.read(GlbWriter.write(mesh, new ArrayList<>()));
			Gltf.Primitive primitive = glb.gltf.meshes.get(0).primitives.get(0);
			double[] positions = glb.readDoubles(primitive.attributes.get("POSITION"));
			int[] indices = glb.readInts(primitive.indices);

			int checked = 0;
			for (int face = 0; face < mesh.getFaceCount(); face++)
			{
				double[] engine = normal(
					point(mesh, mesh.getFaceIndices1()[face]),
					point(mesh, mesh.getFaceIndices2()[face]),
					point(mesh, mesh.getFaceIndices3()[face]));
				double[] file = normal(
					slice(positions, indices[face * 3]),
					slice(positions, indices[face * 3 + 1]),
					slice(positions, indices[face * 3 + 2]));
				double length = Math.sqrt(dot(engine, engine) * dot(file, file));
				if (length < 1e-9)
				{
					continue;
				}

				// Engine space mirrors into glTF space by negating Y; a normal mirrors the same way
				double[] mirrored = {engine[0], -engine[1], engine[2]};
				assertTrue("mesh " + mesh.getId() + " face " + face + " faces inward in the file",
					dot(mirrored, file) / length > 0.99);
				checked++;
			}
			assertTrue(checked > 0);
		}
	}

	private static double[] point(Mesh mesh, int v)
	{
		return new double[]{mesh.getVerticesX()[v], mesh.getVerticesY()[v], mesh.getVerticesZ()[v]};
	}

	private static double[] slice(double[] positions, int v)
	{
		return new double[]{positions[v * 3], positions[v * 3 + 1], positions[v * 3 + 2]};
	}

	private static double[] normal(double[] a, double[] b, double[] c)
	{
		double[] ab = {b[0] - a[0], b[1] - a[1], b[2] - a[2]};
		double[] ac = {c[0] - a[0], c[1] - a[1], c[2] - a[2]};
		return new double[]{ab[1] * ac[2] - ab[2] * ac[1], ab[2] * ac[0] - ab[0] * ac[2], ab[0] * ac[1] - ab[1] * ac[0]};
	}

	private static double dot(double[] a, double[] b)
	{
		return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
	}

	/** Re-writes a file after editing its JSON, the way a round trip through Blender would. */
	static byte[] edit(byte[] glbBytes, Consumer<Gltf> edit)
	{
		Glb glb = Glb.read(glbBytes);
		edit.accept(glb.gltf);
		return Glb.write(glb.gltf, glb.binBytes());
	}

	/**
	 * Without the writer's hints - a file Blender re-exported without custom attributes - vertices
	 * weld by position and colors come from COLOR_0. The mesh is the same shape with the same colors,
	 * though duplicate vertices sharing a place and a group merge into one.
	 */
	@Test
	public void testWithoutHintsTheSameGeometryComesBack() throws Exception
	{
		Mesh mesh = LiveFixtures.mole();
		byte[] stripped = edit(GlbWriter.write(mesh, new ArrayList<>()), gltf ->
		{
			gltf.meshes.get(0).primitives.get(0).attributes.remove(GlbWriter.RS_VERTEX);
			gltf.meshes.get(0).primitives.get(0).attributes.remove(GlbWriter.RS_HSL);
		});
		Mesh back = GltfToMeshConverter.convert(stripped, mesh.getId(), 1, Collections.emptyMap()).mesh;

		assertEquals(mesh.getFaceCount(), back.getFaceCount());
		assertTrue(back.getVerticesCount() <= mesh.getVerticesCount());
		for (int face = 0; face < mesh.getFaceCount(); face++)
		{
			int[] original = {mesh.getFaceIndices1()[face], mesh.getFaceIndices2()[face], mesh.getFaceIndices3()[face]};
			int[] converted = {back.getFaceIndices1()[face], back.getFaceIndices2()[face], back.getFaceIndices3()[face]};
			for (int k = 0; k < 3; k++)
			{
				assertArrayEquals("face " + face + " corner " + k, point(mesh, original[k]), point(back, converted[k]), 1e-4);
			}
			// Luminance comes back clamped to 1..126 - 0 is black under any light, so an authored
			// color never lands there - and the original is compared under the same clamp
			int hsl = mesh.getFaceColors()[face] & 0xFFFF;
			int clamped = (hsl & ~127) | Math.max(RsColor.MIN_LUMINANCE, Math.min(RsColor.MAX_LUMINANCE, hsl & 127));
			assertEquals("face " + face + " draws the same color",
				RsColor.hslToRgb(clamped), RsColor.hslToRgb(back.getFaceColors()[face] & 0xFFFF));
		}
	}

	/** A color edited in Blender must win over the stale hint the writer left beside it. */
	@Test
	public void testAnEditedColorOverridesTheHint()
	{
		Mesh mesh = new TestMesh().build();
		byte[] glb = GlbWriter.write(mesh, new ArrayList<>());
		Glb parsed = Glb.read(glb);
		int colorAccessor = parsed.gltf.meshes.get(0).primitives.get(0).attributes.get("COLOR_0");
		Gltf.BufferView view = parsed.gltf.bufferViews.get(parsed.gltf.accessors.get(colorAccessor).bufferView);

		// Paint face 0's three corners pure blue in the BIN chunk
		byte[] bin = parsed.binBytes();
		java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bin).order(java.nio.ByteOrder.LITTLE_ENDIAN);
		for (int corner = 0; corner < 3; corner++)
		{
			int at = view.byteOffset + corner * 16;
			buffer.putFloat(at, 0f);
			buffer.putFloat(at + 4, 0f);
			buffer.putFloat(at + 8, 1f);
		}
		Mesh back = GltfToMeshConverter.convert(Glb.write(parsed.gltf, bin), 1, 1, Collections.emptyMap()).mesh;

		assertEquals(RsColor.rgbToHsl(0x0000FF), back.getFaceColors()[0]);
		assertEquals("the untouched face keeps its exact color", mesh.getFaceColors()[1], back.getFaceColors()[1]);
	}

	/**
	 * Blender's importer drops a scalar custom attribute unless it is float or unsigned byte, and its
	 * exporter keeps a primitive's extras nowhere - so the hints are floats and the extras sit on the
	 * mesh, where both survive a Blender round trip.
	 */
	@Test
	public void testTheHintsAreStoredWhereBlenderKeepsThem()
	{
		Gltf gltf = Glb.read(GlbWriter.write(new TestMesh().build(), new ArrayList<>())).gltf;
		Gltf.Primitive primitive = gltf.meshes.get(0).primitives.get(0);
		assertEquals(Gltf.FLOAT, gltf.accessors.get(primitive.attributes.get(GlbWriter.RS_VERTEX)).componentType);
		assertEquals(Gltf.FLOAT, gltf.accessors.get(primitive.attributes.get(GlbWriter.RS_HSL)).componentType);
		assertNull(primitive.extras);
		assertTrue(gltf.meshes.get(0).extras.has(GlbWriter.EXTRA_PRIORITY));
	}

	/** Files written before the extras moved to the mesh still read the same. */
	@Test
	public void testExtrasOnThePrimitiveAreStillRead()
	{
		Mesh mesh = new TestMesh().build();
		byte[] legacy = edit(GlbWriter.write(mesh, new ArrayList<>()), gltf ->
		{
			gltf.meshes.get(0).primitives.get(0).extras = gltf.meshes.get(0).extras;
			gltf.meshes.get(0).extras = null;
		});
		assertSameMesh(mesh, GltfToMeshConverter.convert(legacy, mesh.getId(), 1, Collections.emptyMap()).mesh);
	}

	private static void assertRefused(byte[] glb, String expected)
	{
		try
		{
			GltfToMeshConverter.convert(glb, 1, 1, Collections.emptyMap());
			fail("expected a refusal naming " + expected);
		}
		catch (GltfException ex)
		{
			assertTrue(ex.getMessage(), ex.getMessage().contains(expected));
		}
	}

	@Test
	public void testRefusesASparseAccessor()
	{
		assertRefused(edit(GlbWriter.write(new TestMesh().build(), new ArrayList<>()),
			gltf -> gltf.accessors.get(0).sparse = new JsonObject()), "sparse");
	}

	@Test
	public void testRefusesAnInterleavedView()
	{
		assertRefused(edit(GlbWriter.write(new TestMesh().build(), new ArrayList<>()),
			gltf -> gltf.bufferViews.get(0).byteStride = 32), "interleaved");
	}

	@Test
	public void testRefusesAnUnsupportedComponentType()
	{
		assertRefused(edit(GlbWriter.write(new TestMesh().build(), new ArrayList<>()),
			gltf -> gltf.accessors.get(0).componentType = 5124), "component type");
	}

	@Test
	public void testRefusesMoreThanOneSkin()
	{
		assertRefused(edit(GlbWriter.write(new TestMesh().build(), new ArrayList<>()),
			gltf -> gltf.skins.add(gltf.skins.get(0))), "skins");
	}

	@Test
	public void testRefusesSomethingThatIsNotAGlb()
	{
		assertRefused("{\"asset\":{\"version\":\"2.0\"}}".getBytes(), "glTF Binary");
	}

	@Test
	public void testRefusesRequiredExtensions()
	{
		assertRefused(edit(GlbWriter.write(new TestMesh().build(), new ArrayList<>()),
			gltf -> gltf.extensionsRequired = Collections.singletonList("KHR_draco_mesh_compression")), "extensions");
	}
}
