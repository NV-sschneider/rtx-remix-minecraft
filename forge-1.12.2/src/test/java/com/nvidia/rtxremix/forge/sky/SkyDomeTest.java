/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.sky;

import org.junit.Test;
import static org.junit.Assert.*;

public class SkyDomeTest {
    @Test public void fixedDimensionsHaveOpaqueConstantRadiance() {
        SkyDome dome = new SkyDome();
        SkyState state = new SkyState();
        state.kind = SkyState.END;
        dome.generate(state);
        assertTrue(dome.pixels.isDirect());
        assertEquals(SkyDome.BYTES, dome.pixels.remaining());
        for (int pixel = 0; pixel < SkyDome.WIDTH * SkyDome.HEIGHT; pixel++) {
            assertEquals(0.0275f, component(dome, pixel, 0), 0.0f);
            assertEquals(0.0210f, component(dome, pixel, 1), 0.0f);
            assertEquals(0.0372f, component(dome, pixel, 2), 0.0f);
            assertEquals(1.0f, component(dome, pixel, 3), 0.0f);
        }
        state.kind = SkyState.SKYLESS;
        dome.generate(state);
        for (int pixel = 0; pixel < SkyDome.WIDTH * SkyDome.HEIGHT; pixel++) {
            assertEquals(0.0f, component(dome, pixel, 0), 0.0f);
            assertEquals(0.0f, component(dome, pixel, 1), 0.0f);
            assertEquals(0.0f, component(dome, pixel, 2), 0.0f);
            assertEquals(1.0f, component(dome, pixel, 3), 0.0f);
        }
    }

    @Test public void skyAndHorizonColorsRemainDistinctAndGroundIsDark() {
        SkyDome dome = new SkyDome();
        SkyState state = new SkyState();
        state.orient();
        state.skyB = 0.7f;
        state.fogR = 0.3f;
        dome.generate(state);
        assertEquals(0.7f, at(dome, 0, 0, 2), 0.00001f);
        assertEquals(0.0f, at(dome, 0, 0, 0), 0.0f);
        assertTrue(at(dome, 0, 63, 0) > 0.28f);
        assertEquals(0.0f, at(dome, 0, 127, 2), 0.0f);
    }

    @Test public void providerDisplayColorsAreDecodedExactlyOnce() {
        assertEquals(0.0f, SkyScene.linear(0.0f), 0.0f);
        assertEquals(1.0f, SkyScene.linear(1.0f), 0.00001f);
        assertEquals(0.21404114f, SkyScene.linear(0.5f), 0.00001f);
        assertEquals(0.003130805f, SkyScene.linear(0.04045f), 0.00000001f);
        assertEquals(0.0f, SkyScene.linear(-1.0f), 0.0f);
        assertEquals(0.0f, SkyScene.linear(Float.NaN), 0.0f);
        assertEquals(0.0f, SkyScene.linear(Float.POSITIVE_INFINITY), 0.0f);
    }

    @Test public void sunsetWarmsOnlyTheSolarHorizonAndRainAttenuatesIt() {
        SkyDome dome = new SkyDome();
        SkyState state = new SkyState();
        state.angle = 0.25f;
        state.orient();
        state.skyR = state.fogR = 0.1f;
        state.sunsetR = state.sunsetAlpha = 1.0f;
        dome.generate(state);
        assertTrue(at(dome, 63, 63, 0) > 0.8f);
        assertEquals(0.1f, at(dome, 191, 63, 0), 0.00001f);
        state.rain = 1.0f;
        dome.generate(state);
        assertEquals(0.1f, at(dome, 63, 63, 0), 0.00001f);
    }

    @Test public void invalidProviderColorsCannotReachTheNativeTexture() {
        SkyDome dome = new SkyDome();
        SkyState state = new SkyState();
        state.orient();
        state.skyR = -1.0f;
        state.skyG = Float.NaN;
        state.skyB = Float.POSITIVE_INFINITY;
        dome.generate(state);
        assertEquals(0.0f, at(dome, 0, 0, 0), 0.0f);
        assertEquals(0.0f, at(dome, 0, 0, 1), 0.0f);
        assertEquals(0.0f, at(dome, 0, 0, 2), 0.0f);
    }

    private static float at(SkyDome dome, int x, int y, int channel) {
        return component(dome, y * SkyDome.WIDTH + x, channel);
    }

    private static float component(SkyDome dome, int pixel, int channel) {
        return dome.pixels.getFloat((pixel * 4 + channel) * Float.BYTES);
    }
}
