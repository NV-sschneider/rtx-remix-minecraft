/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.effect;

import com.nvidia.rtxremix.HardcodedVertex;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Render-thread payload, valid until the next beginFrame/discard. Native handles live in EffectScene. */
public final class CapturedEffectBatch {
    static final int MAX_QUADS = 4096;
    final float[] transform = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0};
    ByteBuffer vertices = direct(64 * 4 * HardcodedVertex.STRIDE);
    Object textureIdentity;
    int textureId;
    boolean weather, blockAtlas, damage, triangles;
    long blend;
    double originX, originY, originZ;
    int quads;
    long contentHash = 0xcbf29ce484222325L;

    boolean matches(Object texture, int id, boolean weather, boolean atlas, long blend,
                    double x, double y, double z) {
        return textureIdentity == texture && textureId == id && this.weather == weather
                && blockAtlas == atlas && this.blend == blend && originX == x && originY == y && originZ == z;
    }

    void initialize(Object texture, int id, boolean weather, boolean atlas, long blend,
                    double x, double y, double z) {
        textureIdentity = texture; textureId = id; this.weather = weather; blockAtlas = atlas; this.blend = blend;
        triangles = false;
        originX = x; originY = y; originZ = z;
        transform[3] = (float) x; transform[7] = (float) y; transform[11] = (float) z;
        contentHash = mix(mix(mix(0xcbf29ce484222325L, Double.doubleToLongBits(x)),
                Double.doubleToLongBits(y)), Double.doubleToLongBits(z));
    }

    void ensure(int additional) {
        int needed = (quads + additional) * verticesPerPrimitive() * HardcodedVertex.STRIDE;
        if (quads + additional > MAX_QUADS) throw new IllegalArgumentException("Effect batch exceeds quad cap");
        if (vertices.capacity() < needed) {
            ByteBuffer replacement = direct(Math.min(MAX_QUADS * 4 * HardcodedVertex.STRIDE,
                    Math.max(needed, vertices.capacity() * 2)));
            vertices.flip(); replacement.put(vertices); vertices = replacement;
        }
        vertices.limit(vertices.capacity());
        vertices.position(quads * verticesPerPrimitive() * HardcodedVertex.STRIDE);
    }

    int verticesPerPrimitive() { return triangles ? 3 : 4; }
    int indicesPerPrimitive() { return triangles ? 3 : 6; }

    void clear() {
        textureIdentity = null; textureId = 0; quads = 0; damage = triangles = false; vertices.clear();
        contentHash = 0xcbf29ce484222325L;
    }
    static long mix(long hash, long value) { return (hash ^ value) * 0x100000001b3L; }
    static ByteBuffer direct(int bytes) { return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder()); }
}
