# Custom NPC Models

Replaces NPC models and animations with custom-authored ones: original geometry, rigs and animation
clips modeled in Blender, exported as glTF and compiled into a bundle that ships inside the plugin.

This repository currently holds the **framework and authoring pipeline**, and no content yet. The
shipped bundle is empty until original models are authored.

## Requirements

**The `GPU` or `117 HD` plugin must be enabled.** Models are substituted while the scene is drawn,
so nothing changes while neither is rendering. 117 HD's optional **Legacy renderer** isn't supported.
The plugin detects all of this and stands down until a supported renderer holds the renderer slot
again. 117 HD isn't a dependency.

**Retro NPC Swapper** (2.3.0 and later) runs alongside this plugin. Each draws the NPCs it swaps, and
any NPC this plugin has a custom model for is left to it entirely: Retro swaps neither that NPC's
model nor its animations. Older versions of Retro NPC Swapper can't share the renderer, so whichever
of the two starts first draws and the other stands down. Turn on the `Fix Interact Highlight
outlines` option in only one of the two plugins. Each turns Interact Highlight's NPC outlines off and
restores them on its own, so with both on, those settings can be restored wrong.

## The side panel

The plugin's button in the sidebar opens a list of every model pack it found. From there you can:

- Switch a whole pack on or off, or open it and switch single models on or off.
- Move packs up or down. Where two packs have a model for the same NPC, the higher one is drawn, and
  the lower one says which pack overrides it.
- Import a pack folder with **Import pack...**, and read every pack from disk again with **Refresh**.

What you switch off is remembered per RuneLite profile. A pack or model you haven't seen before starts
switched on. Models for NPCs that can never be swapped (see "How it works") are listed, greyed out,
with the reason.

### The Custom Model Hub

The hub is a collection of reviewed, original model packs hosted on GitHub. It's **off by default**:
switch on `Enable Custom Model Hub` in the plugin's settings (under **Custom Model Hub**) to use it.
Doing so contacts GitHub, which means sending it your IP address, and the setting says so before it
takes effect. While it's off, the plugin makes no network requests at all.

With it on, the side panel lists the hub's packs below your own. From there you can **Install**
one, **Update** it when the hub has a newer version, or **Remove** it. Every download is checked
against the size and SHA-256 the hub lists, and read as a pack, before anything is written. Installed
hub packs live in `~/.runelite/plugin-data/custom-npc-models/hub/`, which the plugin manages, so
don't edit it by hand. A hub pack can be removed from its own card even with the hub switched off.

<details>
<summary>How it works</summary>

- The plugin wraps the renderer's draw callbacks and hands it a prebuilt model whenever a bound NPC
  is drawn. That geometry isn't something the client decoded, so the client can't animate it. The
  plugin skins and lights it in Java, reading the frame index the client is already driving.
- **No animation is changed.** Each authored clip is keyed to a live sequence the NPC already plays,
  and has exactly that sequence's frame count. The client keeps playing its own animations, and only
  the geometry they drive is different. An action that has no authored clip holds the movement pose.
- **Clickboxes are untouched.** The client resolves clickboxes from the original model before the
  draw callback runs.
- Everything is drawn from **packs**. A pack is one compiled `bundle.dat`. The plugin reads the pack
  inside its own jar, the development bundle under `./gradlew run`, and every pack in its data
  folder (see "Using a pack without the Hub" below). There's no fallback to the game cache, so an
  NPC whose model can't be built stays vanilla.
- When two packs have a model for the same NPC, the first in priority order draws it. By default
  that's the development bundle, then hub and local packs, then the pack inside the plugin. Each
  model is built and animated from its own pack alone, so packs authored separately can reuse mesh,
  rig and sequence ids without clashing. A pack that can't be read is skipped with a warning in the
  log, and the rest still load. The side panel changes the priority order, and which packs and
  models are drawn. That takes effect straight away, rebuilding only the NPCs it changes.
- `Interact Highlight` compatibility: the **Compatibility** section's `Fix Interact Highlight
  outlines` option draws that plugin's NPC hover and interact outlines around the custom model
  instead of the original. It does this by turning those two settings off in Interact Highlight
  while active, then restoring them when this plugin stops.
- Safety settings (on by default) disable custom models on PvP worlds and in the Wilderness.
- Some NPCs are never swapped, whatever a pack says: Jagex's third-party client rules forbid 
  extra visual indicators of boss mechanics, and name wave-based minigames explicitly. 
  The list is fixed in code (`SwapBlacklist`) and has no setting. `generateAssets` refuses a manifest
  that binds one of these NPCs, and the plugin drops them from any binding that names them, so it 
  never claims one from Retro NPC Swapper either. `exportGltf` still exports them, but writes no manifest
  entry.
- Retro NPC Swapper compatibility: both plugins wrap the renderer, and each can stack on top of the
  other. They're loaded by separate classloaders, so each wrapper exposes the renderer beneath it
  through a plain Java `Supplier`. Neither needs the other's classes. This plugin tells Retro which
  NPC ids it's drawing with a RuneLite `PluginMessage` (namespace `npc-model-swap`), and Retro leaves
  those alone. When this plugin stops, or stands down in the Wilderness, it withdraws those claims
  and Retro takes the NPCs back.

</details>

## Authoring Custom Assets
### The manifest

`assets/models.json` lists what gets bundled. Each entry names a `.glb` beside it, the synthetic
mesh and rig ids (keep them at 1,000,000 and up, and stable), the NPC ids that wear it, an optional
scale (1/128ths, applied after animation, as the game does), recolors and NPC lighting
adjustments (`ambient`, `contrast`, as in the NPC definition), and a map from each glTF animation
name to the live sequence it stands in for:

```json
{
  "models": [
    {
      "name": "Giant Mole",
      "glb": "giant-mole.glb",
      "meshId": 1005779,
      "rigId": 1005779,
      "npcIds": [5779],
      "scaleXZ": 118,
      "scaleY": 118,
      "animations": { "3309": 3309, "3313": 3313 }
    }
  ]
}
```

`./gradlew generateAssets` converts every entry, validates the result and writes
`src/main/resources/com/customnpcmodels/custom-assets.dat`. Nothing is written if anything fails.
It reads no geometry from the game cache, only the live sequences' frame counts and lengths, which
the clips are sampled against. Use `-PassetsDir=<dir>` to use another directory.

<details>
<summary>Trying an edit in game</summary>

This walkthrough recolors an existing NPC and draws it in the development client, without Blender.
It uses the Giant Mole (id 5779). The same steps work for any NPC.

1. Find the NPC's id: `./gradlew dumpNpcDefinitions -Pnpc="giant mole"`.
2. Export it into `assets-dev`:
   `./gradlew exportGltf -Pnpc=5779 -Pout=assets-dev`
   This writes `assets-dev/giant-mole.glb` and an `assets-dev/models.json` entry binding it back to
   NPC 5779. Without `-Pout`, the export goes to `build/gltf/`, which `./gradlew clean` deletes.
3. Paint it: `./gradlew paintGltf -Pglb=assets-dev/giant-mole.glb`, then save. The first save keeps
   the original as `giant-mole.glb.bak`.
4. Optionally check the result before going in game:
   - `./gradlew viewAnimations -Pglb=assets-dev/giant-mole.glb` plays every animation as the game
     will draw it.
   - `./gradlew compareGltf -Pnpc=5779 -Pglb=assets-dev/giant-mole.glb` should list your faces as
     recolored and show 0.00 units moved at rest.
5. Build the development bundle:
   `./gradlew generateAssets -PassetsDir=assets-dev -Pdev`
   It should end with `Wrote ...src\test\resources\com\customnpcmodels\custom-assets-dev.dat`.
   **Don't leave out `-Pdev`.** Without it, the export is written into the shipped bundle,
   `src/main/resources/com/customnpcmodels/custom-assets.dat`, which goes into the Hub jar. That
   export is Jagex geometry. If this happens, delete that file.
6. Prepare the development client. `./gradlew run` uses your normal RuneLite profile, including its
   Plugin Hub plugins and their settings, so:
   - Turn on **GPU** or **117 HD**. 117 HD's **Legacy renderer** isn't supported.
   - **Retro NPC Swapper** 2.3.0 or later can stay on: it leaves your NPC to this plugin. Turn off
     an older version, and any other plugin that substitutes models. Those can't share the renderer,
     so whichever takes it first wins and this plugin quietly stands down. This can be done after the
     client has started, and the swap happens within a tick. The profile is shared with your normal
     client, so turn it back on there afterwards.
7. Start the client with `./gradlew run`, log in (see
   [Using Jagex Accounts](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts)), and go to
   the NPC. Custom models are off in the Wilderness and on PvP worlds by default.
8. After changing the `.glb` or `models.json`, run `generateAssets` again and **restart
   `./gradlew run`**. The bundle is read when the client starts, from the copy Gradle makes in
   `build/resources/test`. Toggling the plugin doesn't pick up a new one.

If the model still doesn't change, see the "Debugging" section below.
</details>

<details>
<summary>Authoring Tools</summary>

- `./gradlew dumpNpcDefinitions -Pnpc=<ids or name>` prints live NPC definitions: model parts,
  scale, recolors, the standing and walking sequences with their frame counts, and whether any part
  is textured.
- `./gradlew exportGltf -Pnpc=<id> [-Pseqs=a,b,...] [-Pout=dir]` exports an NPC from the live cache
  as a `.glb`, with its rig and animations, plus a manifest entry binding it back to the same NPC.
  Without `-Pseqs` it exports every sequence that animates the NPC's rig. That covers the attacks,
  blocks and deaths the NPC definition never names. A rig shared by more than 64 sequences, such as
  the humanoid rig, gets only the definition's own sequences (standing, walking, turning, running),
  and the export says to name the rest with `-Pseqs`.
  Use it to seed Blender work from something that already animates correctly. An NPC built from
  several models gets one mesh per model, named `part_NN_model_<id>`, so each can be hidden on its
  own. A vertex group that every exported clip scales to nothing, such as an effect only an attack
  shows, is reported: it is visible in the rest pose but not in those clips. A joint that some clip
  flattens, as death clips often do, can't have other joints keyed against it, so those hang from
  the nearest ancestor that stays whole, and the export says which. **The output is Jagex
  geometry.** It defaults to the gitignored `build/gltf/` and must never be committed or bundled for
  release.
- `./gradlew generateAssets -PassetsDir=assets` Builds the asset bundle from the authoring manifest
  (`models.json`) and the .glb files beside it
- `./gradlew generateAssets -PassetsDir=assets-dev -Pdev` writes the gitignored
  `custom-assets-dev.dat` on the test classpath instead. `./gradlew run` loads it as the development
  pack, which takes priority over every other pack. This is how a cache export is round-tripped
  into the game to compare against the original.
- `./gradlew generateAssets -PassetsDir=<dir> -PpackOut[=<out>]` builds a pack instead: a
  `bundle.dat` and a `pack.json`, written to `<out>`, or to `build/packs/<pack id>/` when no
  directory is given. The manifest needs a `pack` block for this:
  `"pack": {"id": "my-pack", "name": "My pack", "author": "...", "version": "1.0", "tags": [...]}`.
  The id may only use lowercase letters, digits and hyphens. `pack.json` also lists the pack's
  models, taken from the bundle.
- `./gradlew writeBlacklistFixture` writes `build/fixtures/blacklisted-pack`: the dev bundle's first
  model, also bound to TzKal-Zuk. `generateAssets` refuses to build such a pack, so this is how to
  check in game that the plugin refuses one too. Import it with **Import pack...**: the panel lists
  the model as partly never swapped, and the log shows `NPC 7706 (the Inferno) is never swapped`.
- `./gradlew serveHubFixture [-Prevision=2]` serves a test Custom Model Hub on `localhost:8765`,
  built from the dev bundle. It has a pack that installs, one built for a newer bundle format, and
  one whose download fails its checksum. Start the client against it with
  `./gradlew run -PhubUrl=http://localhost:8765/`: the plugin only accepts another hub address in
  developer mode, which `./gradlew run` always is. Restart the fixture with `-Prevision=2` to offer
  an update. Stop it with Ctrl+C.
- `./gradlew compareGltf -Pnpc=<id> -Pglb=<file> [-Pseqs=a,b,...]` reports how far a `.glb` has
  moved from the NPC it was exported from, without starting the client: converter warnings, which
  vertex groups moved at rest, which faces were recolored, and the worst vertex error over every
  frame of each sequence. An untouched Blender round trip should show nothing moved or recolored and
  the same pose errors as the file Blender was given.
- `./gradlew paintGltf [-Pglb=<file>]` opens a `.glb` in a window for painting face colors. It shows
  the model lit the way the plugin lights it. Click or drag over faces to paint them, fill a
  connected patch of one color, or Alt+click a face to pick up its color. Every color on offer is one
  the engine can draw, and each face takes exactly one. Saving writes back to the same file and
  changes nothing but the colors: geometry, rig, animations and everything Blender added stay as they
  were. The first save keeps the original beside it as `<file>.bak`. Without `-Pglb` it asks for a
  file. For color-only edits this is easier than Vertex Paint, and the result still opens in Blender.
  A file with more than one mesh lists them under **Parts**. Untick one to hide it, and brush, fill
  and color pick all skip it. It is still saved.
- `./gradlew viewAnimations [-Pglb=<file>]` plays a `.glb`'s animations as the game will draw them.
  Each animation becomes the clip `generateAssets` would build, sampled at its live sequence's frames,
  and the plugin's own skinner poses it. Frames step with nothing in between, and each is held as
  long as the sequence says. The model is lit once at rest, then scaled and recolored as
  `models.json` says. An animation plays against the sequence its `models.json` entry maps it to, or
  the sequence its name is when there is no mapping. That is how `exportGltf` names them. Sequence
  timings come from the live cache (`-PcacheDir` to use another). An animation with no live sequence
  is listed but can't be played, and reports why. Space plays and pauses, the arrow keys step a
  frame, and the converter's warnings are under **Conversion report**.
</details>

<details>
<summary>Editing an export in Blender</summary>

Tested with Blender 5.2. Every setting below was measured with `compareGltf`. With any of them
wrong, the file still converts, but it comes out wrong without saying so.

1. Export the NPC into `assets-dev`. Every sequence on its rig comes along; pass `-Pseqs` to
   choose them yourself:
   `./gradlew exportGltf -Pnpc=5779 -Pout=assets-dev`
2. In a new Blender file, delete the default objects and set **Output > Frame Rate to 50 fps
   before importing**. A game tick is 0.02 s, so at 50 fps every key lands on a whole frame.
3. **File > Import > glTF 2.0**, with *Merge Vertices* off (the default) and *Disable Bone Shape* on.
   Otherwise the importer adds a stray Icosphere, and an unskinned mesh in the export is refused.
   The model then looks grey in the default Solid view. The colors did come in, but Solid view
   shades by the material's viewport color. To see them, switch *Viewport Shading* to
   **Material Preview**, or open the Solid shading options and set *Color* to **Attribute**.
4. Edit the mesh:
   - Select vertices through their vertex group (`group_N`) and move, scale or sculpt them. Every copy
     of a vertex moves together, so the original vertex numbering survives. Deleting vertices, or
     moving one copy of a vertex apart from the others, is fine too. The converter then welds by
     position and says so.
   - Recolor in Vertex Paint with **face selection masking**, filling whole faces. A face takes the
     average of its three corners, so a half-painted face comes out muddy. Or leave color for
     `paintGltf` after export.
   - Moving vertices well away from their bone draws a "Joint for group N sits ... from the
     centroid" warning. That is expected when you meant to do it. The animation still carries them.
   - Each of the NPC's models is its own object (`part_NN_model_<id>`), so one can be hidden in the
     outliner while you work on the rest. Keep them as separate objects. A joined mesh keeps only one
     part's per-face render types and priorities, so they are dropped with a warning. The objects may
     export in any order: each carries its part index, and the converter puts the faces back in their
     original order.
5. **File > Export > glTF 2.0**, format glTF Binary, with:
   - *Data > Mesh > Attributes* **on**. This keeps `_RS_VERTEX` and `_RS_HSL`, the original vertex
     numbering and exact colors. Without them vertices are renumbered and unedited colors can drift.
   - *Data > Mesh > Use Vertex Color* **Active**. *Material* loses transparency.
   - *Include > Custom Properties* **on**. This carries the model priority and per-face render
     types, which ride in the mesh's extras.
   - *Animation > Animation Mode* **Actions**, so each sequence exports as its own animation named after it.
   - *Animation > Always Sample Animations* **off**. This keeps the step keys exactly as imported.
     Sampling at 24 fps puts a whole tile of error into every clip.
   - *Draco Mesh Compression* off.
6. Save it beside the export, e.g. `assets-dev/giant-mole-edited.glb`, and point the manifest entry's
   `glb` at it. Check it with `./gradlew compareGltf -Pnpc=5779 -Pglb=assets-dev/giant-mole-edited.glb`,
   preview the model in action with `./gradlew viewAnimations`, then build it into the dev bundle with
   `./gradlew generateAssets -PassetsDir=assets-dev -Pdev` and start `./gradlew run`.
</details>

<details>
<summary>What the glTF must look like</summary>

- A single binary `.glb`, one skin at most, no sparse accessors, no interleaved buffer views and no
  required extensions (such as Draco or quantization).
- Colors come from `COLOR_0`, one color per face (the corners are averaged). Alpha is transparency.
  Textures aren't supported.
- Each vertex belongs to exactly one bone. The heaviest weight wins, and any significant weight that
  gets discarded is reported.
- Animations should use step or linear interpolation. Bones can translate, rotate and scale, but
  scale is only exact along the model's own axes. Scaling along an axis the bone has already rotated
  is shear, which is approximated and reported.
- Units are 1 metre to 1 tile, +Y up. The pipeline converts to the engine's 128 units per tile with
  +Y down, and reverses triangle winding to match.
- A clip is keyed by its model's rig and the live sequence id, so two models can both provide a
  clip for the same sequence, as long as each has its own `rigId`.
</details>

<details>
<summary>Using a pack without the Hub</summary>

Local packs are for trying your own models. They supplement the models the plugin ships with, and
nothing about them leaves your machine.

1. Build the pack: `./gradlew generateAssets -PassetsDir=<dir> -PpackOut` (see "Authoring Tools").
2. In the side panel, choose **Import pack...** and pick the pack's folder, the one holding
   `bundle.dat`. The pack is checked before anything is copied. It's copied into the plugin's data
   folder, `~/.runelite/plugin-data/custom-npc-models/local/<name>/`
   (`%USERPROFILE%\.runelite\plugin-data\custom-npc-models\local\<name>\` on Windows), and loaded
   straight away. `<name>` is the pack's id, or else its folder's name, lowercased, with anything but
   letters, digits, `-` and `_` turned into `-`. An import never overwrites a pack already there: to
   replace one, delete its folder first, then import again.

You can also copy a pack folder into `local` yourself, then choose **Refresh**. Folder names may use
letters, digits, spaces, `.`, `-` and `_`, but may not start or end with a dot or a space, or be a
name Windows reserves such as `con` or `aux`. To remove a local pack, delete its folder and choose
**Refresh**.

Blacklisted NPCs are ignored in local packs too. A model made from an `exportGltf` export is Jagex
geometry: it's fine in your own local folder, but never share or upload it.
</details>

<details>
<summary>Debugging</summary>

The development client runs with `--debug`, so the plugin's decisions are written to
`~/.runelite/logs/client.log` (`%USERPROFILE%\.runelite\logs\client.log` on Windows). Search it for
these lines:

| Logged | Root Cause/Remediation Steps |
|---|---|
| `Draw callbacks held by unsupported renderer <class>; skipping model swap` | Something else holds the renderer slot. `com.retronpcswapper...` is a Retro NPC Swapper older than 2.3.0 (update it or turn it off), and `rs117.hd` outside `rs117.hd.renderer.zone` is 117 HD's Legacy renderer. Turn it off. |
| No `Attached custom draw callbacks over ...` at all | Neither GPU nor 117 HD is on. |
| `Custom NPC models loaded: ModelCatalog{npcs=0, ...}` | No pack has a model for any NPC. Check `-PassetsDir`, check that `generateAssets` ended with `Wrote ...` (nothing is written if anything fails), and restart the client. |
| `Custom NPC models loaded: ModelCatalog{...}` with counts you don't expect | The client is reading an older bundle. Regenerate it and restart the client. `conflicts` counts models another pack took priority over. |
| `Custom NPC model pack '<id>' could not be read: <reason>` | That pack is skipped. `version 2, this build reads version 3` means it was built before packs had their own rigs: regenerate it, including the dev bundle. |
| `Custom Model Hub list failed: <reason>` | The panel shows the same message with a **Retry** button. Check that GitHub is reachable. The hub is only asked for anything while `Enable Custom Model Hub` is on. |
| `Could not install hub pack <id>` | The download was fine, but writing it to `plugin-data/custom-npc-models/hub/` failed. The pack already installed, if any, is left as it was. |
| `Using the test Custom Model Hub at <url>` | The client was started with `-PhubUrl`, so it talks to that test hub instead of the real one. |
| `NPC <id> (<content>) is never swapped; dropping it from '<name>' in pack <id>` | The NPC is on the swap blacklist (see "How it works"). This is intentional, and there's no way to turn it off. |
| Models loaded, but no `Built custom model '<name>' from pack <id> for NPC id <id>` near the NPC | The NPC on screen isn't one of the entry's `npcIds` (many NPCs have several ids; check with `dumpNpcDefinitions`), or you're in the Wilderness or on a PvP world. |
</details>

### If you'd like to report a bug or request a feature, please create an issue [here](https://github.com/AJD-/CustomNPCModels/issues)
