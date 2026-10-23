/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

/** A bounded round trip that ends at the reference screenshot pose. */
final class EntityFixtureMotion {
    private static final int OUTWARD_STEPS = 40;
    private static final int TOTAL_STEPS = OUTWARD_STEPS * 2;
    private static final double STEP_DISTANCE = 0.05;
    private int step;

    void reset() { step = 0; }

    void advance() {
        if (step < TOTAL_STEPS) step++;
    }

    double offsetX() {
        return Math.min(step, TOTAL_STEPS - step) * STEP_DISTANCE;
    }

    float yaw() {
        return step < OUTWARD_STEPS || isComplete() ? 270f : 90f;
    }

    boolean isComplete() { return step == TOTAL_STEPS; }
}
