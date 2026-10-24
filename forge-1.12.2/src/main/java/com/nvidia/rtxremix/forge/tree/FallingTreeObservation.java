/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.tree;

import java.util.UUID;

/** Evidence survives the short-lived owner so retirement cannot erase an animation failure. */
public final class FallingTreeObservation {
    private final UUID owner;
    private long frame = -1, firstUpdates = -1, lastUpdates;
    private int capturedFrames, clientFrames, renderableFrames, maximumParts, maximumBranches;
    private boolean retired;

    public FallingTreeObservation(UUID owner) {
        if (owner == null) throw new IllegalArgumentException("A falling-tree observation requires its spawned owner");
        this.owner = owner;
    }

    public void sample(UUID owner, long frame, boolean clientAlive, boolean renderable,
            int branches, int nativeParts, long transformUpdates) {
        if (!this.owner.equals(owner)) throw new IllegalArgumentException("Falling-tree owner changed");
        if (frame < this.frame) throw new IllegalArgumentException("Native frame moved backwards");
        if (frame == this.frame) return;
        this.frame = frame;
        if (clientAlive) clientFrames++;
        if (clientAlive && renderable) renderableFrames++;
        maximumBranches = Math.max(maximumBranches, branches);
        maximumParts = Math.max(maximumParts, nativeParts);
        if (clientAlive && renderable && branches > 0 && nativeParts > 0) {
            capturedFrames++;
            if (firstUpdates < 0) firstUpdates = transformUpdates;
            lastUpdates = Math.max(lastUpdates, transformUpdates);
        }
        retired = clientFrames > 0 && !clientAlive && nativeParts == 0;
    }

    public boolean animated() { return capturedFrames >= 3 && lastUpdates > firstUpdates; }
    public boolean passed() { return animated() && retired; }
    public int capturedFrames() { return capturedFrames; }
    public int clientFrames() { return clientFrames; }
    public int renderableFrames() { return renderableFrames; }
    public int maximumParts() { return maximumParts; }
    public int maximumBranches() { return maximumBranches; }
    public long transformUpdates() { return firstUpdates < 0 ? 0 : lastUpdates - firstUpdates; }
}
