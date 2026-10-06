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
package com.customnpcmodels.inject;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.junit.Test;

public class AssetCodecTest
{
	/**
	 * The skeleton's real ids - mesh 2944, framemap 338, idle sequence 262 - so the three factories
	 * read as one coherent kit rather than three unrelated blobs: the rig's group sets address
	 * exactly the three vertex groups this mesh declares, and the clip's transform indices stay in
	 * range of the rig's three transforms.
	 *
	 * <p>The geometry is one textured triangle, the smallest shape that still gives every array the
	 * codec writes something to carry. Only the priority and the vertex groups are asserted as
	 * literals; every other value is compared against this same fixture, so any valid one would do.
	 */
	private static Mesh mesh()
	{
		return new Mesh(2944, 5,
			new float[]{0f, 10f, 20f},
			new float[]{0f, -30f, 5f},
			new float[]{0f, 40f, -15f},
			new int[]{0}, new int[]{1}, new int[]{2},
			new short[]{(short) 0x3A05},   // packed HSL, not an RGB color
			new byte[]{1},
			null,                      // deliberately null - see the round-trip test below
			new byte[]{2},
			new short[]{37},           // any texture id; the codec does not interpret it
			// one face mapped by the one texture triangle, which names the mesh's own vertices
			new byte[]{0},
			new int[]{0}, new int[]{1}, new int[]{2},
			// populated and empty, the pair the null-versus-empty test has to tell apart
			new int[][]{{0, 1}, {}, {2}});
	}

	/**
	 * Three transforms whose types - pivot, rotate, translate, see {@link Rig#getType} for the
	 * codes - are deliberately out of order, so a codec that wrote a transform's index where its
	 * type belongs would fail rather than round-trip. The group sets address the mesh's own groups,
	 * and the second one holds two so a row of more than one survives the matrix encoding.
	 */
	private static Rig rig()
	{
		return new Rig(338, new int[]{0, 2, 1}, new int[][]{{0}, {0, 1}, {2}});
	}

	/**
	 * Two frames against rig 338, carrying a different number of ops each so a frame cannot come
	 * back with the op count of the wrong row. The three deltas are distinct per axis at the op the
	 * assertions read, which is what catches a transposed x/y/z instead of passing on symmetry.
	 */
	private static Clip clip()
	{
		return new Clip(262, 338,
			new int[][]{{0, 1}, {1}},
			new int[][]{{5, -5}, {0}},
			new int[][]{{0, 128}, {7}},
			new int[][]{{-3, 3}, {0}});
	}

	private static AssetBundle roundTrip(AssetBundle bundle) throws IOException
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		AssetCodec.write(bundle, out);
		return AssetCodec.read(new ByteArrayInputStream(out.toByteArray()));
	}

	private static AssetBundle sample()
	{
		Map<Integer, Mesh> meshes = new LinkedHashMap<>();
		meshes.put(2944, mesh());
		Map<Integer, Rig> rigs = new LinkedHashMap<>();
		rigs.put(338, rig());
		return new AssetBundle(meshes, rigs, Collections.singletonList(clip()));
	}

	@Test
	public void testMeshSurvivesRoundTrip() throws IOException
	{
		Mesh original = mesh();
		Mesh restored = roundTrip(sample()).getMesh(2944);

		assertNotNull(restored);
		assertEquals(original.getId(), restored.getId());

		// The model-level priority is the per-face fallback a merge needs when only one part carries
		// a priority array, so losing it in the round trip would change draw order on merged models
		assertEquals(5, restored.getPriority());
		assertEquals(original.getPriority(), restored.getPriority());

		assertEquals(original.getVerticesCount(), restored.getVerticesCount());
		assertEquals(original.getFaceCount(), restored.getFaceCount());
		assertArrayEquals(original.getVerticesX(), restored.getVerticesX(), 0f);
		assertArrayEquals(original.getVerticesY(), restored.getVerticesY(), 0f);
		assertArrayEquals(original.getVerticesZ(), restored.getVerticesZ(), 0f);
		assertArrayEquals(original.getFaceIndices1(), restored.getFaceIndices1());
		assertArrayEquals(original.getFaceColors(), restored.getFaceColors());
		assertArrayEquals(original.getFaceRenderTypes(), restored.getFaceRenderTypes());
		assertArrayEquals(original.getFaceRenderPriorities(), restored.getFaceRenderPriorities());
		assertArrayEquals(original.getFaceTextures(), restored.getFaceTextures());

		// Without these the renderer falls back to a hardcoded (0,0), (1,0), (0,1) per face, which
		// stretches the whole texture across every face separately
		assertArrayEquals(original.getTextureCoords(), restored.getTextureCoords());
		assertArrayEquals(original.getTexIndices1(), restored.getTexIndices1());
		assertArrayEquals(original.getTexIndices2(), restored.getTexIndices2());
		assertArrayEquals(original.getTexIndices3(), restored.getTexIndices3());
	}

	/**
	 * Null and empty are different to the renderer - a null transparency array is what puts a model
	 * on the opaque path, while an empty one is a zero-face model - so the encoding has to keep them
	 * apart rather than normalising one into the other.
	 */
	@Test
	public void testNullArraysStayNullAndEmptyStaysEmpty() throws IOException
	{
		Mesh restored = roundTrip(sample()).getMesh(2944);

		assertNull("a null array must not come back as empty", restored.getFaceTransparencies());
		assertEquals("an empty vertex group must not come back as null or populated",
			0, restored.getVertexGroup(1).length);
		assertArrayEquals(new int[]{0, 1}, restored.getVertexGroup(0));
	}

	@Test
	public void testRigAndClipSurviveRoundTrip() throws IOException
	{
		AssetBundle restored = roundTrip(sample());

		Rig restoredRig = restored.getRig(338);
		assertNotNull(restoredRig);
		assertEquals(3, restoredRig.getTransformCount());
		assertEquals(2, restoredRig.getType(1));
		assertArrayEquals(new int[]{0, 1}, restoredRig.getGroups(1));

		Clip restoredClip = restored.getClip(338, 262);
		assertNotNull(restoredClip);
		assertNull("a clip is found by its rig as well as its sequence", restored.getClip(339, 262));
		assertEquals(338, restoredClip.getRigId());
		assertEquals(2, restoredClip.getFrameCount());
		assertEquals(2, restoredClip.getOpCount(0));
		assertEquals(1, restoredClip.getTransform(0, 1));
		assertEquals(-5, restoredClip.getDx(0, 1));
		assertEquals(128, restoredClip.getDy(0, 1));
		assertEquals(3, restoredClip.getDz(0, 1));
	}

	@Test
	public void testOutOfRangeGroupIsEmptyRatherThanAThrow()
	{
		// Rigs are shared across a category and address more groups than any one mesh uses, so this
		// is the normal case rather than an error
		assertEquals(0, mesh().getVertexGroup(99).length);
		assertEquals(0, mesh().getVertexGroup(-1).length);
	}

	@Test
	public void testEmptyBundleRoundTrips() throws IOException
	{
		assertTrue(roundTrip(AssetBundle.empty()).isEmpty());
	}

	@Test
	public void testRejectsForeignData()
	{
		try
		{
			AssetCodec.read(new ByteArrayInputStream(new byte[]{1, 2, 3, 4, 5, 6, 7, 8}));
			fail("expected a refusal rather than a partial read");
		}
		catch (IOException expected)
		{
			// A bundle that is not ours must be refused outright, not decoded into plausible
			// geometry that then renders as garbage
		}
	}

	/**
	 * A bundle written by a different generator has to be refused, not read. Silently misreading a
	 * changed layout produces geometry that looks plausible and renders as garbage, which is far
	 * harder to diagnose than a startup failure.
	 */
	@Test
	public void testRejectsAnUnknownVersion() throws IOException
	{
		ByteArrayOutputStream raw = new ByteArrayOutputStream();
		try (DataOutputStream data = new DataOutputStream(new GZIPOutputStream(raw)))
		{
			data.writeInt(0x434E5043);              // correct magic
			data.writeInt(AssetCodec.VERSION + 1);
			data.writeInt(0);
			data.writeInt(0);
			data.writeInt(0);
		}

		try
		{
			AssetCodec.read(new ByteArrayInputStream(raw.toByteArray()));
			fail("expected a refusal for a bundle from a newer generator");
		}
		catch (IOException expected)
		{
			assertTrue("the message should say to regenerate, not just that something went wrong",
				expected.getMessage().contains("regenerate"));
		}
	}

	@Test
	public void testRejectsALengthTheDataCannotHold() throws IOException
	{
		ByteArrayOutputStream raw = new ByteArrayOutputStream();
		try (DataOutputStream data = new DataOutputStream(new GZIPOutputStream(raw)))
		{
			data.writeInt(0x434E5043);
			data.writeInt(AssetCodec.VERSION);
			data.writeInt(1);           // one mesh
			data.writeInt(1);           // its id
			data.writeByte(0);          // its priority
			data.writeInt(9_000_000);   // vertices it claims, with none behind them
		}

		try
		{
			AssetCodec.read(new ByteArrayInputStream(raw.toByteArray()));
			fail("expected a refusal before allocating for data that isn't there");
		}
		catch (IOException expected)
		{
			assertTrue(expected.getMessage(), expected.getMessage().contains("Implausible array length"));
		}
	}

	@Test
	public void testRejectsANonFiniteCoordinate() throws IOException
	{
		float[] vy = mesh().getVerticesY().clone();
		vy[0] = Float.NaN;

		String message = refusalFor(meshWith(vy, null, null, null));
		assertTrue(message, message.contains("NaN"));
	}

	/**
	 * A rig is one table written as two blocks, so they can disagree without anything else noticing.
	 * {@link Skinner} bounds its loop on the transform count and indexes the group sets with
	 * it, which turns a short groups block into an exception inside the render path - a frame into
	 * the fight rather than at load.
	 */
	@Test
	public void testRejectsARigWhoseTablesDisagree() throws IOException
	{
		ByteArrayOutputStream raw = new ByteArrayOutputStream();
		try (DataOutputStream data = new DataOutputStream(new GZIPOutputStream(raw)))
		{
			data.writeInt(0x434E5043);
			data.writeInt(AssetCodec.VERSION);

			data.writeInt(0);                       // no meshes

			data.writeInt(1);                       // one rig
			data.writeInt(338);                     // its id
			data.writeInt(3);                       // three transform types
			data.writeInt(0);
			data.writeInt(2);
			data.writeInt(1);
			data.writeInt(2);                       // but only two group sets
			data.writeInt(1);
			data.writeInt(0);
			data.writeInt(1);
			data.writeInt(0);

			data.writeInt(0);                       // no clips
		}

		try
		{
			AssetCodec.read(new ByteArrayInputStream(raw.toByteArray()));
			fail("expected a refusal for a rig whose two tables disagree");
		}
		catch (IOException expected)
		{
			assertTrue("the message should name the mismatch it found: " + expected.getMessage(),
				expected.getMessage().contains("3 transforms but 2 group sets"));
		}
	}

	/**
	 * Writes a bundle holding one malformed mesh and returns what reading it back threw.
	 *
	 * <p>Goes through {@link AssetCodec#write} rather than hand-assembled bytes because
	 * neither {@link Mesh} nor {@link Clip} validates its own arguments - the writer will
	 * happily emit any of these, which is exactly the point. That also keeps each case a
	 * one-argument change away from the good fixture, so what is being rejected is legible.
	 */
	private static String refusalFor(Mesh malformed) throws IOException
	{
		Map<Integer, Mesh> meshes = new LinkedHashMap<>();
		meshes.put(malformed.getId(), malformed);
		return refusalFor(new AssetBundle(meshes, Collections.emptyMap(), Collections.emptyList()));
	}

	private static String refusalFor(Clip malformed) throws IOException
	{
		return refusalFor(new AssetBundle(Collections.emptyMap(), Collections.emptyMap(),
			Collections.singletonList(malformed)));
	}

	private static String refusalFor(AssetBundle bundle) throws IOException
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		AssetCodec.write(bundle, out);

		try
		{
			AssetCodec.read(new ByteArrayInputStream(out.toByteArray()));
			fail("expected a refusal rather than geometry that throws somewhere else later");
			return null;
		}
		catch (IOException expected)
		{
			assertTrue("the message should say to regenerate: " + expected.getMessage(),
				expected.getMessage().contains("regenerate the bundle"));
			return expected.getMessage();
		}
	}

	/**
	 * Builds a mesh from the good fixture with one column replaced, so each rejection test differs
	 * from a mesh that loads by exactly the thing being rejected.
	 */
	private static Mesh meshWith(float[] vy, int[] i1, short[] colors, int[][] vertexGroups)
	{
		Mesh good = mesh();
		return new Mesh(good.getId(), good.getPriority(),
			good.getVerticesX(), vy == null ? good.getVerticesY() : vy, good.getVerticesZ(),
			i1 == null ? good.getFaceIndices1() : i1, good.getFaceIndices2(), good.getFaceIndices3(),
			colors == null ? good.getFaceColors() : colors,
			good.getFaceRenderTypes(), good.getFaceTransparencies(),
			good.getFaceRenderPriorities(), good.getFaceTextures(),
			good.getTextureCoords(), good.getTexIndices1(), good.getTexIndices2(),
			good.getTexIndices3(), vertexGroups == null ? good.getVertexGroups() : vertexGroups);
	}

	/**
	 * Each vertex axis is its own length-prefixed block and {@link Mesh} takes its vertex count
	 * from the x axis alone, so a short y axis reads cleanly and then runs off the end wherever the
	 * mesh is next walked.
	 */
	@Test
	public void testRejectsAMeshWhoseVertexAxesDisagree() throws IOException
	{
		String message = refusalFor(meshWith(new float[]{0f, -30f}, null, null, null));
		assertTrue("the message should name the three lengths: " + message,
			message.contains("3, 2 and 3 vertices"));
	}

	/**
	 * The failure this one prevents is the expensive one: a face index past the vertex arrays
	 * throws inside {@code Lighter.computeNormals}, reached from
	 * {@code ModelCache.ensureBuilt}, which only records an id as unbuildable when the build
	 * returns null. A throw skips that, so the work is retried on every spawn of that NPC.
	 */
	@Test
	public void testRejectsAFaceIndexPastTheVertices() throws IOException
	{
		String message = refusalFor(meshWith(null, new int[]{7}, null, null));
		assertTrue("the message should name the face and the vertex it reached for: " + message,
			message.contains("face 0 names vertex 7 of 3"));
	}

	/**
	 * A group member is a vertex index the skinner writes through on the render path, where
	 * {@code CustomDrawCallbacks} catches the throw and silently draws the vanilla model instead.
	 */
	@Test
	public void testRejectsAVertexGroupMemberPastTheVertices() throws IOException
	{
		String message = refusalFor(meshWith(null, null, null, new int[][]{{0, 1}, {}, {9}}));
		assertTrue("the message should name the group and the vertex it reached for: " + message,
			message.contains("vertex group 2 names vertex 9 of 3"));
	}

	/**
	 * A per-face column may be absent entirely - null carries meaning to the renderer - but a
	 * present one has to cover every face, because every consumer indexes it by face.
	 */
	@Test
	public void testRejectsAPerFaceColumnOfTheWrongLength() throws IOException
	{
		String message = refusalFor(
			meshWith(null, null, new short[]{(short) 0x3A05, (short) 0x3A06}, null));
		assertTrue("the message should name the column and both counts: " + message,
			message.contains("2 face colors for 1 faces"));
	}

	/**
	 * A clip's four columns are one table written as four blocks. {@link Skinner} bounds its
	 * loop on the transform column's length and then indexes the three delta columns with it, so a
	 * short one throws on the render path rather than at load.
	 */
	@Test
	public void testRejectsAClipWhoseFrameCountsDisagree() throws IOException
	{
		String message = refusalFor(new Clip(262, 338,
			new int[][]{{0, 1}, {1}},
			new int[][]{{5, -5}, {0}},
			new int[][]{{0, 128}},          // one frame short
			new int[][]{{-3, 3}, {0}}));

		assertTrue("the message should name the four frame counts: " + message,
			message.contains("2, 2, 1 and 2 frames"));
	}

	@Test
	public void testRejectsAClipFrameWhoseOpCountsDisagree() throws IOException
	{
		String message = refusalFor(new Clip(262, 338,
			new int[][]{{0, 1}, {1}},
			new int[][]{{5, -5}, {0}},
			new int[][]{{0, 128}, {7}},
			new int[][]{{-3}, {0}}));       // frame 0 is one op short

		assertTrue("the message should name the frame and the four op counts: " + message,
			message.contains("frame 0 has 2, 2, 2 and 1 ops"));
	}

	@Test
	public void testBundleLookupsMissUnknownIds() throws IOException
	{
		AssetBundle restored = roundTrip(new AssetBundle(
			Collections.emptyMap(), Collections.emptyMap(), Collections.emptyList()));

		assertNull(restored.getMesh(1));
		assertNull(restored.getRig(1));
		assertNull(restored.getClip(1, 1));
		assertNull(restored.getBinding(1));
	}

	private static AssetBundle withBindings(NpcBinding... bindings)
	{
		AssetBundle base = sample();
		return new AssetBundle(base.getMeshes(), base.getRigs(), base.getClips(),
			java.util.Arrays.asList(bindings));
	}

	@Test
	public void testBindingSurvivesRoundTrip() throws IOException
	{
		NpcBinding original = TestBinding.of("Skeleton", new int[]{70, 71}, new int[]{2944})
			.scale(118, 140)
			.recolors(new short[]{0x3A05}, new short[]{(short) -25049})
			.build();
		AssetBundle restored = roundTrip(withBindings(original));

		assertEquals(1, restored.getBindings().size());
		NpcBinding binding = restored.getBinding(71);
		assertNotNull("every NPC id a binding names should look it up", binding);
		assertSame(binding, restored.getBinding(70));
		assertEquals("Skeleton", binding.getName());
		assertArrayEquals(new int[]{70, 71}, binding.getNpcIds());
		assertArrayEquals(new int[]{2944}, binding.getMeshIds());
		assertEquals(118, binding.getScaleXZ());
		assertEquals(140, binding.getScaleY());
		assertArrayEquals(original.getRecolorFind(), binding.getRecolorFind());
		assertArrayEquals(original.getRecolorReplace(), binding.getRecolorReplace());
	}

	@Test
	public void testBindingWithoutRecolorsKeepsThemNull() throws IOException
	{
		NpcBinding binding = roundTrip(withBindings(
			TestBinding.of("Plain", new int[]{70}, new int[]{2944}).build()))
			.getBinding(70);

		assertNull(binding.getRecolorFind());
		assertNull(binding.getRecolorReplace());
		assertTrue(!binding.hasRecolors());
	}

	@Test
	public void testBindingKeepsItsChathead() throws IOException
	{
		NpcBinding binding = roundTrip(withBindings(
			TestBinding.of("Vyrewatch maid", new int[]{223}, new int[]{2944}).chathead(3709).build()))
			.getBinding(223);

		assertEquals(3709, binding.getChatheadNpcId());
		assertTrue(binding.hasChathead());
	}

	@Test
	public void testBindingWithoutAChatheadKeepsItsOwn() throws IOException
	{
		NpcBinding binding = roundTrip(withBindings(
			TestBinding.of("Plain", new int[]{70}, new int[]{2944}).build()))
			.getBinding(70);

		assertEquals(NpcBinding.NO_CHATHEAD, binding.getChatheadNpcId());
		assertTrue(!binding.hasChathead());
	}

	@Test
	public void testRejectsANegativeChathead() throws IOException
	{
		String message = refusalFor(withBindings(
			TestBinding.of("Headless", new int[]{70}, new int[]{2944}).chathead(-2).build()));

		assertTrue(message, message.contains("chathead NPC -2"));
	}

	@Test
	public void testBindingKeepsItsCustomChathead() throws IOException
	{
		NpcBinding binding = roundTrip(withBindings(
			TestBinding.of("Talker", new int[]{223}, new int[]{2944}).chatheadMesh(2944, 338).build()))
			.getBinding(223);

		assertEquals(2944, binding.getChatheadMeshId());
		assertEquals(338, binding.getChatheadRigId());
		assertTrue(binding.hasCustomChathead());
	}

	@Test
	public void testRejectsBothKindsOfChathead() throws IOException
	{
		String message = refusalFor(withBindings(
			TestBinding.of("Two heads", new int[]{70}, new int[]{2944}).chathead(3709).chatheadMesh(2944, 338).build()));

		assertTrue(message, message.contains("both a chathead NPC and a chathead mesh"));
	}

	@Test
	public void testRejectsAChatheadMeshTheBundleLacks() throws IOException
	{
		String message = refusalFor(withBindings(
			TestBinding.of("Headless", new int[]{70}, new int[]{2944}).chatheadMesh(9999, 338).build()));

		assertTrue(message, message.contains("chathead mesh 9999"));
	}

	@Test
	public void testRejectsAChatheadRigTheBundleLacks() throws IOException
	{
		String message = refusalFor(withBindings(
			TestBinding.of("Stiff", new int[]{70}, new int[]{2944}).chatheadMesh(2944, 999).build()));

		assertTrue(message, message.contains("chathead rig 999"));
	}

	@Test
	public void testRejectsABindingToAMissingMesh() throws IOException
	{
		String message = refusalFor(withBindings(
			TestBinding.of("Ghost", new int[]{70}, new int[]{2944, 9999}).build()));

		assertTrue("the message should name the missing mesh: " + message, message.contains("mesh 9999"));
	}

	@Test
	public void testRejectsAnNpcBoundTwice() throws IOException
	{
		String message = refusalFor(withBindings(
			TestBinding.of("First", new int[]{70}, new int[]{2944}).build(),
			TestBinding.of("Second", new int[]{71, 70}, new int[]{2944}).build()));

		assertTrue("the message should name the NPC: " + message, message.contains("NPC 70"));
	}

	@Test
	public void testRejectsUnpairedRecolors() throws IOException
	{
		String message = refusalFor(withBindings(
			TestBinding.of("Odd", new int[]{70}, new int[]{2944}).recolors(new short[]{1, 2}, new short[]{3}).build()));

		assertTrue(message, message.contains("unpaired recolors"));
	}

	@Test
	public void testRejectsANonPositiveScale() throws IOException
	{
		String message = refusalFor(withBindings(
			TestBinding.of("Flat", new int[]{70}, new int[]{2944}).scale(128, 0).build()));

		assertTrue(message, message.contains("scale 128/0"));
	}

	@Test
	public void testBindingKeepsItsRig() throws IOException
	{
		NpcBinding rigged = roundTrip(withBindings(
			TestBinding.of("Rigged", new int[]{70}, new int[]{2944}).rig(338).build()))
			.getBinding(70);
		assertEquals(338, rigged.getRigId());

		NpcBinding still = roundTrip(withBindings(
			TestBinding.of("Still", new int[]{70}, new int[]{2944}).build()))
			.getBinding(70);
		assertEquals("a model with no rig says so, rather than naming one", NpcBinding.STATIC, still.getRigId());
	}

	@Test
	public void testRejectsABindingToAMissingRig() throws IOException
	{
		String message = refusalFor(withBindings(
			TestBinding.of("Unrigged", new int[]{70}, new int[]{2944}).rig(999).build()));

		assertTrue(message, message.contains("rig 999"));
	}

	/** The first mesh is the key a model is switched off by, so two models cannot share one. */
	@Test
	public void testRejectsTwoBindingsStartingWithOneMesh() throws IOException
	{
		String message = refusalFor(withBindings(
			TestBinding.of("First", new int[]{70}, new int[]{2944}).build(),
			TestBinding.of("Recolored", new int[]{71}, new int[]{2944}).recolors(new short[]{1}, new short[]{2}).build()));

		assertTrue(message, message.contains("starts with mesh 2944"));
	}

	/**
	 * Two models on their own rigs may both answer for one live sequence - the humanoid sequences
	 * are shared by every humanoid NPC - and each must come back with its own clip.
	 */
	@Test
	public void testClipsOnDifferentRigsShareASequence() throws IOException
	{
		AssetBundle base = sample();
		Map<Integer, Rig> rigs = new LinkedHashMap<>(base.getRigs());
		rigs.put(339, new Rig(339, new int[]{0, 2, 1}, new int[][]{{0}, {0, 1}, {2}}));
		Clip other = new Clip(262, 339,
			new int[][]{{0}},
			new int[][]{{99}},
			new int[][]{{0}},
			new int[][]{{0}});

		AssetBundle restored = roundTrip(new AssetBundle(base.getMeshes(), rigs, Arrays.asList(clip(), other)));

		assertEquals(2, restored.getClips().size());
		assertEquals(2, restored.getClip(338, 262).getFrameCount());
		assertEquals(99, restored.getClip(339, 262).getDx(0, 0));
	}

	@Test
	public void testRejectsOneClipTwice() throws IOException
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		// The bundle keys clips as it is built, so the duplicate is written by hand past it
		try (DataOutputStream data = new DataOutputStream(new GZIPOutputStream(out)))
		{
			data.writeInt(0x434E5043);
			data.writeInt(AssetCodec.VERSION);
			data.writeInt(0);                       // no meshes
			data.writeInt(0);                       // no rigs
			data.writeInt(2);                       // two clips, both sequence 262 on rig 338
			for (int i = 0; i < 2; i++)
			{
				data.writeInt(262);
				data.writeInt(338);
				for (int column = 0; column < 4; column++)
				{
					data.writeInt(1);               // one frame
					data.writeInt(1);               // of one op
					data.writeInt(0);
				}
			}
			data.writeInt(0);                       // no bindings
		}

		try
		{
			AssetCodec.read(new ByteArrayInputStream(out.toByteArray()));
			fail("expected a refusal for a clip that would shadow another");
		}
		catch (IOException expected)
		{
			assertTrue(expected.getMessage(), expected.getMessage().contains("appears twice on rig 338"));
		}
	}

	/** Nothing has shipped in the old layout, so it is refused rather than read two ways. */
	@Test
	public void testRejectsAVersion2Bundle() throws IOException
	{
		ByteArrayOutputStream raw = new ByteArrayOutputStream();
		try (DataOutputStream data = new DataOutputStream(new GZIPOutputStream(raw)))
		{
			data.writeInt(0x434E5043);
			data.writeInt(2);
		}

		try
		{
			AssetCodec.read(new ByteArrayInputStream(raw.toByteArray()));
			fail("expected a version 2 bundle to be refused");
		}
		catch (IOException expected)
		{
			assertTrue(expected.getMessage(), expected.getMessage().contains("version 2"));
		}
	}

	/** Packs come from disk and the hub, so a mesh past what the renderer can take never loads. */
	@Test
	public void testRejectsAMeshPastTheCeiling() throws IOException
	{
		int count = AssetCodec.MAX_VERTICES + 1;
		Mesh huge = new TestMesh()
			.id(2944)
			.vx(new float[count])
			.vy(new float[count])
			.vz(new float[count])
			.i1(new int[0])
			.i2(new int[0])
			.i3(new int[0])
			.colors(new short[0])
			.groups(null)
			.build();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		Map<Integer, Mesh> meshes = new LinkedHashMap<>();
		meshes.put(2944, huge);
		AssetCodec.write(new AssetBundle(meshes, Collections.emptyMap(), Collections.emptyList()), out);

		try
		{
			AssetCodec.read(new ByteArrayInputStream(out.toByteArray()));
			fail("expected a refusal for a mesh past the vertex ceiling");
		}
		catch (IOException expected)
		{
			assertTrue(expected.getMessage(), expected.getMessage().contains("ceiling"));
		}
	}

	@Test
	public void testRejectsAFaceTheLighterWouldHide() throws IOException
	{
		Mesh good = mesh();
		Mesh hidden = new Mesh(good.getId(), good.getPriority(),
			good.getVerticesX(), good.getVerticesY(), good.getVerticesZ(),
			good.getFaceIndices1(), good.getFaceIndices2(), good.getFaceIndices3(),
			good.getFaceColors(), new byte[]{2}, good.getFaceTransparencies(),
			good.getFaceRenderPriorities(), good.getFaceTextures(),
			good.getTextureCoords(), good.getTexIndices1(), good.getTexIndices2(), good.getTexIndices3(),
			good.getVertexGroups());

		String message = refusalFor(hidden);
		assertTrue(message, message.contains("render type 2"));
	}

	/** A small file can inflate to anything, so the reader counts what it inflates. */
	@Test
	public void testTheInflatedSizeIsCapped() throws IOException
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		AssetCodec.write(sample(), out);

		try
		{
			AssetCodec.read(new ByteArrayInputStream(out.toByteArray()), 64);
			fail("expected reading past the cap to be refused");
		}
		catch (IOException expected)
		{
			assertTrue(expected.getMessage(), expected.getMessage().contains("inflates past"));
		}
	}

	/** Each part can pass on its own and the model still be too big: the ceiling is on the merge. */
	@Test
	public void testRejectsABindingThatMergesPastTheCeiling() throws IOException
	{
		// Whole triangles, since the merge only keeps vertices a face uses
		int faces = AssetCodec.MAX_VERTICES / 6 + 1;
		int count = faces * 3;
		Map<Integer, Mesh> meshes = new LinkedHashMap<>();
		for (int id : new int[]{1, 2})
		{
			// Every vertex somewhere different, so the merge has none to weld
			float[] vx = new float[count];
			float[] vy = new float[count];
			for (int v = 0; v < count; v++)
			{
				vx[v] = id * count + v;
				vy[v] = v % 3;
			}
			int[] i1 = new int[faces];
			int[] i2 = new int[faces];
			int[] i3 = new int[faces];
			for (int f = 0; f < faces; f++)
			{
				i1[f] = f * 3;
				i2[f] = f * 3 + 1;
				i3[f] = f * 3 + 2;
			}
			meshes.put(id, new TestMesh()
				.id(id)
				.vx(vx)
				.vy(vy)
				.vz(new float[count])
				.i1(i1)
				.i2(i2)
				.i3(i3)
				.colors(new short[faces])
				.groups(null)
				.build());
		}
		NpcBinding binding = TestBinding.of("Big", new int[]{70}, new int[]{1, 2}).build();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		AssetCodec.write(new AssetBundle(meshes, Collections.emptyMap(), Collections.emptyList(),
			Collections.singletonList(binding)), out);

		try
		{
			AssetCodec.read(new ByteArrayInputStream(out.toByteArray()));
			fail("expected a refusal for a binding merging past the vertex ceiling");
		}
		catch (IOException expected)
		{
			assertTrue(expected.getMessage(), expected.getMessage().contains("merges to"));
		}
	}

	@Test
	public void testRejectsABindingNamingTooManyMeshes() throws IOException
	{
		// One small mesh named over and over would otherwise merge into an enormous one
		int[] meshIds = new int[AssetCodec.MAX_PARTS + 1];
		Arrays.fill(meshIds, 2944);
		String message = refusalFor(withBindings(TestBinding.of("Many", new int[]{70}, meshIds).build()));

		assertTrue(message, message.contains("past the " + AssetCodec.MAX_PARTS));
	}

	@Test
	public void testRejectsPartsWhoseFacesPassTheCeilingBeforeMerging() throws IOException
	{
		// Half the face ceiling and one more, on three vertices: twice over is past it
		int faces = AssetCodec.MAX_FACES / 2 + 1;
		int[] i2 = new int[faces];
		int[] i3 = new int[faces];
		Arrays.fill(i2, 1);
		Arrays.fill(i3, 2);
		Mesh mesh = new TestMesh()
			.id(1)
			.vx(new float[]{0, 1, 0})
			.vy(new float[]{0, 0, 1})
			.vz(new float[3])
			.i1(new int[faces])
			.i2(i2)
			.i3(i3)
			.colors(new short[faces])
			.groups(null)
			.build();
		NpcBinding binding = TestBinding.of("Doubled", new int[]{70}, new int[]{1, 1}).build();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		AssetCodec.write(new AssetBundle(Collections.singletonMap(1, mesh), Collections.emptyMap(),
			Collections.emptyList(), Collections.singletonList(binding)), out);

		try
		{
			AssetCodec.read(new ByteArrayInputStream(out.toByteArray()));
			fail("expected a refusal for parts past the face ceiling");
		}
		catch (IOException expected)
		{
			assertTrue(expected.getMessage(), expected.getMessage().contains("merges to " + faces * 2 + " faces"));
		}
	}
}
