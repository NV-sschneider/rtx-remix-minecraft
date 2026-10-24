/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.terrain;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public class BetterFoliageCaptureHooksTest {
    private static final float[] QUAD = {0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0};
    private static final float[] UV = {0, 0, 1, 0, 1, 1, 0, 1};
    private static final int[] COLORS = {0xff2468ac, 0xff2468ac, 0xff2468ac, 0xff2468ac};

    @Test public void onlyOwnedBufferOnOwnerThreadUsesUnlitRgbAndOriginalMaterialPostprocess() throws Exception {
        BlockCaptureBuffer owned = new BlockCaptureBuffer(), other = new BlockCaptureBuffer();
        BetterFoliageCaptureHooks.begin(owned);
        try {
            assertTrue(BetterFoliageCaptureHooks.ambient(false, owned));
            float biome = 0x68 / 256f;
            assertEquals(biome, BetterFoliageCaptureHooks.lighting(0.3f, owned, false) * biome, 0);
            assertEquals(0.3f, BetterFoliageCaptureHooks.lighting(0.3f, owned, true), 0);
            assertEquals(0.3f, BetterFoliageCaptureHooks.lighting(0.3f, other, false), 0);
            AtomicReference<Throwable> error = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try {
                    assertFalse(BetterFoliageCaptureHooks.ambient(false, owned));
                    assertEquals(0.3f, BetterFoliageCaptureHooks.lighting(0.3f, owned, false), 0);
                } catch (Throwable failure) { error.set(failure); }
            });
            worker.start(); worker.join(); if (error.get() != null) throw new AssertionError(error.get());
            try { BetterFoliageCaptureHooks.begin(other); fail("Expected nested scope rejection"); }
            catch (IllegalStateException expected) { assertTrue(BetterFoliageCaptureHooks.active(owned)); }
        } finally { BetterFoliageCaptureHooks.end(); }
        assertFalse(BetterFoliageCaptureHooks.ambient(false, owned));
        assertEquals(0.3f, BetterFoliageCaptureHooks.lighting(0.3f, owned, false), 0);
    }

    @Test public void capacityReservationsAndEmissionCannotGrowBufferAndItCanBeReusedAfterFailure() {
        BlockCaptureBuffer buffer = new BlockCaptureBuffer(); int capacity = buffer.getByteBuffer().capacity();
        buffer.beginBlock(160, 64, -160);
        try {
            try { buffer.reserveQuads(Integer.MAX_VALUE); fail("Expected reservation rejection"); }
            catch (IllegalStateException expected) { assertEquals(capacity, buffer.getByteBuffer().capacity()); }
            for (int i = 0; i < BlockMeshDecoder.MAX_VERTICES / 4; i++) quad(buffer);
            try { quad(buffer); fail("Expected vertex cap"); }
            catch (IllegalStateException expected) { assertEquals(capacity, buffer.getByteBuffer().capacity()); }
        } finally { buffer.finishDrawing(); buffer.releaseBlock(); }
        buffer.beginBlock(160, 64, -160); quad(buffer); buffer.finishDrawing();
        MeshPacking packed = new MeshPacking(); assertEquals(0, buffer.append(packed)); buffer.releaseBlock();
        assertEquals(4, packed.vertexCount()); assertEquals(0, packed.vertices().getFloat(0), 0);
        assertEquals(1, packed.vertices().getFloat(MeshPacking.STRIDE), 0);
        assertEquals(0xff2468ac, packed.vertices().getInt(32));
    }

    private static void quad(BlockCaptureBuffer buffer) { buffer.quad(QUAD, UV, COLORS, 0, 0, 1, 0, 0, 1, 1); }
}
