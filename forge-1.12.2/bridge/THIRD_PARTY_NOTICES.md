# Third-party notices

The Java facade, JNI implementation and C API header are compiled from the
repository-root `rtxremix-java/` sources, shared with NVIDIA RTX Remix for Minecraft:
https://github.com/NVIDIAGameWorks/rtx-remix-minecraft

Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
The Java facade, JNI implementation and Forge interop adapter use the Apache
License, Version 2.0, reproduced in `LICENSE`.

The RTX Remix C API header `rtxremix-java/native/include/remix_c.h` is copyright (c)
2023-2026 NVIDIA CORPORATION and uses the MIT license reproduced in its header.
It matches the runtime pinned by the source checkout and must remain synchronized
with that runtime and the JNI implementation.

This bridge does not redistribute Minecraft, Forge, Java, Visual Studio, or
the RTX Remix runtime. A distribution including the runtime must also include
the runtime's notices and license texts under upstream `packaging/licenses/`.
