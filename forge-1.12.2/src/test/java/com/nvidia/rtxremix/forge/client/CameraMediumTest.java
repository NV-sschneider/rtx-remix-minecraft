/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import com.nvidia.rtxremix.MaterialInfo;
import org.junit.Test;
import static org.junit.Assert.*;

public class CameraMediumTest {
    @Test public void submitsAirAndWaterEveryFrameWithOneTextureFreeMaterial() {
        Fake api = new Fake(); CameraMedium medium = new CameraMedium(api);
        medium.apply(false); medium.apply(true); medium.apply(true); medium.apply(false);
        assertEquals(4, api.selections); assertEquals(1, api.creations);
        assertEquals(2, medium.selectionChanges()); assertEquals(0, medium.selectedMaterial());
        assertFalse(medium.isUnderwater()); assertEquals(1, medium.resourceCount());
        medium.close(); medium.close();
        assertEquals(1, api.destructions); assertEquals(0, medium.resourceCount());
    }
    @Test public void failedClearRetainsSelectedMaterialUntilRetrySucceeds() {
        Fake api = new Fake(); CameraMedium medium = new CameraMedium(api);
        medium.apply(true); api.failClear = true;
        try { medium.close(); fail(); } catch (IllegalStateException expected) { }
        assertEquals(1, medium.resourceCount()); assertEquals(42, medium.selectedMaterial());
        assertEquals(0, api.destructions);
        api.failClear = false; medium.close();
        assertEquals(0, medium.resourceCount()); assertEquals(1, api.destructions);
    }
    @Test public void failedDestroyRetainsHandleButNeverLeavesItSelected() {
        Fake api = new Fake(); CameraMedium medium = new CameraMedium(api);
        medium.apply(true); api.failDestroy = true;
        try { medium.close(); fail(); } catch (LinkageError expected) { }
        assertEquals(0, medium.selectedMaterial()); assertEquals(1, medium.resourceCount());
        api.failDestroy = false; medium.close();
        assertEquals(0, medium.resourceCount()); assertEquals(1, api.destructions);
    }
    @Test public void failedInitialSelectionStillOwnsTheNewMaterialForCleanup() {
        Fake api = new Fake(); CameraMedium medium = new CameraMedium(api);
        api.failSelect = true;
        try { medium.apply(true); fail(); } catch (IllegalStateException expected) { }
        assertEquals(1, medium.resourceCount()); assertEquals(0, medium.selectedMaterial());
        api.failSelect = false; medium.close(); assertEquals(1, api.destructions);
    }

    private static final class Fake implements CameraMedium.Backend {
        int creations, selections, destructions;
        long selected;
        boolean failClear, failSelect, failDestroy;
        public long createMaterial(MaterialInfo info) {
            assertEquals(0, info.albedoTexture); assertEquals(0, info.translucentExt.transmittanceTexture);
            assertEquals(0, info.translucentExt.useDiffuseLayer); assertNull(info.opaqueExt);
            assertEquals(1.333f, info.translucentExt.refractiveIndex, 0);
            creations++; return 42;
        }
        public void select(long handle) {
            if (failSelect || failClear && handle == 0) throw new IllegalStateException("selection");
            selected = handle; selections++;
        }
        public void destroyMaterial(long handle) {
            assertEquals(0, selected); assertEquals(42, handle);
            if (failDestroy) throw new UnsatisfiedLinkError("destroy");
            destructions++;
        }
    }
}
