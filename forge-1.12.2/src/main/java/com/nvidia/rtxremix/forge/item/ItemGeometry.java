/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.item;

import com.nvidia.rtxremix.HardcodedVertex;
import com.nvidia.rtxremix.MeshInfo;
import com.nvidia.rtxremix.MeshInfoSurfaceTriangles;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Bounded frame scratch for the already-tinted vertices emitted by Forge's item pipeline. */
final class ItemGeometry {
    static final int MAX_VERTICES = 131072;
    private ByteBuffer vertices = direct(256 * HardcodedVertex.STRIDE);
    private ByteBuffer indices = direct(384 * Integer.BYTES);
    private final MeshInfoSurfaceTriangles surface = new MeshInfoSurfaceTriangles();
    private final MeshInfo info = new MeshInfo();
    private int count;
    private boolean lastOpaque, lastBounded;

    ItemGeometry() { info.surfaces = new MeshInfoSurfaceTriangles[] {surface}; }
    int count() { return count; }
    void truncate(int checkpoint) {
        if (checkpoint < 0 || checkpoint > count || (checkpoint & 3) != 0) throw new IllegalArgumentException("Invalid item checkpoint");
        count = checkpoint;
        vertices.position(count * HardcodedVertex.STRIDE);
    }

    /** Reads four completed ITEM vertices without changing the producer's buffer state. */
    boolean append(ByteBuffer source, int first, int stride, int uvOffset, int colorOffset, int normalOffset,
                   float minU, float minV, float maxU, float maxV) {
        if (count + 4 > MAX_VERTICES || first < 0 || stride < 12 || uvOffset < 12 || uvOffset + 8 > stride
                || colorOffset < 12 || colorOffset + 4 > stride || normalOffset < 12 || normalOffset + 3 > stride
                || ((long) first + 4) * stride > source.limit()
                || !Float.isFinite(minU) || !Float.isFinite(minV) || !Float.isFinite(maxU) || !Float.isFinite(maxV)
                || minU > maxU || minV > maxV) return false;
        boolean opaque = true, bounded = minU < maxU && minV < maxV;
        for (int v = 0; v < 4; v++) {
            int base = (first + v) * stride;
            for (int axis = 0; axis < 3; axis++) if (!Float.isFinite(source.getFloat(base + axis * 4))) return false;
            float u = source.getFloat(base + uvOffset), t = source.getFloat(base + uvOffset + 4);
            if (!Float.isFinite(u) || !Float.isFinite(t)) return false;
            bounded &= u >= minU && u <= maxU && t >= minV && t <= maxV;
            opaque &= (source.get(base + colorOffset + 3) & 255) == 255;
        }
        ensure((count + 4) * HardcodedVertex.STRIDE);
        int minimum = HardcodedVertex.packHalf2x16(minU, minV);
        int maximum = HardcodedVertex.packHalf2x16(maxU, maxV);
        for (int v = 0; v < 4; v++) {
            int base = (first + v) * stride;
            for (int axis = 0; axis < 3; axis++) vertices.putFloat(source.getFloat(base + axis * 4));
            float nx = source.get(base + normalOffset), ny = source.get(base + normalOffset + 1), nz = source.get(base + normalOffset + 2);
            float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (length == 0) {
                // Forge's slow conversion may omit normals; the completed face still determines them.
                int a = first * stride, b = (first + 1) * stride, c = (first + 2) * stride;
                float ax = source.getFloat(b) - source.getFloat(a), ay = source.getFloat(b + 4) - source.getFloat(a + 4);
                float az = source.getFloat(b + 8) - source.getFloat(a + 8);
                float bx = source.getFloat(c) - source.getFloat(a), by = source.getFloat(c + 4) - source.getFloat(a + 4);
                float bz = source.getFloat(c + 8) - source.getFloat(a + 8);
                nx = ay * bz - az * by; ny = az * bx - ax * bz; nz = ax * by - ay * bx;
                length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (!Float.isFinite(length) || length == 0) { vertices.position(count * HardcodedVertex.STRIDE); return false; }
            }
            vertices.putFloat(nx / length).putFloat(ny / length).putFloat(nz / length);
            vertices.putFloat(source.getFloat(base + uvOffset)).putFloat(source.getFloat(base + uvOffset + 4));
            int color = base + colorOffset;
            vertices.putInt((source.get(color + 3) & 255) << 24 | (source.get(color) & 255) << 16
                    | (source.get(color + 1) & 255) << 8 | (source.get(color + 2) & 255));
            vertices.putInt(minimum).putInt(maximum);
            for (int pad = 0; pad < 5; pad++) vertices.putInt(0);
        }
        count += 4;
        lastOpaque = opaque; lastBounded = bounded;
        return true;
    }

    boolean lastQuadOpaque() { return lastOpaque; }
    boolean lastQuadBounded() { return lastBounded; }

    long fingerprint(int first, int verticesCount) {
        long hash = 0xcbf29ce484222325L;
        for (int i = first * HardcodedVertex.STRIDE, end = (first + verticesCount) * HardcodedVertex.STRIDE; i < end; i += 4) {
            hash = (hash ^ (vertices.getInt(i) & 0xffffffffL)) * 0x100000001b3L;
        }
        return hash;
    }

    MeshInfo info(int first, int verticesCount, long material, long hash) {
        return info(first, verticesCount, material, hash, true);
    }

    MeshInfo info(int first, int verticesCount, long material, long hash, boolean spriteBounds) {
        ByteBuffer page = vertices.duplicate().order(ByteOrder.nativeOrder());
        page.position(first * HardcodedVertex.STRIDE).limit((first + verticesCount) * HardcodedVertex.STRIDE);
        surface.vertexBuffer = page.slice().order(ByteOrder.nativeOrder());
        int indexBytes = verticesCount / 4 * 6 * Integer.BYTES;
        if (indices.capacity() < indexBytes) indices = direct(indexBytes);
        indices.clear();
        for (int base = 0; base < verticesCount; base += 4) {
            indices.putInt(base).putInt(base + 2).putInt(base + 1);
            indices.putInt(base).putInt(base + 3).putInt(base + 2);
        }
        indices.flip();
        surface.indexBuffer = indices;
        surface.vertexCount = verticesCount;
        surface.indexCount = verticesCount / 4 * 6;
        surface.material = material;
        surface.flags = spriteBounds ? MeshInfoSurfaceTriangles.TEXCOORD_BOUNDS : 0;
        info.hash = hash;
        return info;
    }

    static long bytes(int verticesCount) { return (long) verticesCount * HardcodedVertex.STRIDE + (long) verticesCount / 4 * 6 * Integer.BYTES; }
    static ByteBuffer direct(int bytes) { return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder()); }
    private void ensure(int bytes) {
        if (vertices.capacity() >= bytes) return;
        ByteBuffer larger = direct(Math.min(MAX_VERTICES * HardcodedVertex.STRIDE, Math.max(bytes, vertices.capacity() * 2)));
        vertices.flip();
        larger.put(vertices);
        vertices = larger;
    }
}
