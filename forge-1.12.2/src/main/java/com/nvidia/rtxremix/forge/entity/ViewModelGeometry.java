/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

import com.nvidia.rtxremix.HardcodedVertex;
import com.nvidia.rtxremix.InstanceCategoryBit;
import com.nvidia.rtxremix.MeshInfo;
import com.nvidia.rtxremix.MeshInfoSurfaceTriangles;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Bounded upload scratch that keeps fine hand motion inside a camera-anchored BLAS. */
public final class ViewModelGeometry {
    private static final int MAX_VERTICES = 131072;
    private ByteBuffer vertices = ByteBuffer.allocateDirect(256 * HardcodedVertex.STRIDE).order(ByteOrder.nativeOrder());
    private final MeshInfoSurfaceTriangles surface = new MeshInfoSurfaceTriangles();
    private final MeshInfo result = MeshInfo.builder().surfaces(surface).build();

    public static boolean applies(int category) { return (category & InstanceCategoryBit.VIEW_MODEL) != 0; }

    /** Submit the returned direct buffers synchronously, before the next prepare call. */
    public MeshInfo prepare(MeshInfo source, float[] pose) {
        if (source.surfaces.length != 1) throw new IllegalArgumentException("View-model capture requires one surface");
        MeshInfoSurfaceTriangles input = source.surfaces[0];
        if (input.vertexCount <= 0 || input.vertexCount > MAX_VERTICES || input.skinning_hasvalue) {
            throw new IllegalArgumentException("Unsupported view-model vertex range");
        }
        int bytes = input.vertexCount * HardcodedVertex.STRIDE;
        if (vertices.capacity() < bytes) vertices = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
        vertices.clear();
        int start = input.vertexBuffer.position();
        for (int at = 0; at < bytes; at += Integer.BYTES) vertices.putInt(input.vertexBuffer.getInt(start + at));
        vertices.flip();
        // Inverse transpose, up to its common determinant; normals are normalized below.
        double a = pose[0], b = pose[1], c = pose[2], d = pose[4], e = pose[5], f = pose[6];
        double g = pose[8], h = pose[9], i = pose[10];
        double aa = e * i - f * h, ab = f * g - d * i, ac = d * h - e * g;
        double ba = c * h - b * i, bb = a * i - c * g, bc = b * g - a * h;
        double ca = b * f - c * e, cb = c * d - a * f, cc = a * e - b * d;
        double determinant = a * aa + b * ab + c * ac;
        double sign = determinant < 0 ? -1 : 1;
        for (int at = 0; at < bytes; at += HardcodedVertex.STRIDE) {
            float x = vertices.getFloat(at), y = vertices.getFloat(at + 4), z = vertices.getFloat(at + 8);
            vertices.putFloat(at, pose[0] * x + pose[1] * y + pose[2] * z + pose[3]);
            vertices.putFloat(at + 4, pose[4] * x + pose[5] * y + pose[6] * z + pose[7]);
            vertices.putFloat(at + 8, pose[8] * x + pose[9] * y + pose[10] * z + pose[11]);
            float nx = vertices.getFloat(at + 12), ny = vertices.getFloat(at + 16), nz = vertices.getFloat(at + 20);
            double tx = aa * nx + ab * ny + ac * nz, ty = ba * nx + bb * ny + bc * nz, tz = ca * nx + cb * ny + cc * nz;
            double length = Math.sqrt(tx * tx + ty * ty + tz * tz);
            if (length != 0) {
                vertices.putFloat(at + 12, (float) (sign * tx / length));
                vertices.putFloat(at + 16, (float) (sign * ty / length));
                vertices.putFloat(at + 20, (float) (sign * tz / length));
            }
        }
        result.hash = source.hash;
        surface.vertexBuffer = vertices; surface.vertexCount = input.vertexCount;
        surface.indexBuffer = input.indexBuffer; surface.indexCount = input.indexCount;
        surface.material = input.material; surface.flags = input.flags;
        return result;
    }
}
