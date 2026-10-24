/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.terrain;

import java.nio.ByteBuffer;

/** Decodes private BLOCK-format capture with per-quad sprite bounds and optional model normals. */
final class BlockMeshDecoder {
    static final int MAX_VERTICES = 4096;
    static final int METADATA_STRIDE = 7;
    private final float[] positions = new float[12], uv = new float[8];
    private final int[] colors = new int[4];
    private boolean complete;
    boolean complete() { return complete; }

    void append(ByteBuffer source, int vertices, float[] metadata, MeshPacking target) {
        complete = true;
        if (vertices < 0 || vertices > MAX_VERTICES || (vertices & 3) != 0
                || (long) vertices * 28 > source.limit() || metadata.length < vertices / 4 * METADATA_STRIDE)
            throw new IllegalArgumentException("Invalid bounded block capture");
        int checkpoint = target.vertexCount();
        try {
            for (int first = 0; first < vertices; first += 4) {
                int meta = first / 4 * METADATA_STRIDE;
                for (int v = 0; v < 4; v++) {
                    int base = (first + v) * 28;
                    for (int axis = 0; axis < 3; axis++) positions[v * 3 + axis] = finite(source.getFloat(base + axis * 4));
                    uv[v * 2] = finite(source.getFloat(base + 16)); uv[v * 2 + 1] = finite(source.getFloat(base + 20));
                    colors[v] = MeshPacking.tintRgba(source.get(base + 12) & 255, source.get(base + 13) & 255,
                            source.get(base + 14) & 255, source.get(base + 15) & 255, 0xffffff);
                }
                float minU = finite(metadata[meta]), minV = finite(metadata[meta + 1]);
                float maxU = finite(metadata[meta + 2]), maxV = finite(metadata[meta + 3]);
                if (minU > maxU || minV > maxV) throw new IllegalArgumentException("Invalid block sprite bounds");
                float nx = metadata[meta + 4], ny = metadata[meta + 5], nz = metadata[meta + 6];
                if (Float.isNaN(nx)) {
                    float ax = positions[3] - positions[0], ay = positions[4] - positions[1], az = positions[5] - positions[2];
                    float bx = positions[6] - positions[0], by = positions[7] - positions[1], bz = positions[8] - positions[2];
                    nx = ay * bz - az * by; ny = az * bx - ax * bz; nz = ax * by - ay * bx;
                    float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                    if (length < 0.000001f) { complete = false; continue; }
                    nx /= length; ny /= length; nz /= length;
                }
                target.quad(positions, uv, colors, finite(nx), finite(ny), finite(nz), minU, minV, maxU, maxV);
            }
        } catch (RuntimeException failure) { target.truncate(checkpoint); throw failure; }
    }

    private static float finite(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) throw new IllegalArgumentException("Non-finite block capture data");
        return value;
    }
}
