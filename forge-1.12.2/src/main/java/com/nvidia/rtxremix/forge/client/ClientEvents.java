/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import com.nvidia.rtxremix.forge.RemixForgeMod;
import com.nvidia.rtxremix.forge.entity.EntityCaptureHooks;
import com.nvidia.rtxremix.forge.item.ItemCaptureHooks;
import com.nvidia.rtxremix.forge.effect.EffectCaptureHooks;
import com.nvidia.rtxremix.forge.cloud.CloudCaptureHooks;
import java.util.UUID;
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
    private final KeyBinding toggle = new KeyBinding("key.rlcraftremix.toggle", Keyboard.KEY_NONE, "key.categories.rlcraftremix");
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
            catch (RuntimeException | LinkageError ex) {
                failure = ex.getClass().getSimpleName() + ": " + ex.getMessage();
                RemixForgeMod.LOG.error("Error releasing Remix renderer", ex);
            }
            resetRequested = false;
        }
        smoke.tick();
    }

    @SubscribeEvent
    public void renderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            EntityCaptureHooks.beginFrame(enabled && mc.world != null);
            ItemCaptureHooks.beginFrame(enabled && mc.world != null);
            EffectCaptureHooks.beginFrame(enabled && mc.world != null);
            CloudCaptureHooks.beginFrame(enabled && mc.world != null);
        } else {
            discardCaptures();
        }
    }

    @SubscribeEvent
    public void renderWorld(RenderWorldLastEvent event) {
        if (!enabled || mc.world == null || !Display.isVisible()
                || mc.displayWidth <= 0 || mc.displayHeight <= 0) {
            discardCaptures();
            return;
        }
        try {
            if (textureReloadRequested) {
                renderer.refreshTextures();
                textureReloadRequested = false;
            }
            EntityCaptureHooks.captureOffscreen(event.getPartialTicks());
            renderer.render(event.getPartialTicks(), EntityCaptureHooks.finishFrame(),
                    ItemCaptureHooks.finishFrame(), EffectCaptureHooks.finishFrame(), CloudCaptureHooks.finishFrame());
        } catch (RuntimeException | LinkageError ex) {
            failure = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            RemixForgeMod.LOG.error("RTX Remix rendering disabled after failure", ex);
            enabled = false;
            resetRequested = true;
            discardCaptures();
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
        if (!value) { discardCaptures(); }
        message(value ? "Remix prototype enabled. Use /mrtx off to restore vanilla rendering." : "Vanilla rendering enabled.");
    }

    void reload() { textureReloadRequested = true; }

    private static void discardCaptures() {
        EntityCaptureHooks.discard(); ItemCaptureHooks.discard(); EffectCaptureHooks.discard();
        CloudCaptureHooks.discard();
    }

    long renderedFrames() { return renderer.frameCount(); }
    boolean isEnabled() { return enabled; }
    int cloudTriangles() { return renderer.cloudTriangles(); }
    int customSkyTriangles() { return renderer.customSkyTriangles(); }
    int cloudResourceCount() { return renderer.cloudResourceCount(); }
    long cloudMeshUploads() { return renderer.cloudMeshUploads(); }
    long cloudRetainedBytes() { return renderer.cloudRetainedBytes(); }
    long cloudSkippedDraws() { return renderer.cloudSkippedDraws(); }
    int cloudMeshCount() { return renderer.cloudMeshCount(); }
    long cloudGeometryHash() { return renderer.cloudGeometryHash(); }
    long cloudPlacementHash() { return renderer.cloudPlacementHash(); }
    long cloudTextureSnapshots() { return renderer.cloudTextureSnapshots(); }
    boolean customSkyHandlerActive() { return renderer.customSkyHandlerActive(); }
    boolean customSkyUncaptured() { return renderer.customSkyUncaptured(); }
    int pbrSpriteCount() { return renderer.pbrSpriteCount(); }
    int pbrTextureCount() { return renderer.pbrTextureCount(); }
    long pbrUpdateCount() { return renderer.pbrUpdateCount(); }
    int pbrSkippedCount() { return renderer.pbrSkippedCount(); }
    String failureMessage() { return failure; }
    boolean entityCaptured(UUID id) { return renderer.entityCaptured(id); }
    int trianglePartCount(UUID id) { return renderer.trianglePartCount(id); }
    long entityTransformUpdates(UUID id) { return renderer.entityTransformUpdates(id); }
    int capturedEntityParts() { return renderer.capturedEntityParts(); }
    int entityResourceCount() { return renderer.entityResourceCount(); }
    long vboUploadSnapshots() { return EntityCaptureHooks.vboUploadSnapshots(); }
    long vboReadbackCount() { return EntityCaptureHooks.vboReadbackCount(); }
    long vboDrawCount() { return EntityCaptureHooks.vboDrawCount(); }
    long deferredVboDrawCount() { return EntityCaptureHooks.deferredVboDrawCount(); }
    long vboCacheBytes() { return EntityCaptureHooks.vboCacheBytes(); }
    int capturedTileParts() { return renderer.capturedTileParts(); }
    boolean tileCaptured(net.minecraft.util.math.BlockPos pos) { return renderer.tileCaptured(pos); }
    long tileTransformUpdates(net.minecraft.util.math.BlockPos pos) { return renderer.tileTransformUpdates(pos); }
    boolean itemCaptured(UUID id) { return renderer.itemCaptured(id); }
    long itemTransformUpdates(UUID id) { return renderer.itemTransformUpdates(id); }
    int capturedItemCount() { return renderer.capturedItemCount(); }
    int itemResourceCount() { return renderer.itemResourceCount(); }
    int particleQuads() { return renderer.particleQuads(); }
    int weatherQuads() { return renderer.weatherQuads(); }
    int effectResourceCount() { return renderer.effectResourceCount(); }
    long effectSkippedQuads() { return renderer.effectSkippedQuads(); }
    int effectBatchCount() { return renderer.effectBatchCount(); }
    long effectRetainedBytes() { return renderer.effectRetainedBytes(); }
    long particleGeometryHash() { return renderer.particleGeometryHash(); }
    long weatherGeometryHash() { return renderer.weatherGeometryHash(); }
    long particleFrameCount() { return renderer.particleFrameCount(); }
    long weatherFrameCount() { return renderer.weatherFrameCount(); }
    long textureReloadCount() { return renderer.textureReloadCount(); }
    boolean cameraMediumUnderwater() { return renderer.cameraMediumUnderwater(); }
    long cameraMediumHandle() { return renderer.cameraMediumHandle(); }
    int cameraMediumResourceCount() { return renderer.cameraMediumResourceCount(); }
    long cameraMediumSelectionChanges() { return renderer.cameraMediumSelectionChanges(); }
    double cameraMediumX() { return renderer.cameraMediumX(); }
    double cameraMediumY() { return renderer.cameraMediumY(); }
    double cameraMediumZ() { return renderer.cameraMediumZ(); }
    long effectTextureSnapshotCount() { return renderer.effectTextureSnapshotCount(); }
    int skyResourceCount() { return renderer.skyResourceCount(); }
    long skyTextureUpdates() { return renderer.skyTextureUpdates(); }
    int terrainRadius() { return renderer.terrainRadius(); }
    int terrainColumnCount() { return renderer.terrainColumnCount(); }
    int terrainCapturedColumnCount() { return renderer.terrainCapturedColumnCount(); }
    boolean terrainColumnCaptured(int x, int z) { return renderer.terrainColumnCaptured(x, z); }
    int terrainSectionCount() { return renderer.terrainSectionCount(); }
    int terrainPendingSectionCount() { return renderer.terrainPendingSectionCount(); }
    int terrainRetirementCount() { return renderer.terrainRetirementCount(); }
    int terrainResourceCount() { return renderer.terrainResourceCount(); }
    long terrainWaterVertexCount() { return renderer.terrainWaterVertexCount(); }
    long terrainLavaVertexCount() { return renderer.terrainLavaVertexCount(); }
    long terrainClearGlassVertexCount() { return renderer.terrainClearGlassVertexCount(); }
    long terrainStainedGlassVertexCount() { return renderer.terrainStainedGlassVertexCount(); }
    long atlasTextureUpdates() { return renderer.atlasTextureUpdates(); }
    long atlasSpriteUpdates(String name) { return renderer.atlasSpriteUpdates(name); }
    long atlasSpriteHash(String name) { return renderer.atlasSpriteHash(name); }
    int blockLightCount() { return renderer.blockLightCount(); }
    int blockLightResourceCount() { return renderer.blockLightResourceCount(); }
    boolean blockLightCaptured(net.minecraft.util.math.BlockPos pos) { return renderer.blockLightCaptured(pos); }
    long terrainPublishedSectionCount() { return renderer.terrainPublishedSectionCount(); }
    long terrainRetiredSectionCount() { return renderer.terrainRetiredSectionCount(); }
    long terrainDiscardedBuildCount() { return renderer.terrainDiscardedBuildCount(); }
    long terrainCapturedBytes() { return renderer.terrainCapturedBytes(); }
    boolean terrainCapacityLimited() { return renderer.terrainCapacityLimited(); }
    boolean terrainSectionReady(int x, int y, int z) { return renderer.terrainSectionReady(x, y, z); }
    long terrainSectionRevision(int x, int y, int z) { return renderer.terrainSectionRevision(x, y, z); }

    String status() {
        return "requested=" + enabled + "; " + renderer.status() + (failure == null ? "" : "; last error=" + failure);
    }

    void message(String text) {
        if (mc.player != null && !Boolean.getBoolean("rlcraftremix.smoke")) {
            mc.player.sendMessage(new TextComponentString("[Remix] " + text));
        }
        RemixForgeMod.LOG.info(text);
    }
}
