/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

import org.junit.Test;
import static org.junit.Assert.*;

public class ModelCaptureScopeTest {
    @Test public void tileDispatchRemainsInactiveUntilItsNormalRendererStarts() {
        ModelCaptureScope scope = new ModelCaptureScope();
        Object tile = new Object();
        assertTrue(scope.enter(tile, false));
        assertNull(scope.owner());
        scope.activate();
        assertSame(tile, scope.owner());
        scope.leave();
        assertNull(scope.owner());
        assertFalse(scope.isOpen());
    }

    @Test public void nestedLivingAndTileRenderersCannotReplaceOrCaptureForTheOuterOwner() {
        ModelCaptureScope scope = new ModelCaptureScope();
        Object tile = new Object();
        scope.enter(tile, false);
        scope.activate();
        assertFalse(scope.enter(new Object(), true));
        assertNull(scope.owner());
        assertFalse(scope.enter(new Object(), false));
        scope.activate();
        assertNull(scope.owner());
        scope.leave();
        assertNull(scope.owner());
        scope.leave();
        assertSame(tile, scope.owner());
        scope.leave();
        assertNull(scope.owner());
    }

    @Test public void rejectedPreviewDoesNotCaptureItsNestedModel() {
        ModelCaptureScope scope = new ModelCaptureScope();
        scope.enter(new Object(), false);
        scope.reject();
        scope.activate();
        scope.enter(new Object(), true);
        assertNull(scope.owner());
        scope.leave();
        assertNull(scope.owner());
        scope.leave();
        assertFalse(scope.isOpen());
    }

    @Test public void frameDiscardClearsAnUnwoundScopeAndAllowsAFreshWorldOwner() {
        ModelCaptureScope scope = new ModelCaptureScope();
        scope.enter(new Object(), true);
        scope.enter(new Object(), false);
        scope.clear();
        Object next = new Object();
        assertTrue(scope.enter(next, true));
        assertSame(next, scope.owner());
        scope.leave();
        scope.leave();
        assertFalse(scope.isOpen());
    }
}
