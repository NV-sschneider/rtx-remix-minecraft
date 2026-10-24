/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.item;

import java.lang.reflect.Field;
import org.junit.Test;
import static org.junit.Assert.*;

public class ItemCaptureHooksTest {
    @Test public void rejectedItemOwnerLeavesItsBufferToEntityCaptureButAcceptedAndNestedItemsDoNot() throws Exception {
        try {
            ItemCaptureHooks.beginFrame(true);
            set("modelDepth", 1);
            // RenderSnowball calls RenderItem, whose item observer accepts only
            // dropped items and living holders. Nesting alone must not claim it.
            assertFalse(ItemCaptureHooks.ownsModelBuffer());
            set("active", new CapturedItem());
            assertTrue(ItemCaptureHooks.ownsModelBuffer());
            set("modelDepth", 2);
            assertTrue(ItemCaptureHooks.ownsModelBuffer());
            ItemCaptureHooks.discard();
            assertFalse(ItemCaptureHooks.ownsModelBuffer());
        } finally { ItemCaptureHooks.discard(); }
    }

    private static void set(String name, Object value) throws Exception {
        Field field = ItemCaptureHooks.class.getDeclaredField(name); field.setAccessible(true); field.set(null, value);
    }
}
