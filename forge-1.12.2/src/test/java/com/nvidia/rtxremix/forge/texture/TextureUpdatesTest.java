/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.texture;

import com.nvidia.rtxremix.TextureBatchEntry;
import com.nvidia.rtxremix.TextureFormat;
import com.nvidia.rtxremix.TexturePatch;
import java.nio.IntBuffer;
import org.junit.Test;
import static org.junit.Assert.*;

public class TextureUpdatesTest {
    @Test public void opaqueProofSurvivesOpaquePatchesButNeverPromotesUnknownOrTranslucentPixels() {
        try (TextureUpdates updates = new TextureUpdates(batch -> { })) {
            TextureUpdates.Binding proven = updates.subscribe(1, 11, 2, 2, true);
            TextureUpdates.Binding unknown = updates.subscribe(2, 22, 1, 1);
            assertTrue(proven.opaque()); assertFalse(unknown.opaque());
            updates.begin(1, 1, 0, 1, 1);
            upload(updates, 1, 0, 1, 1, IntBuffer.wrap(new int[] {0xff123456})); updates.end(true);
            assertFalse(proven.opaque()); // Pending pixels cannot authorize an opaque native draw.
            updates.flush(); assertTrue(proven.opaque());
            updates.begin(1, 0, 1, 1, 1);
            upload(updates, 0, 1, 1, 1, IntBuffer.wrap(new int[] {0xfe123456})); updates.end(true);
            assertFalse(proven.opaque()); updates.flush(); assertFalse(proven.opaque()); assertTrue(proven.current());
            updates.begin(1, 0, 1, 1, 1);
            upload(updates, 0, 1, 1, 1, IntBuffer.wrap(new int[] {0xff123456})); updates.end(true); updates.flush();
            assertFalse(proven.opaque()); // Partial repair cannot prove the complete image again.
            updates.begin(2, 0, 0, 1, 1);
            upload(updates, 0, 0, 1, 1, IntBuffer.wrap(new int[] {-1})); updates.end(true); updates.flush();
            assertFalse(unknown.opaque());
        }
    }

    @Test public void unknownFailedDeletedAndReleasedRevisionsRevokeTheInitialOpaqueProof() {
        try (TextureUpdates updates = new TextureUpdates(batch -> { })) {
            TextureUpdates.Binding wrongFormat = updates.subscribe(1, 11, 1, 1, true);
            TextureUpdates.Binding failed = updates.subscribe(2, 22, 1, 1, true);
            TextureUpdates.Binding deleted = updates.subscribe(3, 33, 1, 1, true);
            TextureUpdates.Binding released = updates.subscribe(4, 44, 1, 1, true);
            updates.begin(1, 0, 0, 1, 1);
            updates.uploaded(3553, 0, 0, 0, 1, 1, 6408, 5121, IntBuffer.wrap(new int[] {-1})); updates.end(true);
            updates.begin(2, 0, 0, 1, 1);
            upload(updates, 0, 0, 1, 1, IntBuffer.wrap(new int[] {-1})); updates.end(false);
            updates.deleted(3); updates.unsubscribe(released);
            assertFalse(wrongFormat.opaque()); assertFalse(failed.opaque());
            assertFalse(deleted.opaque()); assertFalse(released.opaque());
            for (int id = 1; id <= 2; id++) {
                updates.begin(id, 0, 0, 1, 1); upload(updates, 0, 0, 1, 1, IntBuffer.wrap(new int[] {-1})); updates.end(true);
            }
            updates.flush(); assertTrue(wrongFormat.current()); assertTrue(failed.current());
            assertFalse(wrongFormat.opaque()); assertFalse(failed.opaque());
        }
    }

    @Test public void copiesActualUploadPositionAndCoalescesOverlapsBeforeMinecraftReusesMemory() {
        final int[] actual = new int[4];
        try (TextureUpdates updates = new TextureUpdates(batch -> {
            for (TextureBatchEntry entry : batch.entries) for (TexturePatch patch : entry.patches) {
                assertEquals(77, entry.handle); assertTrue(patch.pixelData.isDirect()); assertEquals(TextureFormat.RGBA8_SRGB, patch.format);
                for (int y = 0; y < patch.height; y++) for (int x = 0; x < patch.width; x++) {
                    int offset = y * patch.rowPitch + x * 4;
                    actual[(patch.dstY + y) * 2 + patch.dstX + x] = (patch.pixelData.get(offset + 3) & 255) << 24
                            | (patch.pixelData.get(offset) & 255) << 16 | (patch.pixelData.get(offset + 1) & 255) << 8 | patch.pixelData.get(offset + 2) & 255;
                }
            }
        })) {
            TextureUpdates.Binding binding = updates.subscribe(10, 77, 2, 2);
            IntBuffer source = IntBuffer.wrap(new int[] {123, 0x11223344, 0x55667788, 0x99aabbcc, 0xddeeff00, 456}); source.position(1); source.limit(5);
            updates.begin(10, 0, 0, 2, 2); upload(updates, 0, 0, 2, 2, source); updates.end(true);
            assertEquals(1, source.position()); assertEquals(5, source.limit()); source.put(1, 0);
            updates.begin(10, 1, 0, 1, 2); upload(updates, 1, 0, 1, 2, IntBuffer.wrap(new int[] {0xff010203, 0xff040506})); updates.end(true);
            assertFalse(binding.current()); updates.flush(); assertTrue(binding.current());
            assertArrayEquals(new int[] {0x11223344, 0xff010203, 0x99aabbcc, 0xff040506}, actual);
            assertEquals(1, updates.updateCount()); assertEquals(2, updates.patchCount());
            updates.flush(); assertEquals(1, updates.updateCount());
        }
    }

    @Test public void dirtyLargeUploadWaitsForTheBoundedNativeBatchAndRetriesAfterFailure() {
        final boolean[] fail = {true}; final long[] maximum = {0};
        try (TextureUpdates updates = new TextureUpdates(batch -> {
            long bytes = 0; for (TextureBatchEntry entry : batch.entries) for (TexturePatch patch : entry.patches) bytes += (long) patch.width * patch.height * 4;
            maximum[0] = Math.max(maximum[0], bytes);
            if (fail[0]) throw new IllegalStateException("native update");
        })) {
            TextureUpdates.Binding binding = updates.subscribe(1, 11, 1024, 2048);
            updates.begin(1, 0, 0, 1024, 2048); upload(updates, 0, 0, 1024, 2048, IntBuffer.allocate(1024 * 2048)); updates.end(true);
            assertFalse(binding.current()); assertThrows(IllegalStateException.class, updates::flush); assertFalse(binding.current());
            fail[0] = false; updates.flush(); assertFalse(binding.current()); updates.flush(); assertTrue(binding.current());
            assertEquals(TextureUpdates.BYTES_PER_FRAME, maximum[0]); assertEquals(2, updates.updateCount());
        }
    }

    @Test public void invalidOrIncompleteRevisionsStayHiddenUntilACompleteLaterUpload() {
        try (TextureUpdates updates = new TextureUpdates(batch -> { })) {
            TextureUpdates.Binding binding = updates.subscribe(1, 11, 2, 2);
            updates.begin(1, 0, 0, 2, 2); upload(updates, 0, 0, 2, 2, IntBuffer.allocate(3)); updates.end(true);
            assertFalse(binding.current()); assertEquals(1, updates.droppedCount()); assertEquals(0, updates.retainedBytes());
            updates.begin(1, 0, 0, 1, 1); upload(updates, 0, 0, 1, 1, IntBuffer.allocate(1)); updates.end(true); updates.flush(); assertFalse(binding.current());
            updates.begin(1, 0, 0, 2, 2); updates.end(true); assertFalse(binding.current());
            updates.begin(1, 0, 0, 2, 2); upload(updates, 0, 0, 2, 2, IntBuffer.allocate(4)); updates.end(false); updates.flush(); assertFalse(binding.current());
            updates.begin(1, 0, 0, 2, 2); upload(updates, 0, 0, 2, 2, IntBuffer.allocate(4)); updates.end(true); updates.flush(); assertTrue(binding.current());
        }
    }

    @Test public void deletionCannotReuseOldNativeTextureAndUnsubscribeDropsPendingBytes() {
        try (TextureUpdates updates = new TextureUpdates(batch -> { })) {
            TextureUpdates.Binding old = updates.subscribe(1, 11, 2, 2);
            updates.begin(1, 0, 0, 2, 2); upload(updates, 0, 0, 2, 2, IntBuffer.allocate(4)); updates.end(true);
            updates.deleted(1); assertTrue(old.deleted()); assertFalse(old.current()); assertEquals(0, updates.retainedBytes());
            TextureUpdates.Binding replacement = updates.subscribe(1, 22, 2, 2);
            updates.begin(1, 0, 0, 2, 2); upload(updates, 0, 0, 2, 2, IntBuffer.allocate(4)); updates.end(true); updates.flush();
            assertTrue(replacement.current()); assertFalse(old.current()); updates.unsubscribe(old); assertTrue(replacement.current());
            updates.unsubscribe(replacement); assertEquals(0, updates.retainedBytes()); assertEquals(0, updates.dirtyCount());
        }
    }

    @Test public void unknownMipsWrongThreadsAndNestedScopesCannotCorruptAnotherTexture() throws Exception {
        try (TextureUpdates updates = new TextureUpdates(batch -> { })) {
            TextureUpdates.Binding first = updates.subscribe(1, 11, 1, 1), second = updates.subscribe(2, 22, 1, 1);
            updates.begin(1, 0, 0, 1, 1); updates.begin(2, 0, 0, 1, 1);
            upload(updates, 0, 0, 1, 1, IntBuffer.allocate(1)); updates.end(true);
            updates.uploaded(3553, 1, 0, 0, 1, 1, 32993, 33639, IntBuffer.allocate(1)); updates.end(true);
            assertTrue(first.current()); assertFalse(second.current()); updates.flush(); assertTrue(second.current());
            Thread worker = new Thread(() -> { TextureRevisionHooks.begin(1, 1, 1); TextureRevisionHooks.deleted(1); TextureRevisionHooks.end(false); });
            worker.start(); worker.join(); assertTrue(first.current());
            updates.begin(1, 0, 0, 1, 1); updates.uploaded(3553, 0, 0, 0, 1, 1, 6408, 5121, IntBuffer.allocate(1)); updates.end(true);
            assertFalse(first.current()); assertTrue(second.current());
        }
    }

    @Test public void rectangleBackpressureRecoversFromRepeatingSubrectsWithoutAFullTextureUpload() {
        final int[] observed = new int[TextureUpdates.MAX_RECTANGLES + 1];
        java.util.Arrays.fill(observed, -1);
        try (TextureUpdates updates = new TextureUpdates(batch -> {
            int count = 0;
            for (TextureBatchEntry entry : batch.entries) for (TexturePatch patch : entry.patches) {
                count++;
                for (int x = 0; x < patch.width; x++) {
                    int at = x * 4;
                    observed[patch.dstX + x] = (patch.pixelData.get(at + 3) & 255) << 24 | (patch.pixelData.get(at) & 255) << 16
                            | (patch.pixelData.get(at + 1) & 255) << 8 | patch.pixelData.get(at + 2) & 255;
                }
            }
            assertTrue(count <= TextureUpdates.MAX_BATCH_PATCHES);
        })) {
            TextureUpdates.Binding first = updates.subscribe(1, 11, TextureUpdates.MAX_RECTANGLES + 1, 1), second = updates.subscribe(2, 22, 1, 1);
            for (int x = 0; x <= TextureUpdates.MAX_RECTANGLES; x++) {
                updates.begin(1, x, 0, 1, 1); upload(updates, x, 0, 1, 1, IntBuffer.allocate(1)); updates.end(true);
            }
            assertFalse(first.current()); assertTrue(second.current());
            assertEquals(TextureUpdates.MAX_RECTANGLES * 4L, updates.retainedBytes()); assertEquals(1, updates.droppedCount());
            updates.flush(); assertFalse(first.current()); // Native progress alone does not repair omitted pixels.
            updates.begin(1, TextureUpdates.MAX_RECTANGLES, 0, 1, 1);
            upload(updates, TextureUpdates.MAX_RECTANGLES, 0, 1, 1, IntBuffer.wrap(new int[] {0xff123456})); updates.end(true);
            for (int i = 0; i <= TextureUpdates.MAX_RECTANGLES / TextureUpdates.MAX_BATCH_PATCHES; i++) updates.flush();
            assertTrue(first.current()); assertTrue(second.current());
            assertTrue(updates.retainedBytes() <= TextureUpdates.MAX_RETAINED_BYTES);
            assertEquals(0, observed[0]); assertEquals(0, observed[TextureUpdates.MAX_RECTANGLES - 1]);
            assertEquals(0xff123456, observed[TextureUpdates.MAX_RECTANGLES]);
        }
    }

    @Test public void repeatedFullUploadsCannotCauseUnboundedCopyWorkWithinAFrame() {
        try (TextureUpdates updates = new TextureUpdates(batch -> { })) {
            TextureUpdates.Binding binding = updates.subscribe(1, 11, 1024, 1024);
            IntBuffer pixels = IntBuffer.allocate(1024 * 1024);
            for (int i = 0; i <= TextureUpdates.MAX_CAPTURE_BYTES_PER_FRAME / TextureUpdates.MAX_PATCH_BYTES; i++) {
                updates.begin(1, 0, 0, 1024, 1024); upload(updates, 0, 0, 1024, 1024, pixels); updates.end(true);
            }
            assertFalse(binding.current()); assertTrue(updates.retainedBytes() <= TextureUpdates.MAX_RETAINED_BYTES);
            updates.flush();
            assertFalse(binding.current()); // The last successfully queued pixels are still stale after a rejected upload.
            updates.begin(1, 0, 0, 1024, 1024); upload(updates, 0, 0, 1024, 1024, pixels); updates.end(true);
            updates.flush(); assertTrue(binding.current());
        }
    }

    @Test public void completedRectanglesAreReusedAndPartialRepairsNeverExposeMissingPixels() {
        try (TextureUpdates updates = new TextureUpdates(batch -> { })) {
            TextureUpdates.Binding binding = updates.subscribe(1, 11, TextureUpdates.MAX_RECTANGLES + 10, 2);
            IntBuffer pixel = IntBuffer.wrap(new int[] {0xff112233});
            for (int x = 0; x < TextureUpdates.MAX_RECTANGLES + 10; x++) {
                updates.begin(1, x, 0, 1, 1); upload(updates, x, 0, 1, 1, pixel); updates.end(true); updates.flush();
            }
            assertTrue(binding.current()); assertEquals(0, updates.droppedCount());
            updates.begin(1, 2, 0, 2, 2); upload(updates, 2, 0, 2, 2, IntBuffer.allocate(1)); updates.end(true);
            updates.begin(1, 2, 0, 2, 1); upload(updates, 2, 0, 2, 1, IntBuffer.allocate(2)); updates.end(true); updates.flush();
            assertFalse(binding.current());
            updates.begin(1, 2, 1, 2, 1); upload(updates, 2, 1, 2, 1, IntBuffer.allocate(2)); updates.end(true); updates.flush();
            assertTrue(binding.current());
        }
    }

    private static void upload(TextureUpdates updates, int x, int y, int width, int height, IntBuffer source) {
        updates.uploaded(3553, 0, x, y, width, height, 32993, 33639, source);
    }
}
