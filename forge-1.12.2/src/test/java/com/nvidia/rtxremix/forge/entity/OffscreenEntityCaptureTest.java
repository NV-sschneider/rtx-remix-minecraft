/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

import org.junit.Test;
import static org.junit.Assert.*;

public class OffscreenEntityCaptureTest {
    @Test public void selectsNearestOwnersUnderCapRegardlessOfWorldListOrder() {
        ClosestEntities<Integer> selection = new ClosestEntities<>(3);
        for (int i = 100; i >= 1; i--) selection.offer(i, i * i);
        assertEquals(3, selection.size());
        assertEquals(Integer.valueOf(1), selection.get(0));
        assertEquals(Integer.valueOf(2), selection.get(1));
        assertEquals(Integer.valueOf(3), selection.get(2));
        selection.clear();
        assertEquals(0, selection.size());
        selection.offer(7, 49);
        assertEquals(Integer.valueOf(7), selection.get(0));
    }

    @Test public void stableTiesDoNotFlickerAndNonfiniteCandidatesCannotDisplaceGeometry() {
        ClosestEntities<String> selection = new ClosestEntities<>(2);
        selection.offer("first", 1); selection.offer("second", 1); selection.offer("third", 1);
        selection.offer("nan", Double.NaN); selection.offer("infinity", Double.POSITIVE_INFINITY);
        selection.offer("negative", -1); selection.offer(null, 0);
        assertEquals("first", selection.get(0)); assertEquals("second", selection.get(1));
    }

    @Test public void primitiveCacheRestoresNestedStatesArraysAliasesAndExactFloatingBits() {
        StateRoot.active = 3;
        StateRoot.alpha.enabled = true;
        StateRoot.alpha.value = Float.intBitsToFloat(0x7fc00073);
        StateRoot.textures[0].id = 7; StateRoot.textures[1].id = 9;
        StateRoot.clear = -0.0;
        PrimitiveStateSnapshot snapshot = new PrimitiveStateSnapshot(StateRoot.class);
        snapshot.capture();
        StateRoot.active = 8; StateRoot.alpha.enabled = false; StateRoot.alpha.value = 1;
        StateRoot.textures[0].id = 2; StateRoot.textures[1].id = 4; StateRoot.clear = 1;
        snapshot.restore();
        assertEquals(3, StateRoot.active); assertTrue(StateRoot.alpha.enabled);
        assertEquals(0x7fc00073, Float.floatToRawIntBits(StateRoot.alpha.value));
        assertEquals(7, StateRoot.textures[0].id); assertEquals(9, StateRoot.textures[1].id);
        assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(StateRoot.clear));
        StateRoot.active = 11; snapshot.capture(); StateRoot.active = 19; snapshot.restore();
        assertEquals(11, StateRoot.active);
    }

    @Test public void managedBodyCanDeactivateWithoutLosingNestedScopeOwnership() {
        ModelCaptureScope scope = new ModelCaptureScope();
        Object projectile = new Object();
        assertTrue(scope.enter(projectile, false));
        assertNull(scope.owner()); scope.activate(); assertSame(projectile, scope.owner());
        assertFalse(scope.enter(new Object(), false));
        scope.activate(); assertNull(scope.owner()); scope.leave();
        assertSame(projectile, scope.owner());
        scope.deactivate(); assertNull(scope.owner());
        assertSame(projectile, scope.outerOwner()); scope.leave(); assertFalse(scope.isOpen());
    }

    private static final class StateRoot {
        static int active;
        static double clear;
        static final Alpha alpha = new Alpha();
        static final Texture[] textures = {new Texture(), new Texture()};
        static final Alpha alias = alpha;
        static final String unrelated = "ignore";
        static final class Alpha { boolean enabled; float value; }
        static final class Texture { int id; }
    }
}
