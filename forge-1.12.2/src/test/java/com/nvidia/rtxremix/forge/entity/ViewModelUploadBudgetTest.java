/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

import org.junit.Test;
import static org.junit.Assert.*;

public class ViewModelUploadBudgetTest {
    @Test public void handAdmissionIsBoundedAndIndependentOfExhaustedWorldAnimation() {
        TriangleUploadBudget world = new TriangleUploadBudget(); ViewModelUploadBudget hands = new ViewModelUploadBudget();
        world.beginFrame(30); assertTrue(world.admit(TriangleUploadBudget.MAX_BYTES)); assertFalse(world.admit(1));
        hands.beginFrame();
        for (int i = 0; i < ViewModelUploadBudget.MAX_UPLOADS; i++) assertTrue(hands.admit(64));
        assertFalse(hands.admit(64)); hands.beginFrame();
        assertFalse(hands.admit(ViewModelUploadBudget.MAX_BYTES + 1)); assertFalse(hands.admit(-1));
        assertTrue(hands.admit(ViewModelUploadBudget.MAX_BYTES)); assertFalse(hands.admit(1));
    }
}
