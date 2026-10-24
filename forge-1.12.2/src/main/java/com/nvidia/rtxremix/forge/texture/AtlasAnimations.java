/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.texture;

import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.TextureBatchEntry;
import com.nvidia.rtxremix.TexturePatch;
import com.nvidia.rtxremix.TexturePatchInfo;
import java.nio.IntBuffer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Forwards Minecraft's real animation uploads into an existing Remix atlas.
 * Construct after the initial atlas snapshot and close before releasing its handle.
 * The observer reads CPU pixels only: texture bindings and GL pixel-store state stay
 * untouched. Minecraft owns animation frame selection, interpolation, and timing.
 */
public final class AtlasAnimations implements AutoCloseable {
    private static final Logger LOGGER = LogManager.getLogger("RLCraftRemixAnimations");
    interface Publisher { void publish(TexturePatchInfo patches); }

    private final Publisher publisher;
    private final Object atlas;
    private final Thread renderThread = Thread.currentThread();
    private final AtlasPatchQueue queue;
    private AtlasMaterialMaps materialMaps;
    private final TextureBatchEntry entry = new TextureBatchEntry();
    private final TexturePatchInfo update = new TexturePatchInfo();
    private final Object[] scopes = new Object[16];
    private int depth;
    private boolean closed;
    private boolean warnedDropped;
    private Throwable observationFailure;
    private long updateCount;
    private long patchCount;

    /** Registers a single render-thread block-atlas owner without creating native resources. */
    public AtlasAnimations(RemixAPI api, Object atlas, long texture, int width, int height) {
        this(publisher(api), atlas, texture, width, height);
    }

    AtlasAnimations(Publisher publisher, Object atlas, long texture, int width, int height) {
        if (publisher == null || atlas == null || texture == 0) {
            throw new IllegalArgumentException("Atlas animations require an existing native texture and atlas");
        }
        this.publisher = publisher;
        this.atlas = atlas;
        this.queue = new AtlasPatchQueue(width, height);
        entry.handle = texture;
        update.entries = new TextureBatchEntry[] {entry};
        AtlasUploadHooks.attach(this);
    }

    /** Borrows matching map atlases, whose lifetime encloses this observer. */
    public void materialMaps(AtlasMaterialMaps maps) { checkThread(); materialMaps = maps; }

    /** Applies at most 64 patches and 4 MiB before scene submission; idle frames do no work. */
    public void flush() {
        checkThread();
        if (closed) return;
        if (observationFailure != null) {
            throw new IllegalStateException("Could not mirror an animated atlas upload", observationFailure);
        }
        if (!warnedDropped && queue.droppedCount() != 0) {
            warnedDropped = true;
            LOGGER.warn("Animated atlas uploads exceeded the bounded rectangle cache or used invalid bounds; {} uploads omitted",
                    queue.droppedCount());
        }
        TexturePatch[] patches = queue.batch(materialMaps == null || materialMaps.resourceCount() == 0
                ? AtlasPatchQueue.BYTES_PER_FLUSH : AtlasPatchQueue.BYTES_PER_FLUSH / 4);
        if (patches == null) return;
        entry.patches = patches;
        if (materialMaps != null) update.entries = materialMaps.batch(entry);
        publisher.publish(update);
        if (materialMaps != null) materialMaps.published(update.entries);
        queue.acknowledge(patches.length);
        updateCount++;
        patchCount += patches.length;
    }

    public long updateCount() { return updateCount; }
    public long patchCount() { return patchCount; }
    public int pendingCount() { return queue.pendingCount(); }
    public long retainedBytes() { return queue.retainedBytes(); }
    public long droppedCount() { return queue.droppedCount(); }
    /** Successful updates of an exact atlas sprite rectangle, without allocating tracking entries. */
    public long rectangleUpdates(int x, int y, int width, int height) {
        return queue.updates(x, y, width, height);
    }
    /** Content hash of that rectangle's most recently submitted pixels; zero before its first update. */
    public long rectangleHash(int x, int y, int width, int height) {
        return queue.hash(x, y, width, height);
    }

    boolean ownsThread() { return !closed && Thread.currentThread() == renderThread; }
    void begin(Object owner) {
        if (depth < scopes.length) scopes[depth] = owner;
        depth++;
    }
    void end() {
        if (depth == 0) return;
        depth--;
        if (depth < scopes.length) scopes[depth] = null;
    }
    void uploaded(int target, int level, int x, int y, int width, int height,
            int format, int type, IntBuffer pixels) {
        if (depth == 0 || depth > scopes.length || scopes[depth - 1] != atlas
                || observationFailure != null) return;
        // TextureUtil uploads Minecraft's ARGB ints using BGRA / UNSIGNED_INT_8_8_8_8_REV.
        if (target != 3553 || level != 0) return;
        if (format != 32993 || type != 33639) return;
        try {
            queue.capture(x, y, width, height, pixels);
            if (materialMaps != null) materialMaps.observed(x, y, width, height);
        }
        // Observer failures are reported by the Remix flush, never from vanilla's
        // GL upload call (including direct-buffer allocation failures).
        catch (Throwable failure) { observationFailure = failure; }
    }

    @Override public void close() {
        checkThread();
        if (closed) return;
        closed = true;
        AtlasUploadHooks.detach(this);
        queue.clear();
        entry.patches = null;
        materialMaps = null;
        for (int i = 0; i < scopes.length; i++) scopes[i] = null;
        depth = 0;
    }

    private void checkThread() {
        if (Thread.currentThread() != renderThread) {
            throw new IllegalStateException("Atlas animation publication must run on its render thread");
        }
    }

    private static Publisher publisher(RemixAPI api) {
        if (api == null) throw new IllegalArgumentException("Atlas animations require Remix");
        return api::updateTextureFromMemory;
    }
}
