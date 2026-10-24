# Source and dependency provenance

This repository preserves the history of
[NVIDIA RTX Remix for Minecraft](https://github.com/NVIDIAGameWorks/rtx-remix-minecraft).
The Forge prototype was developed against upstream commit
`f2c6fdaa763f45676f1576e63f8e054bf4ef16af`. The Fabric renderer and its build
remain at the repository root; the prototype lives in `forge-1.12.2/`.

The port targets Minecraft Java Edition 1.12.2 with Forge 14.23.5.2860,
the version used by RLCraft 2.9.3. The development build uses the official
Forge MDK wrapper (Gradle 4.9), pinned ForgeGradle 3.0.197, MCP stable
39 mappings, and a local Temurin Java 8 JDK. Minecraft game assets stay
in the ignored launcher and development caches; they are not distributed
as source or included in the mod JAR.

Fabric and Forge compile the shared Java facade, JNI implementation and C API
header in repository-root `rtxremix-java/`. The facade supports Java 8 without
external dependencies. `forge-1.12.2/bridge/` contains the port's separate
OpenGL interop adapter, native build entry point and contract tests. The Forge
native build enables its existing runtime-module pin through a compile-time
option; the Fabric native build retains its module-loading behavior.
The existing Remix native API remains 0.16.0. Use only the matching runtime
pinned by the root `packman/remix-sdk.packman.xml`:
`rtx_remix_runtime@minecraft-rtx2-71a581da-win64-release`.
The runtime is separately licensed and staged under the ignored root
`remix-runtime/win64` directory. Runtime binaries are not tracked in this source
repository. Apache-2.0 license and attribution files accompany the shared
bridge and the Forge adapter; the shared C API header retains its MIT license.

The original development setup records official download URLs and verified
checksums for Java, Forge, Prism, and Minecraft in ignored manifests under the
repository's `.tools/` directory. Toolchains, launcher profiles, game assets,
and the `.gradle-home/` Forge cache remain local dependencies rather than source
deliverables. The separate Fabric and Forge builds allow the original 1.21.1
renderer to remain a comparison baseline.
