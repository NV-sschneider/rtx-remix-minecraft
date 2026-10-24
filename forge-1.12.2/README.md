# RTX Remix prototype for Forge 1.12.2

An experimental backport of NVIDIA's Minecraft RTX Remix integration to Java 8,
LWJGL 2, and Forge 1.12.2, the platform used by RLCraft 2.9.3. It captures terrain,
materials, sky, clouds, entities, held items, and supported world effects for the
Remix path tracer. Creative fixtures exercise individual rendering and lifecycle
contracts; they do not establish complete RLCraft survival compatibility.

The Fabric renderer remains at the repository root. This module has its own
Gradle 4.9 build. Both builds compile the shared Java 8-compatible facade and JNI
shim under `rtxremix-java/`, using the unchanged Remix C API 0.16.0. The Forge
`bridge/` directory supplies the LWJGL 2 interop adapter and its native build.

## Run the installed prototype

From the repository root in PowerShell, using the configured portable Prism:

```powershell
.\forge-1.12.2\scripts\launch-prototype.ps1
```

Prism launches **Forge 1.12.2 - Remix Port** using your signed-in Minecraft Java
account. The renderer starts disabled. Enter a disposable Creative world and use
`/mrtx on`; `/mrtx off` restores vanilla rendering. `/mrtx status` reports scene
ownership and streaming progress, and `/mrtx reload` rebuilds captured textures
and their consumers. A renderer toggle can be assigned in Controls; new
installations leave it unbound to avoid modpack shortcuts.

`/mrtx settings` opens the native graphics menu. **Alt+X** toggles the native
Remix developer menu. Opening either menu releases held Minecraft inputs and
mouse capture; closing it restores the current gameplay or screen session.
An inventory remains the same open container while the native menu is shown.
The configured test instances use windowed mode; the launcher preserves their
display settings. Fullscreen, resize, and minimize/restore
have dedicated optional fixtures below.

New installs use a modest local tone-mapping shadow level of `2.5`, with the
runtime's exposure and highlight settings unchanged. The same-room validation
increased sampled shadow brightness by about 11% without increasing clipped
highlights. `/mrtx shadows <0-5>` adjusts this control; `/mrtx shadows status`
reports its saved override and `/mrtx shadows reset` saves the native baseline,
`2`. The preference lives separately in `config/rlcraft-remix-visibility.properties`
and selects local tone mapping when applied. With no saved override, Remix's
existing settings remain in control. Installs preserve an existing `rtx.conf`;
use `/mrtx shadows 2.5` to apply the new level there. The command does not rewrite
`rtx.conf`.

The runtime and its GL timeline remain alive while Remix is off, but world
resources are released. Shutdown follows Minecraft's `Display.destroy()` call.
The pinned runtime DLL stays loaded until process exit because unloading it
after native shutdown deadlocked in testing. Launch visibly through Prism:
a hidden launcher window previously allowed successful API calls without visible
ray tracing.

## Build and install

Building requires Windows x64, a Java 8 JDK, and Visual Studio x64 C++ tools.
Java 21 is used separately for the Fabric build. The development setup keeps Java
8 and portable Prism under ignored `.tools/`, and the Forge cache under
`.gradle-home/`; neither dependencies nor account data belong in Git.

Run from the repository root with the game closed:

```powershell
.\forge-1.12.2\scripts\fetch-runtime.ps1
.\forge-1.12.2\scripts\build-bridge.ps1 -RuntimeDirectory .\remix-runtime\win64
.\forge-1.12.2\scripts\build.ps1
.\forge-1.12.2\scripts\install-prototype.ps1
```

The runtime script stages the root `packman/remix-sdk.packman.xml` pin under
`remix-runtime/win64`. Development installation verifies a fresh sibling staging
directory, replaces the instance's mod-owned `remix-sdk` with rollback on failure,
and preserves an existing `rtx.conf`. Its file selection and packaging share
`config/runtime-payload-excludes.txt`, excluding metadata, debug symbols, the
unused native bridge, and Python payloads; the locally built JNI bridge takes
precedence. Destination links and a running instance reject installation. It
leaves the package cache and saved worlds alone. Do not run Gradle
or reinstall while a client using that checkout is alive.

The reobfuscated development mod is
`forge-1.12.2/build/libs/rlcraft-remix-forge-0.1.0-prototype.jar`. Its native bridge
is `forge-1.12.2/bridge/build/native/win64/remixbridge.dll`. The development JAR
requires both that bridge and the matching staged runtime.

To produce a self-contained installable JAR, with the prerequisites above:

```powershell
.\forge-1.12.2\scripts\build.ps1 -Tasks packageForge
```

The result in `forge-1.12.2/build/distributions/` contains the mod, LWJGL 2 JNI
bridge, runtime DLLs, USD plugins, and license texts. Install it in an existing
Forge 1.12.2 instance's `mods` directory. Minecraft, Forge, and RLCraft are obtained
separately. Windows x64 is the supported Forge package target.

The first `/mrtx on` extracts the package before loading native code. A SHA-256
inventory verifies the files before they replace the live SDK. A changed payload
or missing SDK dependency replaces the mod-owned `remix-sdk` directory and
`rtx.conf`; a matching installation preserves configuration and restores a missing
`rtx.conf` separately. Authored work under `rtx-remix` is left untouched.
`-Drlcraftremix.runtime=<directory>` selects a development SDK and bypasses
extraction. Packaging verifies agreement between the extraction inventory and
embedded files and checks inclusion of the runtime license texts.

## Terrain, fluids, and lighting

Baked block capture preserves model geometry, biome tint, and alpha categories
without baking Minecraft's lightmap or ambient occlusion into the material.
Terrain follows the render camera and current render distance, reading only
loaded client chunks. It never requests distant terrain. Capture runs incrementally
on the render thread because Forge models and world data are mutable. Nearby
sections build first; completed sections replace their previous assets atomically.
Block edits invalidate neighboring sections, including diagonal dependencies.
Chunk replacement, unload, resource reload, and world changes invalidate stale work.

The mesh budget is 512 MiB, including assets being uploaded or retired. Runtime
copies and acceleration structures require additional GPU memory. Each frame
probes at most 32 columns, visits 4,096 blocks, uploads one page of at most
1,146,880 bytes, and retires four pages. The two-millisecond capture budget is
cooperative: individual model/native calls cannot be interrupted. Atlas creation
and complete teardown are separate from it. A section publishes up to 128
instances atomically. Capacity failures retain previous geometry and retry after
invalidation; they do not silently lower the requested render distance.

Water and lava use the installed vanilla liquid tessellator for exposed faces,
corner heights, flow, and overlays, while retaining unlit biome color. Water uses
refraction with an index of 1.333 and lava uses surface emission. The actual camera
selects an underwater medium using Minecraft's surface and Forge viewpoint rules.
Reload and exit from water clear the selected medium safely. Custom fluids using
baked models keep that path; custom `LIQUID` renderers outside `BlockLiquid` are
reported and skipped.

Vanilla clear/stained glass blocks and panes use refraction with an index of
1.52. Pane models provide closed geometry with physical thickness, so they use
the same volume material as blocks rather than applying a thin-wall model to both
faces. Custom mod glass remains on its ordinary material unless explicitly
classified. Water, lava, and glass retain their physical material rules when PBR
maps are present.

Block lighting retains the brightest emitter per four-block cell, selecting up
to 128 lights within 48 blocks at most five times per second. Native changes are
limited to eight per frame. Colors use simple torch, redstone, and cool-emitter
profiles; lava emits through its surface instead of adding a second point light.
Held luminous block items have a separate bounded light path tied to successfully
captured item poses, including both hands. Open-flame torch lights extinguish
underwater by default. The local first-person light has no volumetric contribution
to avoid camera-following light trails. These profiles are approximations, not
per-mod authored lighting rules.

## Resource packs and animated materials

The active resource-pack stack supplies block albedo and optional LabPBR-style
sidecars. For `assets/minecraft/textures/blocks/stone.png`, the sidecars are
`stone_n.png` and `stone_s.png` in the same directory. Namespaces and ordinary
resource-pack priority are preserved; no external shader pack is required.

| Map/channel | Supported interpretation |
| --- | --- |
| `_n` red/green | Tangent-space normal X/Y; reconstruct positive Z with +Y down the image, then encode to the pinned runtime's two-channel octahedral format. |
| `_s` red | Smoothness; convert to roughness as `(1 - red / 255)^2`. |
| `_s` green | Values 230–255 classify metal; lower values classify dielectric. Individual metal optical constants are not reproduced. |
| `_s` alpha | 0–254 scale emission using albedo color; 255 means no emission. |

Normal blue/alpha and specular blue are not used; height/parallax, porosity,
subsurface scattering, and per-material dielectric reflectance are not implemented.
Missing sidecars leave default normals, roughness, metalness, and emission.
Ordinary terrain uses the mapped channels; water, lava, and glass accept authored
normals while keeping their separate optical rules.

A sidecar must match the stitched albedo frame width and height. It may contain
one static frame or a vertical strip containing every source albedo frame.
**Omit sidecar animation metadata**: the albedo owns frame order, timing, and
interpolation, including reordered sequences. The verified FoamFix 0.10.15 sprite
subclass is supported when its public storage API reports CPU animation; its
GPU-copy mode is rejected explicitly because it bypasses the upload observer.
Rotated sprites and other custom animated sprite contracts,
malformed images, incompatible sizes, and over-budget sidecars are reported and
omitted. Each decoded sidecar is capped at 16 MiB; the owner admits 1,024 sprites,
64 MiB of source pixels, 32 MiB of animation patches, and 128 MiB of native maps.
An animated frame may contain at most 256 × 256 pixels.

The atlas observer mirrors successful mip-zero animation uploads from CPU data.
It publishes up to 64 rectangles and 4 MiB per frame, with a coalescing cache of
4,096 rectangles/32 MiB. Albedo creation and patches use the same sRGB format.
PBR updates accompany the matching albedo frames. Normal maps use `RG8_UNORM`
with `NORMAL_OTH` for both initial uploads and patches: the runtime cannot
partially update textures created with its `NORMAL_DX`/`NORMAL_OGL` conversion.
Reload or world release detaches observers and releases material consumers before
their atlas handles. Custom uploads outside the observed atlas animation contract
need a separate adapter.

## Sky and clouds

The Overworld uses a 256 × 128 HDR dome built from provider sky, fog, and sunrise
colors. Sun/moon meshes use active resource-pack textures; 500 procedural stars
follow the celestial orbit. World time, weather, and moon phase drive direction
and lighting. Dome textures update at most four times per second, while celestial
movement updates each frame. The End uses a violet dome and nonsurface dimensions
such as the Nether use a black dome without sun or moon lighting.

Cloud capture observes vanilla CPU draws and Forge's compiled cloud geometry at
its actual draw, preserving placement, UV phase, and weather tint. It avoids the
duplicate depth-only fancy-cloud pass. Custom Forge sky renderers can contribute
fixed-function CPU quads, triangles, strips, and fans with supported formats and
blend states. Their geometry is submitted to the runtime sky category; cloud
geometry remains in the world. An active custom sky handler with no supported
draws is reported explicitly.

This is bounded draw observation, not universal sky-mod support. Shader programs,
unobserved immediate-mode/display-list geometry, unknown bindings, and arbitrary
dynamic custom-sky textures are outside that contract. The special Forge cloud
color texture is handled by its own adapter. Cloud capture permits 16 batches and
32,766 output vertices per frame; texture storage is limited to 16 textures/32 MiB.

## Entities, items, and tile renderers

Model capture observes existing draws on the render thread. Live model-view
matrices preserve animation, parent transforms, renderer offsets, and Forge
pre-render transforms. Ordinary `ModelRenderer` boxes are cached by part,
display-list identity, scale, and texture. Recompilation invalidates the cache;
mutating a compiled model without rebuilding its list requires an adapter.

Optional adapters cover LLibrary ordinary box parts, Mo' Bends 1.2.1 parts and
`MutatedBox` faces, and Lycanites 2.0.8.9 CPU/VBO OBJ groups. Lycanites geometry
retains per-group transforms, color, normals, and animated UVs. Its recovery cache
permits one first-use VBO readback up to 1 MiB per frame, with 512 meshes/16 MiB
retained on the CPU. These interfaces do not establish correct rendering of every
creature using those mods. Nearby offscreen entities have a bounded supplementary
capture path for shadows/reflections; it does not capture every distant entity.

An ordinary alpha-blended entity base uses opaque rendering only when its entire
texture, vertex colors, and tint have alpha 255. This avoids ghosting for opaque
OBJ models whose renderer leaves blending enabled. Textures containing only alpha
0/255 can also use cutout rendering when the original alpha test rejects zero
and every vertex/tint is opaque; the original alpha test preserves holes such as
Flowersaur's foliage. Texture updates recheck these proofs: transparent pixels
revoke full opacity, and fractional or unknown pixels revoke binary coverage.
Retained instances change their blend state without rebuilding geometry.
Fractional alpha, glint, and additive layers keep their blending behavior.

First-person hands and baked held items use their actual equip, swing, use, and
view transforms, with a separate native view-model category. Small local meshes
contain the hand pose and camera rotation; their retained instances use only a
translation matching the runtime camera's float view-matrix round trip. This
preserves fine motion at large world coordinates. Unchanged poses reuse meshes,
and replaced meshes retire after their instances switch. Each hand capture scene
has a separate rotating allowance of eight uploads and 4 MiB per frame, so world
animation cannot consume its upload budget. Held lights still receive world-space
poses. Local body geometry
uses a separate category for shadows and reflections. Third-person, hidden-HUD,
and inventory transitions release the relevant occurrences. Dropped items use
actual `RenderItem` vertices, stack copies, tint, and ItemPhysic transforms and
borrow the animated block atlas. Nonliving entity CPU buffers cover conventional
projectiles and sprites. Known armor/item glint scopes preserve their animated
texture transforms and additive emission; arbitrary custom item renderers and
shader glints still require adapters.

Ordinary tile renderers contribute model parts and supported CPU quad/triangle
buffers. Fast TESRs contribute only the successful range appended to Forge's shared
buffer. Failed tile calls roll back their captured range without attributing a
neighbor's geometry to that tile. Arbitrary direct OpenGL, shader, or custom vertex
layouts remain unsupported. Native resources are retained by owner and occurrence;
pose changes update instances, and unseen occurrences retire before their assets.

Entity/effect texture owners observe supported CPU uploads through Minecraft's
texture helpers, including `DynamicTexture` revisions, and observe deletion.
Updates are bounded and applied before scene submission. Ordinary textures stay
hidden while their pending or lost pixels await publication or repair. The block
atlas may retain its last published animation pixels while independent sprite
patches drain, so an animation backlog does not hide atlas-backed entities.
Lost pixels and full-image replacement still require complete repair and
publication. Deletion permanently invalidates the old binding, even if its GL ID
is reused. The observer does not see arbitrary direct GL uploads, GPU copies,
render-to-texture changes, or shader writes. Such producers need an adapter.
First-use snapshots are not a substitute for observing later changes.

## Better Foliage and effects

The optional Better Foliage adapter targets the 1.12.2 2.3.3 release, whose
installed metadata may report 2.3.2. It invokes the mod's public render hooks inside
a bounded private buffer and routes base models through the same unlit terrain
path. Its scoped lighting hook removes baked lighting before Better Foliage applies
its original biome/material tint. It restores the mod's context and does not
change user configuration or vanilla worker rendering. Unsupported versions or
contracts fail explicitly instead of silently dropping or duplicating base blocks.
The adapter is original code against the verified MIT-licensed release interfaces;
it does not copy the mod's geometry algorithms.

Particles and precipitation observe successful CPU draws in their existing world
passes. Quads, triangles, triangle strips, and fans preserve geometry, UVs, and
vertex color. Conventional alpha blending uses albedo; supported `SRC_ALPHA/ONE`
and `ONE/ONE` additive draws use emission and retain the source-alpha distinction.
Known additive entity eyes/projectile sprites use the corresponding material
rules. Vanilla block-breaking overlays use a static black decal whose alpha is
derived from the destroy texture's grayscale darkening, preserving the underlying
block color. Remix cannot represent vanilla's multiplicative crack material:
source values above half brightness contribute no decal, so their brightening is
omitted. The compact damage atlas supports static grayscale destroy sprites up to
512 pixels per side; colored, animated, rotated, or malformed sources are omitted
with a warning. Stage changes, resource reload, and cancellation retain their
normal lifecycle; ordinary particle textures use their original atlas.

Fancy Block Particles 2.4.1 cube debris preserves and transforms its supplied
outward lighting normals. Its placement animation records original model colors
and biome tint before Minecraft bakes lighting into them, while the mod evaluates
and draws its model once. A bounded color stream is used only when the standard
renderer, buffer, vertex count, and quad contract match. FBP settings, animation,
particle tint, and intentional dark smoke are preserved.
Placement draws use ordinary world lighting when a bounded check proves every
vertex fully opaque and every source animation frame's level-zero pixels opaque
or binary-alpha.
Binary textures additionally require the captured alpha test to reject transparent
texels. Removing inherited particle blending makes filtered binary-texture edges
use cutout coverage instead of fractional blend weighting, matching the entity
cutout policy. Fractional level-zero alpha, unknown sprite implementations, and
draws exceeding the proof budget retain their original blending.

Ordinary premultiplied `ONE/ONE_MINUS_SRC_ALPHA` effects remain unsupported:
interpreting them as ordinary alpha or additive emission would change their
meaning. Effect capture also excludes custom shaders, unsupported blend equations,
nonidentity texture matrices, unknown texture bindings, and non-buffer geometry.
Existing mod-authored vertex shading is preserved; the observer cannot infer and
remove arbitrary lighting already mixed into those colors. The explicit FBP
placement adapter supplies its original colors through the contract above.

Effects admit 32 batches and 8,192 primitives per frame, with 16 textures/32 MiB
and at most one first-use texture snapshot per frame. Historical diagnostic names
such as `particleQuads` count admitted primitives even when a triangle path is used.
Meshes are transient and texture ownership survives between frames. Reload and
teardown release consumers before borrowed atlas handles. Native release failures
retain ownership for retry.

## Presentation and optional terrain draw reduction

The scene image reaches Minecraft's framebuffer before the usual HUD. Native
Remix menus are presented separately after Minecraft's UI. **Minecraft framebuffer
screenshots do not contain the native menu.** Inspect an owned game-window or
desktop capture when validating its actual appearance; a resolved menu state or
an ordinary F2 screenshot alone does not establish visible native presentation.
Window recreation within the same GL context preserves the interop timeline;
resizing reimports the color image. Replacing the GL context is unsupported and
is rejected by the native ownership check. Menu capture follows window changes.

The optional Neat adapter keeps its original health-bar draw in an RGBA overlay
and composites it after the Remix world image, before Minecraft's HUD. Neat still
chooses entities, names, health values, distance, focus, and F1 visibility; its
draw runs once and the adapter does not change its configuration. Other world
HUD renderers need their own presentation adapter.

Vanilla traversal still hosts capture and normally draws the world before Remix
replaces its color output. Experimental SOLID VBO draw suppression is opt-in:

```text
-Drlcraftremix.suppressSolidTerrain=true
```

`-Drlcraftremix.disableSolidTerrainSuppression=true` overrides it. Suppression
requires the exact supported VBO path, a current loaded-chunk identity and section
revision, complete captured geometry, no pending reload, and an eligible previous
frame from the same world/context/window/size. Model failures, malformed geometry,
unsupported block pipelines, postprocessing shaders, and anaglyph reject the gate.
The current view-distance window is also checked before terrain retirement.
Traversal, capture callbacks, lightmap state, buffer bindings, and list cleanup
continue normally. Cutout, translucent, entity, and display-list draws are not
covered by this switch.

If rendering fails after a draw was suppressed, the pre-HUD recovery path can
reuse the previous completed image for one frame and disable suppression for
fallback. The Forge fixture verifies an injected failure with a byte-for-byte
image comparison, then checks release, re-enable, reload, and resize. This still
requires live validation with the intended mod set. Keep it disabled when assessing a new pack;
it is not a general performance or full terrain-parity guarantee. Native terrain
continues to stream and can have pending or capacity-limited regions.

## Diagnostics and validation

`/mrtx status` includes frame count; terrain radius, columns, sections, pending and
retiring work, owned bytes and capacity state; fluid/glass vertices; atlas updates;
entity/tile/item counts; effect counts and omissions; clouds/custom-sky triangles;
PBR sprites/maps; and skipped SOLID draws/fallback frames. A submitted-frame count
alone does not prove visible output. Repeated capacity or omission counters identify
a bounded or unsupported path rather than completed scene coverage.

The Java 8 CI job builds and runs Forge JVM tests independently of the Fabric job.
These tests validate packing, state, lifetimes, and bytecode contracts without
loading JNI. Optional installed-mod contract tests accept JVM properties
`rtxremix.betterFoliageJar` and `rtxremix.rlcraftMods`; they inspect matching public
bytecode contracts and do not substitute for in-game tests. Native interop can be
checked locally with:

```powershell
.\forge-1.12.2\scripts\build-bridge.ps1 -RuntimeDirectory .\remix-runtime\win64 -GpuSmoke
```

`scripts/test-runtime-payload.ps1` checks inventory exclusions, local bridge
precedence, replacement/rollback, authored-file preservation, and path/link
rejection using small temporary fixtures under `work/`. It does not install or
launch a game.

The opt-in game fixture creates a unique disposable Creative world and checks
visible colored output, disable/re-enable, and resource release on disconnect:

```powershell
.\forge-1.12.2\scripts\launch-prototype.ps1 -SmokeTest -ExitAfterSmoke
```

Every switch below requires `-SmokeTest`. Combine the relevant switches for a
focused run; their presence describes an available check, not a recorded pass.

| Switch | Additional checks |
| --- | --- |
| `-EntitySmokeTest` | Cow/pig capture, pose updates, movement, and release. |
| `-SkySmokeTest` | Noon, sunset, midnight, rain, textures, and restored time/weather. |
| `-MaterialSmokeTest` | Water/lava animation, block lights, underwater entry/reload/exit, and glass. |
| `-PbrSmokeTest` | Original in-memory pack, static/reordered/interpolated maps, panes, pack change/removal, and exact selected-pack preservation. |
| `-ObjectSmokeTest` | Chest lids, beds, dropped items, optional Lycanites model, screens, resize, and reload. |
| `-TileSmokeTest` | Owned fast/custom TESR geometry, mutation, reload, dynamic texture, and removal. |
| `-FirstPersonSmokeTest` | Equip/swing/bow/FOV, body/view categories, screens, and block damage stages/reload/cancel. |
| `-FirstPersonFarSmokeTest` | Moves the first-person arena 16,384 blocks farther along X to exercise coordinate precision; also requires `-FirstPersonSmokeTest`. |
| `-HeldLightSmokeTest` | Held-light ownership, movement, extinguishing, toggle, and cleanup. |
| `-GlintSmokeTest` | Item/armor glint, animated UVs, material categories, and reload. |
| `-CombatSmokeTest` | Nonliving projectiles and offscreen entity capture. |
| `-EffectSmokeTest` | Particles/rain, additive strips/fans, CPU texture revisions/deletion/replacement, camera movement, and reload. |
| `-CloudSmokeTest` | Clouds and an owned custom sky renderer, motion, weather, and cleanup. |
| `-FoliageSmokeTest` | Installed Better Foliage geometry, AO independence, reload, and removal. |
| `-CompatibilitySmokeTest` | Actual installed mod creatures, animation, offscreen capture, reload, and removal. |
| `-SurvivalSmokeTest` | Server damage, client melee/projectile/eating actions, native capture, and restored player state, including First Aid when installed. |
| `-FallingTreeSmokeTest` | Installed Dynamic Trees generation, player chopping, falling branch capture/animation, and retirement. |
| `-MobHudSmokeTest` | Installed Neat health-bar pixels, disabled/focused/F1 visibility rules, and settings restoration. |
| `-BlockEffectSmokeTest` | Contrasting crack stages 1/8, real FBP plant/stone debris, intermediate stone/tinted-grass placement poses, and cleanup. |
| `-VisibilitySmokeTest` | Same-room shadow controls at 2 and 2.5, image comparison including highlights, and exact native-setting restoration. |
| `-DimensionSmokeTest` | Server-owned Overworld/Nether/End/Overworld transitions with rendering enabled. |
| `-WindowSmokeTest` | Native menus in gameplay/inventory, input release, fullscreen, resize, and restoration. |
| `-TerrainOptimizationSmokeTest` | Supported SOLID draw suppression, injected failure with pixel-exact pre-HUD recovery, release, reload, and resize. |
| `-SoakSmokeTest` | Repeated loaded-chunk travel, reload, renderer disable/re-enable, and bounded-resource observations. |
| `-TerrainSmokeTest` | Travel, a published edit, view-distance changes, retirement, and recovery. |

`-WindowMinimizeSmokeTest` also requires `-WindowSmokeTest` and the separate
`scripts/check-window-minimize.ps1` helper. Run the helper in another PowerShell
with the same `-InstanceName`/`-LauncherDirectory`; it waits for the fixture's
request and validates the owning game process/window before minimizing and
restoring it. It does not select an unrelated desktop window.

The falling-tree, mob-HUD, and block-effect fixtures require their corresponding
installed mods; the FBP fixture uses its enabled, unfrozen placement configuration
without changing it. Select the RLCraft instance with `-InstanceName` for these
checks. Screenshots identify each crack stage, debris type, and placement pose;
placement evidence requires new corrected-color, proven opaque/cutout draws and
changing native geometry across intermediate frames.

`-SoakSeconds` sets the soak duration from 60 to 7200 seconds, default 1800. The
fixture must also finish four travel cycles, a reload, and a disable/re-enable
cycle, so a short requested duration is a minimum. Its peak-memory observations
do not by themselves establish that a session is leak-free.

Multiplayer is a separate run. `scripts/multiplayer-server.py prepare` verifies
cached Forge server inputs, and `prepare --pack rlcraft-2.9.3` also verifies the
official matching server pack. The `run` command creates a fresh fixture directory,
binds only to loopback, keeps online authentication enabled, and stops its own
server after a bounded duration or a `stop.request` file in that run directory.
It requires explicit EULA acceptance. For example, in a separate terminal:

```powershell
python .\forge-1.12.2\scripts\multiplayer-server.py run --fixture-id local-forge-check --port 25575 --seconds 900 --accept-eula
```

After the helper reports `ACCEPTING`, launch the matching signed-in client.
At this point `ready.json` has `fixtureReady: false`. After login, the helper
teleports the player, waits for loaded destination chunks, constructs the scene,
and verifies its blocks and exact cow UUID before reporting `FIXTURE_READY`
and writing `fixture-ready.json`:

```powershell
.\forge-1.12.2\scripts\launch-prototype.ps1 -SmokeTest -MultiplayerSmokeTest -MultiplayerPort 25575 -MultiplayerFixtureId local-forge-check -ExitAfterSmoke
```

Use a new fixture ID for each run. The multiplayer fixture checks encrypted
connections, exact server-owned markers, disconnect cleanup, and reconnection
with new world/network objects. Add `--pack rlcraft-2.9.3` to the server command
and the RLCraft `-InstanceName` to the launcher for the pack test. This disposable
flat Creative world checks network/render lifecycle; it does not establish full
survival balance or natural world-generation compatibility.

`-TerrainNaturalSmokeTest` requires `-TerrainSmokeTest` and uses natural generation.
`-TerrainSmokeRadius` accepts 4–32, default 4. If the initial radius reaches the
mesh cap, the fixture records incomplete coverage and reduces to four to verify
recovery; that is not a pass for full coverage at the original radius.

```powershell
.\forge-1.12.2\scripts\launch-prototype.ps1 -InstanceName 'RLCraft 2.9.3 - Remix Test' -SmokeTest -TerrainSmokeTest -TerrainNaturalSmokeTest -TerrainSmokeRadius 8 -ExitAfterSmoke
```

Fixtures restore the settings and game rules they change and never replace or
delete existing worlds. Cleanup failure is reported and leaves the fixture open
instead of exiting with unrestored state. The PBR fixture temporarily adds only
its owned in-memory pack while retaining default, selected, and server packs.
A normal launch clears smoke-test JVM options. Captures are in the instance's
`.minecraft/screenshots` and structured `RLC_SMOKE` records in
`.minecraft/logs/latest.log`; imported instances may use `minecraft/` instead.

Use fixture details with screenshots: PBR logs expose map counts, update counts,
omissions and pack-selection preservation; effects expose additive/triangle
counts, texture updates/patches, dirty textures and dropped updates; tile checks
expose geometry revisions; window checks expose menu and input lifecycle stages.
Image checks reject blank output but do not establish correct material appearance.

Recorded local Creative checks include the combined Forge/RLCraft terrain,
materials, objects, effects, and sky runs of 2026-10-23. The RLCraft radius-eight
route traveled 320 blocks and converged against actually loaded chunks. A Forge
radius-32 stress run held mesh ownership at 536,837,280 bytes for 24 frames and
recovered at radius four; RLCraft radius twelve also reached the cap. Eight chunks
is a tested starting point, with denser areas still able to exhaust the budget.

On 2026-10-24, separate Forge checks passed clouds/custom sky/PBR (`060806`),
first-person and Fast TESR (`062921`), block-damage stages/reload/cancellation
(`063950`), and native-menu/window lifecycle (`065132`). The last included actual
owned-window captures, since native menus cannot be verified in Minecraft's
framebuffer alone. Later checks passed additive/triangle effects and CPU texture
replacement (`073428`), item glint (`074207`), dimension transitions (`074723`),
and minimize/resume, exact terrain recovery, and four streaming cycles (`075450`).
The latter run observed complete capture of the actually loaded radius-eight
columns, one texture reload, and release/re-enable; it was a short test, not a
long-session memory-stability result.

RLCraft checks passed clouds/custom sky (`071527`) and projectiles/offscreen
capture, PBR animation/reload, first-person/block damage, held lights, and dynamic
Fast TESR textures (`072608`). Glint and Better Foliage passed in `080107`.
Fancy Block Particles dust passed after correcting the completed buffer's
unpadded stride (`081517`). Window/minimize, dimension transitions, exact terrain
recovery, and a four-cycle short soak passed in `080722`. Survival combat,
projectiles, food use, reload, and restoration passed in `084402`, including
matching client/server health and First Aid limb state before returning to the
saved game mode. These results apply to their tested snapshots and fixtures.
Forge multiplayer passed two authenticated, encrypted TCP sessions with captured
terrain/entities and zero world resources after each disconnect (`084832`).
The complete dragon/Maka/Rahovart cohort passed animation, movement, offscreen
capture, texture reload, and retirement (`085423`); screenshots confirmed the
opaque-base correction for Rahovart. Full RLCraft multiplayer also passed both
authenticated sessions with verified server fixtures and zero client resources
after each disconnect (`090034`). The first 1,808-second RLCraft streaming soak
(`091402`) passed its capture and lifecycle checks, but failed native texture
validation: the runtime rejected animated albedo patches because their format
differed from the sRGB atlas. Atlas creation and patches now explicitly use the
same format. The GPU bridge regression also verifies a retained sRGB texture
actually changes rendered pixels after an update, without replacing the material,
mesh, or instance. Java update counters alone do not prove native application.
The same GPU check compares the runtime's converted DirectX normal texture with
the two-channel normal encoding, then verifies that patching the retained normal
texture changes the lighting. Animated normals use that updateable format.

The corrected RLCraft run (`102535`) passed 1,800 seconds of streaming, with 71
travel cycles, 18 resource reloads, and 17 disable/re-enable cycles. Its native
log contained no rejected texture updates. Every completed stop converged against
actually loaded columns without a capacity failure. Final pose restoration
queued terrain recapture, followed by a successful zero-resource world unload
and normal process exit. Passive process-memory samples did not show sustained
growth after warmup. This flat Creative fixture does not establish leak freedom
or long-session natural survival coverage.

A fresh instance containing only the self-contained package passed extraction,
materials/PBR, block entities, effects, toggles, and unload (`102119`); its second
launch passed rendering and preservation checks (`102337`). Neither launch
reported a rejected native texture update. All 189 native payload files matched
their inventory hashes after each launch, and both authored-file sentinels
remained unchanged. The shared bridge also passed the root Fabric demo-world
launch, rendered-image check, and normal shutdown with 211 retained terrain pages
(`090831`).

The pinned runtime reports 42 undisposed common device objects at process exit
in both the Forge and Fabric clients. Both clients finish shutdown, but this
native accounting issue remains unresolved and is distinct from the checked
zero world-resource ownership. The matching runtime source is maintained
separately; a fix must ship in a matching published runtime package.

The playthrough regression run `20261024-165322-9f94c6eb` passed the six-mob
movement/reload/retirement cohort, Dynamic Trees capture on all 19 renderable
falling frames, all six Neat visibility cases, and FBP damage/debris/placement
checks. Inspected placement images retain stone and grass shading through two
animation poses; damage images retain the underlying obsidian and quartz colors.
The shadow comparison (`160935`) raised mean shadow luminance from 43.06 to 47.74
without increasing clipped highlights; exposure and highlight controls were
unchanged.

Native first-person probes under the Forge runtime configuration pass at the
origin and at positive/negative 15,000-block coordinates, including rotated
cameras and per-frame replacement of opaque, cutout, and alpha-blended meshes.
The windowed RLCraft run `20261024-171909-a0f028f4` passed the settled sword image
check, swing/bow-use/FOV checks, hand/body visibility transitions, reload, and held
lights at X=15,662; sword and drawn-bow screenshots were inspected. Earlier
captures reached the newly selected item while its equip animation was still
lowered. The fixture now waits for both vanilla equip-progress samples before
applying its strict sword-pixel check. Optional
`-Drlcraftremix.viewModelDiagnostics=true` records bounded pose/projection details
and captures a vanilla control if that image check fails.

The default Arisaur's binary-alpha body now renders as solid geometry. Flowersaur
contains authored fractional alpha, so it retains blending; its raised tail's
visibility at the horizon remains unresolved. The user's exact reported creature
is not yet identified, and still screenshots do not establish temporal ghosting.
The same-camera comparison (`20261024-173301-2b173457`) confirms that the tail is
visible with Remix disabled. Add
`-Drlcraftremix.compatibilityVanillaComparison=true` to the compatibility fixture
to capture this pair: it verifies the same client entities and root positions,
waits 40 client ticks while disabled, then restores and settles the complete native
cohort before continuing the animation/reload/removal checks. Idle animation
continues between captures, so the comparison is not pixel-aligned.
Separate diagnostic runs with `rtx.nativeMipBias = -16` (`173526`) and
`rtx.resolveOpaquenessThreshold = 0.5` (`173727`) still show the tail defect; the
native logs confirm each setting was loaded. Both overrides were removed after
testing. These results do not establish the cause or justify forcing fractional
materials opaque.

The isolated native retained-alpha probe passes with Forge settings and ordinary
world geometry: alpha-255 samples occlude contrasting backgrounds, alpha-128
samples reveal them, and a same-handle texture patch restores coverage. This
rules out a blanket inability to render opaque pixels on a blended surface; it
does not reproduce Flowersaur's mesh, texture footprint, or temporal behavior.

## RLCraft test instance

The [official RLCraft 2.9.3 client ZIP](https://www.curseforge.com/minecraft/modpacks/rlcraft/files/4612979)
is distributed separately. Import it into a separate Prism instance, keeping the
Forge-only instance as a regression baseline. The inspected manifest specifies
Minecraft 1.12.2, Forge 14.23.5.2860, and 187 mod entries. Complete any browser
mod downloads requested by Prism, select a Java 8 JDK, and allocate memory in the
instance settings. With the game closed:

```powershell
.\forge-1.12.2\scripts\install-prototype.ps1 -GameDirectory '<RLCraft game directory>'
.\forge-1.12.2\scripts\launch-prototype.ps1 -InstanceName 'RLCraft 2.9.3 - Remix Test'
```

`-InstanceName` is the folder under Prism's `instances`, which may differ from
its display name. `-LauncherDirectory` selects another Prism installation.
If an older setup assigned Remix to F8, unbind it in Controls to preserve Better
Foliage's shortcut. Start with the original mods and a windowed Creative scene;
compare stationary terrain after chunk animation settles. API/interface coverage
and fixture success do not establish every mod's custom shader, geometry,
texture-update, or survival effect path.

See [UPSTREAM.md](UPSTREAM.md) for versions and provenance and
[bridge/README.md](bridge/README.md) for the native integration contract.
