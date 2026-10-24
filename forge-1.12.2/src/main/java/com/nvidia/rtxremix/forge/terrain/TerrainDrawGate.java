/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.terrain;

/** One-frame permission to omit replaced SOLID draws, with failures reverting subsequent draws to vanilla. */
public final class TerrainDrawGate {
    public interface Replacement {
        /** True only for a current, complete, published replacement of this section. */
        boolean available(int sectionX, int sectionY, int sectionZ);
    }
    private Thread owner;
    private Replacement replacement;
    private boolean armed;
    private int skipped, drawn;
    private RuntimeException failure;

    /** The caller must own a valid fallback image before granting frame permission. */
    public void beginFrame(boolean eligible, Replacement replacement) {
        owner = Thread.currentThread(); this.replacement = replacement;
        armed = eligible && replacement != null; skipped = drawn = 0; failure = null;
    }

    boolean skip(boolean knownSolidVbo, int x, int y, int z) {
        if (Thread.currentThread() != owner) return false;
        if (armed && knownSolidVbo) {
            try {
                if (replacement.available(x, y, z)) { skipped++; return true; }
            } catch (RuntimeException error) { failure = error; armed = false; }
        }
        drawn++; return false;
    }

    /** Disarms subsequent draws while preserving the failure-frame counters for presentation. */
    public void endFrame() { armed = false; replacement = null; }
    public void discard() { endFrame(); owner = null; skipped = drawn = 0; failure = null; }
    public int skippedDrawCount() { return skipped; }
    public int vanillaDrawCount() { return drawn; }
    public RuntimeException failure() { return failure; }
}
