/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

import java.nio.ByteBuffer;
import java.util.IdentityHashMap;
import java.util.Map;

/** Bounded snapshots of exact Lycanites POSITION_TEX_NORMAL uploads; never owns GL buffers. */
final class EntityVboCache {
    static final int STRIDE = 24;
    static final int MAX_ENTRIES = 512;
    static final long MAX_BYTES = 16L * 1024 * 1024;
    static final int MAX_READBACK_BYTES = 1024 * 1024;
    private final IdentityHashMap<Object, Snapshot> entries = new IdentityHashMap<Object, Snapshot>();
    private long bytes, clock;
    private boolean readback;

    static final class Snapshot {
        final int bufferId;
        final ByteBuffer vertices;
        long used;
        Snapshot(int bufferId, ByteBuffer vertices) { this.bufferId = bufferId; this.vertices = vertices; }
    }

    void beginFrame() { readback = false; }
    int size() { return entries.size(); }
    long bytes() { return bytes; }
    void clear() { entries.clear(); bytes = 0; readback = false; }
    void remove(Object mesh) {
        Snapshot old = entries.remove(mesh);
        if (old != null) bytes -= old.vertices.capacity();
    }
    Snapshot get(Object mesh, int bufferId, int vertices) {
        Snapshot snapshot = entries.get(mesh);
        if (snapshot == null) return null;
        if (snapshot.bufferId != bufferId || (long) vertices * STRIDE > snapshot.vertices.limit()) {
            remove(mesh);
            return null;
        }
        snapshot.used = ++clock;
        return snapshot;
    }
    boolean put(Object mesh, int bufferId, ByteBuffer uploaded) {
        remove(mesh);
        int length = uploaded.remaining();
        if (mesh == null || bufferId == 0 || length == 0 || length % (STRIDE * 3) != 0
                || length / STRIDE > EntityTriangles.MAX_GROUP_VERTICES) return false;
        while (entries.size() >= MAX_ENTRIES || bytes + length > MAX_BYTES) {
            Object oldest = null;
            long first = Long.MAX_VALUE;
            for (Map.Entry<Object, Snapshot> entry : entries.entrySet()) {
                if (entry.getValue().used < first) { first = entry.getValue().used; oldest = entry.getKey(); }
            }
            if (oldest == null) return false;
            remove(oldest);
        }
        ByteBuffer copy = EntityMesh.direct(length);
        copy.put(uploaded.duplicate()).flip();
        Snapshot snapshot = new Snapshot(bufferId, copy);
        snapshot.used = ++clock;
        entries.put(mesh, snapshot);
        bytes += length;
        return true;
    }
    boolean reserveReadback(int vertices) {
        long length = (long) vertices * STRIDE;
        if (readback || vertices <= 0 || vertices % 3 != 0 || vertices > EntityTriangles.MAX_GROUP_VERTICES
                || length > MAX_READBACK_BYTES) return false;
        readback = true;
        return true;
    }
}
