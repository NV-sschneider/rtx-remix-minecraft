/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.effect;

import com.nvidia.rtxremix.HardcodedVertex;
import java.nio.ByteBuffer;
import org.lwjgl.util.vector.Matrix4f;

/** Copies completed quads, retaining UV and RGBA while omitting vanilla lightmap coordinates. */
final class EffectVertexDecoder {
    private final float[] positions = new float[12], uv = new float[8];
    private final int[] colors = new int[4];

    int append(ByteBuffer source, int vertices, int stride, int uvOffset, int colorOffset,
               int fallbackColor, Matrix4f transform, int allowedQuads, CapturedEffectBatch target) {
        if (vertices < 0 || (vertices & 3) != 0 || stride < 20 || uvOffset < 12
                || uvOffset + 8 > stride || colorOffset < -1 || colorOffset + 4 > stride
                || (long) vertices * stride > source.limit()) throw new IllegalArgumentException("Invalid effect vertex buffer");
        int count = Math.min(vertices / 4, Math.min(allowedQuads, CapturedEffectBatch.MAX_QUADS - target.quads));
        target.ensure(count);
        int startQuads = target.quads;
        long startHash = target.contentHash;
        try {
            for (int quad = 0; quad < count; quad++) {
                float minU = Float.POSITIVE_INFINITY, minV = Float.POSITIVE_INFINITY;
                float maxU = Float.NEGATIVE_INFINITY, maxV = Float.NEGATIVE_INFINITY;
                boolean valid = true;
                for (int v = 0; v < 4; v++) {
                    int offset = (quad * 4 + v) * stride;
                    float x = source.getFloat(offset), y = source.getFloat(offset + 4), z = source.getFloat(offset + 8);
                    positions[v * 3] = transform.m00 * x + transform.m10 * y + transform.m20 * z + transform.m30;
                    positions[v * 3 + 1] = transform.m01 * x + transform.m11 * y + transform.m21 * z + transform.m31;
                    positions[v * 3 + 2] = transform.m02 * x + transform.m12 * y + transform.m22 * z + transform.m32;
                    float u = source.getFloat(offset + uvOffset), t = source.getFloat(offset + uvOffset + 4);
                    if (!Float.isFinite(positions[v * 3]) || !Float.isFinite(positions[v * 3 + 1])
                            || !Float.isFinite(positions[v * 3 + 2]) || !Float.isFinite(u) || !Float.isFinite(t)) {
                        valid = false; break;
                    }
                    uv[v * 2] = u; uv[v * 2 + 1] = t;
                    minU = Math.min(minU, u); minV = Math.min(minV, t); maxU = Math.max(maxU, u); maxV = Math.max(maxV, t);
                    int c = offset + colorOffset;
                    colors[v] = colorOffset < 0 ? fallbackColor : (source.get(c + 3) & 255) << 24
                            | (source.get(c) & 255) << 16 | (source.get(c + 1) & 255) << 8 | source.get(c + 2) & 255;
                }
                if (!valid) {
                    // Vanilla precipitation's center column normalizes (0,0), producing
                    // one NaN quad. OpenGL drops that primitive, not the whole rain batch.
                    continue;
                }
                float ax = positions[3] - positions[0], ay = positions[4] - positions[1], az = positions[5] - positions[2];
                float bx = positions[6] - positions[0], by = positions[7] - positions[1], bz = positions[8] - positions[2];
                float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
                float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (!Float.isFinite(length) || length < 0.000001f) continue;
                nx /= length; ny /= length; nz /= length;
                int minimum = HardcodedVertex.packHalf2x16(minU, minV), maximum = HardcodedVertex.packHalf2x16(maxU, maxV);
                for (int v = 0; v < 4; v++) {
                    target.vertices.putFloat(positions[v * 3]).putFloat(positions[v * 3 + 1]).putFloat(positions[v * 3 + 2]);
                    target.vertices.putFloat(nx).putFloat(ny).putFloat(nz);
                    target.vertices.putFloat(uv[v * 2]).putFloat(uv[v * 2 + 1]).putInt(colors[v]);
                    target.vertices.putInt(minimum).putInt(maximum);
                    for (int pad = 0; pad < 5; pad++) target.vertices.putInt(0);
                    for (int axis = 0; axis < 3; axis++) target.contentHash = CapturedEffectBatch.mix(target.contentHash,
                            Float.floatToIntBits(positions[v * 3 + axis]));
                    target.contentHash = CapturedEffectBatch.mix(target.contentHash, Float.floatToIntBits(uv[v * 2]));
                    target.contentHash = CapturedEffectBatch.mix(target.contentHash, Float.floatToIntBits(uv[v * 2 + 1]));
                    target.contentHash = CapturedEffectBatch.mix(target.contentHash, colors[v]);
                }
                target.quads++;
            }
        } catch (RuntimeException error) {
            target.quads = startQuads;
            target.contentHash = startHash;
            target.vertices.position(startQuads * 4 * HardcodedVertex.STRIDE);
            throw error;
        }
        return target.quads - startQuads;
    }

}
