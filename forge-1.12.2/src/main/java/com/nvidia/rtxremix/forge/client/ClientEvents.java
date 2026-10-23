/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import com.nvidia.rtxremix.forge.RemixForgeMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.Display;

public final class ClientEvents {
    private final Minecraft mc = Minecraft.getMinecraft();
    private final PrototypeRenderer renderer = new PrototypeRenderer(mc);
    private final SmokeController smoke = new SmokeController(mc, this);
    private final KeyBinding toggle = new KeyBinding("key.rlcraftremix.toggle", Keyboard.KEY_F8, "key.categories.rlcraftremix");
    private boolean enabled;
    private boolean resetRequested;
    private boolean textureReloadRequested;
    private String failure;

    public void register() {
        RendererLifecycle.attach(renderer);
        ClientRegistry.registerKeyBinding(toggle);
        ClientCommandHandler.instance.registerCommand(new RemixCommand(this));
        ((IReloadableResourceManager) mc.getResourceManager()).registerReloadListener(manager -> textureReloadRequested = true);
    }

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public void screenInitialized(GuiScreenEvent.InitGuiEvent.Post event) {
        // Menu adapters can expose a main-menu marker at higher event priority.
        smoke.screenInitialized(event.getGui());
    }

    @SubscribeEvent
    public void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) { return; }
        while (toggle.isPressed()) { setEnabled(!enabled); }
        if (resetRequested || !enabled || mc.world == null) {
            try { renderer.releaseWorld(); }
            catch (RuntimeException | LinkageError ex) { RemixForgeMod.LOG.error("Error releasing Remix renderer", ex); }
            resetRequested = false;
        }
        smoke.tick();
    }

    @SubscribeEvent
    public void renderWorld(RenderWorldLastEvent event) {
        if (!enabled || mc.world == null || mc.currentScreen != null || !Display.isVisible()
                || mc.displayWidth <= 0 || mc.displayHeight <= 0) { return; }
        try {
            if (textureReloadRequested) {
                renderer.refreshTextures();
                textureReloadRequested = false;
            }
            renderer.render(event.getPartialTicks());
        } catch (RuntimeException | LinkageError ex) {
            failure = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            RemixForgeMod.LOG.error("RTX Remix rendering disabled after failure", ex);
            enabled = false;
            resetRequested = true;
            message("Renderer stopped: " + failure + ". See logs/latest.log.");
        }
    }

    @SubscribeEvent
    public void worldUnload(WorldEvent.Unload event) {
        if (event.getWorld().isRemote) { resetRequested = true; }
    }

    void setEnabled(boolean value) {
        enabled = value;
        failure = null;
        message(value ? "Terrain prototype enabled. Use /mrtx off to restore vanilla rendering." : "Vanilla rendering enabled.");
    }

    void reload() { textureReloadRequested = true; }

    long renderedFrames() { return renderer.frameCount(); }
    boolean isEnabled() { return enabled; }
    String failureMessage() { return failure; }

    String status() {
        return "requested=" + enabled + "; " + renderer.status() + (failure == null ? "" : "; last error=" + failure);
    }

    void message(String text) {
        if (mc.player != null) { mc.player.sendMessage(new TextComponentString("[Remix] " + text)); }
        RemixForgeMod.LOG.info(text);
    }
}
