/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.terrain;

/** Receives borrowed, unlit quad arrays; implementations consume them before returning. */
interface BlockQuadSink {
    void quad(float[] positions, float[] uv, int[] colors, float nx, float ny, float nz,
              float minU, float minV, float maxU, float maxV);
}
