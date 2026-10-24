/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

/** Accounting survives capture cleanup until the independent world-completion callback. */
final class TerrainFrameState {
    private long frame, skippedTotal, fallbackFrames, fallbackFailures;
    private int skipped, vanilla;
    private boolean copied, finished = true;

    void begin() { frame++; copied = false; finished = false; }
    void copied() { copied = true; }
    boolean finish(int skipped, int vanilla) {
        if (finished) return false;
        finished = true;
        this.skipped = skipped; this.vanilla = vanilla; skippedTotal += skipped;
        return skipped != 0 && !copied;
    }
    void recovered(boolean success) { if (success) fallbackFrames++; else fallbackFailures++; }
    long frame() { return frame; }
    int skipped() { return skipped; }
    int vanilla() { return vanilla; }
    long skippedTotal() { return skippedTotal; }
    long fallbackFrames() { return fallbackFrames; }
    long fallbackFailures() { return fallbackFailures; }
}
