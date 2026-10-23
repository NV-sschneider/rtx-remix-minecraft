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
    private SkyScene sky;
    private WorldClient world;
    private long hwnd;
    private long frames;
    private boolean windowClosing;
    private boolean initialized;
    private final PresentInfo present = new PresentInfo();

    PrototypeRenderer(Minecraft mc) { this.mc = mc; }

    void render(float partialTicks, List<CapturedEntityPart> capturedParts) {
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
            sky = new SkyScene(api);
        }
        terrain.update();
        entities.sync(capturedParts);
        CameraInfo captured = camera.capture(mc, partialTicks);
        api.setupCamera(captured);
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
        if (terrain != null) { terrain.refreshTextures(); }
        if (entities != null) {
            entities.close();
            entities = new EntityScene(api);
        }
        if (sky != null) {
            sky.close();
            sky = new SkyScene(api);
        }
    }
    long frameCount() { return api == null ? 0 : frames; }
    boolean entityCaptured(UUID id) { return entities != null && entities.contains(id); }
    long entityTransformUpdates(UUID id) { return entities == null ? 0 : entities.transformUpdates(id); }
    int capturedEntityParts() { return entities == null ? 0 : entities.partCount(); }
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
                + (terrain != null && terrain.isCapacityLimited() ? "; terrain capacity limit reached" : "")
                + "; entity parts=" + capturedEntityParts()
                + "; sky=" + (sky == null ? "released" : sky.status());
    }

    /** Toggle/world changes retain the runtime and its monotonically increasing GL timeline. */
    void releaseWorld() {
        EntityCaptureHooks.discard();
        releaseScene();
    }

    private void releaseScene() {
        try {
            if (terrain != null) { terrain.close(); }
        } finally {
            terrain = null;
            world = null;
            try {
                if (entities != null) { entities.close(); }
            } finally {
                entities = null;
                try {
                    if (sky != null) { sky.close(); }
                } finally { sky = null; }
            }
        }
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
