/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.overlay;

/** Prevents a paused, resized, unloaded, or reopened menu from replaying an old native image. */
final class OverlayFrameStamp {
    private long frame, session;
    private int width, height;
    private boolean valid;
    void capture(long frame, long session, int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Menu image must be nonempty");
        this.frame = frame; this.session = session; this.width = width; this.height = height; valid = true;
    }
    boolean matches(boolean capturing, long frame, long session, int width, int height) {
        return capturing && valid && this.frame == frame && this.session == session && this.width == width && this.height == height;
    }
    void clear() { valid = false; }
}
