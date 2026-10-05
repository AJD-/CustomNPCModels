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
package com.customnpcmodels.packs;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A pack's {@code pack.json}: read by {@link DirectoryPackSource}, written by {@link HubInstaller},
 * and checked by {@link PackImporter} before a folder is imported. One class, so the three cannot
 * drift apart.
 */
final class PackJson
{
	/**
	 * The id the pack's author gave it. Only {@link PackImporter} reads it, to name the folder it
	 * copies a pack into; once installed, the folder decides the pack's id. Kept as raw JSON, so an
	 * id that isn't a string is ignored rather than failing the whole file.
	 */
	JsonElement id;
	String name;
	String author;
	String description;
	String version;
	String license;
	List<String> tags;

	/** The hub commit an installed hub pack was fetched at. */
	String commit;

	/** What an installed hub pack's {@code pack.json} holds. */
	static PackJson of(HubEntry entry)
	{
		PackJson json = new PackJson();
		json.id = new JsonPrimitive(entry.getId());
		json.name = entry.getName();
		json.author = entry.getAuthor();
		json.description = entry.getDescription();
		json.version = entry.getVersion();
		json.license = entry.getLicense();
		json.tags = entry.getTags();
		json.commit = entry.getCommit();
		return json;
	}

	/** {@link #id} when it is a string or number, or null. */
	String idText()
	{
		return id != null && id.isJsonPrimitive() ? id.getAsString() : null;
	}

	/** The pack in {@code folder}, described as this file says. */
	PackInfo toInfo(PackInfo folder)
	{
		return new PackInfo(folder.getId(), folder.getKind(),
			name == null || name.isBlank() ? folder.getName() : name.trim(),
			author, description, version, license,
			tags == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(tags)),
			commit);
	}
}
