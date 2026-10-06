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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import com.customnpcmodels.inject.Mesh;
import com.customnpcmodels.inject.RsColor;
import com.customnpcmodels.inject.TestMesh;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.Test;

/**
 * The painter's save: repainted faces come back in their new color, and nothing else about the file
 * changes - geometry, groups, transparency, the skin, and whatever Blender added.
 */
public class GlbPaintDocumentTest
{
	private static final short RED = RsColor.rgbToHsl(0xC02020);

	private static Mesh convert(byte[] glb)
	{
		return GltfToMeshConverter.convert(glb, 1, 1, Collections.emptyMap()).mesh;
	}

	@Test
	public void testAnUnpaintedSaveConvertsToTheSameMesh()
	{
		byte[] glb = GlbWriter.write(new TestMesh().build(), new ArrayList<>());
		GlbPaintDocument document = GlbPaintDocument.load(glb);

		assertFalse(document.isDirty());
		GltfStaticTest.assertSameMesh(convert(glb), convert(document.save()));
	}

	@Test
	public void testPaintingChangesOnlyThatFace()
	{
		Mesh mesh = new TestMesh().build();
		GlbPaintDocument document = GlbPaintDocument.load(GlbWriter.write(mesh, new ArrayList<>()));
		document.paint(1, RED);
		assertTrue(document.isDirty());

		byte[] saved = document.save();
		GltfToMeshConverter.Result back = GltfToMeshConverter.convert(saved, 1, 1, Collections.emptyMap());
		assertEquals(mesh.getFaceColors()[0], back.mesh.getFaceColors()[0]);
		assertEquals(RED, back.mesh.getFaceColors()[1]);
		assertTrue("the original vertex numbering survives", back.keptVertexNumbering);

		Mesh expected = new TestMesh().colors(new short[]{mesh.getFaceColors()[0], RED}).build();
		GltfStaticTest.assertSameMesh(expected, back.mesh);

		// And the saved file opens again with the new color
		assertEquals(RED, GlbPaintDocument.load(saved).color(1));
	}

	@Test
	public void testSkinDataIsUnchanged()
	{
		byte[] glb = GlbWriter.write(new TestMesh().build(), new ArrayList<>());
		GlbPaintDocument document = GlbPaintDocument.load(glb);
		document.paint(0, RED);

		Glb before = Glb.read(glb);
		Glb after = Glb.read(document.save());
		assertEquals(before.gltf.skins.size(), after.gltf.skins.size());
		assertArrayEquals(before.readDoubles(before.gltf.skins.get(0).inverseBindMatrices),
			after.readDoubles(after.gltf.skins.get(0).inverseBindMatrices), 0);
	}

	/**
	 * Blender merges identical corners, so neighboring faces of one color can share vertices.
	 * Painting one must not bleed into the other, and Blender's normalized-short colors must be
	 * written back in their own encoding.
	 */
	@Test
	public void testSharedCornersDoNotBleed()
	{
		short grey = RsColor.rgbToHsl(0x808080);
		double[] linear = {RsColor.srgbToLinear(0x80), RsColor.srgbToLinear(0x80), RsColor.srgbToLinear(0x80)};

		Gltf gltf = new Gltf();
		Glb.BinBuilder bin = new Glb.BinBuilder(gltf);
		int position = bin.floats(new double[]{0, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1}, "VEC3", Gltf.ARRAY_BUFFER, true);
		int[] shorts = new int[16];
		for (int v = 0; v < 4; v++)
		{
			for (int c = 0; c < 3; c++)
			{
				shorts[v * 4 + c] = (int) Math.round(linear[c] * 65535);
			}
			shorts[v * 4 + 3] = 65535;
		}
		int color = bin.unsignedShorts(shorts, "VEC4", Gltf.ARRAY_BUFFER);
		gltf.accessors.get(color).normalized = true;
		int indices = bin.unsignedShorts(new int[]{0, 1, 2, 0, 2, 3}, "SCALAR", Gltf.ELEMENT_ARRAY_BUFFER);

		Gltf.Primitive primitive = new Gltf.Primitive();
		primitive.attributes = new LinkedHashMap<>();
		primitive.attributes.put("POSITION", position);
		primitive.attributes.put("COLOR_0", color);
		primitive.indices = indices;
		Gltf.MeshDef meshDef = new Gltf.MeshDef();
		meshDef.primitives.add(primitive);
		gltf.meshes.add(meshDef);
		Gltf.Node node = new Gltf.Node();
		node.mesh = 0;
		gltf.nodes.add(node);
		byte[] data = bin.finish();
		byte[] glb = Glb.write(gltf, data);

		GlbPaintDocument document = GlbPaintDocument.load(glb);
		assertEquals(grey, document.color(0));
		assertEquals(grey, document.color(1));
		document.paint(0, RED);
		byte[] saved = document.save();

		Mesh back = convert(saved);
		assertEquals(RED, back.getFaceColors()[0]);
		assertEquals(grey, back.getFaceColors()[1]);

		Glb after = Glb.read(saved);
		Gltf.Accessor savedColor = after.accessor(after.gltf.meshes.get(0).primitives.get(0).attributes.get("COLOR_0"));
		assertEquals("colors keep their encoding", Gltf.UNSIGNED_SHORT, savedColor.componentType);
		assertEquals("one vertex per corner", 6, savedColor.count);
	}

	@Test
	public void testBlenderAdditionsSurvive()
	{
		byte[] glb = GlbWriter.write(new TestMesh().priority(3).build(), new ArrayList<>());
		Glb parsed = Glb.read(glb);
		JsonObject json = Glb.readJson(glb);
		JsonArray materials = new JsonArray();
		JsonObject material = new JsonObject();
		material.addProperty("name", "DefaultMaterial");
		material.addProperty("doubleSided", true);
		materials.add(material);
		json.add("materials", materials);
		JsonObject primitive = json.getAsJsonArray("meshes").get(0).getAsJsonObject()
			.getAsJsonArray("primitives").get(0).getAsJsonObject();
		primitive.addProperty("material", 0);
		byte[] blender = Glb.write(json, parsed.binBytes());

		GlbPaintDocument document = GlbPaintDocument.load(blender);
		document.paint(0, RED);
		JsonObject saved = Glb.readJson(document.save());

		assertEquals(material, saved.getAsJsonArray("materials").get(0));
		JsonObject mesh = saved.getAsJsonArray("meshes").get(0).getAsJsonObject();
		assertEquals(0, mesh.getAsJsonArray("primitives").get(0).getAsJsonObject().get("material").getAsInt());
		assertEquals(3, mesh.getAsJsonObject("extras").get(GlbWriter.EXTRA_PRIORITY).getAsInt());
	}

	@Test
	public void testTransparencyIsKept()
	{
		Mesh mesh = new TestMesh().transparencies(new byte[]{(byte) 100, 0}).build();
		GlbPaintDocument document = GlbPaintDocument.load(GlbWriter.write(mesh, new ArrayList<>()));
		document.paint(0, RED);

		Mesh back = convert(document.save());
		assertEquals(RED, back.getFaceColors()[0]);
		assertArrayEquals(mesh.getFaceTransparencies(), back.getFaceTransparencies());
	}

	/** A file written in parts lists them, named and in face order, for the painter to hide. */
	@Test
	public void testPartsAreListedInFaceOrder() throws Exception
	{
		List<MeshPart> parts = GltfStaticTest.moleParts();
		byte[] glb = GlbWriter.write(LiveFixtures.mole(), parts, null, new ArrayList<>(), Collections.emptyMap(),
			new ArrayList<>());
		List<MeshPart> listed = GlbPaintDocument.load(glb).parts();

		assertEquals(parts.size(), listed.size());
		for (int p = 0; p < parts.size(); p++)
		{
			assertEquals(parts.get(p).name, listed.get(p).name);
			assertEquals(parts.get(p).firstFace, listed.get(p).firstFace);
			assertEquals(parts.get(p).faceCount, listed.get(p).faceCount);
		}
		assertEquals(1, GlbPaintDocument.load(GlbWriter.write(new TestMesh().build(), new ArrayList<>())).parts().size());
	}

	/** Painting a face of one part rewrites that part only; every other part's faces keep their colors. */
	@Test
	public void testPaintingOnePartLeavesTheOthers() throws Exception
	{
		List<MeshPart> parts = GltfStaticTest.moleParts();
		byte[] glb = GlbWriter.write(LiveFixtures.mole(), parts, null, new ArrayList<>(), Collections.emptyMap(),
			new ArrayList<>());
		GlbPaintDocument document = GlbPaintDocument.load(glb);
		short[] expected = convert(glb).getFaceColors().clone();
		MeshPart last = parts.get(parts.size() - 1);
		for (int face = last.firstFace; face < last.firstFace + last.faceCount; face += 3)
		{
			document.paint(face, RED);
			expected[face] = RED;
		}

		byte[] saved = document.save();
		assertArrayEquals(expected, convert(saved).getFaceColors());
		assertEquals(parts.size(), GlbPaintDocument.load(saved).parts().size());
	}

	/** A real NPC: every seventh face repainted, everything else exactly as before. */
	@Test
	public void testPaintingTheMoleKeepsItsGeometry() throws Exception
	{
		Mesh mole = LiveFixtures.mole();
		byte[] glb = GlbWriter.write(mole, new ArrayList<>());
		GlbPaintDocument document = GlbPaintDocument.load(glb);
		short[] expected = convert(glb).getFaceColors().clone();
		for (int face = 0; face < document.faceCount(); face += 7)
		{
			document.paint(face, RED);
			expected[face] = RED;
		}

		GltfToMeshConverter.Result back = GltfToMeshConverter.convert(document.save(), 1, 1, Collections.emptyMap());
		assertTrue(back.keptVertexNumbering);
		assertArrayEquals(expected, back.mesh.getFaceColors());
		Mesh before = convert(glb);
		assertArrayEquals(before.getVerticesX(), back.mesh.getVerticesX(), 0);
		assertArrayEquals(before.getVerticesY(), back.mesh.getVerticesY(), 0);
		assertArrayEquals(before.getVerticesZ(), back.mesh.getVerticesZ(), 0);
		assertArrayEquals(before.getFaceIndices1(), back.mesh.getFaceIndices1());
	}

	/** A model made from scratch may have no colors at all; painting one face gives the file some. */
	@Test
	public void testAFileWithoutColorsGetsThem()
	{
		byte[] glb = GltfStaticTest.edit(GlbWriter.write(new TestMesh().build(), new ArrayList<>()), gltf ->
		{
			gltf.meshes.get(0).primitives.get(0).attributes.remove("COLOR_0");
			gltf.meshes.get(0).primitives.get(0).attributes.remove(GlbWriter.RS_HSL);
		});
		GlbPaintDocument document = GlbPaintDocument.load(glb);
		short grey = document.color(1);
		document.paint(0, RED);

		byte[] saved = document.save();
		Mesh back = convert(saved);
		assertEquals(RED, back.getFaceColors()[0]);
		assertEquals(grey, back.getFaceColors()[1]);
		assertTrue(Glb.read(saved).gltf.meshes.get(0).primitives.get(0).attributes.containsKey("COLOR_0"));
	}

	@Test
	public void testNonTriangleModesAreRefused()
	{
		byte[] glb = GltfStaticTest.edit(GlbWriter.write(new TestMesh().build(), new ArrayList<>()),
			gltf -> gltf.meshes.get(0).primitives.get(0).mode = 1);
		try
		{
			GlbPaintDocument.load(glb);
			fail("expected a line primitive to be refused");
		}
		catch (GltfException ex)
		{
			assertTrue(ex.getMessage(), ex.getMessage().contains("only triangles"));
		}
	}

	@Test
	public void testMorphTargetsAreRefused()
	{
		byte[] glb = GlbWriter.write(new TestMesh().build(), new ArrayList<>());
		JsonObject json = Glb.readJson(glb);
		json.getAsJsonArray("meshes").get(0).getAsJsonObject().getAsJsonArray("primitives").get(0)
			.getAsJsonObject().add("targets", new JsonArray());
		try
		{
			GlbPaintDocument.load(Glb.write(json, Glb.read(glb).binBytes()));
			fail("expected morph targets to be refused");
		}
		catch (GltfException ex)
		{
			assertTrue(ex.getMessage(), ex.getMessage().contains("morph targets"));
		}
	}
}
