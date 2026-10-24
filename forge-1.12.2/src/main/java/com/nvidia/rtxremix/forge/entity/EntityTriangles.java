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
    enum Rejection { NONE, LAYOUT, GROUP_LIMIT, FRAME_LIMIT, TEXTURE_TRANSFORM, VERTEX_DATA, ZERO_NORMAL }
    private ByteBuffer vertices;
    private ByteBuffer indices;
    private final MeshInfoSurfaceTriangles surface = new MeshInfoSurfaceTriangles();
    private final MeshInfo info = new MeshInfo();
    private int count;
    private long fingerprint;
    private Rejection rejection = Rejection.NONE;
    private int rejectedVertex = -1;

    EntityTriangles() { info.surfaces = new MeshInfoSurfaceTriangles[] {surface}; }
    int count() { return count; }
    long fingerprint() { return fingerprint; }
    /** Evaluated once when a retained mesh is created, never during unchanged per-frame submission. */
    boolean opaque(int first, int vertexCount) {
        if (first < 0 || vertexCount <= 0 || vertexCount % 3 != 0 || first > count - vertexCount) return false;
        for (int vertex = first; vertex < first + vertexCount; vertex++) {
            if ((vertices.getInt(vertex * HardcodedVertex.STRIDE + 32) >>> 24) != 255) return false;
        }
        return true;
    }
    Rejection rejection() { return rejection; }
    int rejectedVertex() { return rejectedVertex; }
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
        beginAppend();
        if (!Float.isFinite(texture.m00) || !Float.isFinite(texture.m01) || !Float.isFinite(texture.m10)
                || !Float.isFinite(texture.m11) || !Float.isFinite(texture.m30) || !Float.isFinite(texture.m31)
                || texture.m03 != 0 || texture.m13 != 0 || texture.m33 != 1) return reject(Rejection.TEXTURE_TRANSFORM, -1);
        return append(source, vertexCount, EntityVboCache.STRIDE, 12, -1, 20, argb, texture);
    }

    /** Converts completed fixed-function quads or triangles into the shared bounded arena. */
    boolean appendPrimitives(ByteBuffer source, int vertexCount, int primitiveSize, int stride,
            int uvOffset, int colorOffset, int normalOffset, int argb, Matrix4f texture) {
        return appendPrimitives(source, vertexCount, primitiveSize, stride,
                uvOffset, colorOffset, normalOffset, argb, texture, 0);
    }

    /** Separates an explicitly layered item surface from its coincident base in local model units. */
    boolean appendPrimitives(ByteBuffer source, int vertexCount, int primitiveSize, int stride,
            int uvOffset, int colorOffset, int normalOffset, int argb, Matrix4f texture, float shellOffset) {
        return appendPrimitivesRange(source, 0, vertexCount, primitiveSize, stride,
                uvOffset, colorOffset, normalOffset, argb, texture, shellOffset);
    }

    /** Copies one completed owner range from a shared batch without slicing or changing its cursor. */
    boolean appendPrimitivesRange(ByteBuffer source, int firstVertex, int vertexCount, int primitiveSize, int stride,
            int uvOffset, int colorOffset, int normalOffset, int argb, Matrix4f texture) {
        return appendPrimitivesRange(source, firstVertex, vertexCount, primitiveSize, stride,
                uvOffset, colorOffset, normalOffset, argb, texture, 0);
    }

    private boolean appendPrimitivesRange(ByteBuffer source, int firstVertex, int vertexCount, int primitiveSize, int stride,
            int uvOffset, int colorOffset, int normalOffset, int argb, Matrix4f texture, float shellOffset) {
        beginAppend();
        if (primitiveSize != 3 && primitiveSize != 4 || firstVertex < 0 || firstVertex % primitiveSize != 0
                || !Float.isFinite(shellOffset)
                || vertexCount <= 0 || vertexCount % primitiveSize != 0
                || stride < 12 || uvOffset < 12 || uvOffset + 8 > stride
                || colorOffset != -1 && (colorOffset < 12 || colorOffset + 4 > stride)
                || normalOffset != -1 && (normalOffset < 12 || normalOffset + 3 > stride)
                || ((long) firstVertex + vertexCount) * stride > source.limit()) return reject(Rejection.LAYOUT, -1);
        long outputCount = (long) vertexCount / primitiveSize * (primitiveSize == 4 ? 6 : 3);
        if (outputCount > MAX_GROUP_VERTICES) return reject(Rejection.GROUP_LIMIT, -1);
        if (outputCount > MAX_FRAME_VERTICES - count) return reject(Rejection.FRAME_LIMIT, -1);
        if (vertices == null) vertices = EntityMesh.direct(MAX_FRAME_VERTICES * HardcodedVertex.STRIDE);
        int checkpoint = count * HardcodedVertex.STRIDE;
        vertices.position(checkpoint);
        int endVertex = firstVertex + vertexCount;
        for (int primitive = firstVertex; primitive < endVertex; primitive += primitiveSize) {
            int a = primitive * stride, b = a + stride, c = b + stride;
            float abx = source.getFloat(b) - source.getFloat(a), aby = source.getFloat(b + 4) - source.getFloat(a + 4);
            float abz = source.getFloat(b + 8) - source.getFloat(a + 8);
            float acx = source.getFloat(c) - source.getFloat(a), acy = source.getFloat(c + 4) - source.getFloat(a + 4);
            float acz = source.getFloat(c + 8) - source.getFloat(a + 8);
            float faceX = aby * acz - abz * acy, faceY = abz * acx - abx * acz, faceZ = abx * acy - aby * acx;
            int emitted = primitiveSize == 4 ? 6 : 3;
            for (int j = 0; j < emitted; j++) {
                int corner = j < 3 ? j : j == 3 ? 0 : j - 2;
                int base = (primitive + corner) * stride;
                float x = source.getFloat(base), y = source.getFloat(base + 4), z = source.getFloat(base + 8);
                float u = source.getFloat(base + uvOffset), v = source.getFloat(base + uvOffset + 4);
                float nx = normalOffset < 0 ? faceX : source.get(base + normalOffset);
                float ny = normalOffset < 0 ? faceY : source.get(base + normalOffset + 1);
                float nz = normalOffset < 0 ? faceZ : source.get(base + normalOffset + 2);
                float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                float transformedU = texture.m00 * u + texture.m10 * v + texture.m30;
                v = texture.m01 * u + texture.m11 * v + texture.m31;
                u = transformedU;
                if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z) || !Float.isFinite(u)
                        || !Float.isFinite(v) || !Float.isFinite(length) || length == 0
                        || texture.m03 != 0 || texture.m13 != 0 || texture.m33 != 1) {
                    vertices.position(checkpoint);
                    return reject(texture.m03 != 0 || texture.m13 != 0 || texture.m33 != 1 ? Rejection.TEXTURE_TRANSFORM
                            : length == 0 ? Rejection.ZERO_NORMAL : Rejection.VERTEX_DATA, primitive + corner);
                }
                nx /= length; ny /= length; nz /= length;
                if (shellOffset != 0) {
                    x += nx * shellOffset; y += ny * shellOffset; z += nz * shellOffset;
                    if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
                        vertices.position(checkpoint); return reject(Rejection.VERTEX_DATA, primitive + corner);
                    }
                }
                int color = base + colorOffset;
                vertices.putFloat(x).putFloat(y).putFloat(z).putFloat(nx).putFloat(ny).putFloat(nz);
                vertices.putFloat(u).putFloat(v).putInt(colorOffset < 0 ? argb : (source.get(color + 3) & 255) << 24
                        | (source.get(color) & 255) << 16 | (source.get(color + 1) & 255) << 8 | source.get(color + 2) & 255);
                for (int pad = 0; pad < 7; pad++) vertices.putInt(0);
            }
        }
        long hash = 0xcbf29ce484222325L;
        for (int offset = checkpoint; offset < vertices.position(); offset += 4) {
            hash = (hash ^ (vertices.getInt(offset) & 0xffffffffL)) * 0x100000001b3L;
        }
        count += (int) outputCount;
        fingerprint = hash;
        return true;
    }

    private boolean append(ByteBuffer source, int vertexCount, int stride, int uvOffset, int colorOffset,
            int normalOffset, int argb, Matrix4f texture) {
        beginAppend();
        if (vertexCount <= 0 || vertexCount % 3 != 0 || stride < 12
                || uvOffset < 12 || uvOffset + 8 > stride
                || colorOffset != -1 && (colorOffset < 12 || colorOffset + 4 > stride)
                || normalOffset < 12 || normalOffset + 3 > stride || (long) vertexCount * stride > source.limit()) return reject(Rejection.LAYOUT, -1);
        if (vertexCount > MAX_GROUP_VERTICES) return reject(Rejection.GROUP_LIMIT, -1);
        if (vertexCount > MAX_FRAME_VERTICES - count) return reject(Rejection.FRAME_LIMIT, -1);
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
                return reject(length == 0 ? Rejection.ZERO_NORMAL : Rejection.VERTEX_DATA, i);
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

    private void beginAppend() { rejection = Rejection.NONE; rejectedVertex = -1; }
    private boolean reject(Rejection cause, int vertex) {
        rejection = cause; rejectedVertex = vertex; return false;
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
