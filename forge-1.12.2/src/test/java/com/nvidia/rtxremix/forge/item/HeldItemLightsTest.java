/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.item;

import org.junit.Test;
import static org.junit.Assert.*;

public class HeldItemLightsTest {
    @Test public void physicalArmMappingHonorsBothPlayerHandednesses() {
        assertEquals(0, HeldItemLights.hand(false, false));
        assertEquals(1, HeldItemLights.hand(false, true));
        assertEquals(0, HeldItemLights.hand(true, true));
        assertEquals(1, HeldItemLights.hand(true, false));
    }
}
