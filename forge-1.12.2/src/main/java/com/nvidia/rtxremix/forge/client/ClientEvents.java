/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import com.nvidia.rtxremix.forge.RemixForgeMod;
import com.nvidia.rtxremix.forge.entity.EntityCaptureHooks;
import com.nvidia.rtxremix.forge.entity.FirstPersonCapture;
import com.nvidia.rtxremix.forge.entity.FastTileCaptureHooks;
import com.nvidia.rtxremix.forge.item.ItemCaptureHooks;
import com.nvidia.rtxremix.forge.light.HeldLightState;
import com.nvidia.rtxremix.forge.effect.EffectCaptureHooks;
import com.nvidia.rtxremix.forge.cloud.CloudCaptureHooks;
import com.nvidia.rtxremix.forge.overlay.OverlayHooks;
import com.nvidia.rtxremix.forge.overlay.OverlayInputGate;
import com.nvidia.rtxremix.forge.overlay.PresentationHooks;
import com.nvidia.rtxremix.forge.frame.WorldFallbackHooks;
import com.nvidia.rtxremix.forge.hud.MobHudHooks;
import com.nvidia.rtxremix.forge.terrain.VanillaTerrainDraws;
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
    private final FrameSubmission submission = new FrameSubmission();
    private final CapturedParts capturedParts = new CapturedParts();
    private final KeyBinding toggle = new KeyBinding("key.rlcraftremix.toggle", Keyboard.KEY_NONE, "key.categories.rlcraftremix");
    private final KeyBinding heldLightToggle = new KeyBinding("key.rlcraftremix.heldLights", Keyboard.KEY_NONE, "key.categories.rlcraftremix");
    private boolean enabled;
    private boolean resetRequested;
    private boolean textureReloadRequested;
    private net.minecraft.client.multiplayer.WorldClient failureFixtureWorld, recoveryFixtureWorld, cacheFixtureWorld;
    private Runnable afterTerrainRecovery, afterTerrainCache;
    private String failure;

    public void register() {
        RendererLifecycle.attach(renderer);
        FirstPersonCapture.setSubmitCallback(this::submitCapturedFrame);
        WorldFallbackHooks.setBeforeHudCallback(this::finishWorld);
        OverlayHooks.setPresentCallback(this::finishOverlay);
        PresentationHooks.attach(new PresentationHooks.Backend() {
            @Override public boolean ownsSwapchain() { return renderer.ownsSwapchain(); }
            @Override public void presentFrame() {
                Throwable failure = renderer.presentFrame();
                if (failure != null) renderingFailed(failure);
            }
        });
        OverlayHooks.attach(new OverlayHooks.Backend() {
            @Override public boolean available() { return enabled && mc.world != null && renderer.menuAvailable(); }
            @Override public void write(String name, String value) { renderer.writeConfig(name, value); }
            @Override public int read(String name, byte[] value) { return renderer.readConfig(name, value); }
        });
        ClientRegistry.registerKeyBinding(toggle);
        ClientRegistry.registerKeyBinding(heldLightToggle);
        ClientCommandHandler.instance.registerCommand(new RemixCommand(this));
        ((IReloadableResourceManager) mc.getResourceManager()).registerReloadListener(manager -> reload());
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
        while (heldLightToggle.isPressed()) { HeldLightState.toggleLocalHeldLights(); }
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
            submission.clear();
            try {
                if (textureReloadRequested) renderer.invalidateTerrainFallback();
                // A color-only replacement cannot supply vanilla post-processing depth or two anaglyph passes.
                renderer.beginTerrainFrame(enabled && mc.world != null && Display.isVisible()
                        && mc.displayWidth > 0 && mc.displayHeight > 0 && !textureReloadRequested && !resetRequested
                        && !mc.gameSettings.anaglyph && !mc.entityRenderer.isShaderActive());
            } catch (RuntimeException | LinkageError ex) { renderingFailed(ex); }
            EntityCaptureHooks.beginFrame(enabled && mc.world != null);
            FastTileCaptureHooks.beginFrame(enabled && mc.world != null);
            ItemCaptureHooks.beginFrame(enabled && mc.world != null);
            EffectCaptureHooks.beginFrame(enabled && mc.world != null);
            CloudCaptureHooks.beginFrame(enabled && mc.world != null);
            com.nvidia.rtxremix.forge.fog.FogCaptureHooks.beginFrame(enabled && mc.world != null);
            MobHudHooks.beginFrame(enabled && mc.world != null && Display.isVisible());
        } else {
            try { renderer.finishTerrainFrame(false); renderer.finishFogFrame(); }
            catch (RuntimeException | LinkageError ex) { renderingFailed(ex); }
            submission.clear();
            discardCaptures();
            MobHudHooks.discard();
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
            renderer.prepareWorld(event.getPartialTicks());
            FirstPersonCapture.prepareWorld(event.getPartialTicks());
            submission.prepare(mc.world, event.getPartialTicks());
        } catch (RuntimeException | LinkageError ex) { renderingFailed(ex); }
    }

    private void submitCapturedFrame() {
        if (!submission.consume(mc.world)) return;
        if (!enabled || !Display.isVisible() || mc.displayWidth <= 0 || mc.displayHeight <= 0) {
            discardCaptures(); return;
        }
        try {
            if (failureFixtureWorld != null && VanillaTerrainDraws.skippedDrawCount() != 0) {
                net.minecraft.client.multiplayer.WorldClient expected = failureFixtureWorld;
                failureFixtureWorld = null;
                if (mc.world == expected) {
                    requireSmokeWorld();
                    throw new IllegalStateException("intentional smoke terrain recovery");
                }
            }
            long cacheCount = renderer.terrainFallbackCaptures();
            renderer.render(submission.partialTicks(),
                    capturedParts.set(EntityCaptureHooks.finishFrame(), FastTileCaptureHooks.finishFrame()),
                    ItemCaptureHooks.finishFrame(), EffectCaptureHooks.finishFrame(), CloudCaptureHooks.finishFrame());
            if (afterTerrainCache != null && mc.world == cacheFixtureWorld && renderer.terrainFallbackCaptures() != cacheCount) {
                afterTerrainCache.run();
            }
        } catch (RuntimeException | LinkageError ex) { renderingFailed(ex); }
    }

    private void finishOverlay() {
        try { renderer.finishOverlay(); }
        catch (RuntimeException | LinkageError ex) { renderingFailed(ex); }
    }

    private void finishWorld() {
        RuntimeException gateFailure = VanillaTerrainDraws.failure();
        if (gateFailure != null) renderingFailed(gateFailure);
        try {
            if (renderer.finishTerrainFrame(true) && afterTerrainRecovery != null && mc.world == recoveryFixtureWorld) {
                afterTerrainRecovery.run();
            }
            MobHudHooks.composite();
        } catch (RuntimeException | LinkageError ex) { renderingFailed(ex); }
    }

    private void renderingFailed(Throwable ex) {
        submission.clear();
        // Keep skipped-draw counts and the cached image alive until the independent pre-HUD hook.
        VanillaTerrainDraws.endFrame();
        failure = ex.getClass().getSimpleName() + ": " + ex.getMessage();
        RemixForgeMod.LOG.error("RTX Remix rendering disabled after failure", ex);
        OverlayHooks.releaseSession();
        enabled = false;
        resetRequested = true;
        discardCaptures();
        message("Renderer stopped: " + failure + ". See logs/latest.log.");
    }

    @SubscribeEvent
    public void worldUnload(WorldEvent.Unload event) {
        if (event.getWorld().isRemote) {
            if (failureFixtureWorld == event.getWorld()) failureFixtureWorld = null;
            if (cacheFixtureWorld == event.getWorld()) { cacheFixtureWorld = null; afterTerrainCache = null; }
            if (recoveryFixtureWorld == event.getWorld()) { recoveryFixtureWorld = null; afterTerrainRecovery = null; }
            mc.addScheduledTask(() -> renderer.releaseWorld(event.getWorld()));
        }
    }

    void setEnabled(boolean value) {
        if (!value) OverlayHooks.releaseSession();
        enabled = value;
        failure = null;
        if (!value) { failureFixtureWorld = null; submission.clear(); VanillaTerrainDraws.endFrame(); discardCaptures(); }
        message(value ? "Remix prototype enabled. Use /mrtx off to restore vanilla rendering." : "Vanilla rendering enabled.");
    }

    void reload() {
        textureReloadRequested = true;
        if (mc.isCallingFromMinecraftThread()) renderer.invalidateTerrainFallback();
    }
    void openSettings() {
        if (!OverlayHooks.requestMenu(OverlayInputGate.Menu.GRAPHICS_SETTINGS)) {
            message("Enable Remix in a world before opening its graphics settings.");
        }
    }

    int fixtureReadConfig(String name, byte[] value) { return renderer.readConfig(name, value); }
    void fixtureWriteConfig(String name, String value) { renderer.writeConfig(name, value); }
    void setShadowVisibility(float value) throws java.io.IOException { renderer.setShadowVisibility(value); }
    void resetShadowVisibility() throws java.io.IOException { renderer.resetShadowVisibility(); }
    String shadowVisibilityStatus() { return renderer.shadowVisibilityStatus(); }

    void fixtureFailNextReplacedFrame() { requireSmokeWorld(); failureFixtureWorld = mc.world; }
    void fixtureCancelTerrainFailure() { failureFixtureWorld = null; }
    void fixtureAfterTerrainRecovery(Runnable callback) {
        if (callback != null) requireSmokeWorld();
        afterTerrainRecovery = callback; recoveryFixtureWorld = callback == null ? null : mc.world;
    }
    void fixtureAfterTerrainCache(Runnable callback) {
        if (callback != null) requireSmokeWorld();
        afterTerrainCache = callback; cacheFixtureWorld = callback == null ? null : mc.world;
    }
    private void requireSmokeWorld() {
        if (!Boolean.getBoolean("rlcraftremix.smoke") || mc.world == null || mc.getIntegratedServer() == null
                || !mc.getIntegratedServer().getFolderName().startsWith("rlcraft-remix-smoke-")) {
            throw new IllegalStateException("Terrain recovery injection requires an owned integrated smoke world");
        }
    }

    private static void discardCaptures() {
        EntityCaptureHooks.discard(); ItemCaptureHooks.discard(); EffectCaptureHooks.discard();
        FastTileCaptureHooks.discard();
        CloudCaptureHooks.discard();
        com.nvidia.rtxremix.forge.fog.FogCaptureHooks.discard();
    }

    long renderedFrames() { return renderer.frameCount(); }
    long windowChanges() { return renderer.windowChanges(); }
    long fogAppliedFrames() { return renderer.fogAppliedFrames(); }
    long fogMissingDepthFrames() { return renderer.fogMissingDepthFrames(); }
    int fogHistorySamples() { return renderer.fogHistorySamples(); }
    long fogHistoryResets() { return renderer.fogHistoryResets(); }
    float fogHistoryWeight() { return renderer.fogHistoryWeight(); }
    String fogHistoryDiagnostics() { return renderer.fogHistoryDiagnostics(); }
    com.nvidia.rtxremix.forge.fog.FogState fogState() { return renderer.fogState(); }
    boolean terrainSuppressionRequested() { return renderer.terrainSuppressionRequested(); }
    boolean terrainSuppressionSupported() { return renderer.terrainSuppressionSupported(); }
    int terrainSkippedDraws() { return renderer.terrainSkippedDraws(); }
    int terrainVanillaDraws() { return renderer.terrainVanillaDraws(); }
    long terrainSkippedDrawTotal() { return renderer.terrainSkippedDrawTotal(); }
    long terrainFallbackFrames() { return renderer.terrainFallbackFrames(); }
    long terrainFallbackFailures() { return renderer.terrainFallbackFailures(); }
    long terrainFallbackCaptures() { return renderer.terrainFallbackCaptures(); }
    int terrainFallbackResources() { return renderer.terrainFallbackResources(); }
    long terrainFallbackBytes() { return renderer.terrainFallbackBytes(); }
    int heldLightCount() { return renderer.heldLightCount(); }
    int localHeldLightCount() { return renderer.localHeldLightCount(); }
    boolean heldLightCaptured(UUID id, int hand) { return renderer.heldLightCaptured(id, hand); }
    long heldLightUpdates() { return renderer.heldLightUpdates(); }
    double heldLightLocalDistance(UUID id, int hand) { return renderer.heldLightLocalDistance(id, hand); }
    int entityCategoryCount(int flags) { return renderer.entityCategoryCount(flags); }
    int itemCategoryCount(int flags) { return renderer.itemCategoryCount(flags); }
    int tileTrianglePartCount(net.minecraft.util.math.BlockPos pos) { return renderer.tileTrianglePartCount(pos); }
    long tileGeometryUpdates(net.minecraft.util.math.BlockPos pos) { return renderer.tileGeometryUpdates(pos); }
    boolean tileTextureCurrent(net.minecraft.util.math.BlockPos pos, int glId) { return renderer.tileTextureCurrent(pos, glId); }
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
    boolean betterFoliageActive() { return renderer.betterFoliageActive(); }
    long betterFoliageVertexCount() { return renderer.betterFoliageVertexCount(); }
    long betterFoliageCaptureCount() { return renderer.betterFoliageCaptureCount(); }
    long sectionBetterFoliageVertices(int x, int y, int z) { return renderer.sectionBetterFoliageVertices(x, y, z); }
    long sectionGeometryHash(int x, int y, int z) { return renderer.sectionGeometryHash(x, y, z); }
    int pbrTextureCount() { return renderer.pbrTextureCount(); }
    long pbrUpdateCount() { return renderer.pbrUpdateCount(); }
    int pbrSkippedCount() { return renderer.pbrSkippedCount(); }
    String failureMessage() { return failure; }
    boolean entityCaptured(UUID id) { return renderer.entityCaptured(id); }
    int trianglePartCount(UUID id) { return renderer.trianglePartCount(id); }
    long entityTransformUpdates(UUID id) { return renderer.entityTransformUpdates(id); }
    int entityEmissivePartCount(UUID id) { return renderer.entityEmissivePartCount(id); }
    long entityTextureUpdates() { return renderer.entityTextureUpdates(); }
    long entityTexturePatches() { return renderer.entityTexturePatches(); }
    long entityTexturePatchBytes() { return renderer.entityTexturePatchBytes(); }
    long droppedEntityTextureRevisions() { return renderer.droppedEntityTextureRevisions(); }
    int dirtyEntityTextures() { return renderer.dirtyEntityTextures(); }
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
    int damageQuads() { return renderer.damageQuads(); }
    long damageGeometryHash() { return renderer.damageGeometryHash(); }
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
    int effectAdditivePrimitives() { return renderer.effectAdditivePrimitives(); }
    int effectTrianglePrimitives() { return renderer.effectTrianglePrimitives(); }
    long effectTextureUpdateCount() { return renderer.effectTextureUpdateCount(); }
    long effectTexturePatchCount() { return renderer.effectTexturePatchCount(); }
    long effectTextureDroppedUpdateCount() { return renderer.effectTextureDroppedUpdateCount(); }
    int effectDirtyTextureCount() { return renderer.effectDirtyTextureCount(); }
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
    long terrainMeshBudgetBytes() { return renderer.terrainMeshBudgetBytes(); }
    int terrainDeferredSectionCount() { return renderer.terrainDeferredSectionCount(); }
    int terrainDiscoveryCycleFrames() { return renderer.terrainDiscoveryCycleFrames(); }
    String terrainPressureDiagnostics() { return renderer.terrainPressureDiagnostics(); }
    boolean itemOrdinaryLighting(UUID id) { return renderer.itemOrdinaryLighting(id); }
    long terrainOverlayVertexCount() { return renderer.terrainOverlayVertexCount(); }
    long terrainSectionOverlayVertices(int x, int y, int z) { return renderer.terrainSectionOverlayVertices(x, y, z); }
    long terrainEvictedSectionCount() { return renderer.terrainEvictedSectionCount(); }
    long terrainInvalidationCount() { return renderer.terrainInvalidationCount(); }
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
