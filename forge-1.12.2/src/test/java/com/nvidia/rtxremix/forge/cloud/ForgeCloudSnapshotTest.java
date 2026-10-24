/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.cloud;

import java.nio.ByteBuffer;
import org.junit.Test;
import org.lwjgl.util.vector.Matrix4f;
import static org.junit.Assert.*;

public class ForgeCloudSnapshotTest {
    @Test public void compiledVerticesSurviveSharedBufferReuseAndApplyCurrentMotionAndTint() {
        Object owner = new Object(); ForgeCloudSnapshot snapshot = new ForgeCloudSnapshot();
        ByteBuffer shared = CloudGeometryTest.quad();
        snapshot.capture(owner, shared, 4, 24, 12, 20);
        shared.putFloat(0, 2000); shared.limit(0);
        CapturedCloudBatch first = replay(snapshot, owner, new Matrix4f(), new Matrix4f(), -1);
        Matrix4f model = new Matrix4f(); model.m30 = -0.03f;
        CapturedCloudBatch moving = replay(snapshot, owner, model, new Matrix4f(), -1);
        assertEquals(6, moving.count);
        assertEquals(first.transform[3] - 0.03f, moving.transform[3], 0.00001f);
        Matrix4f uv = new Matrix4f(); uv.m30 = 1f / 256;
        CapturedCloudBatch tinted = replay(snapshot, owner, model, uv, 0xff804020);
        assertEquals(0xcc10100c, tinted.vertices.getInt(32));
        assertEquals(1f / 256, tinted.vertices.getFloat(24), 0);
        assertNotEquals(first.hash, tinted.hash);
        assertEquals(0, snapshot.data(owner).getFloat(0), 0);
    }

    @Test public void replacementDisposalAndWrongOwnerCannotReplayStaleCompiledGeometry() throws Exception {
        Object first = new Object(), second = new Object(); ForgeCloudSnapshot snapshot = new ForgeCloudSnapshot();
        snapshot.capture(first, CloudGeometryTest.quad(), 4, 24, 12, 20);
        assertNull(snapshot.data(second));
        final boolean[] otherThread = {false};
        Thread thread = new Thread(() -> otherThread[0] = snapshot.data(first) == null); thread.start(); thread.join();
        assertTrue(otherThread[0]);
        snapshot.capture(second, CloudGeometryTest.quad(), 4, 24, 12, 20);
        assertNull(snapshot.data(first)); snapshot.dispose(first); assertNotNull(snapshot.data(second));
        snapshot.dispose(second); assertNull(snapshot.data(second)); assertEquals(0, snapshot.count);
    }

    @Test public void retentionIsBoundedAndOnlyCompleteQuadsSurvive() {
        Object owner = new Object(); ForgeCloudSnapshot snapshot = new ForgeCloudSnapshot();
        int count = ForgeCloudSnapshot.MAX_VERTICES + 4;
        ByteBuffer source = ByteBuffer.allocate(count * 24); source.limit(0);
        snapshot.capture(owner, source, count, 24, 12, 20);
        assertEquals(ForgeCloudSnapshot.MAX_VERTICES, snapshot.count); assertEquals(count, snapshot.originalCount);
        assertEquals(0, snapshot.count % 4); assertTrue(snapshot.count / 4 * 6 <= CloudCaptureHooks.MAX_VERTICES);
        assertEquals(snapshot.count * 24, snapshot.data(owner).capacity()); assertEquals(0, source.limit());
        snapshot.capture(owner, ByteBuffer.allocate(4), 4, 24, 12, 20);
        assertNull(snapshot.data(owner)); assertEquals(0, snapshot.count);
    }

    private static CapturedCloudBatch replay(ForgeCloudSnapshot source, Object owner, Matrix4f model, Matrix4f uv, int tint) {
        CapturedCloudBatch batch = CloudGeometryTest.batch();
        new CloudGeometry().append(source.data(owner), source.count, 7, source.stride, source.uvOffset,
                source.colorOffset, -1, tint, model, uv, 32766, batch);
        return batch;
    }
}
