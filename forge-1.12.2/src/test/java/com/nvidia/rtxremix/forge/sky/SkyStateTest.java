/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.sky;

import org.junit.Test;
import static org.junit.Assert.*;

public class SkyStateTest {
    @Test public void celestialAngleMatchesTheVanillaEastToWestOrbit() {
        SkyState state = new SkyState();
        state.angle = 0.75f;
        state.orient();
        assertEquals(1.0f, state.sunX, 0.00001f);
        assertEquals(0.0f, state.sunY, 0.00001f);
        state.angle = 0.0f;
        state.orient();
        assertEquals(0.0f, state.sunX, 0.00001f);
        assertEquals(1.0f, state.sunY, 0.00001f);
        state.angle = 0.25f;
        state.orient();
        assertEquals(-1.0f, state.sunX, 0.00001f);
        assertEquals(0.0f, state.sunY, 0.00001f);
    }

    @Test public void weatherMoonPhaseAndHorizonControlDirectLight() {
        SkyState state = new SkyState();
        state.orient();
        assertEquals(3.0f, state.sunStrength(), 0.00001f);
        assertEquals(0.0f, state.moonStrength(), 0.0f);
        state.rain = 0.5f;
        assertEquals(1.5f, state.sunStrength(), 0.00001f);
        state.thunder = 0.5f;
        assertEquals(0.75f, state.sunStrength(), 0.00001f);
        state.rain = state.thunder = 0.0f;
        state.angle = 0.5f;
        state.moonBrightness = 1.0f;
        state.orient();
        assertEquals(0.0f, state.sunStrength(), 0.0f);
        assertEquals(0.04f, state.moonStrength(), 0.00001f);
        state.moonBrightness = 0.0f;
        assertEquals(0.0f, state.moonStrength(), 0.0f);
        state.angle = 0.0f;
        state.orient();
        state.kind = SkyState.SKYLESS;
        assertEquals(0.0f, state.sunStrength(), 0.0f);
        state.kind = SkyState.END;
        assertEquals(0.0f, state.sunStrength(), 0.0f);
    }
}
