/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import com.nvidia.rtxremix.*;
import com.nvidia.rtxremix.forge.RemixForgeMod;
import com.nvidia.rtxremix.forge.entity.CapturedEntityPart;
import com.nvidia.rtxremix.forge.entity.EntityCaptureHooks;
import com.nvidia.rtxremix.forge.entity.EntityScene;
import com.nvidia.rtxremix.forge.item.CapturedItem;
import com.nvidia.rtxremix.forge.item.ItemCaptureHooks;
import com.nvidia.rtxremix.forge.item.ItemScene;
import com.nvidia.rtxremix.forge.effect.CapturedEffectBatch;
import com.nvidia.rtxremix.forge.effect.EffectCaptureHooks;
import com.nvidia.rtxremix.forge.effect.EffectScene;
import com.nvidia.rtxremix.forge.interop.LegacyGlInterop;
import com.nvidia.rtxremix.forge.sky.SkyScene;
import com.nvidia.rtxremix.forge.terrain.TerrainCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** All methods are called on Minecraft's render thread; native handles never cross threads. */
final class PrototypeRenderer {
    private final Minecraft mc;
    private final CameraCapture camera = new CameraCapture();
    private RemixAPI api;
    private LegacyGlInterop interop;
    private TerrainCapture terrain;
    private EntityScene entities;
    private ItemScene items;
    private EffectScene effects;
    private SkyScene sky;
    private CameraMedium medium;
    private WorldClient world;
    private long hwnd;
    private long frames;
    private long textureReloads;
    private boolean windowClosing;
    private boolean initialized;
    private final PresentInfo present = new PresentInfo();

    PrototypeRenderer(Minecraft mc) { this.mc = mc; }

    void render(float partialTicks, List<CapturedEntityPart> capturedParts,
            List<CapturedItem> capturedItems, List<CapturedEffectBatch> capturedEffects) {
        if (windowClosing) { return; }
        if (api == null) { initialize(); }
        if (!initialized) { throw new IllegalStateException("Native initialization failed; restart Minecraft before retrying."); }
        if (hwnd != LegacyGlInterop.currentWindowHandle()) {
            throw new IllegalStateException("Minecraft's window changed; restart the game before enabling Remix again.");
        }
        if (world != mc.world) {
            releaseScene();
            world = mc.world;
            terrain = new TerrainCapture(api, mc);
            entities = new EntityScene(api);
            items = new ItemScene(api);
            effects = new EffectScene(api);
            sky = new SkyScene(api);
            medium = new CameraMedium(api);
        }
        terrain.update();
        entities.sync(capturedParts);
        items.sync(capturedItems, terrain.getAtlasTexture());
        effects.sync(capturedEffects, terrain.getAtlasTexture());
        CameraInfo captured = camera.capture(mc, partialTicks);
        api.setupCamera(captured);
        medium.update(world, captured.parameterizedExt.position);
        terrain.updateLights(captured.parameterizedExt.position);
        sky.update(world, partialTicks, captured.parameterizedExt.position);
        api.submit(null);
        long done = interop.copyToFramebuffer(api.dxvkGetInteropInfo(), mc.getFramebuffer().framebufferObject,
                mc.displayWidth, mc.displayHeight, true);
        present.skipVulkanSwapchain = 1;
        present.hwndOverride = hwnd;
        present.clientDoneSemaphore_ValueToWait = done;
        api.present(present);
        if (++frames == 1) {
            RemixForgeMod.LOG.info("RTX Remix Forge: first native frame submitted and copied into Minecraft's framebuffer.");
        }
        if (Boolean.getBoolean("rlcraftremix.smoke") && (frames == 1 || frames == 180)) {
            CameraInfoParameterizedEXT c = captured.parameterizedExt;
            RemixForgeMod.LOG.info("RLC_RENDER frame={} sections={} pending={} camera=({},{},{}) forward=({},{},{}) fov={}",
                    frames, terrain.getSectionCount(), terrain.getPendingSectionCount(),
                    c.position.x, c.position.y, c.position.z, c.forward.x, c.forward.y, c.forward.z, c.fovYInDegrees);
        }
    }

    private void initialize() {
        File runtime = new File(System.getProperty("rlcraftremix.runtime",
                new File(mc.gameDir, "remix-sdk").getAbsolutePath()), "d3d9.dll");
        RemixAPI.redactCommandLineOptions(runtime.getAbsolutePath(), Arrays.asList(
                "--username", "--accessToken", "--uuid", "--xuid", "--clientId"));
        api = RemixAPI.load(runtime.getAbsolutePath());
        hwnd = LegacyGlInterop.currentWindowHandle();
        api.startup(StartupInfo.builder().hwnd(hwnd).forceNoVkSwapchain(true).interopImage(true).build());
        interop = new LegacyGlInterop(api.dxvkGetInteropInfo());
        initialized = true;
        frames = 0;
        RemixForgeMod.LOG.info("RTX Remix Forge: native renderer initialized (API {}.{}.{}, HWND {}).",
                RemixAPI.VERSION_MAJOR, RemixAPI.VERSION_MINOR, RemixAPI.VERSION_PATCH, Long.toHexString(hwnd));
    }

    void refreshTextures() {
        EntityCaptureHooks.clearVboCache();
        if (medium != null) { medium.close(); medium = null; }
        // These scenes borrow the terrain atlas. Release their materials first.
        closeAtlasBorrowers();
        if (terrain != null) { terrain.refreshTextures(); }
        if (terrain != null) {
            items = new ItemScene(api);
            effects = new EffectScene(api);
            medium = new CameraMedium(api);
        }
        if (entities != null) {
            entities.close();
            entities = new EntityScene(api);
        }
        if (sky != null) {
            sky.close();
            sky = new SkyScene(api);
        }
        textureReloads++;
    }
    long frameCount() { return api == null ? 0 : frames; }
    boolean entityCaptured(UUID id) { return entities != null && entities.contains(id); }
    int trianglePartCount(UUID id) { return entities == null ? 0 : entities.trianglePartCount(id); }
    long entityTransformUpdates(UUID id) { return entities == null ? 0 : entities.transformUpdates(id); }
    int capturedEntityParts() { return entities == null ? 0 : entities.partCount(); }
    int entityResourceCount() { return entities == null ? 0 : entities.resourceCount(); }
    int capturedTileParts() { return entities == null ? 0 : entities.tilePartCount(); }
    boolean tileCaptured(net.minecraft.util.math.BlockPos pos) { return entities != null && entities.containsTile(pos); }
    long tileTransformUpdates(net.minecraft.util.math.BlockPos pos) { return entities == null ? 0 : entities.tileTransformUpdates(pos); }
    boolean itemCaptured(UUID id) { return items != null && items.contains(id); }
    long itemTransformUpdates(UUID id) { return items == null ? 0 : items.transformUpdates(id); }
    int capturedItemCount() { return items == null ? 0 : items.count(); }
    int itemResourceCount() { return items == null ? 0 : items.resourceCount(); }
    int particleQuads() { return effects == null ? 0 : effects.particleQuads(); }
    int weatherQuads() { return effects == null ? 0 : effects.weatherQuads(); }
    int effectResourceCount() { return effects == null ? 0 : effects.resourceCount(); }
    long effectSkippedQuads() { return effects == null ? 0 : effects.skippedQuadCount(); }
    int effectBatchCount() { return effects == null ? 0 : effects.batchCount(); }
    long effectRetainedBytes() { return effects == null ? 0 : effects.retainedBytes(); }
    long particleGeometryHash() { return effects == null ? 0 : effects.particleGeometryHash(); }
    long weatherGeometryHash() { return effects == null ? 0 : effects.weatherGeometryHash(); }
    long particleFrameCount() { return effects == null ? 0 : effects.particleFrameCount(); }
    long weatherFrameCount() { return effects == null ? 0 : effects.weatherFrameCount(); }
    long textureReloadCount() { return textureReloads; }
    boolean cameraMediumUnderwater() { return medium != null && medium.isUnderwater(); }
    long cameraMediumHandle() { return medium == null ? 0 : medium.selectedMaterial(); }
    int cameraMediumResourceCount() { return medium == null ? 0 : medium.resourceCount(); }
    long cameraMediumSelectionChanges() { return medium == null ? 0 : medium.selectionChanges(); }
    double cameraMediumX() { return medium == null ? 0 : medium.cameraX(); }
    double cameraMediumY() { return medium == null ? 0 : medium.cameraY(); }
    double cameraMediumZ() { return medium == null ? 0 : medium.cameraZ(); }
    long effectTextureSnapshotCount() { return effects == null ? 0 : effects.textureSnapshotCount(); }
    int skyResourceCount() { return sky == null ? 0 : sky.resourceCount(); }
    long skyTextureUpdates() { return sky == null ? 0 : sky.textureUpdates(); }
    int terrainRadius() { return terrain == null ? 0 : terrain.getRadius(); }
    int terrainColumnCount() { return terrain == null ? 0 : terrain.getColumnCount(); }
    int terrainCapturedColumnCount() { return terrain == null ? 0 : terrain.getCapturedColumnCount(); }
    boolean terrainColumnCaptured(int x, int z) { return terrain != null && terrain.isColumnCaptured(x, z); }
    int terrainSectionCount() { return terrain == null ? 0 : terrain.getSectionCount(); }
    int terrainPendingSectionCount() { return terrain == null ? 0 : terrain.getPendingSectionCount(); }
    int terrainRetirementCount() { return terrain == null ? 0 : terrain.getRetirementCount(); }
    int terrainResourceCount() { return terrain == null ? 0 : terrain.getResourceCount(); }
    long terrainWaterVertexCount() { return terrain == null ? 0 : terrain.getWaterVertexCount(); }
    long terrainLavaVertexCount() { return terrain == null ? 0 : terrain.getLavaVertexCount(); }
    long terrainClearGlassVertexCount() { return terrain == null ? 0 : terrain.getClearGlassVertexCount(); }
    long terrainStainedGlassVertexCount() { return terrain == null ? 0 : terrain.getStainedGlassVertexCount(); }
    long atlasTextureUpdates() { return terrain == null ? 0 : terrain.getAtlasTextureUpdates(); }
    long atlasSpriteUpdates(String name) { return terrain == null ? 0 : terrain.getAtlasSpriteUpdates(name); }
    long atlasSpriteHash(String name) { return terrain == null ? 0 : terrain.getAtlasSpriteHash(name); }
    int blockLightCount() { return terrain == null ? 0 : terrain.getBlockLightCount(); }
    int blockLightResourceCount() { return terrain == null ? 0 : terrain.getBlockLightCount(); }
    boolean blockLightCaptured(net.minecraft.util.math.BlockPos pos) {
        return terrain != null && terrain.isBlockLightCaptured(pos);
    }
    long terrainPublishedSectionCount() { return terrain == null ? 0 : terrain.getPublishedSectionCount(); }
    long terrainRetiredSectionCount() { return terrain == null ? 0 : terrain.getRetiredSectionCount(); }
    long terrainDiscardedBuildCount() { return terrain == null ? 0 : terrain.getDiscardedBuildCount(); }
    long terrainCapturedBytes() { return terrain == null ? 0 : terrain.getCapturedBytes(); }
    boolean terrainCapacityLimited() { return terrain != null && terrain.isCapacityLimited(); }
    boolean terrainSectionReady(int x, int y, int z) {
        return terrain != null && terrain.isSectionReady(x, y, z);
    }
    long terrainSectionRevision(int x, int y, int z) {
        return terrain == null ? 0 : terrain.getSectionRevision(x, y, z);
    }
    String status() {
        return api == null ? "native renderer stopped" : "native renderer active; frames=" + frames
                + "; terrain radius=" + terrainRadius() + "; columns=" + terrainColumnCount()
                + "; sections=" + terrainSectionCount() + "; pending=" + terrainPendingSectionCount()
                + "; retiring=" + terrainRetirementCount() + "; terrain bytes=" + terrainCapturedBytes()
                + "; water vertices=" + terrainWaterVertexCount() + "; lava vertices=" + terrainLavaVertexCount()
                + "; atlas updates=" + atlasTextureUpdates() + "; block lights=" + blockLightCount()
                + (terrain != null && terrain.isCapacityLimited() ? "; terrain capacity limit reached" : "")
                + "; entity parts=" + capturedEntityParts()
                + "; tile parts=" + capturedTileParts()
                + "; items=" + capturedItemCount() + "; particle quads=" + particleQuads()
                + "; weather quads=" + weatherQuads()
                + "; omitted effect quads=" + effectSkippedQuads()
                + "; underwater=" + cameraMediumUnderwater()
                + "; glass vertices=" + terrainClearGlassVertexCount() + "/" + terrainStainedGlassVertexCount()
                + "; sky=" + (sky == null ? "released" : sky.status());
    }

    /** Toggle/world changes retain the runtime and its monotonically increasing GL timeline. */
    void releaseWorld() {
        EntityCaptureHooks.discard();
        ItemCaptureHooks.discard();
        EffectCaptureHooks.discard();
        releaseScene();
    }

    private void closeAtlasBorrowers() {
        Throwable failure = null;
        try { if (effects != null) { effects.close(); effects = null; } }
        catch (RuntimeException | LinkageError ex) { failure = ex; }
        try { if (items != null) { items.close(); items = null; } }
        catch (RuntimeException | LinkageError ex) { failure = append(failure, ex); }
        rethrow(failure);
    }

    private void releaseScene() {
        EntityCaptureHooks.clearVboCache();
        Throwable failure = null;
        try { if (medium != null) { medium.close(); medium = null; } }
        catch (RuntimeException | LinkageError ex) { failure = ex; }
        try { closeAtlasBorrowers(); }
        catch (RuntimeException | LinkageError ex) { failure = append(failure, ex); }
        // Keep the borrowed atlas alive if a consumer failed to release it.
        try { if (items == null && effects == null && terrain != null) { terrain.close(); terrain = null; } }
        catch (RuntimeException | LinkageError ex) { failure = append(failure, ex); }
        try { if (entities != null) { entities.close(); entities = null; } }
        catch (RuntimeException | LinkageError ex) { failure = append(failure, ex); }
        try { if (sky != null) { sky.close(); sky = null; } }
        catch (RuntimeException | LinkageError ex) { failure = append(failure, ex); }
        if (failure == null) world = null;
        rethrow(failure);
    }

    private static Throwable append(Throwable first, Throwable next) {
        if (first == null) return next;
        first.addSuppressed(next); return first;
    }
    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
        if (failure instanceof LinkageError) throw (LinkageError) failure;
    }

    /** Called immediately before LWJGL destroys the current OpenGL context. */
    void beforeDisplayDestroy() {
        windowClosing = true;
        try {
            releaseWorld();
        } finally {
            try {
                if (interop != null) { interop.close(); }
            } finally {
                interop = null;
            }
        }
    }

    /** Remix shutdown must run after the HWND has been destroyed. */
    void afterDisplayDestroy() {
        if (api != null) {
            try { api.shutdown(); } finally { api = null; }
        }
    }
}
