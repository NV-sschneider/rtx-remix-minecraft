/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.texture;

import com.nvidia.rtxremix.TexturePatch;
import com.nvidia.rtxremix.TextureFormat;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/** Copies successful mip-zero uploads before Minecraft reuses its upload buffer. */
final class AtlasPatchQueue {
    static final int MAX_RECTANGLES = 4096;
    static final int MAX_PATCH_BYTES = 4 * 1024 * 1024;
    static final long MAX_RETAINED_BYTES = 32L * 1024 * 1024;
    static final int PATCHES_PER_FLUSH = 64;
    static final int BYTES_PER_FLUSH = 4 * 1024 * 1024;

    private final int width, height;
    private final Long2ObjectOpenHashMap<Slot> slots = new Long2ObjectOpenHashMap<Slot>();
    private final Slot[] rectangles = new Slot[MAX_RECTANGLES];
    private int rectangleCount;
    private final Slot[] pending = new Slot[MAX_RECTANGLES];
    private int pendingHead, pendingCount;
    private final TexturePatch[][] batches = new TexturePatch[PATCHES_PER_FLUSH + 1][];
    private long retainedBytes;
    private long dropped;

    AtlasPatchQueue(int width, int height) {
        if (width <= 0 || width > 65535 || height <= 0 || height > 65535) {
            throw new IllegalArgumentException("Atlas dimensions must fit unsigned 16-bit coordinates");
        }
        this.width = width;
        this.height = height;
    }

    void capture(int x, int y, int patchWidth, int patchHeight, IntBuffer argb) {
        long pixels = (long) patchWidth * patchHeight;
        long bytes = pixels * 4;
        if (x < 0 || y < 0 || patchWidth <= 0 || patchHeight <= 0
                || (long) x + patchWidth > width || (long) y + patchHeight > height
                || bytes > MAX_PATCH_BYTES || argb == null || pixels > argb.remaining()) {
            dropped++;
            return;
        }
        long key = rectangleKey(x, y, patchWidth, patchHeight);
        Slot slot = slots.get(key);
        if (slot == null) {
            if (slots.size() == MAX_RECTANGLES || bytes > MAX_RETAINED_BYTES - retainedBytes) {
                dropped++;
                return;
            }
            slot = new Slot(x, y, patchWidth, patchHeight, (int) bytes);
            for (int i = 0; i < rectangleCount; i++) {
                if (overlaps(slot.patch, rectangles[i].patch)) {
                    slot.overlaps = true;
                    rectangles[i].overlaps = true;
                }
            }
            rectangles[rectangleCount++] = slot;
            slots.put(key, slot);
            retainedBytes += bytes;
        }
        ByteBuffer rgba = slot.patch.pixelData;
        rgba.clear();
        int start = argb.position();
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < (int) pixels; i++) {
            int pixel = argb.get(start + i);
            rgba.put((byte) (pixel >>> 16)).put((byte) (pixel >>> 8))
                    .put((byte) pixel).put((byte) (pixel >>> 24));
            hash = (hash ^ (pixel & 0xffffffffL)) * 0x100000001b3L;
        }
        rgba.flip();
        slot.pendingHash = hash;
        slot.hashDirty = false;
        // Keep the original FIFO position: continuously animated sprites must not
        // push a large backlog behind the same first batch on every game tick.
        // Stitched sprites are disjoint. For custom overlapping uploads, copy the
        // newest shared pixels into older pending rectangles so either order agrees.
        if (slot.overlaps) {
            for (int i = 0; i < rectangleCount; i++) {
                Slot other = rectangles[i];
                if (other != slot && other.queued && overlaps(slot.patch, other.patch)) {
                    copyIntersection(slot.patch, other.patch);
                    other.hashDirty = true;
                }
            }
        }
        if (!slot.queued) {
            slot.queued = true;
            pending[(pendingHead + pendingCount++) % pending.length] = slot;
        }
    }

    TexturePatch[] batch() {
        return batch(BYTES_PER_FLUSH);
    }

    TexturePatch[] batch(int byteLimit) {
        int count = 0;
        int bytes = 0;
        for (int i = 0; i < pendingCount; i++) {
            Slot slot = pending[(pendingHead + i) % pending.length];
            // A non-PBR upload can be larger than the material-batch share. It
            // keeps the existing 4 MiB single-patch bound and runs by itself.
            if (count == PATCHES_PER_FLUSH || count != 0 && slot.bytes > byteLimit - bytes) break;
            count++;
            bytes += slot.bytes;
        }
        if (count == 0) return null;
        TexturePatch[] result = batches[count];
        if (result == null) result = batches[count] = new TexturePatch[count];
        for (int i = 0; i < count; i++) {
            result[i] = pending[(pendingHead + i) % pending.length].patch;
        }
        return result;
    }

    void acknowledge(int count) {
        for (int i = 0; i < count; i++) {
            Slot slot = pending[pendingHead];
            pending[pendingHead] = null;
            pendingHead = (pendingHead + 1) % pending.length;
            pendingCount--;
            slot.queued = false;
            slot.updates++;
            slot.publishedHash = slot.hashDirty ? contentHash(slot.patch.pixelData) : slot.pendingHash;
        }
    }

    int pendingCount() { return pendingCount; }
    long retainedBytes() { return retainedBytes; }
    long droppedCount() { return dropped; }
    long updates(int x, int y, int width, int height) {
        Slot slot = slots.get(rectangleKey(x, y, width, height));
        return slot == null ? 0 : slot.updates;
    }
    long hash(int x, int y, int width, int height) {
        Slot slot = slots.get(rectangleKey(x, y, width, height));
        return slot == null ? 0 : slot.publishedHash;
    }

    void clear() {
        for (int i = 0; i < pending.length; i++) pending[i] = null;
        pendingHead = pendingCount = 0;
        slots.clear();
        for (int i = 0; i < rectangleCount; i++) rectangles[i] = null;
        rectangleCount = 0;
        for (int i = 0; i < batches.length; i++) batches[i] = null;
        retainedBytes = 0;
    }

    static long rectangleKey(int x, int y, int width, int height) {
        return (long) x << 48 | (long) y << 32 | (long) width << 16 | height;
    }

    private static boolean overlaps(TexturePatch a, TexturePatch b) {
        return a.dstX < b.dstX + b.width && b.dstX < a.dstX + a.width
                && a.dstY < b.dstY + b.height && b.dstY < a.dstY + a.height;
    }

    private static void copyIntersection(TexturePatch from, TexturePatch to) {
        int left = Math.max(from.dstX, to.dstX), right = Math.min(from.dstX + from.width, to.dstX + to.width);
        int top = Math.max(from.dstY, to.dstY), bottom = Math.min(from.dstY + from.height, to.dstY + to.height);
        for (int y = top; y < bottom; y++) {
            int source = ((y - from.dstY) * from.width + left - from.dstX) * 4;
            int destination = ((y - to.dstY) * to.width + left - to.dstX) * 4;
            for (int i = 0; i < (right - left) * 4; i++) {
                to.pixelData.put(destination + i, from.pixelData.get(source + i));
            }
        }
    }

    private static long contentHash(ByteBuffer rgba) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < rgba.limit(); i += 4) {
            int argb = (rgba.get(i + 3) & 255) << 24 | (rgba.get(i) & 255) << 16
                    | (rgba.get(i + 1) & 255) << 8 | rgba.get(i + 2) & 255;
            hash = (hash ^ (argb & 0xffffffffL)) * 0x100000001b3L;
        }
        return hash;
    }

    private static final class Slot {
        final TexturePatch patch = new TexturePatch();
        final int bytes;
        boolean queued;
        boolean overlaps;
        boolean hashDirty;
        long pendingHash;
        long publishedHash;
        long updates;

        Slot(int x, int y, int width, int height, int bytes) {
            patch.dstX = x;
            patch.dstY = y;
            patch.width = width;
            patch.height = height;
            patch.rowPitch = width * 4;
            // The albedo atlas is sRGB; the API's patch default is linear UNORM.
            patch.format = TextureFormat.RGBA8_SRGB;
            patch.pixelData = ByteBuffer.allocateDirect(bytes);
            this.bytes = bytes;
        }
    }
}
