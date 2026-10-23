/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

import java.nio.ByteBuffer;
import org.junit.Test;
import static org.junit.Assert.*;

public class EntityVboCacheTest {
    @Test public void snapshotsOnlyUploadedRangeWithoutMutatingProducerState() {
        EntityVboCache cache = new EntityVboCache(); Object mesh = new Object();
        ByteBuffer source = EntityMesh.direct(96);
        source.putFloat(24, 7.5f).position(20).mark().position(24).limit(96);
        assertTrue(cache.put(mesh, 3, source));
        assertEquals(24, source.position()); assertEquals(96, source.limit());
        source.reset(); assertEquals(20, source.position());
        source.putFloat(24, 99);
        EntityVboCache.Snapshot captured = cache.get(mesh, 3, 3);
        assertEquals(7.5f, captured.vertices.getFloat(0), 0);
        assertEquals(72, captured.vertices.limit()); assertEquals(72, cache.bytes());
    }
    @Test public void identityAndBufferRevisionPreventRecycledHandleReuse() {
        EntityVboCache cache = new EntityVboCache();
        Object first = new EqualKey(), second = new EqualKey();
        ByteBuffer bytes = EntityMesh.direct(72);
        assertTrue(cache.put(first, 3, bytes));
        assertNull(cache.get(second, 3, 3));
        assertNull(cache.get(first, 4, 3)); assertEquals(0, cache.bytes());
        assertTrue(cache.put(first, 3, bytes));
        cache.remove(first); assertNull(cache.get(first, 3, 3));
    }
    @Test public void aNewUploadReplacesBytesAndMalformedUploadInvalidatesOldSnapshot() {
        EntityVboCache cache = new EntityVboCache(); Object mesh = new Object();
        ByteBuffer bytes = EntityMesh.direct(72);
        bytes.putFloat(0, 1); assertTrue(cache.put(mesh, 3, bytes));
        bytes.putFloat(0, 2); assertTrue(cache.put(mesh, 3, bytes));
        assertEquals(2, cache.get(mesh, 3, 3).vertices.getFloat(0), 0);
        bytes.limit(71); assertFalse(cache.put(mesh, 3, bytes));
        assertNull(cache.get(mesh, 3, 3)); assertEquals(0, cache.bytes());
    }
    @Test public void entryAndByteCapsEvictOldestUseAndClearReleasesAllReferences() {
        EntityVboCache cache = new EntityVboCache(); Object oldest = new Object(), retained = new Object();
        ByteBuffer bytes = EntityMesh.direct(72);
        cache.put(oldest, 1, bytes); cache.put(retained, 2, bytes);
        for (int i = 2; i < EntityVboCache.MAX_ENTRIES; i++) cache.put(new Object(), i + 1, bytes);
        cache.get(retained, 2, 3);
        cache.put(new Object(), 999, bytes);
        assertNull(cache.get(oldest, 1, 3)); assertNotNull(cache.get(retained, 2, 3));
        assertEquals(EntityVboCache.MAX_ENTRIES, cache.size());
        cache.clear();
        ByteBuffer large = EntityMesh.direct(EntityTriangles.MAX_GROUP_VERTICES * EntityVboCache.STRIDE);
        for (int i = 0; i < 30; i++) cache.put(new Object(), i + 1, large);
        assertTrue(cache.bytes() <= EntityVboCache.MAX_BYTES);
        assertTrue(cache.size() < 30);
        cache.clear(); assertEquals(0, cache.bytes()); assertEquals(0, cache.size());
    }
    @Test public void fallbackReadbackHasOneCallPerFrameAndAByteLimit() {
        EntityVboCache cache = new EntityVboCache();
        assertFalse(cache.reserveReadback(-1)); assertFalse(cache.reserveReadback(4));
        assertFalse(cache.reserveReadback(EntityTriangles.MAX_GROUP_VERTICES + 3));
        assertTrue(cache.reserveReadback(EntityTriangles.MAX_GROUP_VERTICES));
        assertFalse(cache.reserveReadback(3));
        cache.beginFrame(); assertTrue(cache.reserveReadback(3));
    }
    private static final class EqualKey {
        @Override public boolean equals(Object other) { return other instanceof EqualKey; }
        @Override public int hashCode() { return 1; }
    }
}
