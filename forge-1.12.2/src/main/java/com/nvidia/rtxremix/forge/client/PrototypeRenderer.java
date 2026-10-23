/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import com.nvidia.rtxremix.*;
import com.nvidia.rtxremix.forge.RemixForgeMod;
import com.nvidia.rtxremix.forge.interop.LegacyGlInterop;
import com.nvidia.rtxremix.forge.terrain.TerrainCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;

import java.io.File;
import java.util.Arrays;

/** All methods are called on Minecraft's render thread; native handles never cross threads. */
final class PrototypeRenderer {
    private final Minecraft mc;
    private final CameraCapture camera = new CameraCapture();
    private RemixAPI api;
    private LegacyGlInterop interop;
    private TerrainCapture terrain;
    private WorldClient world;
    private long sun;
    private long hwnd;
    private long frames;
    private boolean windowClosing;
    private boolean initialized;
    private final LightInfo sunlight = LightInfo.builder()
            .hash(0x524c43524146544cL).radiance(3.0f, 2.8f, 2.5f)
            .distantExt(LightInfoDistantEXT.builder().direction(0.43643578f, -0.87287156f, 0.21821789f).build()).build();
    private final PresentInfo present = new PresentInfo();

    PrototypeRenderer(Minecraft mc) { this.mc = mc; }

    void render(float partialTicks) {
        if (windowClosing) { return; }
        if (api == null) { initialize(); }
        if (!initialized) { throw new IllegalStateException("Native initialization failed; restart Minecraft before retrying."); }
        if (hwnd != LegacyGlInterop.currentWindowHandle()) {
            throw new IllegalStateException("Minecraft's window changed; restart the game before enabling Remix again.");
        }
        if (world != mc.world) {
            if (terrain != null) { terrain.close(); }
            world = mc.world;
            terrain = new TerrainCapture(api, mc);
        }
        terrain.update();
        CameraInfo captured = camera.capture(mc, partialTicks);
        api.setupCamera(captured);
        api.drawLightInstance(sun);
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
        sun = api.createLight(sunlight);
        initialized = true;
        frames = 0;
        RemixForgeMod.LOG.info("RTX Remix Forge: native renderer initialized (API {}.{}.{}, HWND {}).",
                RemixAPI.VERSION_MAJOR, RemixAPI.VERSION_MINOR, RemixAPI.VERSION_PATCH, Long.toHexString(hwnd));
    }

    void refreshTextures() { if (terrain != null) { terrain.refreshTextures(); } }
    long frameCount() { return api == null ? 0 : frames; }
    String status() {
        return api == null ? "native renderer stopped" : "native renderer active; frames=" + frames
                + "; sections=" + (terrain == null ? 0 : terrain.getSectionCount())
                + "; pending=" + (terrain == null ? 0 : terrain.getPendingSectionCount());
    }

    /** Toggle/world changes retain the runtime and its monotonically increasing GL timeline. */
    void releaseWorld() {
        try {
            if (terrain != null) { terrain.close(); }
        } finally {
            terrain = null;
            world = null;
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
                if (api != null && sun != 0) { api.destroyLight(sun); }
                sun = 0;
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
