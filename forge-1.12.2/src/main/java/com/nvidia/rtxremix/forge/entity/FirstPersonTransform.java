/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

import org.lwjgl.util.vector.Matrix4f;

/** Separates the observed hand pose from its exact submitted world-camera anchor. */
final class FirstPersonTransform {
    static final float SCALE = 0.1f;
    private FirstPersonTransform() { }

    static boolean root(Matrix4f worldProjection, Matrix4f handProjection, Matrix4f correction) {
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
        return true;
    }

    static void capture(Matrix4f correction, Matrix4f modelView, Matrix4f scratch, float[] destination) {
        EntityTransform.world(correction, modelView, scratch, 0, 0, 0, destination);
    }

    /** World-space consumers such as lights use the same pose without changing the retained view model. */
    static void world(Matrix4f inverse, float[] local, double x, double y, double z, float[] destination) {
        for (int column = 0; column < 3; column++) {
            destination[column] = inverse.m00 * local[column] + inverse.m10 * local[column + 4] + inverse.m20 * local[column + 8];
            destination[column + 4] = inverse.m01 * local[column] + inverse.m11 * local[column + 4] + inverse.m21 * local[column + 8];
            destination[column + 8] = inverse.m02 * local[column] + inverse.m12 * local[column + 4] + inverse.m22 * local[column + 8];
        }
        destination[3] = (float) (x + inverse.m30 + inverse.m00 * local[3] + inverse.m10 * local[7] + inverse.m20 * local[11]);
        destination[7] = (float) (y + inverse.m31 + inverse.m01 * local[3] + inverse.m11 * local[7] + inverse.m21 * local[11]);
        destination[11] = (float) (z + inverse.m32 + inverse.m02 * local[3] + inverse.m12 * local[7] + inverse.m22 * local[11]);
    }
}
