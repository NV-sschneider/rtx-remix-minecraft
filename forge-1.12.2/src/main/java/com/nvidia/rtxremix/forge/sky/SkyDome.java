/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.sky;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** A small HDR equirectangular sky, with no GL state or native resource ownership. */
final class SkyDome {
    static final int WIDTH = 256;
    static final int HEIGHT = 128;
    static final int BYTES = WIDTH * HEIGHT * 4 * Float.BYTES;
    private final float[] directions = new float[WIDTH * HEIGHT * 2];
    final ByteBuffer pixels = ByteBuffer.allocateDirect(BYTES).order(ByteOrder.nativeOrder());

    SkyDome() {
        for (int y = 0; y < HEIGHT; y++) {
            double theta = (y + 0.5) / HEIGHT * Math.PI;
            for (int x = 0; x < WIDTH; x++) {
                double phi = (x + 0.5) / WIDTH * Math.PI * 2.0 - Math.PI * 0.5;
                int index = (y * WIDTH + x) * 2;
                // The runtime's equirectangular convention mirrors world X.
                directions[index] = -(float) (Math.sin(theta) * Math.cos(phi));
                directions[index + 1] = (float) Math.cos(theta);
            }
        }
    }

    void generate(SkyState state) {
        pixels.clear();
        if (state.kind != SkyState.NORMAL) {
            float r = state.kind == SkyState.END ? 0.0275f : 0.0f;
            float g = state.kind == SkyState.END ? 0.0210f : 0.0f;
            float b = state.kind == SkyState.END ? 0.0372f : 0.0f;
            for (int index = 0; index < WIDTH * HEIGHT; index++) put(r, g, b);
        } else {
            for (int index = 0; index < directions.length; index += 2) {
                sample(state, directions[index], directions[index + 1]);
            }
        }
        pixels.flip();
    }

    private void sample(SkyState state, float x, float y) {
        float horizon = 1.0f - Math.min(1.0f, Math.max(0.0f, y) * 4.0f);
        float r = lerp(state.skyR, state.fogR, horizon);
        float g = lerp(state.skyG, state.fogG, horizon);
        float b = lerp(state.skyB, state.fogB, horizon);
        float towardSun = Math.max(0.0f, x * Math.signum(state.sunX));
        float sunset = state.sunsetAlpha * horizon * horizon * towardSun * towardSun
                * state.celestialVisibility();
        r = lerp(r, state.sunsetR, sunset);
        g = lerp(g, state.sunsetG, sunset);
        b = lerp(b, state.sunsetB, sunset);
        // The lower hemisphere supplies reflected sky color without lighting caves from below.
        float below = y < 0.0f ? Math.max(0.0f, 1.0f + y * 8.0f) : 1.0f;
        r *= below;
        g *= below;
        b *= below;
        put(r, g, b);
    }

    private void put(float r, float g, float b) {
        pixels.putFloat(radiance(r)).putFloat(radiance(g)).putFloat(radiance(b)).putFloat(1.0f);
    }

    private static float radiance(float value) {
        return Float.isNaN(value) || Float.isInfinite(value) ? 0.0f : Math.max(0.0f, value);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
