# Third-party notices

The Java facade under `src/main/java/com/nvidia/rtxremix/` and
`native/remixbridge.cpp` originate from NVIDIA RTX Remix for Minecraft,
commit `f2c6fdaa763f45676f1576e63f8e054bf4ef16af`:
https://github.com/NVIDIAGameWorks/rtx-remix-minecraft

Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
These files use the Apache License, Version 2.0, reproduced in `LICENSE`.
`CommandLineRedaction.java`, `Transform.java`, and `native/remixbridge.cpp`
identify their local changes.

The RTX Remix C API header `native/include/remix_c.h` is copyright (c)
2023-2026 NVIDIA CORPORATION and uses the MIT license reproduced in its header.
It matches the runtime pinned by the source checkout and must remain synchronized
with that runtime and the JNI implementation.

This bridge does not redistribute Minecraft, Forge, Java, Visual Studio, or
the RTX Remix runtime. A distribution including the runtime must also include
the runtime's notices and license texts under upstream `packaging/licenses/`.
