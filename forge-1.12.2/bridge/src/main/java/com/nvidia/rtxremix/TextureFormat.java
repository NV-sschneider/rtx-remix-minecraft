/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix;

/**
 * Texture formats for texture creation APIs
 */
public final class TextureFormat {
    /** RGBA 8-bit per channel, linear color space */
    public static final int RGBA8_UNORM = 0;
    
    /** RGBA 8-bit per channel, sRGB color space (gamma corrected) */
    public static final int RGBA8_SRGB = 1;
    
    /** BGRA 8-bit per channel, linear color space */
    public static final int BGRA8_UNORM = 2;
    
    /** BGRA 8-bit per channel, sRGB color space (gamma corrected) */
    public static final int BGRA8_SRGB = 3;

    /** Single channel 8-bit (roughness, metallic, height) */
    public static final int R8_UNORM = 4;

    /** Two channel 8-bit (normal XY) */
    public static final int RG8_UNORM = 5;

    /** RGBA 16-bit half-float (HDR) */
    public static final int RGBA16_SFLOAT = 6;

    /** RGBA 32-bit float (HDR full precision) */
    public static final int RGBA32_SFLOAT = 7;

    /** Single-channel 32-bit float */
    public static final int R32_SFLOAT = 8;

    private TextureFormat() {}
}
