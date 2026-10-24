/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.texture;

import java.nio.IntBuffer;
import org.junit.Test;
import static org.junit.Assert.*;

public class TextureRevisionHooksTest {
    @Test public void entityAndEffectObserversOwnIndependentCopiesAndDetachIndependently() {
        final int[] red = new int[2];
        try (TextureUpdates entity = new TextureUpdates(batch -> red[0] = batch.entries[0].patches[0].pixelData.get(0) & 255);
             TextureUpdates effect = new TextureUpdates(batch -> red[1] = batch.entries[0].patches[0].pixelData.get(0) & 255)) {
            TextureUpdates.Binding a = entity.subscribe(7, 11, 1, 1), b = effect.subscribe(7, 22, 1, 1);
            IntBuffer pixels = IntBuffer.wrap(new int[] {0xff123456});
            upload(pixels); pixels.put(0, 0);
            assertFalse(a.current()); assertFalse(b.current());
            entity.flush(); assertTrue(a.current()); assertFalse(b.current());
            effect.flush(); assertArrayEquals(new int[] {0x12, 0x12}, red);
            entity.close();
            upload(IntBuffer.wrap(new int[] {0xffabcdef})); effect.flush();
            assertEquals(0xab, red[1]); assertEquals(0x12, red[0]);
            assertTrue(b.current()); TextureRevisionHooks.deleted(7); assertTrue(b.deleted());
            assertEquals(0, effect.retainedBytes());
        }
        // Both slots must be reusable after the complete scene teardown.
        try (TextureUpdates replacement = new TextureUpdates(batch -> { })) { replacement.subscribe(7, 33, 1, 1); }
    }

    @Test public void duplicateAndThirdObserversCannotDisplaceExistingSubscribers() {
        try (TextureUpdates first = new TextureUpdates(batch -> { }); TextureUpdates second = new TextureUpdates(batch -> { })) {
            assertThrows(IllegalStateException.class, () -> TextureRevisionHooks.attach(first));
            assertThrows(IllegalStateException.class, () -> new TextureUpdates(batch -> { }));
            TextureUpdates.Binding a = first.subscribe(7, 11, 1, 1), b = second.subscribe(7, 22, 1, 1);
            upload(IntBuffer.allocate(1)); assertFalse(a.current()); assertFalse(b.current());
            first.flush(); second.flush(); assertTrue(a.current()); assertTrue(b.current());
        }
    }
    private static void upload(IntBuffer pixels) {
        TextureRevisionHooks.begin(7, 1, 1);
        TextureRevisionHooks.uploaded(3553, 0, 0, 0, 1, 1, 32993, 33639, pixels);
        TextureRevisionHooks.end(true);
    }
}
