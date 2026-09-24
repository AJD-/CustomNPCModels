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
package com.customnpcmodels.cache;

import com.customnpcmodels.inject.Clip;
import com.customnpcmodels.inject.Rig;
import java.io.File;
import java.io.IOException;
import java.util.Map;
import net.runelite.cache.ConfigType;
import net.runelite.cache.IndexType;
import net.runelite.cache.definitions.FrameDefinition;
import net.runelite.cache.definitions.FramemapDefinition;
import net.runelite.cache.definitions.ModelDefinition;
import net.runelite.cache.definitions.SequenceDefinition;
import net.runelite.cache.definitions.loaders.FrameLoader;
import net.runelite.cache.definitions.loaders.FramemapLoader;
import net.runelite.cache.definitions.loaders.ModelLoader;
import net.runelite.cache.definitions.loaders.SequenceLoader;
import net.runelite.cache.fs.Archive;
import net.runelite.cache.fs.ArchiveFiles;
import net.runelite.cache.fs.FSFile;
import net.runelite.cache.fs.Store;

/**
 * Reads what the authoring tools need out of the live OSRS cache: models, sequences, framemaps and
 * frames.
 *
 * <p>Two very different consumers. The glTF exporter reads geometry and animation to seed Blender
 * work and to build round-trip fixtures - Jagex data, which never reaches a shipped bundle. The
 * asset generator reads only sequence <em>metadata</em>, the frame counts and lengths an authored
 * clip is sampled against, because the client goes on playing the live sequence and hands over its
 * frame index.
 *
 * <p>Test sourceSet only. Decoding is delegated to {@code net.runelite:cache}.
 */
public final class CacheFiles
{
	private static final String CACHE_DIR_PROPERTY = "customnpcmodels.cacheDir";

	private static final String[] DEFAULT_CACHE_DIRS = {
		".runelite/jagexcache/oldschool/LIVE",
		"jagexcache/oldschool/LIVE"
	};

	private CacheFiles()
	{
	}

	/**
	 * The live cache directory, from {@code -PcacheDir}, else the first default that exists. Null
	 * when there is none, which cache-dependent tests treat as a skip rather than a failure.
	 */
	public static File resolveLiveCacheDir()
	{
		String configured = System.getProperty(CACHE_DIR_PROPERTY);
		if (configured != null && !configured.isEmpty())
		{
			File dir = new File(configured);
			return dir.isDirectory() ? dir : null;
		}

		File home = new File(System.getProperty("user.home"));
		for (String candidate : DEFAULT_CACHE_DIRS)
		{
			File dir = new File(home, candidate);
			if (dir.isDirectory())
			{
				return dir;
			}
		}
		return null;
	}

	/** Opens and loads the live cache, or returns null when there is none. The caller closes it. */
	public static Store openLiveCache() throws IOException
	{
		File dir = resolveLiveCacheDir();
		if (dir == null)
		{
			return null;
		}

		Store store = new Store(dir);
		store.load();
		return store;
	}

	public static SequenceDefinition loadSequence(Store store, int sequenceId) throws IOException
	{
		byte[] data = loadFile(store, IndexType.CONFIGS, ConfigType.SEQUENCE.getId(), sequenceId);
		if (data == null)
		{
			return null;
		}

		return new SequenceLoader()
			.configureForRevision(store.getIndex(IndexType.CONFIGS).getRevision())
			.load(sequenceId, data);
	}

	public static ModelDefinition decodeModel(Store store, int modelId)
	{
		try
		{
			Archive archive = store.getIndex(IndexType.MODELS).getArchive(modelId);
			if (archive == null)
			{
				return null;
			}
			byte[] data = archive.decompress(store.getStorage().loadArchive(archive));
			return data == null ? null : new ModelLoader().load(modelId, data);
		}
		catch (IOException | RuntimeException e)
		{
			return null;
		}
	}

	/**
	 * Decodes one sequence into a clip, registering the rig it references.
	 *
	 * <p>A frame names its own framemap in its first two bytes, which is why the framemap has to be
	 * loaded before the frame can be. Returns null for a sequence with no frames, or one driven by
	 * the newer skeletal (animaya) system, which carries no frame list at all.
	 */
	public static Clip buildClip(Store store, int sequenceId, Map<Integer, Rig> rigs)
		throws IOException
	{
		SequenceDefinition sequence = loadSequence(store, sequenceId);
		if (sequence == null || sequence.frameIDs == null || sequence.frameIDs.length == 0)
		{
			return null;
		}

		int frameCount = sequence.frameIDs.length;
		int[][] transforms = new int[frameCount][];
		int[][] dx = new int[frameCount][];
		int[][] dy = new int[frameCount][];
		int[][] dz = new int[frameCount][];

		int rigId = -1;
		for (int i = 0; i < frameCount; i++)
		{
			int packed = sequence.frameIDs[i];
			byte[] frameData = loadFile(store, IndexType.ANIMATIONS, packed >> 16, packed & 0xFFFF);
			if (frameData == null || frameData.length < 2)
			{
				transforms[i] = new int[0];
				dx[i] = new int[0];
				dy[i] = new int[0];
				dz[i] = new int[0];
				continue;
			}

			int framemapId = ((frameData[0] & 0xFF) << 8) | (frameData[1] & 0xFF);
			FramemapDefinition framemap = loadFramemap(store, framemapId, rigs);
			if (framemap == null)
			{
				return null;
			}

			if (rigId == -1)
			{
				rigId = framemapId;
			}
			else if (rigId != framemapId)
			{
				// A sequence that switched rigs mid-animation would need a per-frame rig id, so fail
				// loudly rather than silently animate against the wrong skeleton
				throw new IOException("Sequence " + sequenceId + " mixes framemaps "
					+ rigId + " and " + framemapId + "; the clip format assumes one per clip");
			}

			FrameDefinition frame = new FrameLoader().load(framemap, packed & 0xFFFF, frameData);
			transforms[i] = frame.indexFrameIds;
			dx[i] = frame.translator_x;
			dy[i] = frame.translator_y;
			dz[i] = frame.translator_z;
		}

		return rigId == -1 ? null : new Clip(sequenceId, rigId, transforms, dx, dy, dz);
	}

	public static FramemapDefinition loadFramemap(Store store, int framemapId, Map<Integer, Rig> rigs)
		throws IOException
	{
		byte[] data = loadFile(store, IndexType.SKELETONS, framemapId, 0);
		if (data == null)
		{
			return null;
		}

		FramemapDefinition framemap = new FramemapLoader().load(framemapId, data);
		if (rigs != null)
		{
			rigs.putIfAbsent(framemapId, new Rig(framemapId, framemap.types, framemap.frameMaps));
		}
		return framemap;
	}

	public static byte[] loadFile(Store store, IndexType indexType, int archiveId, int fileId)
		throws IOException
	{
		Archive archive = store.getIndex(indexType).getArchive(archiveId);
		if (archive == null)
		{
			return null;
		}

		byte[] container = store.getStorage().loadArchive(archive);
		if (container == null)
		{
			return null;
		}

		if (archive.getFileData() != null && archive.getFileData().length == 1)
		{
			return archive.decompress(container);
		}

		ArchiveFiles files = archive.getFiles(container);
		FSFile file = files.findFile(fileId);
		return file == null ? null : file.getContents();
	}
}
