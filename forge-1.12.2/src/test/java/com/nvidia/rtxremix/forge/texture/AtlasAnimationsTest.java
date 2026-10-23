/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.texture;

import com.nvidia.rtxremix.TexturePatchInfo;
import java.nio.IntBuffer;
import org.junit.Test;
import static org.junit.Assert.*;

public class AtlasAnimationsTest {
    @Test public void onlyObservedAnimationTicksAdvanceTheRetainedNativeTexture() {
        Object atlas = new Object();
        RecordingPublisher publisher = new RecordingPublisher();
        try (AtlasAnimations animations = new AtlasAnimations(publisher, atlas, 42L, 64, 64)) {
            animations.flush();
            assertEquals(0, publisher.calls);
            AtlasUploadHooks.beginAtlas(atlas);
            upload(0, 0xff112233);
            upload(0, 0xff445566);
            AtlasUploadHooks.endAtlas();
            assertEquals(1, animations.pendingCount());
            animations.flush();
            assertEquals(1, publisher.calls);
            assertEquals(42L, publisher.handle);
            assertEquals(0x44, publisher.red);
            assertEquals(1, animations.rectangleUpdates(1, 2, 1, 1));
            long hash = animations.rectangleHash(1, 2, 1, 1);
            for (int i = 0; i < 100; i++) animations.flush();
            assertEquals(1, publisher.calls);
            assertEquals(hash, animations.rectangleHash(1, 2, 1, 1));
            AtlasUploadHooks.beginAtlas(atlas);
            upload(0, 0xff778899);
            AtlasUploadHooks.endAtlas();
            animations.flush();
            assertEquals(2, animations.patchCount());
            assertNotEquals(hash, animations.rectangleHash(1, 2, 1, 1));
        }
    }

    @Test public void ignoresMipsUnrelatedAtlasesAndUploadsOutsideTheAnimationScope() {
        Object atlas = new Object();
        RecordingPublisher publisher = new RecordingPublisher();
        try (AtlasAnimations animations = new AtlasAnimations(publisher, atlas, 42L, 64, 64)) {
            upload(0, 1);
            AtlasUploadHooks.beginAtlas(new Object());
            upload(0, 2);
            AtlasUploadHooks.endAtlas();
            AtlasUploadHooks.beginAtlas(atlas);
            upload(1, 3);
            AtlasUploadHooks.beginAtlas(new Object());
            upload(0, 4);
            AtlasUploadHooks.endAtlas();
            upload(0, 0xffabcdef);
            AtlasUploadHooks.endAtlas();
            animations.flush();
            assertEquals(1, publisher.calls);
            assertEquals(0xab, publisher.red);
        }
    }

    @Test public void failedPublicationKeepsThePendingPixelsAndSuccessCountersUnchanged() {
        Object atlas = new Object();
        RecordingPublisher publisher = new RecordingPublisher();
        publisher.fail = true;
        try (AtlasAnimations animations = new AtlasAnimations(publisher, atlas, 42L, 64, 64)) {
            AtlasUploadHooks.beginAtlas(atlas);
            upload(0, 0xff112233);
            AtlasUploadHooks.endAtlas();
            try { animations.flush(); fail("Publication should fail"); }
            catch (IllegalStateException expected) { assertEquals("native failure", expected.getMessage()); }
            assertEquals(1, animations.pendingCount());
            assertEquals(0, animations.updateCount());
            assertEquals(0, animations.rectangleUpdates(1, 2, 1, 1));
            publisher.fail = false;
            animations.flush();
            assertEquals(0, animations.pendingCount());
            assertEquals(1, animations.updateCount());
        }
    }

    @Test public void closingDropsQueuedFramesBeforeAReplacementAtlasRegisters() {
        Object atlas = new Object();
        RecordingPublisher publisher = new RecordingPublisher();
        AtlasAnimations first = new AtlasAnimations(publisher, atlas, 42L, 64, 64);
        AtlasUploadHooks.beginAtlas(atlas);
        upload(0, 0xff112233);
        AtlasUploadHooks.endAtlas();
        first.close();
        assertEquals(0, first.pendingCount());
        assertEquals(0, first.retainedBytes());
        first.flush();
        try (AtlasAnimations second = new AtlasAnimations(publisher, atlas, 77L, 64, 64)) {
            second.flush();
            assertEquals(0, publisher.calls);
            AtlasUploadHooks.beginAtlas(atlas);
            upload(0, 0xff445566);
            AtlasUploadHooks.endAtlas();
            second.flush();
            assertEquals(77L, publisher.handle);
        }
    }

    @Test public void offThreadUploadsCannotMutateTheRenderThreadsCaptureScope() throws Exception {
        Object atlas = new Object();
        RecordingPublisher publisher = new RecordingPublisher();
        try (AtlasAnimations animations = new AtlasAnimations(publisher, atlas, 42L, 64, 64)) {
            AtlasUploadHooks.beginAtlas(atlas);
            Thread worker = new Thread(() -> {
                AtlasUploadHooks.beginAtlas(new Object());
                upload(0, 0xff990000);
                AtlasUploadHooks.endAtlas();
            });
            worker.start();
            worker.join();
            upload(0, 0xff220000);
            AtlasUploadHooks.endAtlas();
            animations.flush();
            assertEquals(0x22, publisher.red);
        }
    }

    private static void upload(int level, int argb) {
        AtlasUploadHooks.uploaded(3553, level, 1, 2, 1, 1, 32993, 33639, IntBuffer.wrap(new int[] {argb}));
    }

    private static final class RecordingPublisher implements AtlasAnimations.Publisher {
        int calls;
        int red;
        long handle;
        boolean fail;
        @Override public void publish(TexturePatchInfo info) {
            if (fail) throw new IllegalStateException("native failure");
            calls++;
            handle = info.entries[0].handle;
            red = info.entries[0].patches[0].pixelData.get(0) & 255;
        }
    }
}
