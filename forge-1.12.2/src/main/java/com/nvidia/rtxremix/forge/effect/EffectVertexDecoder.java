/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.effect;

import com.nvidia.rtxremix.HardcodedVertex;
import java.nio.ByteBuffer;
import org.lwjgl.util.vector.Matrix4f;

/** Copies completed fixed-function primitives, retaining UV/RGBA and omitting lightmaps. */
final class EffectVertexDecoder {
    private final float[] positions = new float[12], uv = new float[8];
    private final int[] colors = new int[4];

    int append(ByteBuffer source, int vertices, int stride, int uvOffset, int colorOffset,
               int fallbackColor, Matrix4f transform, int allowedQuads, CapturedEffectBatch target) {
        return append(source, vertices, 7, stride, uvOffset, colorOffset, fallbackColor, transform, allowedQuads, target);
    }

    int append(ByteBuffer source, int vertices, int mode, int stride, int uvOffset, int colorOffset,
               int fallbackColor, Matrix4f transform, int allowedQuads, CapturedEffectBatch target) {
        int primitives = primitiveCount(mode, vertices), corners = mode == 7 ? 4 : 3;
        if (primitives < 0 || target.triangles != (mode != 7) || stride < 20 || uvOffset < 12
                || uvOffset + 8 > stride || colorOffset < -1 || colorOffset + 4 > stride
                || (long) vertices * stride > source.limit()) throw new IllegalArgumentException("Invalid effect vertex buffer");
        int count = Math.min(primitives, Math.min(Math.max(0, allowedQuads), CapturedEffectBatch.MAX_QUADS - target.quads));
        target.ensure(count);
        int startQuads = target.quads;
        long startHash = target.contentHash;
        try {
            for (int quad = 0; quad < count; quad++) {
                float minU = Float.POSITIVE_INFINITY, minV = Float.POSITIVE_INFINITY;
                float maxU = Float.NEGATIVE_INFINITY, maxV = Float.NEGATIVE_INFINITY;
                boolean valid = true;
                for (int v = 0; v < corners; v++) {
                    int offset = vertex(mode, quad, v) * stride;
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
                for (int v = 0; v < corners; v++) {
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
            target.vertices.position(startQuads * corners * HardcodedVertex.STRIDE);
            throw error;
        }
        return target.quads - startQuads;
    }

    static int primitiveCount(int mode, int vertices) {
        if (vertices < 0) return -1;
        if (mode == 7) return vertices % 4 == 0 ? vertices / 4 : -1;
        if (mode == 4) return vertices % 3 == 0 ? vertices / 3 : -1;
        return mode == 5 || mode == 6 ? Math.max(0, vertices - 2) : -1;
    }

    private static int vertex(int mode, int primitive, int corner) {
        if (mode == 7) return primitive * 4 + corner;
        if (mode == 4) return primitive * 3 + corner;
        if (mode == 6) return corner == 0 ? 0 : primitive + corner;
        // Triangle strips alternate winding. Emit independent triangles in the
        // same orientation before the scene applies its existing index reversal.
        return primitive + (corner < 2 && (primitive & 1) != 0 ? 1 - corner : corner);
    }

}
