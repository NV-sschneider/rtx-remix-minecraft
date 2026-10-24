/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

import org.lwjgl.util.vector.Matrix4f;

/** Projects the actual hand draw through the world camera without reevaluating its pose. */
final class FirstPersonTransform {
    static final float SCALE = 0.1f;
    private FirstPersonTransform() { }

    static boolean root(Matrix4f worldInverse, Matrix4f worldProjection, Matrix4f handProjection,
            Matrix4f correction, Matrix4f destination) {
        if (!Float.isFinite(worldProjection.m00) || !Float.isFinite(worldProjection.m11)
                || worldProjection.m00 == 0 || worldProjection.m11 == 0) return false;
        float x = SCALE * handProjection.m00 / worldProjection.m00;
        float y = SCALE * handProjection.m11 / worldProjection.m11;
        if (!Float.isFinite(x) || !Float.isFinite(y) || x <= 0 || y <= 0) return false;
        correction.setIdentity();
        correction.m00 = x; correction.m11 = y; correction.m22 = SCALE;
        // Include asymmetric projection offsets (for example anaglyph or camera mods).
        correction.m20 = SCALE * (handProjection.m20 - worldProjection.m20) / worldProjection.m00;
        correction.m21 = SCALE * (handProjection.m21 - worldProjection.m21) / worldProjection.m11;
        Matrix4f.mul(worldInverse, correction, destination);
        return true;
    }
}
