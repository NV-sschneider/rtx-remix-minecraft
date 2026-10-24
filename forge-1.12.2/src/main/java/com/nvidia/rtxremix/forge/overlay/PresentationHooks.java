/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.overlay;

import org.lwjgl.opengl.Display;

/** Replaces the GL buffer swap while Remix owns presentation, including disabled/worldless frames. */
public final class PresentationHooks {
    public interface Backend {
        boolean ownsSwapchain();
        void presentFrame();
    }
    private static Backend backend;
    private static Runnable present;
    private PresentationHooks() { }
    public static void attach(Backend value) {
        if (backend != null) throw new IllegalStateException("Presentation backend is already attached");
        backend = value; present = value::presentFrame;
    }
    public static void updateDisplay() {
        if (backend == null || !backend.ownsSwapchain()) Display.update();
        else LegacyDisplayUpdate.update(present);
    }
}
