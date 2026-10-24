/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import java.nio.FloatBuffer;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.WorldServer;
import net.minecraftforge.client.IRenderHandler;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;

/** Cloud modes, movement, reload and an owned custom-sky handler in a disposable smoke save. */
final class FixtureClouds implements AutoCloseable {
    private static final long TIMEOUT = TimeUnit.SECONDS.toNanos(180);
    private static final long PROGRESS = TimeUnit.SECONDS.toNanos(10);
    private static final int SETTLE_FRAMES = 20;
    private enum Phase { ARRIVE, OFF, FAST, MOTION, FANCY, MOVE, MOVED, RELOAD, CLEARED,
        CUSTOM_SKY, CUSTOM_RESTORED, RETURN, DONE }
    private final Minecraft mc;
    private final ClientEvents events;
    private final IntegratedServer server;
    private final WorldServer world;
    private final WorldClient clientWorld;
    private final String saveName;
    private final UUID playerId;
    private final FixtureTerrain.Output output;
    private final int originalClouds, originalDistance;
    private final IRenderHandler originalSky;
    private final IRenderHandler customSky = new FixtureSky();
    private final double returnX, returnY, returnZ, cameraY;
    private final float returnYaw, returnPitch;
    private Phase phase = Phase.ARRIVE;
    private Future<?> action;
    private long since = System.nanoTime(), lastProgress = since, readyFrame = -1;
    private long geometryBaseline, placementBaseline, frameBaseline, reloadBaseline;
    private int fastTriangles;
    private boolean moved, closed;

    FixtureClouds(Minecraft mc, ClientEvents events, IntegratedServer server, WorldServer world,
            String saveName, double x, double y, double z, float yaw, float pitch, FixtureTerrain.Output output) {
        if (!saveName.startsWith("rlcraft-remix-smoke-") || !saveName.equals(server.getFolderName())
                || mc.getIntegratedServer() != server || mc.world == null || mc.player == null) {
            throw new IllegalArgumentException("Cloud fixture requires its dedicated smoke world");
        }
        this.mc = mc; this.events = events; this.server = server; this.world = world;
        clientWorld = mc.world; this.saveName = saveName; this.output = output;
        playerId = mc.player.getUniqueID();
        returnX = x; returnY = y; returnZ = z; returnYaw = yaw; returnPitch = pitch;
        cameraY = Math.max(16, Math.min(240, clientWorld.provider.getCloudHeight() - 24));
        originalClouds = mc.gameSettings.clouds; originalDistance = mc.gameSettings.renderDistanceChunks;
        originalSky = clientWorld.provider.getSkyRenderer();
        mc.gameSettings.clouds = 0;
        if (originalDistance < 4) { mc.gameSettings.renderDistanceChunks = 4; mc.renderGlobal.loadRenderers(); }
        action = server.callFromMainThread(() -> {
            requireWorld(); FixtureEnvironment.setSky(world, 6000L, false); movePlayer(false); return null;
        });
        log("STARTED");
    }

    boolean tick() throws Exception {
        if (closed || mc.world != clientWorld || mc.getIntegratedServer() != server
                || !events.isEnabled() || events.failureMessage() != null) {
            throw new IllegalStateException("Cloud fixture renderer/world unavailable");
        }
        if (System.nanoTime() - since >= TIMEOUT) {
            log("TIMEOUT"); capture("clouds-timeout-" + phase.name().toLowerCase(Locale.ROOT));
            throw new IllegalStateException("Cloud fixture timed out: " + status());
        }
        if (System.nanoTime() - lastProgress >= PROGRESS) { log("PROGRESS"); lastProgress = System.nanoTime(); }
        if (phase != Phase.RETURN && phase != Phase.DONE) holdPose(cameraX(moved), cameraY, cameraZ(), moved ? 165f : 180f, -16f);
        switch (phase) {
            case ARRIVE:
                if (complete()) { FixtureEnvironment.setSky(clientWorld, 6000L, false); transition(Phase.OFF); }
                break;
            case OFF:
                if (settled(emptyClouds())) {
                    capture("clouds-off"); log("OFF_PASS");
                    mc.gameSettings.clouds = 1; transition(Phase.FAST);
                }
                break;
            case FAST:
                if (settled(present() && events.cloudTextureSnapshots() > 0)) {
                    fastTriangles = events.cloudTriangles(); geometryBaseline = events.cloudGeometryHash();
                    frameBaseline = events.renderedFrames(); capture("clouds-fast"); log("FAST_PASS"); transition(Phase.MOTION);
                }
                break;
            case MOTION:
                if (settled(present() && events.renderedFrames() > frameBaseline + 40
                        && events.cloudGeometryHash() != geometryBaseline)) {
                    capture("clouds-fast-moving"); log("MOTION_PASS");
                    mc.gameSettings.clouds = 2; transition(Phase.FANCY);
                }
                break;
            case FANCY:
                if (settled(present() && events.cloudTriangles() > fastTriangles)) {
                    capture("clouds-fancy"); log("FANCY_PASS");
                    placementBaseline = events.cloudPlacementHash(); moved = true;
                    action = server.callFromMainThread(() -> { requireWorld(); movePlayer(true); return null; });
                    transition(Phase.MOVE);
                }
                break;
            case MOVE:
                if (complete()) transition(Phase.MOVED);
                break;
            case MOVED:
                if (settled(present() && Math.abs(mc.player.posX - cameraX(true)) < 0.1
                        && events.cloudPlacementHash() != placementBaseline)) {
                    capture("clouds-camera-moved"); log("CAMERA_PASS");
                    reloadBaseline = events.textureReloadCount(); events.reload(); transition(Phase.RELOAD);
                }
                break;
            case RELOAD:
                if (settled(present() && events.textureReloadCount() > reloadBaseline && events.cloudTextureSnapshots() > 0)) {
                    capture("clouds-reloaded"); log("RELOAD_PASS");
                    mc.gameSettings.clouds = 0; transition(Phase.CLEARED);
                }
                break;
            case CLEARED:
                if (settled(emptyClouds())) {
                    capture("clouds-cleared"); log("RETIREMENT_PASS");
                    clientWorld.provider.setSkyRenderer(customSky); transition(Phase.CUSTOM_SKY);
                }
                break;
            case CUSTOM_SKY:
                if (settled(emptyClouds() && events.customSkyHandlerActive() && !events.customSkyUncaptured()
                        && events.customSkyTriangles() >= 14 && events.cloudSkippedDraws() == 0)) {
                    capture("custom-sky-handler"); log("CUSTOM_SKY_PASS");
                    restoreSky(); transition(Phase.CUSTOM_RESTORED);
                }
                break;
            case CUSTOM_RESTORED:
                if (settled(emptyClouds() && clientWorld.provider.getSkyRenderer() == originalSky
                        && (originalSky != null || !events.customSkyHandlerActive() && events.customSkyTriangles() == 0))) {
                    capture("custom-sky-restored"); log("CUSTOM_SKY_RESTORED_PASS");
                    action = server.callFromMainThread(() -> { requireWorld(); restorePlayer(); return null; });
                    transition(Phase.RETURN);
                }
                break;
            case RETURN:
                if (complete()) {
                    holdPose(returnX, returnY, returnZ, returnYaw, returnPitch);
                    restoreSettings(); transition(Phase.DONE); log("PASS");
                }
                break;
            default: break;
        }
        return phase == Phase.DONE;
    }

    private boolean present() { return events.cloudTriangles() > 0 && events.cloudMeshCount() > 0 && events.cloudSkippedDraws() == 0; }
    private boolean emptyClouds() { return events.cloudTriangles() == 0 && events.cloudMeshCount() == 0; }
    private boolean settled(boolean ready) {
        if (!ready) { readyFrame = -1; return false; }
        if (readyFrame < 0) readyFrame = events.renderedFrames();
        return events.renderedFrames() - readyFrame >= SETTLE_FRAMES;
    }
    private boolean complete() throws Exception { if (!action.isDone()) return false; action.get(); return true; }
    private double cameraX(boolean displaced) { return returnX + (displaced ? 48 : 0); }
    private double cameraZ() { return returnZ; }
    private void movePlayer(boolean displaced) { player().connection.setPlayerLocation(cameraX(displaced), cameraY, cameraZ(), displaced ? 165f : 180f, -16f); }
    private void restorePlayer() { player().connection.setPlayerLocation(returnX, returnY, returnZ, returnYaw, returnPitch); }
    private EntityPlayerMP player() {
        EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(playerId);
        if (player == null) throw new IllegalStateException("Cloud fixture player is absent");
        return player;
    }
    private void requireWorld() {
        if (!server.isCallingFromMinecraftThread() || !saveName.equals(server.getFolderName()) || server.getWorld(0) != world)
            throw new IllegalStateException("Cloud fixture no longer owns its server world");
    }
    private void holdPose(double x, double y, double z, float yaw, float pitch) {
        mc.player.setPositionAndRotation(x, y, z, yaw, pitch);
        mc.player.prevPosX = mc.player.lastTickPosX = x; mc.player.prevPosY = mc.player.lastTickPosY = y;
        mc.player.prevPosZ = mc.player.lastTickPosZ = z;
        mc.player.prevRotationYaw = mc.player.rotationYawHead = mc.player.prevRotationYawHead = yaw;
        mc.player.prevRotationPitch = pitch;
        mc.player.motionX = mc.player.motionY = mc.player.motionZ = 0; mc.player.capabilities.isFlying = true;
    }
    private void restoreSky() { if (clientWorld.provider.getSkyRenderer() == customSky) clientWorld.provider.setSkyRenderer(originalSky); }
    private void restoreSettings() {
        mc.gameSettings.clouds = originalClouds;
        if (mc.gameSettings.renderDistanceChunks != originalDistance) {
            mc.gameSettings.renderDistanceChunks = originalDistance;
            if (mc.world == clientWorld) mc.renderGlobal.loadRenderers();
        }
    }
    private void transition(Phase next) { phase = next; since = lastProgress = System.nanoTime(); readyFrame = -1; log("PHASE"); }
    private void capture(String name) throws Exception { output.log("CLOUD_CAPTURE", status() + " file=" + output.capture(name, output.image())); }
    private void log(String event) { output.log("CLOUD_" + event, status()); }
    String status() {
        return "cloudPhase=" + phase + " mode=" + mc.gameSettings.clouds + " triangles=" + events.cloudTriangles()
                + " fastTriangles=" + fastTriangles + " meshes=" + events.cloudMeshCount()
                + " uploads=" + events.cloudMeshUploads() + " textureSnapshots=" + events.cloudTextureSnapshots()
                + " geometry=" + events.cloudGeometryHash() + " placement=" + events.cloudPlacementHash()
                + " skyTriangles=" + events.customSkyTriangles() + " skyHandler=" + events.customSkyHandlerActive()
                + " skyUncaptured=" + events.customSkyUncaptured() + " skipped=" + events.cloudSkippedDraws()
                + " bytes=" + events.cloudRetainedBytes() + " resources=" + events.cloudResourceCount()
                + " reloads=" + events.textureReloadCount() + " moved=" + moved;
    }

    /** Queue owned pose restoration before the controller queues its environment/rule cleanup. */
    @Override public void close() {
        if (closed) return;
        restoreSky(); restoreSettings();
        if (phase != Phase.DONE && mc.world == clientWorld && mc.getIntegratedServer() == server && mc.player != null
                && playerId.equals(mc.player.getUniqueID())) {
            holdPose(returnX, returnY, returnZ, returnYaw, returnPitch);
            server.callFromMainThread(() -> { requireWorld(); restorePlayer(); return null; });
        }
        closed = true;
    }

    /** Known fixed-function extension geometry; no provider replacement survives fixture cleanup. */
    private static final class FixtureSky extends IRenderHandler {
        private static final ResourceLocation SUN = new ResourceLocation("textures/environment/sun.png");
        private final FloatBuffer color = BufferUtils.createFloatBuffer(16);

        @Override public void render(float partialTicks, WorldClient world, Minecraft mc) {
            int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
            int texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            boolean textured = GL11.glIsEnabled(GL11.GL_TEXTURE_2D), blend = GL11.glIsEnabled(GL11.GL_BLEND);
            boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST), cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
            boolean depthWrite = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
            int src = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB), dst = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
            int srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA), dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
            color.clear(); GL11.glGetFloat(GL11.GL_CURRENT_COLOR, color);
            GlStateManager.pushMatrix();
            try {
                GlStateManager.disableDepth(); GlStateManager.depthMask(false); GlStateManager.disableCull();
                GlStateManager.enableBlend(); GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
                GlStateManager.disableTexture2D();
                Tessellator tessellator = Tessellator.getInstance(); BufferBuilder buffer = tessellator.getBuffer();
                buffer.begin(GL11.GL_TRIANGLE_FAN, DefaultVertexFormats.POSITION_COLOR);
                buffer.pos(0, 45, -100).color(255, 160, 32, 255).endVertex();
                for (int i = 0; i <= 12; i++) {
                    double angle = i * Math.PI * 2 / 12;
                    buffer.pos(Math.cos(angle) * 30, 45 + Math.sin(angle) * 30, -100).color(32, 180, 255, 220).endVertex();
                }
                tessellator.draw();
                GlStateManager.enableTexture2D(); mc.getTextureManager().bindTexture(SUN);
                GlStateManager.color(1, 1, 1, 1);
                buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
                buffer.pos(35, 30, -100).tex(0, 1).endVertex(); buffer.pos(65, 30, -100).tex(1, 1).endVertex();
                buffer.pos(65, 60, -100).tex(1, 0).endVertex(); buffer.pos(35, 60, -100).tex(0, 0).endVertex();
                tessellator.draw();
            } finally {
                GlStateManager.popMatrix(); GlStateManager.color(color.get(0), color.get(1), color.get(2), color.get(3));
                GlStateManager.bindTexture(texture);
                if (textured) GlStateManager.enableTexture2D(); else GlStateManager.disableTexture2D();
                if (blend) GlStateManager.enableBlend(); else GlStateManager.disableBlend();
                if (depth) GlStateManager.enableDepth(); else GlStateManager.disableDepth();
                if (cull) GlStateManager.enableCull(); else GlStateManager.disableCull();
                GlStateManager.depthMask(depthWrite); GlStateManager.tryBlendFuncSeparate(src, dst, srcAlpha, dstAlpha);
                GlStateManager.setActiveTexture(active);
            }
        }
    }
}
