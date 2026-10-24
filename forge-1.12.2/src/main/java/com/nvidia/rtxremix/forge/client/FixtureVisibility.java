/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

/** Same-room tone-mapping comparison, including highlight clipping and restoration of user settings. */
final class FixtureVisibility implements AutoCloseable {
    private enum Phase { SETUP, ARRIVE, BASELINE, BRIGHTER, RESTORE, RETURN, DONE }
    private final Minecraft mc;
    private final ClientEvents events;
    private final IntegratedServer server;
    private final WorldServer world;
    private final WorldClient clientWorld;
    private final String save;
    private String originalMode, originalShadows;
    private final Layout layout;
    private final FixtureTerrain.Output output;
    private final UUID playerId;
    private final int originalView;
    private final boolean originalHud, originalFlying;
    private final double returnX, returnY, returnZ;
    private final float returnYaw, returnPitch;
    private final byte[] config = new byte[128];
    private Phase phase = Phase.SETUP;
    private Future<?> action;
    private BufferedImage baseline;
    private VisibilityImageComparison comparison;
    private long since = System.nanoTime(), progress = since, stableSince, stableFrame = -1;
    private boolean cleanupQueued, serverFlying, serverConfigured, restored;
    private boolean clientConfigured, serverSetupQueued, configChanged;

    FixtureVisibility(Minecraft mc, ClientEvents events, IntegratedServer server, WorldServer world,
            String save, Layout layout, double x, double y, double z, float yaw, float pitch,
            FixtureTerrain.Output output) {
        this.mc = mc; this.events = events; this.server = server; this.world = world; this.save = save;
        this.layout = layout; this.output = output; clientWorld = mc.world; playerId = mc.player.getUniqueID();
        returnX = x; returnY = y; returnZ = z; returnYaw = yaw; returnPitch = pitch;
        originalView = mc.gameSettings.thirdPersonView; originalHud = mc.gameSettings.hideGUI;
        originalFlying = mc.player.capabilities.isFlying;
    }

    private void setup() {
        originalMode = read(VisibilitySettings.MODE_KEY); originalShadows = read(VisibilitySettings.SHADOWS_KEY);
        // Setup runs only after the controller owns this fixture and can close it on failure.
        clientConfigured = true;
        mc.gameSettings.thirdPersonView = 0; mc.gameSettings.hideGUI = true;
        serverSetupQueued = true;
        action = server.callFromMainThread(() -> {
            requireWorld(); EntityPlayerMP player = player();
            serverFlying = player.capabilities.isFlying; serverConfigured = true;
            player.capabilities.isFlying = true; player.sendPlayerAbilities();
            player.connection.setPlayerLocation(cameraX(), cameraY(), cameraZ(), 180, 0); return null;
        });
        log("STARTED");
    }

    static Layout build(WorldServer world, int x, int y, int z) {
        FixtureCompatibility.requireSmokeSave(world.getMinecraftServer().getFolderName());
        if (!world.getMinecraftServer().isCallingFromMinecraftThread() || world.provider.getDimension() != 0)
            throw new IllegalStateException("Visibility layout requires the owned overworld server thread");
        for (int dx = -7; dx <= 7; dx++) for (int dz = -7; dz <= 7; dz++) for (int dy = 0; dy <= 6; dy++) {
            boolean shell = dy == 0 || dy == 6 || dx == -7 || dx == 7 || dz == -7;
            world.setBlockState(new BlockPos(x + dx, y + dy, z + dz), shell
                    ? (dx % 2 == 0 ? Blocks.QUARTZ_BLOCK : Blocks.STONEBRICK).getDefaultState() : Blocks.AIR.getDefaultState(), 3);
        }
        Layout layout = new Layout(x, y, z);
        world.setBlockState(layout.torch.down(), Blocks.QUARTZ_BLOCK.getDefaultState(), 3);
        world.setBlockState(layout.torch, Blocks.TORCH.getDefaultState(), 3);
        return layout;
    }

    boolean tick() throws Exception {
        if (mc.getIntegratedServer() != server || mc.world != clientWorld || mc.player == null
                || !events.isEnabled() || events.failureMessage() != null)
            throw new IllegalStateException("Visibility fixture renderer/world unavailable");
        if (System.nanoTime() - since > TimeUnit.SECONDS.toNanos(180)) {
            output.capture("visibility-timeout-" + phase.name().toLowerCase(Locale.ROOT), output.image());
            throw new IllegalStateException("Visibility fixture timed out: " + status());
        }
        if (System.nanoTime() - progress > TimeUnit.SECONDS.toNanos(10)) { log("PROGRESS"); progress = System.nanoTime(); }
        if (phase != Phase.SETUP && phase != Phase.RETURN && phase != Phase.DONE) holdPose();
        switch (phase) {
            case SETUP:
                setup(); transition(Phase.ARRIVE);
                break;
            case ARRIVE:
                if (complete()) {
                    configChanged = true;
                    events.fixtureWriteConfig(VisibilitySettings.MODE_KEY, "1");
                    events.fixtureWriteConfig(VisibilitySettings.SHADOWS_KEY, "2"); transition(Phase.BASELINE);
                }
                break;
            case BASELINE:
                if (settled(ready(2))) {
                    baseline = output.image(); output.capture("visibility-shadows-2", baseline); log("BASELINE_PASS");
                    events.fixtureWriteConfig(VisibilitySettings.SHADOWS_KEY, "2.5"); transition(Phase.BRIGHTER);
                }
                break;
            case BRIGHTER:
                if (settled(ready(2.5f))) {
                    BufferedImage adjusted = output.image(); output.capture("visibility-shadows-2_5", adjusted);
                    comparison = new VisibilityImageComparison(baseline, adjusted); log("COMPARISON");
                    if (!comparison.shadowsImproved() || !comparison.highlightsPreserved())
                        throw new IllegalStateException("Shadow visibility comparison needs review: " + comparison.describe());
                    restoreConfig(); transition(Phase.RESTORE);
                }
                break;
            case RESTORE:
                if (settled(read(VisibilitySettings.MODE_KEY).equals(originalMode)
                        && read(VisibilitySettings.SHADOWS_KEY).equals(originalShadows))) {
                    log("RESTORATION_PASS"); restoreClient(); action = restoreServer(); cleanupQueued = true; transition(Phase.RETURN);
                }
                break;
            case RETURN:
                if (complete()) { transition(Phase.DONE); log("PASS"); }
                break;
            default: break;
        }
        return phase == Phase.DONE;
    }

    private boolean ready(float shadows) {
        return read(VisibilitySettings.MODE_KEY).equals("1")
                && Math.abs(Float.parseFloat(read(VisibilitySettings.SHADOWS_KEY)) - shadows) < 0.0001f
                && events.blockLightCaptured(layout.torch) && events.terrainColumnCaptured(layout.x >> 4, layout.z >> 4)
                && events.terrainPendingSectionCount() == 0 && events.terrainRetirementCount() == 0;
    }
    private String read(String name) {
        int size = events.fixtureReadConfig(name, config);
        if (size <= 0 || size > config.length) throw new IllegalStateException("Cannot read visibility option " + name);
        return new String(config, 0, size - 1, StandardCharsets.UTF_8);
    }
    private void restoreConfig() {
        if (!configChanged || restored) return;
        events.fixtureWriteConfig(VisibilitySettings.MODE_KEY, originalMode);
        events.fixtureWriteConfig(VisibilitySettings.SHADOWS_KEY, originalShadows); restored = true;
    }
    private double cameraX() { return layout.x + 0.5; }
    private double cameraY() { return layout.y + 1.1; }
    private double cameraZ() { return layout.z + 4.5; }
    private void holdPose() {
        mc.player.setPositionAndRotation(cameraX(), cameraY(), cameraZ(), 180, 0);
        mc.player.prevPosX = mc.player.lastTickPosX = cameraX(); mc.player.prevPosY = mc.player.lastTickPosY = cameraY();
        mc.player.prevPosZ = mc.player.lastTickPosZ = cameraZ(); mc.player.prevRotationYaw = 180; mc.player.prevRotationPitch = 0;
        mc.player.motionX = mc.player.motionY = mc.player.motionZ = 0; mc.player.capabilities.isFlying = true;
    }
    private EntityPlayerMP player() {
        EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(playerId);
        if (player == null) throw new IllegalStateException("Visibility fixture player unavailable"); return player;
    }
    private void requireWorld() {
        FixtureCompatibility.requireSmokeSave(save);
        if (!server.isCallingFromMinecraftThread() || !save.equals(server.getFolderName()) || server.getWorld(0) != world)
            throw new IllegalStateException("Visibility fixture no longer owns server world");
    }
    private Future<?> restoreServer() {
        return server.callFromMainThread(() -> {
            requireWorld();
            if (serverConfigured) {
                EntityPlayerMP player = player(); player.capabilities.isFlying = serverFlying; player.sendPlayerAbilities();
                player.connection.setPlayerLocation(returnX, returnY, returnZ, returnYaw, returnPitch); serverConfigured = false;
            }
            return null;
        });
    }
    private void restoreClient() {
        if (!clientConfigured) return;
        mc.gameSettings.thirdPersonView = originalView; mc.gameSettings.hideGUI = originalHud;
        if (mc.world == clientWorld && mc.getIntegratedServer() == server
                && mc.player != null && playerId.equals(mc.player.getUniqueID())) mc.player.capabilities.isFlying = originalFlying;
        clientConfigured = false;
    }
    private boolean complete() throws Exception { if (!action.isDone()) return false; action.get(); return true; }
    private boolean settled(boolean ready) {
        if (!ready) { stableFrame = -1; return false; }
        if (stableFrame < 0) { stableFrame = events.renderedFrames(); stableSince = System.nanoTime(); }
        return events.renderedFrames() - stableFrame >= 120 && System.nanoTime() - stableSince >= TimeUnit.SECONDS.toNanos(3);
    }
    private void transition(Phase next) { phase = next; since = progress = System.nanoTime(); stableFrame = -1; log("PHASE"); }
    private void log(String name) { output.log("VISIBILITY_" + name, status()); }
    String status() {
        return "phase=" + phase + " localMode=" + read(VisibilitySettings.MODE_KEY) + " shadows=" + read(VisibilitySettings.SHADOWS_KEY)
                + " originalMode=" + originalMode + " originalShadows=" + originalShadows
                + " exposure=" + read("rtx.localtonemap.exposure") + " highlights=" + read("rtx.localtonemap.highlights")
                + " pendingTerrain=" + events.terrainPendingSectionCount() + (comparison == null ? "" : " " + comparison.describe());
    }
    @Override public void close() {
        restoreClient();
        try { restoreConfig(); }
        finally { if (serverSetupQueued && !cleanupQueued) { restoreServer(); cleanupQueued = true; } }
    }
    static final class Layout {
        final int x, y, z;
        final BlockPos torch;
        Layout(int x, int y, int z) { this.x = x; this.y = y; this.z = z; torch = new BlockPos(x - 5, y + 2, z - 5); }
    }
}
