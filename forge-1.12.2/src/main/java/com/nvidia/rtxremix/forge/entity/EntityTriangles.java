/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

import com.nvidia.rtxremix.HardcodedVertex;
import com.nvidia.rtxremix.MeshInfo;
import com.nvidia.rtxremix.MeshInfoSurfaceTriangles;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.util.vector.Matrix4f;

/** One bounded reusable frame arena for completed, independently emitted OBJ triangles. */
final class EntityTriangles {
    static final int MAX_GROUP_VERTICES = 32766;
    static final int MAX_FRAME_VERTICES = MAX_GROUP_VERTICES * 4;
    private ByteBuffer vertices;
    private ByteBuffer indices;
    private final MeshInfoSurfaceTriangles surface = new MeshInfoSurfaceTriangles();
    private final MeshInfo info = new MeshInfo();
    private int count;
    private long fingerprint;

    EntityTriangles() { info.surfaces = new MeshInfoSurfaceTriangles[] {surface}; }
    int count() { return count; }
    long fingerprint() { return fingerprint; }
    void reset() { count = 0; }
    void truncate(int checkpoint) {
        if (checkpoint < 0 || checkpoint > count || checkpoint % 3 != 0) throw new IllegalArgumentException("Invalid OBJ checkpoint");
        count = checkpoint;
    }

    /** Absolute reads preserve the producer's position, limit and mark. Rejection is atomic. */
    boolean append(ByteBuffer source, int vertexCount, int stride, int uvOffset, int colorOffset, int normalOffset) {
        return append(source, vertexCount, stride, uvOffset, colorOffset, normalOffset, 0xffffffff, null);
    }

    boolean appendVbo(ByteBuffer source, int vertexCount, int argb, Matrix4f texture) {
        if (!Float.isFinite(texture.m00) || !Float.isFinite(texture.m01) || !Float.isFinite(texture.m10)
                || !Float.isFinite(texture.m11) || !Float.isFinite(texture.m30) || !Float.isFinite(texture.m31)
                || texture.m03 != 0 || texture.m13 != 0 || texture.m33 != 1) return false;
        return append(source, vertexCount, EntityVboCache.STRIDE, 12, -1, 20, argb, texture);
    }

    private boolean append(ByteBuffer source, int vertexCount, int stride, int uvOffset, int colorOffset,
            int normalOffset, int argb, Matrix4f texture) {
        if (vertexCount <= 0 || vertexCount % 3 != 0 || vertexCount > MAX_GROUP_VERTICES
                || vertexCount > MAX_FRAME_VERTICES - count || stride < 12
                || uvOffset < 12 || uvOffset + 8 > stride
                || colorOffset != -1 && (colorOffset < 12 || colorOffset + 4 > stride)
                || normalOffset < 12 || normalOffset + 3 > stride || (long) vertexCount * stride > source.limit()) return false;
        if (vertices == null) vertices = EntityMesh.direct(MAX_FRAME_VERTICES * HardcodedVertex.STRIDE);
        vertices.position(count * HardcodedVertex.STRIDE);
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < vertexCount; i++) {
            int base = i * stride;
            float x = source.getFloat(base), y = source.getFloat(base + 4), z = source.getFloat(base + 8);
            float u = source.getFloat(base + uvOffset), v = source.getFloat(base + uvOffset + 4);
            if (texture != null) {
                float transformedU = texture.m00 * u + texture.m10 * v + texture.m30;
                v = texture.m01 * u + texture.m11 * v + texture.m31;
                u = transformedU;
            }
            float nx = source.get(base + normalOffset), ny = source.get(base + normalOffset + 1);
            float nz = source.get(base + normalOffset + 2);
            float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)
                    || !Float.isFinite(u) || !Float.isFinite(v) || length == 0) {
                vertices.position(count * HardcodedVertex.STRIDE);
                return false;
            }
            vertices.putFloat(x).putFloat(y).putFloat(z);
            vertices.putFloat(nx / length).putFloat(ny / length).putFloat(nz / length);
            vertices.putFloat(u).putFloat(v);
            int color = base + colorOffset;
            vertices.putInt(colorOffset == -1 ? argb : (source.get(color + 3) & 255) << 24
                    | (source.get(color) & 255) << 16 | (source.get(color + 1) & 255) << 8 | (source.get(color + 2) & 255));
            for (int pad = 0; pad < 7; pad++) vertices.putInt(0);
            for (int offset = vertices.position() - HardcodedVertex.STRIDE; offset < vertices.position(); offset += 4) {
                hash = (hash ^ (vertices.getInt(offset) & 0xffffffffL)) * 0x100000001b3L;
            }
        }
        count += vertexCount;
        fingerprint = hash;
        return true;
    }

    MeshInfo info(int first, int vertexCount, long material, long hash) {
        if (first < 0 || vertexCount <= 0 || vertexCount > MAX_GROUP_VERTICES || vertexCount % 3 != 0
                || first > count - vertexCount) throw new IllegalArgumentException("Invalid OBJ triangle range");
        ByteBuffer view = vertices.duplicate().order(ByteOrder.nativeOrder());
        view.position(first * HardcodedVertex.STRIDE).limit((first + vertexCount) * HardcodedVertex.STRIDE);
        surface.vertexBuffer = view.slice().order(ByteOrder.nativeOrder());
        if (indices == null) {
            indices = EntityMesh.direct(MAX_GROUP_VERTICES * Integer.BYTES);
            for (int base = 0; base < MAX_GROUP_VERTICES; base += 3) {
                indices.putInt(base).putInt(base + 2).putInt(base + 1);
            }
        }
        indices.position(0).limit(vertexCount * Integer.BYTES);
        surface.indexBuffer = indices;
        surface.vertexCount = surface.indexCount = vertexCount;
        surface.material = material;
        info.hash = hash;
        return info;
    }

    static long bytes(int vertexCount) { return (long) vertexCount * (HardcodedVertex.STRIDE + Integer.BYTES); }
}
