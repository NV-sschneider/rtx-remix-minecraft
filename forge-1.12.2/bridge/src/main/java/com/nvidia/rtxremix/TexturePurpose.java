/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix;

/**
 * Describes how a texture will be used, affecting mip generation and encoding conversion.
 * Format (SRGB vs UNORM etc.) controls bit interpretation; purpose controls processing.
 */
public final class TexturePurpose {
    /** Color/albedo/emissive: standard linear mip filtering */
    public static final int COLOR = 0;

    /** Linear data (roughness, metallic, opacity): linear mip filtering */
    public static final int DATA = 1;

    /** Normal map in DirectX convention (Y+ down): converted to octahedral internally */
    public static final int NORMAL_DX = 2;

    /** Normal map in OpenGL convention (Y+ up): converted to octahedral internally */
    public static final int NORMAL_OGL = 3;

    /** Normal map already in octahedral encoding (2-channel): used as-is */
    public static final int NORMAL_OTH = 4;

    /** Height/displacement map: mip levels use max-reduction for hierarchical POM */
    public static final int HEIGHTMAP = 5;

    private TexturePurpose() {}
}
