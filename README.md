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

Only one model-substituting plugin can hold the renderer slot at a time. While **Retro NPC Swapper**
is drawing, this plugin stands down, and the reverse is also true.

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
- Everything is drawn from the bundle. There's no fallback to the game cache, so an NPC whose
  binding can't be built stays vanilla.
- `Interact Highlight` compatibility: the **Compatibility** section's `Fix Interact Highlight
  outlines` option draws that plugin's NPC hover and interact outlines around the custom model
  instead of the original. It does this by turning those two settings off in Interact Highlight
  while active, then restoring them when this plugin stops.
- Safety settings (on by default) disable custom models on PvP worlds and in the Wilderness.

</details>

## Authoring

All tooling lives in the test sourceSet and never ships. `./gradlew run` starts a development
client with the plugin loaded.

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

### Tools

- `./gradlew dumpNpcDefinitions -Pnpc=<ids or name>` prints live NPC definitions: model parts,
  scale, recolors, the standing and walking sequences with their frame counts, and whether any part
  is textured.
- `./gradlew exportGltf -Pnpc=<id> [-Pseqs=a,b,...] [-Pout=dir]` exports an NPC from the live cache
  as a `.glb`, with its rig and animations, plus a manifest entry binding it back to the same NPC.
  Use it to seed Blender work from something that already animates correctly. **The output is Jagex
  geometry.** It defaults to the gitignored `build/gltf/` and must never be committed or bundled for
  release.
- `./gradlew generateAssets -PassetsDir=assets-dev -Pdev` writes the gitignored
  `custom-assets-dev.dat` on the test classpath instead. `./gradlew run` loads it on top of the
  shipped bundle, and the Hub jar never can. This is how a cache export is round-tripped into the
  game to compare against the original.
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

### Editing an export in Blender

Tested with Blender 5.2. Every setting below was measured with `compareGltf`. With any of them
wrong, the file still converts, but it comes out wrong without saying so.

1. Export the NPC with every sequence you want to keep, into `assets-dev`:
   `./gradlew exportGltf -Pnpc=5779 -Pseqs=3309,3310,3311,3312,3313,3314,3315 -Pout=assets-dev`
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
   then build it into the dev bundle with `./gradlew generateAssets -PassetsDir=assets-dev -Pdev`
   and start `./gradlew run`.

The edited file is still Jagex geometry. Keep it in `assets-dev` and never in `assets`.

### What the glTF must look like

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
- A clip is keyed by its live sequence id alone, so two models can't both provide a clip for the same
  sequence.

### If you'd like to report a bug or request a feature, please create an issue [here](https://github.com/AJD-/CustomNPCModels/issues)
