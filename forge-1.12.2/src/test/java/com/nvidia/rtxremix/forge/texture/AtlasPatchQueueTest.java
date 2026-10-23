/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.texture;

import com.nvidia.rtxremix.TexturePatch;
import java.nio.IntBuffer;
import org.junit.Test;
import static org.junit.Assert.*;

public class AtlasPatchQueueTest {
    @Test public void copiesArgbToRgbaWithoutMovingSourceBufferOrFlippingRows() {
        AtlasPatchQueue queue = new AtlasPatchQueue(64, 32);
        IntBuffer input = IntBuffer.wrap(new int[] {0, 0x12345678, 0x90abcdef, 0xff010203, 0x04050607, 0});
        input.position(1).limit(5);
        queue.capture(7, 11, 2, 2, input);
        input.put(1, 0);
        TexturePatch patch = queue.batch()[0];
        assertTrue(patch.pixelData.isDirect());
        assertEquals(7, patch.dstX);
        assertEquals(11, patch.dstY);
        assertEquals(8, patch.rowPitch);
        assertEquals(16, patch.pixelData.remaining());
        byte[] rgba = new byte[16];
        patch.pixelData.duplicate().get(rgba);
        assertArrayEquals(new byte[] {0x34, 0x56, 0x78, 0x12, (byte) 0xab, (byte) 0xcd, (byte) 0xef, (byte) 0x90,
                1, 2, 3, (byte) 0xff, 5, 6, 7, 4}, rgba);
        assertEquals(1, input.position());
        assertEquals(5, input.limit());
    }

    @Test public void coalescesTheLatestFrameAndKeepsOverlappingWritesCoherent() {
        AtlasPatchQueue queue = new AtlasPatchQueue(16, 16);
        queue.capture(0, 0, 2, 1, pixels(2, 0xff110000));
        queue.capture(1, 0, 2, 1, pixels(2, 0xff220000));
        queue.capture(0, 0, 2, 1, pixels(2, 0xff330000));
        TexturePatch[] patches = queue.batch();
        assertEquals(2, patches.length);
        assertEquals(0, patches[0].dstX);
        assertEquals(1, patches[1].dstX);
        assertEquals(0x33, patches[0].pixelData.get(4) & 255);
        assertEquals(0x33, patches[1].pixelData.get(0) & 255);
        assertEquals(0x22, patches[1].pixelData.get(4) & 255);
        assertEquals(0, queue.updates(0, 0, 2, 1));
        assertEquals(0, queue.hash(0, 0, 2, 1));
        queue.acknowledge(2);
        assertEquals(1, queue.updates(0, 0, 2, 1));
        long oldHash = queue.hash(0, 0, 2, 1);
        queue.capture(0, 0, 2, 1, pixels(2, 0xff440000));
        assertEquals(oldHash, queue.hash(0, 0, 2, 1));
        queue.acknowledge(1);
        assertNotEquals(oldHash, queue.hash(0, 0, 2, 1));
        assertNull(queue.batch());
        assertEquals(16, queue.retainedBytes());
    }

    @Test public void boundsEachFlushAndKeepsUnsentRectanglesForLaterFrames() {
        AtlasPatchQueue queue = new AtlasPatchQueue(256, 256);
        for (int x = 0; x < 100; x++) queue.capture(x, 0, 1, 1, pixels(1, x));
        assertEquals(64, queue.batch().length);
        queue.acknowledge(64);
        assertEquals(36, queue.batch().length);
        queue.acknowledge(36);
        assertEquals(0, queue.pendingCount());
        assertEquals(400, queue.retainedBytes());
        queue.clear();
        assertEquals(0, queue.retainedBytes());
        assertEquals(0, queue.updates(1, 0, 1, 1));
    }

    @Test public void continuousAnimationDoesNotStarveTheEndOfTheBacklog() {
        AtlasPatchQueue queue = new AtlasPatchQueue(256, 1);
        for (int tick = 0; tick < 4; tick++) {
            for (int x = 0; x < 256; x++) queue.capture(x, 0, 1, 1, pixels(1, tick));
            assertEquals(tick * 64, queue.batch()[0].dstX);
            queue.acknowledge(64);
        }
        for (int x = 0; x < 256; x++) assertEquals(1, queue.updates(x, 0, 1, 1));
    }

    @Test public void enforcesPixelByteBudgetWithoutStarvingTheNextLargePatch() {
        AtlasPatchQueue queue = new AtlasPatchQueue(2048, 1024);
        IntBuffer pixels = pixels(1024 * 1024, 0xff123456);
        queue.capture(0, 0, 1024, 1024, pixels);
        queue.capture(1024, 0, 1024, 1024, pixels);
        assertEquals(1, queue.batch().length);
        queue.acknowledge(1);
        assertEquals(1024, queue.batch()[0].dstX);
        queue.acknowledge(1);
        assertEquals(0, queue.pendingCount());
    }

    @Test public void rejectsInvalidOversizedAndOverflowingRectanglesWithoutAllocating() {
        AtlasPatchQueue queue = new AtlasPatchQueue(4096, 4096);
        IntBuffer data = pixels(4, 0);
        queue.capture(-1, 0, 1, 1, data);
        queue.capture(4096, 0, 1, 1, data);
        queue.capture(0, 0, 3, 2, data);
        queue.capture(0, 0, 0, 1, data);
        queue.capture(0, 0, 2048, 2048, data);
        queue.capture(0, 0, Integer.MAX_VALUE, Integer.MAX_VALUE, data);
        assertEquals(6, queue.droppedCount());
        assertEquals(0, queue.retainedBytes());
        assertNull(queue.batch());
    }

    @Test public void boundsRetainedRectanglesButKeepsExistingSpritesUpdatable() {
        AtlasPatchQueue queue = new AtlasPatchQueue(8192, 1);
        IntBuffer pixel = pixels(1, 0);
        for (int x = 0; x <= AtlasPatchQueue.MAX_RECTANGLES; x++) queue.capture(x, 0, 1, 1, pixel);
        assertEquals(AtlasPatchQueue.MAX_RECTANGLES, queue.pendingCount());
        assertEquals(1, queue.droppedCount());
        queue.capture(0, 0, 1, 1, pixels(1, 0xffff0000));
        assertEquals(1, queue.droppedCount());
        assertEquals(AtlasPatchQueue.MAX_RECTANGLES * 4, queue.retainedBytes());
        assertEquals(0, queue.batch()[0].dstX);
    }

    @Test public void rectangleKeysKeepAllCoordinateAndDimensionBitsDistinct() {
        assertNotEquals(AtlasPatchQueue.rectangleKey(1, 2, 16, 32), AtlasPatchQueue.rectangleKey(2, 1, 16, 32));
        assertNotEquals(AtlasPatchQueue.rectangleKey(1, 2, 16, 32), AtlasPatchQueue.rectangleKey(1, 2, 32, 16));
        assertNotEquals(AtlasPatchQueue.rectangleKey(65535, 0, 1, 1), AtlasPatchQueue.rectangleKey(32767, 0, 1, 1));
    }

    private static IntBuffer pixels(int count, int value) {
        int[] result = new int[count];
        java.util.Arrays.fill(result, value);
        return IntBuffer.wrap(result);
    }
}
