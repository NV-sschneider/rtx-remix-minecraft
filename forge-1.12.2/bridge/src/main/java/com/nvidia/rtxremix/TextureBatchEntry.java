/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix;

/**
 * Groups one or more {@link TexturePatch patches} targeting a single
 * texture handle. The runtime applies all patches in an entry, then
 * regenerates the mip chain scoped to the union of patch rects.
 */
public final class TextureBatchEntry {
    public long handle;
    public TexturePatch[] patches;
}
