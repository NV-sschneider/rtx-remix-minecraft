/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.cloud;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;

/** One bounded copy of Forge's compiled cloud vertices, shared by its VBO and display-list paths. */
final class ForgeCloudSnapshot {
    // Quad expansion produces six output vertices for every four input vertices.
    static final int MAX_VERTICES = CloudCaptureHooks.MAX_VERTICES / 6 * 4;
    private WeakReference<Object> owner = new WeakReference<Object>(null);
    private Thread thread;
    private ByteBuffer data;
    int count, originalCount, stride, uvOffset, colorOffset;

    void capture(Object renderer, ByteBuffer source, int vertices, int size, int uv, int color) {
        clear();
        if (renderer == null || vertices < 0 || vertices % 4 != 0 || size < 12 || size > 64
                || (long) vertices * size > source.capacity()) return;
        count = Math.min(vertices, MAX_VERTICES);
        originalCount = vertices; stride = size; uvOffset = uv; colorOffset = color;
        data = ByteBuffer.allocate(count * size).order(source.order());
        // BufferBuilder has not finished drawing yet, so its limit need not describe the written range.
        ByteBuffer readable = source.duplicate(); readable.clear(); readable.limit(count * size); data.put(readable);
        data.flip(); owner = new WeakReference<Object>(renderer); thread = Thread.currentThread();
    }

    ByteBuffer data(Object renderer) { return renderer == owner.get() && thread == Thread.currentThread() ? data : null; }
    void dispose(Object renderer) { if (renderer == owner.get()) clear(); }
    private void clear() { owner.clear(); thread = null; data = null; count = originalCount = 0; }
}
