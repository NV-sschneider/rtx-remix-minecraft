/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import com.nvidia.rtxremix.forge.overlay.OverlayHooks;
import com.nvidia.rtxremix.forge.terrain.VanillaTerrainDraws;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.world.WorldServer;
import org.lwjgl.BufferUtils;
import org.lwjgl.LWJGLException;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;

/** Pixel-exact recovery and invalidation checks in the disposable smoke save only. */
final class FixtureTerrainOptimization implements AutoCloseable {
    private static final long TIMEOUT = TimeUnit.SECONDS.toNanos(180), PROGRESS = TimeUnit.SECONDS.toNanos(10);
    private static final long MAX_IMAGE_BYTES = 64L * 1024 * 1024;
    private static final String INTENTIONAL_FAILURE = "intentional smoke terrain recovery";
    private enum Phase { ARRIVE, BASELINE, CAPTURE, FAILURE, VANILLA, REENABLE, RELOAD, RESIZE, RESTORE_SIZE, RETURN, DONE }
    private final Minecraft mc;
    private final ClientEvents events;
    private final IntegratedServer server;
    private final WorldServer world;
    private final WorldClient clientWorld;
    private final String save;
    private final UUID playerId;
    private final FixtureTerrain.Output output;
    private final double x, y, z;
    private final float yaw, pitch;
    private final boolean originalFlying;
    private final int originalWidth, originalHeight, originalX, originalY;
    private final boolean originalFullscreen;
    private final Runnable selectFrame = this::selectFrame;
    private final Runnable verifyRecovery = this::verifyRecovery;
    private final Runnable firstCache = this::firstCache;
    private Phase phase = Phase.ARRIVE;
    private Future<?> action;
    private long since = System.nanoTime(), progress = since, readyFrame = -1;
    private long selectedFrame, selectedCapture, restoredBaseline, failureBaseline, skippedBaseline, reloadBaseline;
    private long referenceHash, restoredHash;
    private ByteBuffer reference, restored;
    private int imageWidth, imageHeight, mismatchBytes, vanillaTicks, resizedWidth, resizedHeight;
    private boolean recoveryObserved, firstCacheObserved, resized, cleanupQueued, closed;
    private RuntimeException callbackFailure;

    FixtureTerrainOptimization(Minecraft mc, ClientEvents events, IntegratedServer server, WorldServer world,
            String saveName, double x, double y, double z, float yaw, float pitch, FixtureTerrain.Output output) {
        if (!Boolean.getBoolean("rlcraftremix.smoke") || !saveName.startsWith("rlcraft-remix-smoke-")
                || !saveName.equals(server.getFolderName()) || mc.getIntegratedServer() != server
                || world != server.getWorld(0) || mc.world == null || mc.player == null || !events.isEnabled()) {
            throw new IllegalArgumentException("Terrain optimization fixture requires its enabled owned smoke world");
        }
        this.mc = mc; this.events = events; this.server = server; this.world = world; save = saveName;
        clientWorld = mc.world; playerId = mc.player.getUniqueID(); this.output = output;
        this.x = x; this.y = y; this.z = z; this.yaw = yaw; this.pitch = pitch;
        originalFlying = mc.player.capabilities.isFlying;
        originalWidth = mc.displayWidth; originalHeight = mc.displayHeight;
        originalX = Display.getX(); originalY = Display.getY(); originalFullscreen = mc.isFullScreen();
        requireGameplay();
        if (!events.terrainSuppressionRequested() || !events.terrainSuppressionSupported()) {
            throw new IllegalStateException("Terrain optimization fixture needs the supported VBO contract and -Drlcraftremix.suppressSolidTerrain=true");
        }
        action = movePlayer(); log("STARTED");
    }

    boolean tick() throws Exception {
        requireClient(); requireGameplay();
        if (callbackFailure != null) throw callbackFailure;
        boolean deliberate = phase == Phase.FAILURE || phase == Phase.VANILLA;
        if ((!events.isEnabled() || events.failureMessage() != null)
                && !(deliberate && intentionalFailure())) {
            throw new IllegalStateException("Terrain optimization renderer unavailable: " + events.failureMessage());
        }
        if (System.nanoTime() - since >= TIMEOUT) {
            log("TIMEOUT"); throw new IllegalStateException("Terrain optimization fixture timed out: " + status());
        }
        if (System.nanoTime() - progress >= PROGRESS) { log("PROGRESS"); progress = System.nanoTime(); }
        if (phase != Phase.DONE) holdPose();
        switch (phase) {
            case ARRIVE:
                if (complete()) transition(Phase.BASELINE);
                break;
            case BASELINE:
                if (settled(cacheLive() && events.terrainSkippedDraws() > 0, 20)) {
                    imageWidth = mc.displayWidth; imageHeight = mc.displayHeight;
                    long bytes = (long) imageWidth * imageHeight * 4;
                    if (bytes <= 0 || bytes > MAX_IMAGE_BYTES) throw new IllegalStateException("Fixture image exceeds its 64 MiB readback bound");
                    reference = BufferUtils.createByteBuffer((int) bytes); restored = BufferUtils.createByteBuffer((int) bytes);
                    restoredBaseline = events.terrainFallbackFrames(); failureBaseline = events.terrainFallbackFailures();
                    transition(Phase.CAPTURE); events.fixtureAfterTerrainCache(selectFrame);
                }
                break;
            case CAPTURE:
                break; // The selected native copy invokes selectFrame before hand overlays/HUD.
            case FAILURE:
                if (events.renderedFrames() != selectedFrame) {
                    throw new IllegalStateException("The selected next replaced frame was not the injected failure");
                }
                if (recoveryObserved) {
                    if (events.isEnabled() || !intentionalFailure() || mismatchBytes != 0
                            || events.terrainFallbackFrames() != restoredBaseline + 1
                            || events.terrainFallbackFailures() != failureBaseline) {
                        throw new IllegalStateException("Terrain failure did not recover exactly once: " + status());
                    }
                    output.capture("terrain-fallback-reference", image(reference));
                    output.capture("terrain-fallback-recovered", image(restored));
                    log("PIXEL_RECOVERY_PASS"); transition(Phase.VANILLA);
                }
                break;
            case VANILLA:
                if (events.renderedFrames() != selectedFrame || events.terrainFallbackCaptures() != selectedCapture) {
                    throw new IllegalStateException("Disabled terrain rendering continued publishing native world frames");
                }
                if (emptyScenes() && events.terrainSkippedDraws() == 0 && events.terrainVanillaDraws() > 0) vanillaTicks++;
                else vanillaTicks = 0;
                if (vanillaTicks >= 5) {
                    log("VANILLA_RELEASE_PASS");
                    skippedBaseline = events.terrainSkippedDrawTotal();
                    transition(Phase.REENABLE); events.fixtureAfterTerrainCache(firstCache); events.setEnabled(true);
                }
                break;
            case REENABLE:
                if (settled(firstCacheObserved && cacheLive() && resumedSkips(), 20)) {
                    log("REENABLE_PASS"); reloadBaseline = events.textureReloadCount();
                    skippedBaseline = events.terrainSkippedDrawTotal(); transition(Phase.RELOAD);
                    events.fixtureAfterTerrainCache(firstCache); events.reload();
                }
                break;
            case RELOAD:
                if (settled(firstCacheObserved && events.textureReloadCount() > reloadBaseline && cacheLive() && resumedSkips(), 20)) {
                    log("RELOAD_PASS");
                    if (originalFullscreen) {
                        output.log("TERRAIN_OPT_RESIZE_OMITTED", "reason=fullscreen; HWND transitions are covered by the window fixture");
                        beginReturn();
                    } else {
                        resizedWidth = originalWidth > 960 ? originalWidth - 96 : originalWidth + 96;
                        resizedHeight = originalHeight > 640 ? originalHeight - 64 : originalHeight + 64;
                        skippedBaseline = events.terrainSkippedDrawTotal(); transition(Phase.RESIZE);
                        events.fixtureAfterTerrainCache(firstCache); resized = true; resize(resizedWidth, resizedHeight);
                    }
                }
                break;
            case RESIZE:
                if (settled(firstCacheObserved && dimensions(resizedWidth, resizedHeight) && cacheLive() && resumedSkips(), 20)) {
                    log("RESIZE_PASS"); skippedBaseline = events.terrainSkippedDrawTotal(); transition(Phase.RESTORE_SIZE);
                    events.fixtureAfterTerrainCache(firstCache); restoreWindow();
                }
                break;
            case RESTORE_SIZE:
                if (settled(firstCacheObserved && dimensions(originalWidth, originalHeight) && cacheLive() && resumedSkips(), 20)) {
                    log("SIZE_RESTORE_PASS"); beginReturn();
                }
                break;
            case RETURN:
                if (complete()) { mc.player.capabilities.isFlying = originalFlying; transition(Phase.DONE); log("PASS"); }
                break;
            default: break;
        }
        return phase == Phase.DONE;
    }

    private void selectFrame() {
        events.fixtureAfterTerrainCache(null);
        try {
            requireClient(); requireGameplay();
            if (phase != Phase.CAPTURE || VanillaTerrainDraws.skippedDrawCount() == 0) {
                throw new IllegalStateException("Selected cache did not replace any terrain draws");
            }
            read(reference); referenceHash = hash(reference);
            selectedFrame = events.renderedFrames(); selectedCapture = events.terrainFallbackCaptures();
            transition(Phase.FAILURE); events.fixtureAfterTerrainRecovery(verifyRecovery); events.fixtureFailNextReplacedFrame();
        } catch (RuntimeException failure) { callbackFailure = failure; }
    }

    private void verifyRecovery() {
        events.fixtureAfterTerrainRecovery(null);
        try {
            requireClient(); read(restored); restoredHash = hash(restored);
            mismatchBytes = 0;
            for (int i = 0; i < reference.capacity(); i++) if (reference.get(i) != restored.get(i)) mismatchBytes++;
            if (events.terrainSkippedDraws() == 0) throw new IllegalStateException("Recovery callback had no omitted terrain draws");
            recoveryObserved = true;
        } catch (RuntimeException failure) { callbackFailure = failure; }
    }

    /** No readback: prove each newly valid cache follows an unsuppressed frame. */
    private void firstCache() {
        events.fixtureAfterTerrainCache(null);
        if (VanillaTerrainDraws.skippedDrawCount() != 0) callbackFailure = new IllegalStateException("Terrain was skipped before the invalidated cache was rebuilt");
        else firstCacheObserved = true;
    }

    private boolean cacheLive() {
        return events.terrainFallbackResources() == 4 && events.terrainFallbackCaptures() > 0
                && events.terrainFallbackBytes() == (long) mc.displayWidth * mc.displayHeight * 8;
    }
    private boolean resumedSkips() { return events.terrainSkippedDraws() > 0 && events.terrainSkippedDrawTotal() > skippedBaseline; }
    private boolean emptyScenes() {
        return events.terrainFallbackResources() == 0 && events.terrainFallbackBytes() == 0
                && events.terrainResourceCount() == 0 && events.entityResourceCount() == 0 && events.itemResourceCount() == 0
                && events.effectResourceCount() == 0 && events.cloudResourceCount() == 0 && events.skyResourceCount() == 0
                && events.cameraMediumResourceCount() == 0 && events.heldLightCount() == 0;
    }
    private boolean intentionalFailure() { return events.failureMessage() != null && events.failureMessage().contains(INTENTIONAL_FAILURE); }
    private boolean dimensions(int width, int height) { return mc.displayWidth == width && mc.displayHeight == height && Display.getWidth() == width && Display.getHeight() == height; }
    private boolean settled(boolean ready, int frames) {
        if (!ready) { readyFrame = -1; return false; }
        if (readyFrame < 0) readyFrame = events.renderedFrames();
        return events.renderedFrames() - readyFrame >= frames;
    }
    private boolean complete() throws Exception { if (!action.isDone()) return false; action.get(); return true; }
    private void transition(Phase next) { phase = next; since = progress = System.nanoTime(); readyFrame = -1; firstCacheObserved = false; log("PHASE"); }
    private void beginReturn() { events.fixtureAfterTerrainCache(null); events.fixtureAfterTerrainRecovery(null); cleanupQueued = true; action = movePlayer(); transition(Phase.RETURN); }
    private void log(String event) { output.log("TERRAIN_OPT_" + event, status()); }
    String status() {
        return "phase=" + phase + " frames=" + events.renderedFrames() + " enabled=" + events.isEnabled()
                + " skipped=" + events.terrainSkippedDraws() + " vanilla=" + events.terrainVanillaDraws()
                + " skippedTotal=" + events.terrainSkippedDrawTotal() + " caches=" + events.terrainFallbackCaptures()
                + " recovered=" + events.terrainFallbackFrames() + " recoveryFailures=" + events.terrainFallbackFailures()
                + " cacheResources=" + events.terrainFallbackResources() + " cacheBytes=" + events.terrainFallbackBytes()
                + " referenceHash=" + Long.toHexString(referenceHash) + " restoredHash=" + Long.toHexString(restoredHash)
                + " mismatchBytes=" + mismatchBytes + " selectedFrame=" + selectedFrame + " firstCache=" + firstCacheObserved
                + " size=" + mc.displayWidth + "x" + mc.displayHeight + " reloads=" + events.textureReloadCount();
    }

    private void requireClient() {
        if (closed || !mc.isCallingFromMinecraftThread() || mc.world != clientWorld || mc.getIntegratedServer() != server
                || mc.player == null || !playerId.equals(mc.player.getUniqueID()) || !save.equals(server.getFolderName())) {
            throw new IllegalStateException("Terrain optimization fixture no longer owns the client world");
        }
    }
    private void requireGameplay() {
        if (mc.currentScreen != null || OverlayHooks.capturing() || !Display.isVisible() || !OpenGlHelper.useVbo()
                || mc.gameSettings.anaglyph || mc.entityRenderer.isShaderActive()) {
            throw new IllegalStateException("Terrain optimization fixture requires visible gameplay with VBOs, closed menus and no post-processing/anaglyph");
        }
    }
    private Future<?> movePlayer() {
        return server.callFromMainThread(() -> {
            if (!server.isCallingFromMinecraftThread() || !save.equals(server.getFolderName()) || server.getWorld(0) != world)
                throw new IllegalStateException("Terrain optimization fixture no longer owns its server world");
            EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(playerId);
            if (player == null) throw new IllegalStateException("Terrain optimization fixture player is absent");
            player.connection.setPlayerLocation(x, y, z, yaw, pitch); return null;
        });
    }
    private void holdPose() {
        mc.player.setPositionAndRotation(x, y, z, yaw, pitch);
        mc.player.prevPosX = mc.player.lastTickPosX = x; mc.player.prevPosY = mc.player.lastTickPosY = y;
        mc.player.prevPosZ = mc.player.lastTickPosZ = z; mc.player.prevRotationYaw = yaw; mc.player.prevRotationPitch = pitch;
        mc.player.motionX = mc.player.motionY = mc.player.motionZ = 0; mc.player.capabilities.isFlying = true;
    }
    private void resize(int width, int height) throws LWJGLException { Display.setDisplayMode(new DisplayMode(width, height)); mc.resize(width, height); }
    private void restoreWindow() throws LWJGLException {
        if (resized) { resize(originalWidth, originalHeight); Display.setLocation(originalX, originalY); resized = false; }
    }

    /** Only two selected-frame readbacks; preserve all pack/read-buffer state used by other mods. */
    private void read(ByteBuffer pixels) {
        if (mc.displayWidth != imageWidth || mc.displayHeight != imageHeight) throw new IllegalStateException("Framebuffer resized during recovery comparison");
        int framebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int pack = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        int alignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT), rowLength = GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH);
        int skipRows = GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS), skipPixels = GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS);
        int readBuffer = 0; boolean saved = false;
        while (GL11.glGetError() != GL11.GL_NO_ERROR) { }
        try {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, mc.getFramebuffer().framebufferObject);
            readBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER); saved = true;
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0); GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1); GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0); GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
            pixels.clear(); GL11.glReadPixels(0, 0, imageWidth, imageHeight, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            int error = GL11.glGetError(); if (error != GL11.GL_NO_ERROR) throw new IllegalStateException("Fixture framebuffer read failed: GL " + error);
        } finally {
            if (saved) GL11.glReadBuffer(readBuffer);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, framebuffer); GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pack);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, alignment); GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, rowLength);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, skipRows); GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, skipPixels);
        }
    }
    private static long hash(ByteBuffer pixels) { long hash = 0xcbf29ce484222325L; for (int i = 0; i < pixels.capacity(); i++) hash = (hash ^ (pixels.get(i) & 255)) * 0x100000001b3L; return hash; }
    private BufferedImage image(ByteBuffer pixels) {
        BufferedImage result = new BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_ARGB);
        for (int row = 0; row < imageHeight; row++) for (int col = 0; col < imageWidth; col++) {
            int i = (row * imageWidth + col) * 4;
            int argb = (pixels.get(i + 3) & 255) << 24 | (pixels.get(i) & 255) << 16 | (pixels.get(i + 1) & 255) << 8 | pixels.get(i + 2) & 255;
            result.setRGB(col, imageHeight - 1 - row, argb);
        }
        return result;
    }

    @Override public void close() {
        if (closed) return;
        events.fixtureCancelTerrainFailure();
        events.fixtureAfterTerrainCache(null); events.fixtureAfterTerrainRecovery(null);
        RuntimeException failure = null;
        try { restoreWindow(); } catch (LWJGLException error) { failure = new IllegalStateException("Unable to restore terrain fixture window", error); }
        if (mc.world == clientWorld && mc.getIntegratedServer() == server && mc.player != null && playerId.equals(mc.player.getUniqueID())) {
            holdPose(); mc.player.capabilities.isFlying = originalFlying;
            if (!cleanupQueued) { cleanupQueued = true; action = movePlayer(); }
            if (intentionalFailure() && !events.isEnabled()) events.setEnabled(true);
        }
        reference = restored = null;
        if (failure != null) throw failure;
        closed = true;
    }
}
