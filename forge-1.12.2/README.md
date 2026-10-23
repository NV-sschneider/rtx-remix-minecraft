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

RLCraft 2.9.3 has passed an initial Creative fixture with its original mod set.
Broader gameplay and survival effects remain unvalidated.
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

The Forge build passed 12 JUnit checks covering mesh packing, real Minecraft lifecycle
bytecode, main-menu initialization ordering, and blank fixture image rejection. The fixture
recognizes replacement main menus through Forge's initialization events.
The authenticated Forge-only in-game test captured 184
native frames with nine terrain sections. Visual inspection confirmed textured
terrain, color and camera alignment, shadows, glass cutouts, and the hand/HUD.
Switching back produced the expected vanilla image. Re-enabling Remix rendered
62 more frames without resetting its timeline; unloading the world while enabled
returned to the menu cleanly. The process then exited cleanly.
The pinned native runtime reports a remaining device-object accounting warning
at process exit; that runtime cleanup warning still needs upstream investigation.

RLCraft 2.9.3 also rendered the Creative fixture with its original mod set enabled.
Matched screenshots showed textured terrain, colored pillars, glass, shadows,
and the pack HUD. Renderer re-enable, return to the replacement main menu, and
process exit completed successfully. An earlier run submitted frames but produced
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

Before enabling Remix, unbind its toggle in Controls to preserve Better Foliage's
F8 binding. Use `/mrtx on`, `/mrtx off`, and `/mrtx status` in a disposable Creative
world. Retain the pack's windowed mode, framebuffer setting, and original mods
for the first comparison. Compare stationary terrain after chunk animation has
settled. Additional foliage, entities, particles, fluids, and animated textures
still need capture support; disabling their mods does not implement that support.
Pack manifest/config inspection alone does not establish runtime compatibility.

See [UPSTREAM.md](UPSTREAM.md) for versions and provenance and
[bridge/README.md](bridge/README.md) for the native integration contract.
