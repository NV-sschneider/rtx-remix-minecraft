/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix;

import java.util.Arrays;

/**
 * 3x4 transformation matrix.
 * Layout: matrix[row * 4 + col] where row is 0-2 and col is 0-3.
 */
public class Transform {
    public float[] matrix = new float[12];

    public Transform() {
        setIdentity();
    }

    public void setIdentity() {
        Arrays.fill(matrix, 0.0f);
        matrix[0] = 1.0f;
        matrix[5] = 1.0f;
        matrix[10] = 1.0f;
    }

    /**
     * @param row Row index (0-2)
     * @param col Column index (0-3)
     * @param value The value to set
     */
    public void set(int row, int col, float value) {
        if (row < 0 || row > 2 || col < 0 || col > 3) {
            throw new IndexOutOfBoundsException("Row must be 0-2, col must be 0-3");
        }
        matrix[row * 4 + col] = value;
    }

    /**
     * @param row Row index (0-2)
     * @param col Column index (0-3)
     * @return The value at the specified position
     */
    public float get(int row, int col) {
        if (row < 0 || row > 2 || col < 0 || col > 3) {
            throw new IndexOutOfBoundsException("Row must be 0-2, col must be 0-3");
        }
        return matrix[row * 4 + col];
    }

    public void setTranslation(float x, float y, float z) {
        matrix[3] = x;
        matrix[7] = y;
        matrix[11] = z;
    }

    public static Transform identity() {
        return new Transform();
    }

    /**
     * Builds a 3x4 TRS matrix: translate, then rotate, then uniform scale.
     * Row-major layout: [R*s | t] for the supplied unit quaternion (x, y, z, w).
     */
    public static Transform fromTRS(float tx, float ty, float tz,
                                    float x, float y, float z, float w, float s) {
        Transform t = new Transform();
        float x2 = x + x, y2 = y + y, z2 = z + z;
        float xx = x * x2, xy = x * y2, xz = x * z2;
        float yy = y * y2, yz = y * z2, zz = z * z2;
        float wx = w * x2, wy = w * y2, wz = w * z2;

        t.matrix[0]  = (1f - (yy + zz)) * s;
        t.matrix[1]  = (xy - wz) * s;
        t.matrix[2]  = (xz + wy) * s;
        t.matrix[3]  = tx;
        t.matrix[4]  = (xy + wz) * s;
        t.matrix[5]  = (1f - (xx + zz)) * s;
        t.matrix[6]  = (yz - wx) * s;
        t.matrix[7]  = ty;
        t.matrix[8]  = (xz - wy) * s;
        t.matrix[9]  = (yz + wx) * s;
        t.matrix[10] = (1f - (xx + yy)) * s;
        t.matrix[11] = tz;
        return t;
    }
}
