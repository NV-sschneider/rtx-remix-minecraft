/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.item;

import org.lwjgl.util.vector.Matrix4f;

/** Removes the camera while retaining every item-renderer transform, including ItemPhysic's pose. */
final class ItemTransform {
    private ItemTransform() { }
    static void world(Matrix4f inverse, Matrix4f model, Matrix4f result, double x, double y, double z, float[] out) {
        Matrix4f.mul(inverse, model, result);
        out[0] = result.m00; out[1] = result.m10; out[2] = result.m20; out[3] = (float) (result.m30 + x);
        out[4] = result.m01; out[5] = result.m11; out[6] = result.m21; out[7] = (float) (result.m31 + y);
        out[8] = result.m02; out[9] = result.m12; out[10] = result.m22; out[11] = (float) (result.m32 + z);
    }
}
