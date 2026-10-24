/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.terrain;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/** Capture-local shading boundary. Concurrent vanilla section builders retain their original lighting. */
public final class BetterFoliageCaptureHooks {
    private static volatile BlockCaptureBuffer active;
    private static volatile Thread owner;

    private BetterFoliageCaptureHooks() { }

    static void begin(BlockCaptureBuffer buffer) {
        if (active != null) throw new IllegalStateException("Nested Better Foliage capture");
        owner = Thread.currentThread(); active = buffer;
    }
    static void end() {
        if (owner != Thread.currentThread()) throw new IllegalStateException("Better Foliage capture changed thread");
        active = null; owner = null;
    }
    static boolean active(Object buffer) { return buffer != null && active == buffer && owner == Thread.currentThread(); }

    /** AO's postprocess branch carries biome tint; its shader's lighting RGB is replaced separately. */
    public static boolean ambient(boolean vanilla, Object buffer) { return active(buffer) || vanilla; }

    /** Called after shading and before the mod's material-color/UV postprocess. */
    public static float lighting(float vanilla, Object buffer, boolean forceFlat) {
        return !forceFlat && active(buffer) ? 1.0f : vanilla;
    }

    public static void reserve(Object buffer, int quads) {
        if (active(buffer)) active.reserveQuads(quads);
    }

    public static void sprite(Object sprite, Object buffer) {
        if (active(buffer)) active.sprite((TextureAtlasSprite) sprite);
    }
}
