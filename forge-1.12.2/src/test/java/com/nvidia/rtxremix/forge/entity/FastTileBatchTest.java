/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

import java.nio.ByteBuffer;
import java.util.List;
import org.junit.Test;
import org.lwjgl.util.vector.Matrix4f;
import static org.junit.Assert.*;

public class FastTileBatchTest {
    @Test public void snapshotsOnlyEachOwnersRangeBeforeProducerReuse() {
        FastTileBatch batch = new FastTileBatch(); Object a = new Object(), b = new Object();
        ByteBuffer source = quads(2); source.position(3).mark().position(9);
        batch.begin();
        assertTrue(append(batch, a, source, 0, 4, 20));
        assertTrue(append(batch, b, source, 4, 4, 40));
        assertEquals(9, source.position()); source.reset(); assertEquals(3, source.position());
        source.putFloat(0, 999); source.putFloat(4 * 28, 999);
        prepare(batch); batch.complete(); List<CapturedEntityPart> parts = batch.finish();
        assertEquals(2, parts.size()); assertEquals(2, batch.capturedRanges());
        assertSame(a, parts.get(0).owner); assertSame(b, parts.get(1).owner);
        assertEquals(20, parts.get(0).transform[3], 0); assertEquals(40, parts.get(1).transform[3], 0);
        assertEquals(0, bytes(parts.get(0)).getFloat(0), 0); assertEquals(3, bytes(parts.get(1)).getFloat(0), 0);
        assertEquals(0xff804020, bytes(parts.get(0)).getInt(32));
        assertEquals(1, bytes(parts.get(0)).getFloat(20), 0);
    }

    @Test public void abortedOrUnfinishedDrawNeverPublishesItsRanges() {
        FastTileBatch batch = new FastTileBatch(); ByteBuffer source = quads(1);
        batch.begin(); assertTrue(append(batch, new Object(), source, 0, 4, 0)); prepare(batch); batch.complete();
        batch.begin(); assertTrue(append(batch, new Object(), source, 0, 4, 0)); prepare(batch);
        assertEquals(1, batch.finish().size()); assertEquals(1, batch.capturedRanges());
        batch.begin(); assertTrue(append(batch, new Object(), source, 0, 4, 0)); batch.complete();
        assertEquals(1, batch.finish().size());
        batch.reset(); assertEquals(0, batch.finish().size());
    }

    @Test public void malformedRangeRejectsAllOccurrencesOfOnlyThatOwner() {
        FastTileBatch batch = new FastTileBatch(); ByteBuffer source = quads(1);
        Object a = new Object(), b = new Object(); batch.begin();
        assertTrue(append(batch, a, source, 0, 4, 0)); assertTrue(append(batch, b, source, 0, 4, 0));
        assertFalse(append(batch, a, source, 4, -4, 0));
        assertFalse(append(batch, a, source, 0, 4, 0));
        prepare(batch); batch.complete(); assertEquals(1, batch.finish().size());
        assertSame(b, batch.finish().get(0).owner); assertEquals(1, batch.rejectedRanges());
        batch.reset(); batch.begin(); assertTrue(append(batch, a, source, 0, 4, 0));
    }

    @Test public void changingBatchPoseDoesNotChangeTheOwnedMeshFingerprint() {
        FastTileBatch batch = new FastTileBatch(); ByteBuffer source = quads(1); Object owner = new Object();
        batch.begin(); assertTrue(append(batch, owner, source, 0, 4, 10)); prepare(batch); batch.complete();
        long hash = batch.finish().get(0).geometryFingerprint;
        batch.reset(); batch.begin(); assertTrue(append(batch, owner, source, 0, 4, 11));
        Matrix4f view = new Matrix4f(); view.m31 = 2;
        batch.prepare(new Object(), 7, 4, 127, new Matrix4f(), view); batch.complete();
        CapturedEntityPart part = batch.finish().get(0);
        assertEquals(hash, part.geometryFingerprint); assertEquals(11, part.transform[3], 0);
        assertEquals(2, part.transform[7], 0); assertTrue(part.alphaBlend); assertEquals(127, part.alphaReference);
    }

    @Test public void partBudgetIsBoundedAndRecoversNextFrame() {
        FastTileBatch batch = new FastTileBatch(); ByteBuffer source = quads(1); batch.begin();
        for (int i = 0; i < FastTileBatch.MAX_PARTS; i++) assertTrue(append(batch, new Object(), source, 0, 4, i));
        assertFalse(append(batch, new Object(), source, 0, 4, 0)); prepare(batch); batch.complete();
        assertEquals(FastTileBatch.MAX_PARTS, batch.finish().size());
        batch.reset(); batch.begin(); assertTrue(append(batch, new Object(), source, 0, 4, 0));
        prepare(batch); batch.complete(); assertEquals(1, batch.finish().size());
    }

    private static boolean append(FastTileBatch batch, Object owner, ByteBuffer source, int first, int count, double x) {
        return batch.append(owner, source, first, count, 28, 16, 12, x, 0, 0);
    }
    private static void prepare(FastTileBatch batch) { batch.prepare(new Object(), 7, 4, 127, new Matrix4f(), new Matrix4f()); }
    private static ByteBuffer bytes(CapturedEntityPart part) {
        return part.triangles.info(part.firstVertex, part.vertexCount, 1, 1).surfaces[0].vertexBuffer;
    }
    private static ByteBuffer quads(int count) {
        ByteBuffer source = EntityMesh.direct(count * 4 * 28);
        for (int q = 0; q < count; q++) for (int i = 0; i < 4; i++) {
            source.putFloat(q * 3 + (i == 1 || i == 2 ? 1 : 0)).putFloat(i >= 2 ? 1 : 0).putFloat(0);
            source.put((byte) 128).put((byte) 64).put((byte) 32).put((byte) 255);
            source.putFloat(i == 1 || i == 2 ? 1 : 0).putFloat(i >= 2 ? 1 : 0).putInt(0);
        }
        source.flip(); return source;
    }
}
