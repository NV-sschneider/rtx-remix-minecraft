/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix;

/**
 * Dome light extension: an environment map that illuminates the scene from
 * all directions (image-based lighting).
 */
public class LightInfoDomeEXT {
    public float[] transform = new float[12];
    public long colorTexture;
    public float opacity = 1.0f;
    public int blendType = MaterialInfoOpaqueEXT.BLEND_TYPE_ALPHA;

    public LightInfoDomeEXT() {
        // Default: swap Y<->Z for equirectangular Y-up → Remix coordinates
        //   [1, 0, 0, 0]
        //   [0, 0, 1, 0]
        //   [0, 1, 0, 0]
        transform[0]  = 1; transform[1]  = 0; transform[2]  = 0; transform[3]  = 0;
        transform[4]  = 0; transform[5]  = 0; transform[6]  = 1; transform[7]  = 0;
        transform[8]  = 0; transform[9]  = 1; transform[10] = 0; transform[11] = 0;
    }

    public void setColorTexture(long textureHandle) {
        this.colorTexture = textureHandle;
    }

    public void setOpacity(float opacity) {
        this.opacity = opacity;
    }

    public void setBlendType(int blendType) {
        this.blendType = blendType;
    }
}
