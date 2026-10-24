/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.cloud;

import com.nvidia.rtxremix.HardcodedVertex;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** A bounded render-thread snapshot of cloud or custom-sky triangles, consumed before the next frame. */
public final class CapturedCloudBatch {
    static final int MAX_VERTICES = 32766;
    static final long HASH_SEED = 0xcbf29ce484222325L;
    final float[] transform = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0};
    ByteBuffer vertices = direct(96 * HardcodedVertex.STRIDE);
    Object textureIdentity;
    int textureId, count;
    boolean sky;
    long blend, hash = HASH_SEED;
    double originX, originY, originZ;
    float anchorX, anchorY, anchorZ;

    void initialize(Object identity, int textureId, boolean sky, long blend, double x, double y, double z) {
        textureIdentity = identity; this.textureId = textureId; this.sky = sky; this.blend = blend;
        originX = x; originY = y; originZ = z;
    }

    boolean matches(Object identity, int id, boolean sky, long blend, double x, double y, double z) {
        return textureIdentity == identity && textureId == id && this.sky == sky && this.blend == blend
                && originX == x && originY == y && originZ == z;
    }

    void anchor(float x, float y, float z) {
        if (count != 0) return;
        anchorX = x; anchorY = y; anchorZ = z;
        transform[3] = (float) (originX + x);
        transform[7] = (float) (originY + y);
        transform[11] = (float) (originZ + z);
    }

    void ensure(int additional) {
        if (additional < 0 || count + additional > MAX_VERTICES) throw new IllegalArgumentException("Cloud vertex budget exceeded");
        int needed = (count + additional) * HardcodedVertex.STRIDE;
        if (vertices.capacity() < needed) {
            ByteBuffer replacement = direct(Math.min(MAX_VERTICES * HardcodedVertex.STRIDE,
                    Math.max(needed, vertices.capacity() * 2)));
            vertices.limit(count * HardcodedVertex.STRIDE).position(0);
            replacement.put(vertices); vertices = replacement;
        }
        vertices.limit(vertices.capacity()).position(count * HardcodedVertex.STRIDE);
    }

    void clear() {
        count = 0; hash = HASH_SEED; textureIdentity = null; textureId = 0; vertices.clear();
    }

    static long mix(long hash, long value) { return (hash ^ value) * 0x100000001b3L; }
    static ByteBuffer direct(int bytes) { return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder()); }
}
