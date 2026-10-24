/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.cloud;

import java.nio.ByteBuffer;
import org.lwjgl.util.vector.Matrix4f;

/** Decodes fixed-function primitives without lightmap coordinates or per-primitive allocations. */
final class CloudGeometry {
    private final float[] xyz = new float[9], uv = new float[6];
    private final int[] rgba = new int[3];

    int append(ByteBuffer source, int vertices, int mode, int stride, int uvOffset, int colorOffset,
            int color, Matrix4f model, Matrix4f texture, int allowed, CapturedCloudBatch target) {
        return append(source, vertices, mode, stride, uvOffset, colorOffset, color, -1, model, texture, allowed, target);
    }

    int append(ByteBuffer source, int vertices, int mode, int stride, int uvOffset, int colorOffset,
            int color, int tint, Matrix4f model, Matrix4f texture, int allowed, CapturedCloudBatch target) {
        int triangles = triangles(mode, vertices);
        if (triangles < 0 || stride < 12 || stride > 64 || uvOffset < -1 || colorOffset < -1
                || uvOffset >= 0 && (uvOffset < 12 || uvOffset + 8 > stride)
                || colorOffset >= 0 && colorOffset + 4 > stride
                || (long) vertices * stride > source.limit()) throw new IllegalArgumentException("Unsupported cloud buffer");
        int count = Math.min(triangles, Math.min(allowed, CapturedCloudBatch.MAX_VERTICES - target.count) / 3);
        target.ensure(count * 3);
        int start = target.count;
        for (int triangle = 0; triangle < count; triangle++) {
            boolean valid = true;
            for (int v = 0; v < 3; v++) {
                int offset = index(mode, triangle, v) * stride;
                float x = source.getFloat(offset), y = source.getFloat(offset + 4), z = source.getFloat(offset + 8);
                xyz[v * 3] = model.m00 * x + model.m10 * y + model.m20 * z + model.m30;
                xyz[v * 3 + 1] = model.m01 * x + model.m11 * y + model.m21 * z + model.m31;
                xyz[v * 3 + 2] = model.m02 * x + model.m12 * y + model.m22 * z + model.m32;
                float u = uvOffset < 0 ? 0 : source.getFloat(offset + uvOffset);
                float t = uvOffset < 0 ? 0 : source.getFloat(offset + uvOffset + 4);
                float q = texture.m03 * u + texture.m13 * t + texture.m33;
                uv[v * 2] = (texture.m00 * u + texture.m10 * t + texture.m30) / q;
                uv[v * 2 + 1] = (texture.m01 * u + texture.m11 * t + texture.m31) / q;
                int c = offset + colorOffset;
                rgba[v] = colorOffset < 0 ? color : (source.get(c + 3) & 255) << 24
                        | (source.get(c) & 255) << 16 | (source.get(c + 1) & 255) << 8 | source.get(c + 2) & 255;
                if (tint != -1) rgba[v] = multiply(rgba[v], tint);
                valid &= Float.isFinite(xyz[v * 3]) && Float.isFinite(xyz[v * 3 + 1]) && Float.isFinite(xyz[v * 3 + 2])
                        && Float.isFinite(uv[v * 2]) && Float.isFinite(uv[v * 2 + 1]);
            }
            if (!valid) continue;
            float ax = xyz[3] - xyz[0], ay = xyz[4] - xyz[1], az = xyz[5] - xyz[2];
            float bx = xyz[6] - xyz[0], by = xyz[7] - xyz[1], bz = xyz[8] - xyz[2];
            float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
            float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (!Float.isFinite(length) || length < 0.000001f) continue;
            nx /= length; ny /= length; nz /= length;
            target.anchor(xyz[0], xyz[1], xyz[2]);
            for (int v = 0; v < 3; v++) {
                float x = xyz[v * 3] - target.anchorX, y = xyz[v * 3 + 1] - target.anchorY, z = xyz[v * 3 + 2] - target.anchorZ;
                target.vertices.putFloat(x).putFloat(y).putFloat(z).putFloat(nx).putFloat(ny).putFloat(nz)
                        .putFloat(uv[v * 2]).putFloat(uv[v * 2 + 1]).putInt(rgba[v]);
                for (int pad = 0; pad < 7; pad++) target.vertices.putInt(0);
                target.hash = CapturedCloudBatch.mix(target.hash, Float.floatToIntBits(x));
                target.hash = CapturedCloudBatch.mix(target.hash, Float.floatToIntBits(y));
                target.hash = CapturedCloudBatch.mix(target.hash, Float.floatToIntBits(z));
                target.hash = CapturedCloudBatch.mix(target.hash, Float.floatToIntBits(uv[v * 2]));
                target.hash = CapturedCloudBatch.mix(target.hash, Float.floatToIntBits(uv[v * 2 + 1]));
                target.hash = CapturedCloudBatch.mix(target.hash, rgba[v]);
            }
            target.count += 3;
        }
        return target.count - start;
    }

    private static int multiply(int color, int tint) {
        return channel(color, tint, 24) << 24 | channel(color, tint, 16) << 16
                | channel(color, tint, 8) << 8 | channel(color, tint, 0);
    }
    private static int channel(int color, int tint, int shift) {
        return (((color >>> shift) & 255) * ((tint >>> shift) & 255) + 127) / 255;
    }

    static int triangles(int mode, int vertices) {
        if (vertices < 0) return -1;
        switch (mode) {
            case 4: return vertices % 3 == 0 ? vertices / 3 : -1;
            case 5: case 6: return vertices == 0 ? 0 : vertices >= 3 ? vertices - 2 : -1;
            case 7: return vertices % 4 == 0 ? vertices / 4 * 2 : -1;
            default: return -1;
        }
    }

    private static int index(int mode, int triangle, int vertex) {
        if (mode == 4) return triangle * 3 + vertex;
        if (mode == 6) return vertex == 0 ? 0 : triangle + vertex;
        if (mode == 5) return triangle + ((triangle & 1) != 0 && vertex < 2 ? 1 - vertex : vertex);
        int quad = triangle / 2 * 4;
        return quad + ((triangle & 1) == 0 ? vertex : vertex == 0 ? 0 : vertex + 1);
    }
}
