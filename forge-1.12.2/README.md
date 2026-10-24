# RTX Remix prototype for Forge 1.12.2

An experimental backport of NVIDIA's Minecraft RTX Remix integration to Java 8,
LWJGL 2, and Forge 1.12.2, the platform used by RLCraft 2.9.3. This is an early
renderer with terrain, fluids, a time-driven sky, entities, items, and world effects.
It is not a complete RLCraft-compatible release.

The Fabric renderer remains at the repository root. This module has its own
Gradle 4.9 build and a temporary, isolated Java 8 bridge snapshot under
`forge-1.12.2/bridge/`; it does not change the shared `rtxremix-java/` facade or
the Fabric build.

## Build an installable package

With a Java 8 JDK, Visual Studio C++ tools, and the pinned Windows x64 runtime
staged in `remix-runtime/win64`, run from the repository root:

```powershell
.\forge-1.12.2\scripts\build.ps1 -Tasks packageForge
```

The distributable JAR is written to `forge-1.12.2/build/distributions/`. It includes
the Java mod, its LWJGL 2 JNI bridge, the runtime DLLs and USD plugins, and the runtime
license texts. Install this JAR in the `mods` folder of an existing Forge 1.12.2
instance; it does not bundle Minecraft, Forge, or RLCraft. The ordinary `build/libs`
JAR is for development and still requires the staged runtime.

The first `/mrtx on` extracts the package before loading any native library. A
SHA-256 inventory verifies the staged files before they replace the live SDK.
A changed payload or missing SDK dependency replaces the mod-owned `remix-sdk`
directory and `rtx.conf`; a matching installation keeps existing configuration
and restores a missing `rtx.conf` separately. Keep authored Remix work under
`rtx-remix`, which this extractor leaves untouched. An explicit
`-Drlcraftremix.runtime=<directory>` uses that development SDK without extraction.
Windows x64 is the currently supported Forge package target.

The repository build workflow also compiles and runs the Forge tests separately
on Java 8. Rendering and native packaging are verified locally on a supported GPU.

## Run the installed prototype

From the repository root in PowerShell, using the configured portable Prism
installation:

```powershell
.\forge-1.12.2\scripts\launch-prototype.ps1
```

Prism launches **Forge 1.12.2 - Remix Port** using your signed-in Minecraft Java
account. Enter a disposable Creative world and use **`/mrtx on`** to enable Remix.
Use `/mrtx off` to restore vanilla rendering. A toggle can be assigned in Controls;
new installations leave it unbound to avoid modpack shortcuts. `/mrtx status` reports the frame
and terrain section/entity-part counts; `/mrtx reload` refreshes captured textures.
The renderer starts disabled. Use a normal window for this prototype.

The development setup also has a separate **Minecraft 1.21.1 - RTX Remix
Baseline** Prism instance containing the original NVIDIA mod, built from the
pinned source for comparison.

## What is implemented

- A Java 8 port of NVIDIA's Java/JNI bridge using its unchanged Remix C API 0.16.0.
- Native OpenGL shared-image and timeline-semaphore interop for LWJGL 2.
- Baked block models, block-atlas textures, and biome tint.
- Source and flowing water/lava geometry, with refractive water and emissive lava.
- Underwater camera media and refractive full-block clear/stained glass.
- Bounded animated atlas updates using Minecraft's actual uploaded frames.
- Nearby block lighting derived from emitting blocks in published terrain.
- A sky dome with sun, moon, stars, and celestial lighting driven by world time
  and weather, using the world's sky/fog colors.
- Retained terrain meshes out to Minecraft's render distance, streamed nearest
  first as the camera moves, with incremental section capture and mesh uploads.
- Block-change, chunk-unload, world-change, and texture-reload handling.
- Living-entity box parts captured during their existing model draws, with
  static textures, tint, alpha state, and retained instances updated as their poses change.
- Model-based tile entities, optional Mo' Bends parts, and Lycanites CPU/VBO OBJ draws.
- Dropped items captured from their actual baked-model vertices and world transforms.
- Particle and precipitation batches observed before their existing OpenGL uploads.
- World rendering behind inventory and pause screens, with window-size changes.
- Remix output copied into Minecraft's framebuffer before the usual HUD.
- Render-thread lifecycle hooks around Minecraft's `Display.destroy()` call.

The runtime and its GL timeline remain alive while Remix is off; world meshes are
released. The runtime is shut down after Minecraft destroys its window. The
native runtime module stays loaded until process exit because unloading the
pinned DLL after shutdown deadlocked during testing.

Prism must launch the game visibly. A hidden launcher window propagated a hidden
state that allowed successful API calls but suppressed ray tracing. The launch
script now uses a normal interactive window.

## Current limits

RLCraft 2.9.3 has passed an initial Creative fixture with its original mod set.
Broader gameplay and survival effects remain unvalidated.
Clouds, custom mod sky renderers, fast tile renderers, unsupported entity/effect
renderers, and shader-only effects need additional capture paths.
First-person hands/items retain vanilla rendering. Nonliving projectiles and
Better Foliage's extra geometry do not yet have native capture adapters.
Terrain arrives progressively, and a server may send
fewer chunks than the client's requested render distance. Glass panes and custom
mod glass retain their ordinary block material; full vanilla glass blocks use refraction.
Fullscreen/context recreation and minimize/restore are not validated.
Existing saved key bindings are preserved; unbind an old Remix F8 binding in
Controls if it conflicts with Better Foliage. Broad mod compatibility is not validated.
This prototype still runs vanilla world rendering before replacing its color
output, so it is not performance optimized.

## Terrain streaming

Terrain capture follows the render camera and Minecraft's render-distance setting.
It reads only loaded client chunks; it does not request or generate distant chunks.
Nearby sections are captured first. A section build advances across frames on the
render thread, where Forge's mutable baked models and world data are safe to read.
Completed geometry uploads in bounded pages. The previous section remains visible
until its replacement is ready, and obsolete mesh resources retire under a separate
per-frame limit.

Block edits invalidate neighboring sections, including diagonal neighbors used by
extended block models. A chunk packet, unload, or edit that arrives during capture
invalidates that build before it can publish stale geometry. Moving or reducing
render distance releases sections outside the new area; returning queues them again.
Resource reload and disabling Remix release the terrain and its atlas resources.

`/mrtx status` reports the target radius, discovered columns, captured sections,
pending work, retirement queue, and owned mesh bytes. A memory limit includes meshes
being uploaded or retired as well as the visible scene. Reaching it pauses uploads
and is reported in the status; the renderer does not silently reduce the requested
radius. The limit is 512 MiB of vertex/index payloads; runtime copies and acceleration
structures use additional GPU memory. A frame probes at most 32 columns, visits at
most 4,096 blocks, uploads one mesh page of at most 1,146,880 bytes, and retires at
most four pages. Higher render distances and complex modded models take longer to fill.
Capture's time budget is cooperative: an individual model call or native operation
cannot be interrupted, and initial atlas capture and complete teardown are separate
from the two-millisecond streaming budget. A complete section publishes atomically,
which can add and remove up to 128 instances each. A section exceeding the bounded
model buffer keeps its previous geometry and reports a capacity failure; it retries
after another invalidation. Vanilla still renders the world before Remix replaces
the framebuffer, so this is not a general frame-rate optimization.

## Fluids, animations, and block lighting

Water and lava use the installed vanilla liquid tessellator for corner heights,
flow direction, exposed faces, and water-overlay selection. The captured geometry
uses biome tint without vanilla lightmap or directional shading. Water has a
refractive material with an index of 1.333; lava uses its atlas texture for emission.
Each keeps a separate material from ordinary blocks. Custom fluids using baked
models retain that path; custom `LIQUID` renderers outside vanilla's `BlockLiquid`
contract are reported and skipped. The actual render camera selects a texture-free
water medium using Minecraft's liquid surface and Forge viewpoint rules. Leaving
water clears that selection; the material stays cached until reload or teardown,
which clear the selection before destroying it.
Animated normal maps are not implemented.

Full vanilla glass and stained-glass blocks use separate refractive materials with
an index of 1.52. Clear glass retains its atlas markings over neutral transmission;
stained glass uses its atlas color for transmission. Panes and custom glass remain
on the ordinary block path because these thin or unknown shapes need separate
material treatment.

The atlas observer mirrors successful mip-zero animation uploads, including
Minecraft's frame timing and interpolation. It uses CPU upload data and
does not read the entire atlas each frame. Up to 64 rectangles and 4 MiB upload
per frame; the coalescing cache is bounded to 4,096 rectangles and 32 MiB. Resource
reload and world release detach the observer before destroying the native atlas.
Custom texture uploads outside `TextureMap.updateAnimations` need an adapter.

Block lighting retains the brightest emitter in each four-block cell of a terrain
section, then selects up to 128 nearby lights within 48 blocks. Selection runs at
most five times per second, with eight native additions/removals per frame. Light
levels come from the block's current Forge light value; torch/fire, redstone, and
cool light sources receive simple color profiles. Lava uses surface emission
instead of a duplicate point light. These are approximate lighting profiles,
not per-mod authored materials. Changed blocks, unloaded terrain, toggling Remix,
and texture reload retire the associated lights.

## Sky

The Overworld uses a 256 by 128 HDR dome built from the provider's sky, fog,
and sunrise/sunset colors. Its lighting and celestial direction follow world
time, rain, thunder, and the moon phase. The sun and moon use the active resource
pack's textures on separate sky meshes; 500 small procedural stars rotate with
the same celestial orbit. This preserves their detail independently of the dome
resolution. Provider colors are converted to linear radiance before interpolation;
sun and moon textures use sRGB sampling. Dome uploads are limited to four per
second, while celestial movement and direct lighting update with each frame.
Resource reload, world changes, and disabling Remix release the sky's native
textures, lights, materials, meshes, and blend state.

The End uses a constant violet dome; nonsurface dimensions such as the Nether
use a black dome without sun or moon lighting. These dimension policies follow
the vanilla 1.12 sky selection. Custom Forge sky renderers, clouds,
and mod-specific celestial effects need separate capture support. Provider sky
color changes are sampled, but this does not reproduce every sky mod's draws.

## Living-entity capture

The core transformer observes `ModelRenderer` display-list calls inside the
existing `RenderLivingBase` render scope. Each part's live model-view matrix
includes Minecraft's animation and parent transforms. Capture removes the view
matrix and restores the render manager's world origin, preserving renderer
offsets. It does not rerun models or cancel vanilla rendering. Canceled renders,
exceptions, first-person rendering, and GUI previews cannot leave capture armed.

Ordinary `ModelBox` geometry is cached per model part, display-list identity,
render scale, and texture object. Recompiling a display list invalidates its cached
geometry; mutating geometry without recompiling still needs an adapter. The optional
LLibrary `AdvancedModelRenderer` hook covers its ordinary box draw path, but
individual Ice and Fire creatures still need runtime validation. Mo' Bends 1.2.1
model parts and visible `MutatedBox` faces have a separate adapter; whole-body
transforms applied by the Forge pre-render event are preserved. Other custom
box subclasses remain unsupported.
Additive eyes, enchantment glint, and faded invisible models are skipped.
Textures are static snapshots refreshed on resource reload or world release.

The same model-part observer captures ordinary tile-entity dispatcher draws,
including chest lids and beds. Fast TESRs, damage overlays, and non-model tile
geometry are omitted. GUI and first-person previews do not enter this scope.

Lycanites 2.0.8.9's `TessellatorModel` adapter observes completed CPU triangle
buffers with their actual per-group transforms, vertex color, normals, and animated
UVs. It accepts up to 32,766 vertices per group and 131,064 per frame. Changed OBJ
meshes upload under an eight-mesh/four-MiB frame budget; deferred replacements keep
their prior native instances while their current transforms keep updating.
`VBOModel` captures actual GPU draw transforms, color, and texture-matrix offsets.
A bounded cache observes successful Lycanites mesh uploads and deletions; buffers
created before capture can be read back once, with at most one readback of up to
one MiB per frame. The CPU cache holds at most 512 meshes and 16 MiB and clears
on reload or world release. No optional mod classes are required in Forge-only runs.

The render thread owns all native resources. Entity identity and draw occurrence
identify retained instances; a changed pose or tint immediately updates its
native description. Unseen instances retire before their meshes and textures.
Capture is bounded to 1,024 parts, 256 per creature, 512 meshes, 64 textures, and
64 MiB each of native mesh and texture data. Over-budget geometry is omitted;
existing geometry remains visible while replacements wait for capacity. At a full
cache, a replacement can remain deferred until other occurrences leave the scene.

## Dropped items and effects

Dropped items use the actual vertices written by `RenderItem`, preserving Forge
tint, alpha, stack copies, and the transforms supplied by ItemPhysic. Their native
materials borrow the live block atlas, so animated item sprites follow the atlas
observer. Capture allows 256 occurrences, 16 copies per owner, 256 meshes, and
32 MiB of mesh data. Custom item tile renderers, non-atlas textures, and glint
passes remain unsupported.

Particles and weather observe completed `BufferBuilder` draws inside the real
particle-manager and precipitation passes. Capture preserves per-vertex color,
UVs, world origin, and conventional alpha blending without updating particles a
second time. It allows 32 batches and 8,192 quads per frame. Native meshes are
transient; textures are cached under a 16-texture/32-MiB limit with at most one
first-use texture readback per frame. Block-atlas particles borrow the terrain
atlas. Additive/premultiplied blends, shaders, nonidentity texture matrices,
dynamic or unknown texture bindings, and non-buffer draws are omitted.

Reload and teardown release item/effect consumers before the borrowed atlas.
Native release failures retain ownership for retry instead of discarding handles.

## Build and install

Building requires Windows x64, a Java 8 JDK, and Visual Studio x64 C++ tools.
The development setup keeps Java 8 and portable Prism under the repository's
ignored `.tools/` directory and the Forge Gradle cache under `.gradle-home/`.
These local dependencies and the authenticated Prism instance are not included
in Git. Java 21 is used for the Fabric build at the repository root.

Run the following commands from the repository root. The runtime script reads
the root `packman/remix-sdk.packman.xml` and stages its pin under
`remix-runtime/win64`:

```powershell
.\forge-1.12.2\scripts\fetch-runtime.ps1
.\forge-1.12.2\scripts\build-bridge.ps1 -RuntimeDirectory .\remix-runtime\win64
.\forge-1.12.2\scripts\build.ps1
.\forge-1.12.2\scripts\install-prototype.ps1
```

Close the Forge game before reinstalling. The installer stages a copy of the
pinned runtime and the local bridge under the instance's `remix-sdk` folder;
it does not modify the runtime package cache or existing saves. It preserves an
existing `rtx.conf`.

The reobfuscated mod is built at
`forge-1.12.2/build/libs/rlcraft-remix-forge-0.1.0-prototype.jar`. The matching
native bridge is `forge-1.12.2/bridge/build/native/win64/remixbridge.dll`. Both
and the pinned runtime are required; the mod JAR alone is insufficient.

## Validation

The relocated prototype was clean-built and validated through its module scripts.
The original 1.21.1 baseline built successfully, passed 799 tests, and initialized
Remix through the authenticated launcher. The Java 8 native GPU test rendered a
red triangle for 30 frames, verified shared-image/timeline synchronization and
GL state restoration, and exited successfully. Run it with:

```powershell
.\forge-1.12.2\scripts\build-bridge.ps1 -RuntimeDirectory .\remix-runtime\win64 -GpuSmoke
```

The Forge build passed 173 JUnit checks covering terrain/entity mesh packing, live
transform conversion, alpha state, real Minecraft lifecycle/capture bytecode, cancellation and exception
cleanup, main-menu initialization ordering, blank fixture image rejection,
bounded fixture motion, sky geometry/orientation, color conversion, dimension
policies, terrain window scheduling through 32 chunks, stale-build rejection,
mesh reservation accounting, and game-rule/time/weather restoration. Additional checks
cover fluid/glass materials, animated atlas patches, camera media, item/effect
decoding, optional model adapters, bounded VBO caching, and retry-safe native
ownership. The fixture recognizes
replacement main menus through Forge's initialization events.

Authenticated Forge-only and RLCraft 2.9.3 entity runs captured a moving cow and
stationary pig with textured terrain, shadows, glass cutouts, and the hand/HUD.
The fixture verified both creatures, native transform updates during a two-block
outward track, and retained-part release. Matched screenshots were inspected at
the fixed reference pose. Re-enabling Remix preserved its timeline; world unload
returned to the menu and the process exited. These runs validate cow/pig box
models in the pack, not every modded creature or translucent effect.

Subsequent authenticated Forge and RLCraft runs passed the combined entity and
sky fixture. Screenshots confirmed a blue noon sky, the sun above the western
horizon at sunset, a textured moon and small stars at midnight, and suppressed
celestial bodies in rain. Each normal sky owned 20 native resources; disabling
and unloading released them all. The tests restored time, weather, and game rules
on both worlds before disconnecting. End and skyless dimension policies have
unit coverage but have not yet been checked in a live dimension transition.

The pinned native runtime reports a remaining device-object accounting warning
at process exit; that runtime cleanup warning still needs upstream investigation.

The combined materials, objects, effects, terrain, entity, and sky run passed in
Forge (`20261023-231001-a3b91d71`) and in RLCraft with natural terrain at radius
eight (`20261023-232105-1d45f1eb`). It verified animated water/lava pixels,
torch removal, underwater entry/reload/exit, glass reload/removal, chest-lid
motion, both bed halves, dropped-item motion, inventory/pause backgrounds,
window resize/restore, and particle/rain motion across camera movement and reload.
Rain retained 440 valid quads while skipping vanilla's invalid center quad.
RLCraft captured seven Maka Alpha triangle groups, including bounded VBO recovery
after reload; a separate run also captured an ordinary Maka. Screenshots were
inspected for glass, fluids, model objects, particles, and weather. The full
RLCraft run returned to 288 loaded/captured columns and 1,746 sections with
407,448,160 bytes of mesh payload, no pending work, and no capacity limit.
World release checks returned owned resources and the VBO cache to zero.
These are Creative capture/lifecycle checks, not comprehensive survival validation.

RLCraft used its original mod set. An earlier terrain-only run submitted frames but produced
black world output; the fixture now rejects that image instead of treating frame
submission as readiness. The cause of that first-run blank output is not yet
established. This fixture does not validate survival effects or missing capture paths.

The opt-in in-game fixture creates a uniquely named flat Creative world,
constructs a small colored-block scene on the server thread, and captures Remix
after at least 180 submitted frames followed by vanilla at the same camera
position. Before accepting the capture, and after re-enabling Remix, it samples
a fixed fixture region for visible, colored geometry. Blank output keeps waiting
within the existing five-minute phase deadline; submitted API calls alone do not
establish readiness. It then checks unloading the world:

```powershell
.\forge-1.12.2\scripts\launch-prototype.ps1 -SmokeTest -ExitAfterSmoke
```

Add `-EntitySmokeTest` to spawn a cow and pig in the fixture. The cow makes a
bounded round trip before both the initial and re-enabled Remix checks. The
fixture requires both creatures in the retained scene, increased cow transform
updates, observed movement, and zero retained parts after disabling or unloading.
It then fixes the creatures' reference poses for screenshot comparison. Run
the same check in the installed pack with:

```powershell
.\forge-1.12.2\scripts\launch-prototype.ps1 -InstanceName 'RLCraft 2.9.3 - Remix Test' -SmokeTest -EntitySmokeTest -ExitAfterSmoke
```

Before disconnecting, the fixture restores the four rules it overrides on both
the server and client. This preserves pack settings when Global GameRules saves
rules on unload. Cleanup failure is reported as a failed test and leaves the
fixture open for inspection instead of automatically exiting with unrestored rules.
Shutdown begins on the owning server thread, and the fixture waits for normal
client disconnection before checking world-resource release.

Add `-SkySmokeTest` to capture clear noon, a west-facing sunset, midnight, and
rain after re-enabling Remix. It checks for a visible daytime sky, changed night
and rain images, texture updates, and released sky resources after disabling and
unloading. The fixture restores the original time and weather on both client and
server before disconnecting. The sky screenshots still require visual inspection
for celestial orientation and appearance.

Add `-MaterialSmokeTest` to inspect source/flowing water and lava, verify actual
sprite pixel changes, remove a torch while keeping a second light, and reload the
atlas. It also enters water, reloads while submerged, checks return to air, and
captures clear/red/blue glass from two viewpoints before reload and removal.
`-ObjectSmokeTest` checks a chest lid, both bed halves, dropped items,
a zombie, and an optional registered Lycanites Maka Alpha. The stable Alpha type
avoids ordinary Maka's random first-spawn replacement and resulting UUID change.
It exercises item movement,
inventory and pause backgrounds, window resize/restore, populated texture reload,
and object removal. `-EffectSmokeTest` adds particle motion, rain, camera movement,
populated texture reload, and dry/empty effect draws. Its small precipitation area
uses Plains biome data in the disposable fixture world to make weather repeatable.
The switches can be combined; all owned resources must release on world unload.

Add `-TerrainSmokeTest` to check capture beyond the old three-by-three area,
travel 192 blocks, replace a visible marker block, reduce render distance from
four to two chunks, expand it again, and return. Readiness checks use published
columns and section revisions, with image captures at the origin, destination,
after the edit, and on return. The fixture checks retirement of the old area and
zero terrain resources after disable and unload, then restores the original
render-distance setting. Add `-TerrainNaturalSmokeTest` to run the same route in
a normally generated landscape instead of the flat test world:

```powershell
.\forge-1.12.2\scripts\launch-prototype.ps1 -InstanceName 'RLCraft 2.9.3 - Remix Test' -SmokeTest -TerrainSmokeTest -TerrainNaturalSmokeTest -TerrainSmokeRadius 8 -ExitAfterSmoke
```

`-TerrainSmokeRadius` accepts 4 through 32 and defaults to 4. Travel scales to
keep the two captured areas separate. If the initial area reaches the mesh-memory
limit, the fixture verifies stable ownership below the limit, records that coverage
was incomplete, and reduces the radius to four to test recovery. This does not count
as full coverage at the originally requested distance.

Terrain validation passed in authenticated Forge-only and RLCraft 2.9.3 runs.
The combined Forge terrain/entity/sky fixture converged to all 81 loaded columns
at radius four. The natural RLCraft run used radius eight and traveled 320 blocks;
it converged to 259 loaded columns at the origin and 274 on return, using
388,958,640 mesh bytes on return. Both runs verified a published block edit,
shrinking and expanding render distance, and zero owned terrain resources after
disable and world unload. Coverage checks compare against Minecraft's actual
loaded chunks, since the server need not deliver every requested column.

A Forge radius-32 stress run reached the memory limit at 536,837,280 bytes and
held that ownership stable for 24 submitted frames. It then recovered at radius
four and passed travel, edit, and cleanup checks. A natural RLCraft radius-12
probe also reached the limit, so complete coverage at high distances is not
established. Eight chunks is the tested starting point for this pack; denser
areas can still reach the cap. These checks establish capture and lifecycle
behavior, not a frame-rate target or broad survival compatibility.

It never replaces or deletes existing worlds. Captures remain under the Forge
instance's `.minecraft/screenshots`; structured `RLC_SMOKE` results are in
`.minecraft/logs/latest.log`. The pixel check rejects empty fixture output;
visual correctness still requires screenshot inspection. Imported pack instances
may use `minecraft/` instead of `.minecraft/`. A normal launch through the script clears
the smoke-test JVM options.

## RLCraft test instance

The [official RLCraft 2.9.3 client ZIP](https://www.curseforge.com/minecraft/modpacks/rlcraft/files/4612979)
is distributed separately. Download the client ZIP in a
browser and place it under the repository's ignored `work/downloads/` directory
for import into a separate pack instance. Keep the Forge-only instance as the
renderer regression baseline. The inspected 2.9.3 manifest specifies Minecraft
1.12.2, Forge 14.23.5.2860, and 187 mod entries, matching this module's target.

Import the ZIP through Prism and name the new instance **RLCraft 2.9.3 - Remix
Test**. Finish any browser downloads Prism requests before installing the
prototype. Select the Java 8 JDK and allocate memory in the instance settings.
With the game closed, install into that instance's game directory:

```powershell
.\forge-1.12.2\scripts\install-prototype.ps1 -GameDirectory '<RLCraft game directory>'
.\forge-1.12.2\scripts\launch-prototype.ps1 -InstanceName 'RLCraft 2.9.3 - Remix Test'
```

`-InstanceName` is the folder name under Prism's `instances` directory; it can
differ from the display name. `-LauncherDirectory` selects another Prism
installation. Both options preserve the default Forge-only launch when omitted.

If an existing installation assigned Remix to F8, unbind it in Controls to preserve
Better Foliage's binding. New installations leave Remix unbound. Use `/mrtx on`,
`/mrtx off`, and `/mrtx status` in a disposable Creative
world. Retain the pack's windowed mode, framebuffer setting, and original mods
for the first comparison. Compare stationary terrain after chunk animation has
settled. Additional foliage and unsupported custom render paths still need adapters;
disabling their mods does not implement that support.
Pack manifest/config inspection alone does not establish runtime compatibility.

See [UPSTREAM.md](UPSTREAM.md) for versions and provenance and
[bridge/README.md](bridge/README.md) for the native integration contract.
