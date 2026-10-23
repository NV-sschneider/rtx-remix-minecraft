/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import com.nvidia.rtxremix.forge.RemixForgeMod;

/** Narrow entry points for the Display.destroy coremod injection. */
public final class RendererLifecycle {
    private static PrototypeRenderer renderer;

    private RendererLifecycle() { }

    static void attach(PrototypeRenderer value) { renderer = value; }

    public static void beforeDisplayDestroy() {
        if (renderer == null) { return; }
        try { renderer.beforeDisplayDestroy(); }
        catch (RuntimeException | LinkageError ex) {
            RemixForgeMod.LOG.error("Unable to release Remix resources before window destruction", ex);
        }
    }

    public static void afterDisplayDestroy() {
        if (renderer == null) { return; }
        try { renderer.afterDisplayDestroy(); }
        catch (RuntimeException | LinkageError ex) {
            RemixForgeMod.LOG.error("Unable to shut down Remix after window destruction", ex);
        } finally { renderer = null; }
    }
}
