/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix;

/**
 * 3D float vector.
 */
public class Float3D {
    public float x;
    public float y;
    public float z;

    public Float3D() {}

    public Float3D(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }
}
