/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.overlay;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiVideoSettings;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/** Adds native graphics settings beside Done; normal screens and their containers stay installed. */
final class OverlayEvents {
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void keyboard(GuiScreenEvent.KeyboardInputEvent.Pre event) {
        if (OverlayHooks.consumeCurrentKeyboardEvent()) event.setCanceled(true);
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void mouse(GuiScreenEvent.MouseInputEvent.Pre event) {
        if (OverlayHooks.capturing()) event.setCanceled(true);
    }
    @SubscribeEvent
    public void initialized(GuiScreenEvent.InitGuiEvent.Post event) {
        if (!(event.getGui() instanceof GuiVideoSettings)) return;
        GuiButton done = null;
        for (GuiButton button : event.getButtonList()) if (button.id == 200) { done = button; break; }
        if (done == null) return;
        int x = done.x, y = done.y, width = done.width;
        done.width = (width - 4) / 2;
        GuiButton settings = new MenuButton(x + done.width + 4, y, width - done.width - 4);
        settings.enabled = OverlayHooks.available(); event.getButtonList().add(settings);
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void clicked(GuiScreenEvent.ActionPerformedEvent.Pre event) {
        if (!(event.getButton() instanceof MenuButton)) return;
        event.setCanceled(true); OverlayHooks.requestMenu(OverlayInputGate.Menu.GRAPHICS_SETTINGS);
    }
    private static final class MenuButton extends GuiButton {
        MenuButton(int x, int y, int width) { super(0x524c43, x, y, width, 20, "RTX Remix..."); }
    }
}
