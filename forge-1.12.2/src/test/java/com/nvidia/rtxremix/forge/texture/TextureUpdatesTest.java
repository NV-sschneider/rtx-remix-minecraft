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
    @Test public void independentAtlasSpritesRemainVisibleAcrossAContinuousBoundedBacklog() {
        final int[] published = {0};
        try (TextureUpdates updates = new TextureUpdates(batch -> {
            int patches = 0, bytes = 0;
            for (TextureBatchEntry entry : batch.entries) for (TexturePatch patch : entry.patches) {
                patches++; bytes += patch.width * patch.height * 4;
            }
            assertTrue(patches <= TextureUpdates.MAX_BATCH_PATCHES);
            assertTrue(bytes <= TextureUpdates.BYTES_PER_FRAME); published[0] += patches;
        })) {
            TextureUpdates.Binding atlas = updates.subscribeAtlas(1, 11, 128, 1, true, true);
            TextureUpdates.Binding image = updates.subscribe(2, 22, 128, 1, true, true);
            for (int frame = 0; frame < 5; frame++) {
                updates.begin(1, 0, 0, 0, 0);
                for (int x = 0; x <= TextureUpdates.MAX_BATCH_PATCHES; x++) {
                    upload(updates, x, 0, 1, 1, IntBuffer.wrap(new int[] {0xff000000 | frame}));
                }
                updates.end(true);
                updates.begin(2, 0, 0, 1, 1);
                upload(updates, 0, 0, 1, 1, IntBuffer.wrap(new int[] {-1})); updates.end(true);
                assertTrue(atlas.current()); assertTrue(atlas.opaque()); assertTrue(atlas.binary());
                assertFalse(image.current()); assertFalse(image.opaque());
                assertEquals(2, updates.dirtyCount());
                updates.flush();
                assertTrue(atlas.current()); assertTrue(atlas.pending > 0);
            }
            assertEquals(5 * TextureUpdates.MAX_BATCH_PATCHES, published[0]);
            assertTrue(updates.retainedBytes() <= TextureUpdates.MAX_RETAINED_BYTES);
            updates.begin(1, 0, 0, 1, 1);
            upload(updates, 0, 0, 1, 1, IntBuffer.wrap(new int[] {0x80123456})); updates.end(true);
            assertTrue(atlas.current()); assertFalse(atlas.opaque()); assertFalse(atlas.binary());
            updates.deleted(1); assertFalse(atlas.current()); assertTrue(atlas.deleted());
            updates.unsubscribe(atlas); updates.unsubscribe(image); assertEquals(0, updates.retainedBytes());
        }
    }

    @Test public void atlasFullReplacementsAndLostPixelRepairsRequireSuccessfulCompletePublication() {
        final boolean[] fail = {false};
        try (TextureUpdates updates = new TextureUpdates(batch -> {
            if (fail[0]) throw new IllegalStateException("native publication");
        })) {
            TextureUpdates.Binding atlas = updates.subscribeAtlas(1, 11, 1024, 2048, true, true);
            updates.begin(1, 0, 0, 1024, 2048);
            upload(updates, 0, 0, 1024, 2048, IntBuffer.allocate(1024 * 2048)); updates.end(true);
            assertFalse(atlas.current()); updates.flush(); assertFalse(atlas.current());
            fail[0] = true; assertThrows(IllegalStateException.class, updates::flush); assertFalse(atlas.current());
            fail[0] = false; updates.flush(); assertTrue(atlas.current());
            updates.begin(1, 0, 0, 1, 1);
            upload(updates, 0, 0, 1, 1, IntBuffer.wrap(new int[] {0xff123456})); updates.end(true);
            assertTrue(atlas.current()); updates.flush();
            updates.begin(1, 4, 5, 1, 1);
            upload(updates, 4, 5, 1, 1, IntBuffer.allocate(0)); updates.end(true);
            assertFalse(atlas.current());
            updates.begin(1, 4, 5, 1, 1);
            upload(updates, 4, 5, 1, 1, IntBuffer.wrap(new int[] {-1})); updates.end(true);
            assertTrue(atlas.lost.empty()); assertFalse(atlas.current());
            fail[0] = true; assertThrows(IllegalStateException.class, updates::flush); assertFalse(atlas.current());
            fail[0] = false; updates.flush(); assertTrue(atlas.current());
            assertFalse(atlas.opaque()); assertFalse(atlas.binary());
        }
    }

    @Test public void opaqueProofSurvivesOpaquePatchesButNeverPromotesUnknownOrTranslucentPixels() {
        try (TextureUpdates updates = new TextureUpdates(batch -> { })) {
            TextureUpdates.Binding proven = updates.subscribe(1, 11, 2, 2, true);
            TextureUpdates.Binding unknown = updates.subscribe(2, 22, 1, 1);
            assertTrue(proven.opaque()); assertFalse(unknown.opaque());
            assertTrue(proven.binary()); assertFalse(unknown.binary());
            updates.begin(1, 1, 0, 1, 1);
            upload(updates, 1, 0, 1, 1, IntBuffer.wrap(new int[] {0xff123456})); updates.end(true);
            assertFalse(proven.opaque()); // Pending pixels cannot authorize an opaque native draw.
            assertFalse(proven.binary()); updates.flush(); assertTrue(proven.opaque()); assertTrue(proven.binary());
            updates.begin(1, 0, 1, 1, 1);
            upload(updates, 0, 1, 1, 1, IntBuffer.wrap(new int[] {0xfe123456})); updates.end(true);
            assertFalse(proven.opaque()); updates.flush(); assertFalse(proven.opaque()); assertTrue(proven.current());
            assertFalse(proven.binary());
            updates.begin(1, 0, 1, 1, 1);
            upload(updates, 0, 1, 1, 1, IntBuffer.wrap(new int[] {0xff123456})); updates.end(true); updates.flush();
            assertFalse(proven.opaque()); // Partial repair cannot prove the complete image again.
            assertFalse(proven.binary());
            updates.begin(2, 0, 0, 1, 1);
            upload(updates, 0, 0, 1, 1, IntBuffer.wrap(new int[] {-1})); updates.end(true); updates.flush();
            assertFalse(unknown.opaque());
            assertFalse(unknown.binary());
        }
    }

    @Test public void transparentPatchesRevokeFullOpacityWithoutLosingTheIndependentBinaryProof() {
        try (TextureUpdates updates = new TextureUpdates(batch -> { })) {
            TextureUpdates.Binding opaque = updates.subscribe(1, 11, 2, 1, true);
            TextureUpdates.Binding binary = updates.subscribe(2, 22, 2, 1, false, true);
            assertTrue(opaque.opaque()); assertTrue(opaque.binary());
            assertFalse(binary.opaque()); assertTrue(binary.binary());
            for (int id = 1; id <= 2; id++) {
                updates.begin(id, 0, 0, 2, 1);
                upload(updates, 0, 0, 2, 1, IntBuffer.wrap(new int[] {0x00123456, 0xffabcdef})); updates.end(true);
            }
            assertFalse(opaque.binary()); assertFalse(binary.binary());
            updates.flush();
            assertFalse(opaque.opaque()); assertFalse(binary.opaque());
            assertTrue(opaque.binary()); assertTrue(binary.binary());
            updates.begin(2, 0, 0, 1, 1);
            upload(updates, 0, 0, 1, 1, IntBuffer.wrap(new int[] {0x01123456})); updates.end(true); updates.flush();
            assertFalse(binary.binary()); assertTrue(opaque.binary());
            updates.begin(2, 0, 0, 1, 1);
            upload(updates, 0, 0, 1, 1, IntBuffer.wrap(new int[] {0xff123456})); updates.end(true); updates.flush();
            assertTrue(binary.current()); assertFalse(binary.binary());
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
            assertFalse(wrongFormat.binary()); assertFalse(failed.binary());
            assertFalse(deleted.binary()); assertFalse(released.binary());
            for (int id = 1; id <= 2; id++) {
                updates.begin(id, 0, 0, 1, 1); upload(updates, 0, 0, 1, 1, IntBuffer.wrap(new int[] {-1})); updates.end(true);
            }
            updates.flush(); assertTrue(wrongFormat.current()); assertTrue(failed.current());
            assertFalse(wrongFormat.opaque()); assertFalse(failed.opaque());
            assertFalse(wrongFormat.binary()); assertFalse(failed.binary());
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
            TextureUpdates.Binding binding = updates.subscribe(1, 11, 1024, 2048, true);
            updates.begin(1, 0, 0, 1024, 2048); upload(updates, 0, 0, 1024, 2048, IntBuffer.allocate(1024 * 2048)); updates.end(true);
            assertFalse(binding.current()); assertThrows(IllegalStateException.class, updates::flush); assertFalse(binding.current());
            assertFalse(binding.binary());
            fail[0] = false; updates.flush(); assertFalse(binding.current()); assertFalse(binding.binary());
            updates.flush(); assertTrue(binding.current()); assertTrue(binding.binary()); assertFalse(binding.opaque());
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
