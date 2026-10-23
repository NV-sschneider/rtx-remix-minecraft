/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.terrain;

import org.junit.Test;
import static org.junit.Assert.*;

public class TerrainBudgetTest {
    @Test public void stagedAndRetiringPagesKeepTheirReservationUntilDestroyed() {
        TerrainBudget budget = new TerrainBudget();
        long page = TerrainBudget.bytes(TerrainBudget.PAGE_VERTICES);
        assertEquals(1146880, page);
        budget.reserve(TerrainBudget.MAX_MESH_BYTES - page);
        assertTrue(budget.canReserve(page));
        budget.reserve(page);
        assertFalse(budget.canReserve(1));
        assertFalse(budget.canReserve(Long.MAX_VALUE));
        budget.release(page);
        assertTrue(budget.canReserve(page));
        budget.release(TerrainBudget.MAX_MESH_BYTES - page);
        assertEquals(0, budget.bytes());
    }

    @Test(expected = IllegalStateException.class) public void refusesOverReservation() {
        new TerrainBudget().reserve(TerrainBudget.MAX_MESH_BYTES + 1);
    }

    @Test(expected = IllegalStateException.class) public void refusesDoubleRelease() {
        TerrainBudget budget = new TerrainBudget();
        budget.reserve(280);
        budget.release(280);
        budget.release(280);
    }
}
