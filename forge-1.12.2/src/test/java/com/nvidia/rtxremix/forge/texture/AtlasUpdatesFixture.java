/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.texture;

import java.nio.IntBuffer;

/** Real bounded texture queue with an in-memory publisher for cross-package scene lifecycle tests. */
public final class AtlasUpdatesFixture implements AutoCloseable {
    private final TextureUpdates updates = new TextureUpdates(batch -> { });
    public TextureUpdates.Binding bind(int glId, long nativeTexture) {
        return updates.subscribeAtlas(glId, nativeTexture, 128, 1, false, true);
    }
    public void animate(int glId) {
        updates.begin(glId, 0, 0, 0, 0);
        for (int x = 0; x <= TextureUpdates.MAX_BATCH_PATCHES; x++) {
            updates.uploaded(3553, 0, x, 0, 1, 1, 32993, 33639, IntBuffer.wrap(new int[] {-1}));
        }
        updates.end(true);
    }
    public void flush() { updates.flush(); }
    public int dirtyCount() { return updates.dirtyCount(); }
    public void release(TextureUpdates.Binding binding) { updates.unsubscribe(binding); }
    @Override public void close() { updates.close(); }
}
