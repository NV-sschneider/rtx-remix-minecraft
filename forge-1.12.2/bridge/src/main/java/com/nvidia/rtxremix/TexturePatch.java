/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix;

import java.nio.ByteBuffer;

/**
 * Describes a single rectangular mip-0 patch update for an existing texture.
 */
public class TexturePatch {
    public int dstX;
    public int dstY;
    public int width;
    public int height;
    public ByteBuffer pixelData;
    public int rowPitch;
    public int format = TextureFormat.RGBA8_UNORM;
}
