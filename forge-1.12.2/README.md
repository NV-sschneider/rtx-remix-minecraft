# RTX Remix terrain prototype for Forge 1.12.2

An experimental backport of NVIDIA's Minecraft RTX Remix integration to Java 8,
LWJGL 2, and Forge 1.12.2, the platform used by RLCraft 2.9.3. This is an early
terrain renderer, not a complete RLCraft-compatible release.

The Fabric renderer remains at the repository root. This module has its own
Gradle 4.9 build and a temporary, isolated Java 8 bridge snapshot under
`forge-1.12.2/bridge/`; it does not change the shared `rtxremix-java/` facade or
the Fabric build.

## Run the installed prototype

From the repository root in PowerShell, using the configured portable Prism
installation:

```powershell
.\forge-1.12.2\scripts\launch-prototype.ps1
```

Prism launches **Forge 1.12.2 - Remix Port** using your signed-in Minecraft Java
account. Enter a disposable Creative world and press **F8** to enable Remix.
Press F8 again to restore vanilla rendering. `/mrtx status` reports the frame
and terrain section counts; `/mrtx reload` refreshes the captured block atlas.
The renderer starts disabled. Use a normal window for this prototype.

The development setup also has a separate **Minecraft 1.21.1 - RTX Remix
Baseline** Prism instance containing the original NVIDIA mod, built from the
pinned source for comparison.

## What is implemented

- A Java 8 port of NVIDIA's Java/JNI bridge using its unchanged Remix C API 0.16.0.
- Native OpenGL shared-image and timeline-semaphore interop for LWJGL 2.
- Baked block models, block-atlas textures, biome tint, and a static sun.
- Retained terrain meshes in a 3 by 3 chunk area, updated one section per frame.
- Block-change, chunk-unload, world-change, and texture-reload handling.
- Remix output copied into Minecraft's framebuffer before the usual HUD.
- Render-thread lifecycle hooks around Minecraft's `Display.destroy()` call.

The runtime and its GL timeline remain alive while F8 is off; world meshes are
released. The runtime is shut down after Minecraft destroys its window. The
native runtime module stays loaded until process exit because unloading the
pinned DLL after shutdown deadlocked during testing.

Prism must launch the game visibly. A hidden launcher window propagated a hidden
state that allowed successful API calls but suppressed ray tracing. The launch
script now uses a normal interactive window.

## Current limits

RLCraft's complete mod collection has not yet been integrated or tested.
Entities, tile-entity renderers, fluids, particles, animated texture updates,
and custom rendering need additional capture paths. Terrain outside the small
capture radius is absent. Glass uses basic alpha behavior, not a finished
physical glass material. Sunlight does not follow world time or weather.
Fullscreen/context recreation and minimize/restore are not validated.
Better Foliage uses F8 too; rebind the Remix toggle in Minecraft Controls before
testing those mods together. Broad mod compatibility is not validated.
This prototype still runs vanilla world rendering before replacing its color
output, so it is not performance optimized.

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

The Forge build passed seven JUnit checks covering mesh packing and real
Minecraft lifecycle bytecode. The authenticated in-game test captured 184
native frames with nine terrain sections. Visual inspection confirmed textured
terrain, color and camera alignment, shadows, glass cutouts, and the hand/HUD.
Switching back produced the expected vanilla image. Re-enabling Remix rendered
62 more frames without resetting its timeline; unloading the world while enabled
returned to the menu cleanly. The process then exited cleanly.
The pinned native runtime reports a remaining device-object accounting warning
at process exit; that runtime cleanup warning still needs upstream investigation.

The opt-in in-game fixture creates a uniquely named flat Creative world,
constructs a small colored-block scene on the server thread, and captures Remix
after at least 180 successful frames followed by vanilla at the same camera
position. It then checks re-enabling Remix and unloading the world:

```powershell
.\forge-1.12.2\scripts\launch-prototype.ps1 -SmokeTest -ExitAfterSmoke
```

It never replaces or deletes existing worlds. Captures remain under the Forge
instance's `.minecraft/screenshots`; structured `RLC_SMOKE` results are in
`.minecraft/logs/latest.log`. Capture completion alone does not prove image
correctness; inspect the screenshots. A normal launch through the script clears
the smoke-test JVM options.

## RLCraft pack acquisition

The [official RLCraft 2.9.3 client ZIP](https://www.curseforge.com/minecraft/modpacks/rlcraft/files/4612979)
is not included or installed by this prototype. Download the client ZIP in a
browser and place it under the repository's ignored `work/downloads/` directory
for import into a separate pack instance. Keep the Forge-only instance as the
renderer regression baseline.

See [UPSTREAM.md](UPSTREAM.md) for versions and provenance and
[bridge/README.md](bridge/README.md) for the native integration contract.
