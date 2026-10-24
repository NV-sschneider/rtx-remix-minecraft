/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

import com.nvidia.rtxremix.CameraInfoParameterizedEXT;

/** Matches the C API's float view-matrix boundary without putting fine poses in world-space floats. */
final class ViewModelCamera {
    private final double[] axes = new double[9], view = new double[9];
    private final float[] rotation = {1, 0, 0, 0, 1, 0, 0, 0, 1};
    private final float[] anchor = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0};

    void prepare(CameraInfoParameterizedEXT camera) {
        axes[0] = camera.right.x; axes[1] = camera.up.x; axes[2] = camera.forward.x;
        axes[3] = camera.right.y; axes[4] = camera.up.y; axes[5] = camera.forward.y;
        axes[6] = camera.right.z; axes[7] = camera.up.z; axes[8] = camera.forward.z;
        for (int column = 0; column < 3; column++) {
            double length = Math.sqrt(axes[column] * axes[column] + axes[column + 3] * axes[column + 3] + axes[column + 6] * axes[column + 6]);
            for (int row = 0; row < 3; row++) axes[row * 3 + column] /= length;
        }
        for (int row = 0; row < 3; row++) {
            rotation[row * 3] = (float) axes[row * 3]; rotation[row * 3 + 1] = (float) axes[row * 3 + 1];
            rotation[row * 3 + 2] = (float) -axes[row * 3 + 2];
        }
        inverse(axes, view);
        float x = (float) -(view[0] * camera.position.x + view[1] * camera.position.y + view[2] * camera.position.z);
        float y = (float) -(view[3] * camera.position.x + view[4] * camera.position.y + view[5] * camera.position.z);
        float z = (float) -(view[6] * camera.position.x + view[7] * camera.position.y + view[8] * camera.position.z);
        // ParameterizedEXT normalizes/inverts in double, then passes a float world-to-view
        // matrix to the renderer. Its inverse, not the input position, defines ray origins.
        for (int at = 0; at < view.length; at++) view[at] = (float) view[at];
        inverse(view, axes);
        anchor[3] = (float) -(axes[0] * x + axes[1] * y + axes[2] * z);
        anchor[7] = (float) -(axes[3] * x + axes[4] * y + axes[5] * z);
        anchor[11] = (float) -(axes[6] * x + axes[7] * y + axes[8] * z);
    }

    float[] anchor() { return anchor; }

    /** Rotation also stays in local geometry: a rotated native inverse would quantize its translation. */
    void pose(float[] local, float[] destination) {
        for (int row = 0; row < 3; row++) for (int column = 0; column < 4; column++) {
            destination[row * 4 + column] = rotation[row * 3] * local[column]
                    + rotation[row * 3 + 1] * local[column + 4] + rotation[row * 3 + 2] * local[column + 8];
        }
    }

    private static void inverse(double[] m, double[] result) {
        double a = m[0], b = m[1], c = m[2], d = m[3], e = m[4], f = m[5], g = m[6], h = m[7], i = m[8];
        result[0] = e * i - f * h; result[1] = c * h - b * i; result[2] = b * f - c * e;
        result[3] = f * g - d * i; result[4] = a * i - c * g; result[5] = c * d - a * f;
        result[6] = d * h - e * g; result[7] = b * g - a * h; result[8] = a * e - b * d;
        double determinant = a * result[0] + b * result[3] + c * result[6];
        for (int at = 0; at < result.length; at++) result[at] /= determinant;
    }
}
