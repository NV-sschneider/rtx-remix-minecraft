/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.texture;

import java.nio.IntBuffer;

/** Render-thread observation points used by the texture transformer; never calls Remix. */
public final class AtlasUploadHooks {
    private static volatile AtlasAnimations target;

    private AtlasUploadHooks() {}

    static void attach(AtlasAnimations animations) {
        if (target != null) throw new IllegalStateException("An animated block atlas is already registered");
        target = animations;
    }
    static void detach(AtlasAnimations animations) {
        if (target == animations) target = null;
    }

    public static void beginAtlas(Object atlas) {
        AtlasAnimations current = target;
        if (current != null && current.ownsThread()) current.begin(atlas);
    }
    public static void endAtlas() {
        AtlasAnimations current = target;
        if (current != null && current.ownsThread()) current.end();
    }
    public static void uploaded(int target, int level, int x, int y, int width, int height,
            int format, int type, IntBuffer pixels) {
        AtlasAnimations current = AtlasUploadHooks.target;
        if (current != null && current.ownsThread()) {
            current.uploaded(target, level, x, y, width, height, format, type, pixels);
        }
    }
}
