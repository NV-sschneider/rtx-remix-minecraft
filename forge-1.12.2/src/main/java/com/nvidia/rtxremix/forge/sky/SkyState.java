/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.sky;

/** Render-thread snapshot of provider colors and celestial state. */
final class SkyState {
    static final int NORMAL = 0;
    static final int END = 1;
    static final int SKYLESS = 2;
    int kind;
    float angle;
    float rain;
    float thunder;
    int moonPhase;
    float moonBrightness;
    float stars;
    float skyR, skyG, skyB;
    float fogR, fogG, fogB;
    float sunsetR, sunsetG, sunsetB, sunsetAlpha;
    float sunX, sunY;

    void orient() {
        double radians = angle * Math.PI * 2.0;
        sunX = -(float) Math.sin(radians);
        sunY = (float) Math.cos(radians);
    }

    float celestialVisibility() {
        return (1.0f - rain) * (1.0f - thunder);
    }

    float sunStrength() {
        return kind == NORMAL ? 3.0f * horizon(sunY) * celestialVisibility() : 0.0f;
    }

    float moonStrength() {
        return kind == NORMAL ? 0.04f * horizon(-sunY) * moonBrightness
                * celestialVisibility() : 0.0f;
    }

    private static float horizon(float height) {
        float value = Math.max(0.0f, Math.min(1.0f, height / 0.1f));
        return value * value * (3.0f - 2.0f * value);
    }
}
