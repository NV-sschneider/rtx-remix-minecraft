/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

import org.lwjgl.util.vector.Matrix4f;

/** Converts a live part pose into Remix's row-major world transform. */
final class EntityTransform {
    private EntityTransform() { }

    static void world(Matrix4f inverseView, Matrix4f partView, Matrix4f scratch,
                      double originX, double originY, double originZ, float[] result) {
        Matrix4f.mul(inverseView, partView, scratch);
        result[0] = scratch.m00; result[1] = scratch.m10; result[2] = scratch.m20;
        result[3] = (float) (scratch.m30 + originX);
        result[4] = scratch.m01; result[5] = scratch.m11; result[6] = scratch.m21;
        result[7] = (float) (scratch.m31 + originY);
        result[8] = scratch.m02; result[9] = scratch.m12; result[10] = scratch.m22;
        result[11] = (float) (scratch.m32 + originZ);
    }
}
