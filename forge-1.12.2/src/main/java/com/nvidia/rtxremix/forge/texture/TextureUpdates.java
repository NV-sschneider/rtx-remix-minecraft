/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.texture;

import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.TextureBatchEntry;
import com.nvidia.rtxremix.TexturePatch;
import com.nvidia.rtxremix.TexturePatchInfo;
import com.nvidia.rtxremix.TextureFormat;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Bounded latest-pixel patches for already owned scene textures, including maps, skins and effects. */
public final class TextureUpdates implements AutoCloseable {
    private static final Logger LOGGER = LogManager.getLogger("RLCraftRemixTextureUpdates");
    static final int MAX_TEXTURES = 64, MAX_RECTANGLES = 4096, MAX_PATCH_BYTES = 4 * 1024 * 1024;
    static final int MAX_BATCH_PATCHES = 64, BYTES_PER_FRAME = 4 * 1024 * 1024;
    static final long MAX_RETAINED_BYTES = 32L * 1024 * 1024;
    static final long MAX_CAPTURE_BYTES_PER_FRAME = 32L * 1024 * 1024;
    interface Publisher { void publish(TexturePatchInfo value); }

    public static final class Binding {
        final int glId, width, height;
        final TextureBatchEntry entry = new TextureBatchEntry();
        final TexturePatch[][] arrays = new TexturePatch[MAX_BATCH_PATCHES + 1][];
        final Long2ObjectOpenHashMap<Slot> rectangles = new Long2ObjectOpenHashMap<Slot>(4);
        final LostTextureRegions lost;
        final boolean independentPatches;
        int pending;
        boolean deleted, released, opaque, binary, requireComplete;
        Binding(int glId, long texture, int width, int height, boolean opaque, boolean binary, boolean independentPatches) {
            this.glId = glId; this.width = width; this.height = height; entry.handle = texture;
            this.opaque = opaque;
            this.binary = binary;
            this.independentPatches = independentPatches;
            lost = new LostTextureRegions(width, height);
        }
        public boolean current() {
            return lost.empty() && !deleted && !released && (pending == 0 || independentPatches && !requireComplete);
        }
        public boolean deleted() { return deleted || released; }
        /** Strict all-alpha-255 proof; partial repairs never promote a previously unknown image. */
        public boolean opaque() { return opaque && current(); }
        /** Alpha proof covers the initial image and every captured patch, including pending atlas pixels. */
        public boolean binary() { return binary && current(); }
    }

    private final Thread thread = Thread.currentThread();
    private final Publisher publisher;
    private final Binding[] bindings = new Binding[MAX_TEXTURES];
    private final Slot[] slots = new Slot[MAX_RECTANGLES], ready = new Slot[MAX_BATCH_PATCHES];
    private final Binding[] batchBindings = new Binding[MAX_BATCH_PATCHES];
    private final int[] batchCounts = new int[MAX_BATCH_PATCHES];
    private final TextureBatchEntry[][] arrays = new TextureBatchEntry[MAX_BATCH_PATCHES + 1][];
    private final TexturePatchInfo update = new TexturePatchInfo();
    private final Binding[] scopes = new Binding[16];
    private final int[] scopeX = new int[16], scopeY = new int[16], scopeW = new int[16], scopeH = new int[16];
    private int depth;
    private long retained, capturedBytes, ticket, uploads, publishedPatches, dropped;
    private boolean closed;
    private boolean warned;
    private String firstReason;
    private int failedId, failedX, failedY, failedW, failedH, failedPending;
    private long failedRetained, failedCaptured;

    public TextureUpdates(RemixAPI api) { this(api::updateTextureFromMemory); }
    TextureUpdates(Publisher publisher) { this.publisher = publisher; TextureRevisionHooks.attach(this); }

    public Binding subscribe(int glId, long texture, int width, int height) {
        return subscribe(glId, texture, width, height, false);
    }

    public Binding subscribe(int glId, long texture, int width, int height, boolean opaque) {
        return subscribe(glId, texture, width, height, opaque, opaque);
    }

    /** The caller may seed alpha proofs only from the complete initial level-zero snapshot. */
    public Binding subscribe(int glId, long texture, int width, int height, boolean opaque, boolean binary) {
        return subscribe(glId, texture, width, height, opaque, binary, false);
    }

    /**
     * Sprite animations may show their last published pixels while other atlas rectangles drain.
     * Lost pixels, deletion and full-image replacement still require complete publication.
     */
    public Binding subscribeAtlas(int glId, long texture, int width, int height, boolean opaque, boolean binary) {
        return subscribe(glId, texture, width, height, opaque, binary, true);
    }

    private Binding subscribe(int glId, long texture, int width, int height, boolean opaque, boolean binary, boolean independentPatches) {
        checkThread();
        if (closed || glId <= 0 || texture == 0 || width <= 0 || height <= 0 || width > 65535 || height > 65535) throw new IllegalArgumentException("Invalid texture observer binding");
        for (int i = 0; i < bindings.length; i++) if (bindings[i] == null) {
            Binding value = new Binding(glId, texture, width, height, opaque, binary, independentPatches); bindings[i] = value; return value;
        }
        throw new IllegalStateException("Scene texture observer capacity exceeded");
    }
    public void unsubscribe(Binding value) {
        checkThread(); if (value == null || value.released) return;
        clear(value); value.released = true;
        for (int i = 0; i < bindings.length; i++) if (bindings[i] == value) bindings[i] = null;
    }

    /** Applies bounded patches; coherent images and atlas repairs stay hidden until their pending pixels arrive. */
    public void flush() {
        checkThread(); if (closed) return;
        capturedBytes = 0;
        if (dropped != 0 && !warned) {
            warned = true;
            LOGGER.warn("Scene texture revision deferred: reason={} gl={} rectangle={},{},{}x{} pending={} retainedBytes={} captureBytes={}; hidden until later CPU uploads repair lost pixels and pending patches drain",
                    firstReason, failedId, failedX, failedY, failedW, failedH, failedPending, failedRetained, failedCaptured);
        }
        int count = 0, bytes = 0, groups = 0;
        long previous = -1;
        while (count < ready.length) {
            Slot selected = null;
            for (Slot slot : slots) if (slot != null && slot.queued && slot.ticket > previous
                    && (selected == null || slot.ticket < selected.ticket)) selected = slot;
            if (selected == null || selected.bytes > BYTES_PER_FRAME - bytes) break;
            ready[count++] = selected; bytes += selected.bytes; previous = selected.ticket;
            int group = 0; while (group < groups && batchBindings[group] != selected.owner) group++;
            if (group == groups) { batchBindings[groups] = selected.owner; batchCounts[groups++] = 0; }
            batchCounts[group]++;
        }
        if (count == 0) return;
        for (int group = 0; group < groups; group++) {
            Binding owner = batchBindings[group]; int length = batchCounts[group];
            if (owner.arrays[length] == null) owner.arrays[length] = new TexturePatch[length];
            owner.entry.patches = owner.arrays[length]; batchCounts[group] = 0;
        }
        for (int i = 0; i < count; i++) {
            Slot slot = ready[i]; int group = 0; while (batchBindings[group] != slot.owner) group++;
            slot.owner.entry.patches[batchCounts[group]++] = slot.patch;
        }
        if (arrays[groups] == null) arrays[groups] = new TextureBatchEntry[groups];
        for (int group = 0; group < groups; group++) arrays[groups][group] = batchBindings[group].entry;
        update.entries = arrays[groups];
        try {
            publisher.publish(update);
            for (int i = 0; i < count; i++) {
                Slot slot = ready[i]; slot.queued = false;
                if (--slot.owner.pending == 0 && slot.owner.lost.empty()) slot.owner.requireComplete = false;
            }
            uploads++; publishedPatches += count;
        } finally {
            for (int i = 0; i < count; i++) ready[i] = null;
            for (int group = 0; group < groups; group++) batchBindings[group] = null;
        }
    }

    void begin(int id, int x, int y, int width, int height) {
        Binding owner = find(id);
        if (owner != null && x == 0 && y == 0 && width == owner.width && height == owner.height) owner.requireComplete = true;
        if (depth < scopes.length) {
            scopes[depth] = owner; scopeX[depth] = x; scopeY[depth] = y; scopeW[depth] = width; scopeH[depth] = height;
        }
        depth++;
        if (depth > scopes.length && owner != null) lose(owner, 0, 0, owner.width, owner.height, "scope-depth");
    }
    void end(boolean succeeded) {
        if (depth == 0) return;
        if (--depth >= scopes.length) return;
        Binding owner = scopes[depth]; scopes[depth] = null;
        if (owner != null && !succeeded) lose(owner, scopeX[depth], scopeY[depth], scopeW[depth], scopeH[depth], "upload-threw");
    }
    void uploaded(int target, int level, int x, int y, int width, int height, int format, int type, IntBuffer source) {
        if (depth == 0 || depth > scopes.length || level != 0) return;
        Binding owner = scopes[depth - 1];
        if (owner == null || owner.deleted || owner.released) return;
        if (x == 0 && y == 0 && width == owner.width && height == owner.height) owner.requireComplete = true;
        long pixels = (long) width * height;
        if (target != 3553 || format != 32993 || type != 33639) {
            lose(owner, x, y, width, height, firstReason == null ? "pixel-format:" + target + "/" + format + "/" + type : "pixel-format"); return;
        }
        if (x < 0 || y < 0 || width <= 0 || height <= 0 || (long) x + width > owner.width || (long) y + height > owner.height) {
            lose(owner, x, y, width, height, "upload-bounds"); return;
        }
        if (source == null || pixels > source.remaining()) { lose(owner, x, y, width, height, "upload-buffer-range"); return; }
        if (width > MAX_PATCH_BYTES / 4) { lose(owner, x, y, width, height, "row-byte-budget"); return; }
        try {
            int rows = Math.max(1, MAX_PATCH_BYTES / 4 / width);
            for (int row = 0; row < height; row += rows) {
                int length = Math.min(rows, height - row);
                String failure = capture(owner, x, y + row, width, length, source, source.position() + row * width);
                if (failure != null) { lose(owner, x, y + row, width, height - row, failure); return; }
            }
        } catch (Throwable failure) { lose(owner, x, y, width, height, "cpu-copy-failed"); }
    }
    void deleted(int id) {
        for (Binding owner : bindings) if (owner != null && owner.glId == id) { clear(owner); owner.deleted = true; }
    }

    private String capture(Binding owner, int x, int y, int width, int height, IntBuffer source, int start) {
        long key = AtlasPatchQueue.rectangleKey(x, y, width, height);
        Slot found = owner.rectangles.get(key); int bytes = width * height * 4;
        long work = bytes;
        boolean overlaps = found != null && found.overlaps;
        if (found == null || overlaps) for (Slot other : slots) if (other != null && other != found && other.owner == owner) {
            int overlapWidth = Math.max(0, Math.min(x + width, other.patch.dstX + other.patch.width) - Math.max(x, other.patch.dstX));
            int overlapHeight = Math.max(0, Math.min(y + height, other.patch.dstY + other.patch.height) - Math.max(y, other.patch.dstY));
            if (overlapWidth != 0 && overlapHeight != 0) {
                overlaps = true; other.overlaps = true;
                if (other.queued) work += (long) overlapWidth * overlapHeight * 4;
            }
        }
        if (work > MAX_CAPTURE_BYTES_PER_FRAME - capturedBytes) return "cpu-frame-budget";
        capturedBytes += work;
        if (found == null) {
            int free = reserve(bytes);
            if (free < 0) return retained + bytes > MAX_RETAINED_BYTES ? "retained-byte-budget" : "rectangle-capacity";
            found = new Slot(owner, x, y, width, height, bytes); slots[free] = found; retained += bytes;
            owner.rectangles.put(key, found);
        }
        found.overlaps = overlaps;
        ByteBuffer rgba = found.patch.pixelData; rgba.clear();
        for (int i = 0; i < width * height; i++) {
            int color = source.get(start + i);
            if ((color >>> 24) != 255) owner.opaque = false;
            if ((color >>> 24) != 0 && (color >>> 24) != 255) owner.binary = false;
            rgba.put((byte) (color >>> 16)).put((byte) (color >>> 8)).put((byte) color).put((byte) (color >>> 24));
        }
        rgba.flip();
        if (overlaps) for (Slot other : slots) if (other != null && other != found && other.owner == owner && other.queued) copyIntersection(found.patch, other.patch);
        if (!found.queued) { found.queued = true; found.ticket = ++ticket; owner.pending++; }
        owner.lost.repair(x, y, width, height);
        return null;
    }

    /** Completed rectangles are reusable cache entries, never permanent admissions against the bound. */
    private int reserve(int bytes) {
        int free = -1;
        for (int i = 0; i < slots.length; i++) if (slots[i] == null) { free = i; break; }
        for (int i = 0; i < slots.length && (free < 0 || retained + bytes > MAX_RETAINED_BYTES); i++) {
            Slot slot = slots[i];
            if (slot != null && !slot.queued) { remove(i); if (free < 0) free = i; }
        }
        return retained + bytes <= MAX_RETAINED_BYTES ? free : -1;
    }
    private void remove(int index) {
        Slot slot = slots[index]; slots[index] = null; retained -= slot.bytes;
        slot.owner.rectangles.remove(AtlasPatchQueue.rectangleKey(slot.patch.dstX, slot.patch.dstY, slot.patch.width, slot.patch.height));
        clearArrays(slot.owner);
    }
    private static void copyIntersection(TexturePatch from, TexturePatch to) {
        int left = Math.max(from.dstX, to.dstX), right = Math.min(from.dstX + from.width, to.dstX + to.width);
        int top = Math.max(from.dstY, to.dstY), bottom = Math.min(from.dstY + from.height, to.dstY + to.height);
        if (left >= right || top >= bottom) return;
        for (int y = top; y < bottom; y++) {
            int source = ((y - from.dstY) * from.width + left - from.dstX) * 4;
            int destination = ((y - to.dstY) * to.width + left - to.dstX) * 4;
            for (int i = 0; i < (right - left) * 4; i++) to.pixelData.put(destination + i, from.pixelData.get(source + i));
        }
    }
    private void lose(Binding owner, int x, int y, int width, int height, String reason) {
        owner.requireComplete = true;
        owner.opaque = false;
        owner.binary = false;
        if (firstReason == null) {
            firstReason = reason; failedId = owner.glId; failedX = x; failedY = y; failedW = width; failedH = height;
            failedPending = owner.pending; failedRetained = retained; failedCaptured = capturedBytes;
        }
        dropped++;
        if (x < 0 || y < 0 || width <= 0 || height <= 0 || (long) x + width > owner.width || (long) y + height > owner.height) owner.lost.all();
        else owner.lost.lose(x, y, width, height);
    }
    private void clear(Binding owner) {
        for (int i = 0; i < slots.length; i++) if (slots[i] != null && slots[i].owner == owner) { retained -= slots[i].bytes; slots[i] = null; }
        owner.pending = 0; owner.rectangles.clear(); owner.lost.clear();
        clearArrays(owner);
    }
    private static void clearArrays(Binding owner) {
        for (TexturePatch[] array : owner.arrays) if (array != null) java.util.Arrays.fill(array, null);
        owner.entry.patches = null;
    }
    private Binding find(int id) { for (Binding owner : bindings) if (owner != null && owner.glId == id && !owner.deleted && !owner.released) return owner; return null; }
    boolean ownsThread() { return !closed && Thread.currentThread() == thread; }
    private void checkThread() { if (Thread.currentThread() != thread) throw new IllegalStateException("Texture updates require their render thread"); }
    public long updateCount() { return uploads; }
    public long patchCount() { return publishedPatches; }
    public long retainedBytes() { return retained; }
    public long droppedCount() { return dropped; }
    public int dirtyCount() { int count = 0; for (Binding owner : bindings) if (owner != null && (owner.pending != 0 || !owner.current())) count++; return count; }
    @Override public void close() {
        checkThread(); if (closed) return;
        TextureRevisionHooks.detach(this);
        for (Binding owner : bindings) if (owner != null) unsubscribe(owner);
        for (int i = 0; i < scopes.length; i++) scopes[i] = null;
        update.entries = null; for (TextureBatchEntry[] array : arrays) if (array != null) java.util.Arrays.fill(array, null);
        depth = 0; closed = true;
    }
    private static final class Slot {
        final Binding owner;
        final TexturePatch patch = new TexturePatch();
        final int bytes;
        long ticket;
        boolean queued;
        boolean overlaps;
        Slot(Binding owner, int x, int y, int width, int height, int bytes) {
            this.owner = owner; this.bytes = bytes;
            patch.dstX = x; patch.dstY = y; patch.width = width; patch.height = height; patch.rowPitch = width * 4;
            patch.format = TextureFormat.RGBA8_SRGB;
            patch.pixelData = ByteBuffer.allocateDirect(bytes);
        }
    }
}
