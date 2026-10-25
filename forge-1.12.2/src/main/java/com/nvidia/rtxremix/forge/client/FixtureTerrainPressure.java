/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

/** Nearby edits, removals and travel must progress while distant terrain is deferred. */
final class FixtureTerrainPressure implements AutoCloseable {
    private enum Phase { ARRIVE, PRESSURE, MUTATING, MUTATED, REMOVING, REMOVED, STABILITY, RETURN, DONE }
    private final Minecraft mc;
    private final ClientEvents events;
    private final IntegratedServer server;
    private final WorldServer world;
    private final WorldClient clientWorld;
    private final String save;
    private final UUID playerId;
    private final FixtureTerrain.Output output;
    private final double returnX, returnY, returnZ;
    private final float returnYaw, returnPitch;
    private final boolean originalFlying;
    private Phase phase = Phase.ARRIVE;
    private Future<?> action;
    private Position position;
    private long since = System.nanoTime(), progress = since, readyFrame = -1;
    private long revision, evictions, stablePublications, stableInvalidations;
    private GuiScreen stabilityScreen;
    private boolean traveled, cleanupQueued;

    FixtureTerrainPressure(Minecraft mc, ClientEvents events, IntegratedServer server, WorldServer world,
            String save, double x, double y, double z, float yaw, float pitch, FixtureTerrain.Output output) {
        FixtureCompatibility.requireSmokeSave(save);
        this.mc = mc; this.events = events; this.server = server; this.world = world; this.save = save;
        this.output = output; clientWorld = mc.world; playerId = mc.player.getUniqueID();
        returnX = x; returnY = y; returnZ = z; returnYaw = yaw; returnPitch = pitch;
        originalFlying = mc.player.capabilities.isFlying;
        action = prepare(0); log("STARTED");
    }

    boolean tick() throws Exception {
        if (mc.world != clientWorld || mc.getIntegratedServer() != server || mc.player == null
                || !events.isEnabled() || events.failureMessage() != null)
            throw new IllegalStateException("Terrain pressure fixture renderer/world unavailable");
        if (events.terrainCapturedBytes() > events.terrainMeshBudgetBytes())
            throw new IllegalStateException("Terrain pressure exceeded its native mesh budget");
        long timeout = phase == Phase.STABILITY ? TimeUnit.MINUTES.toNanos(30) : TimeUnit.SECONDS.toNanos(300);
        if (System.nanoTime() - since > timeout) {
            capture("terrain-pressure-timeout");
            throw new IllegalStateException("Terrain pressure fixture timed out: " + status());
        }
        if (System.nanoTime() - progress > TimeUnit.SECONDS.toNanos(10)) { log("PROGRESS"); progress = System.nanoTime(); }
        if (position != null && phase != Phase.RETURN && phase != Phase.DONE) holdPose();
        switch (phase) {
            case ARRIVE:
                if (action.isDone()) { position = (Position) action.get(); transition(Phase.PRESSURE); }
                break;
            case PRESSURE:
                if (settled(events.terrainDeferredSectionCount() > 0 && ready(Blocks.GOLD_BLOCK), 30)) {
                    capture(traveled ? "terrain-pressure-arrival" : "terrain-pressure-filled"); log("CAPACITY_PASS");
                    baseline(); action = replace(Blocks.DIAMOND_BLOCK); transition(Phase.MUTATING);
                }
                break;
            case MUTATING:
                if (complete()) transition(Phase.MUTATED);
                break;
            case MUTATED:
                if (settled(changed(Blocks.DIAMOND_BLOCK) && events.terrainRetirementCount() == 0, 20)) {
                    capture("terrain-pressure-edited-" + traveled); log("EDIT_PASS");
                    baseline(); action = replace(Blocks.AIR); transition(Phase.REMOVING);
                }
                break;
            case REMOVING:
                if (complete()) transition(Phase.REMOVED);
                break;
            case REMOVED:
                if (settled(changed(Blocks.AIR) && events.terrainRetirementCount() == 0, 20)) {
                    capture("terrain-pressure-removed-" + traveled); log("REMOVAL_PASS");
                    if (!traveled) {
                        traveled = true; action = prepare((FixtureTerrain.RADIUS + 2) * 16); transition(Phase.ARRIVE);
                    } else {
                        evictions = events.terrainEvictedSectionCount(); stablePublications = events.terrainPublishedSectionCount();
                        stableInvalidations = events.terrainInvalidationCount();
                        if (mc.currentScreen != null) throw new IllegalStateException("Cannot pause pressure fixture over another screen");
                        // Natural worlds keep scheduling block changes even with random ticks disabled.
                        // Isolate renderer convergence only after live edits, removals and travel passed.
                        stabilityScreen = new GuiScreen() {
                            @Override public boolean doesGuiPauseGame() { return true; }
                            @Override public void drawScreen(int mouseX, int mouseY, float partialTicks) { }
                        };
                        mc.displayGuiScreen(stabilityScreen);
                        transition(Phase.STABILITY);
                    }
                }
                break;
            case STABILITY:
                if (mc.currentScreen != stabilityScreen) throw new IllegalStateException("Pressure stability screen was replaced");
                if (events.terrainEvictedSectionCount() != evictions
                        || events.terrainPublishedSectionCount() != stablePublications
                        || events.terrainInvalidationCount() != stableInvalidations) {
                    evictions = events.terrainEvictedSectionCount(); stablePublications = events.terrainPublishedSectionCount(); readyFrame = -1;
                    stableInvalidations = events.terrainInvalidationCount();
                }
                if (settled(mc.isGamePaused() && ready(Blocks.AIR) && events.terrainDeferredSectionCount() > 0
                        && events.terrainPendingSectionCount() == 0 && events.terrainRetirementCount() == 0,
                        Math.max(120, 2 * events.terrainDiscoveryCycleFrames()))) {
                    log("NO_CHURN_PASS"); action = restore(); cleanupQueued = true; transition(Phase.RETURN);
                }
                break;
            case RETURN:
                if (complete()) { transition(Phase.DONE); log("PASS"); }
                break;
            default: break;
        }
        return phase == Phase.DONE;
    }

    private Future<Position> prepare(int offset) {
        return server.callFromMainThread(() -> {
            requireWorld();
            int x = (((int) Math.floor(returnX) + offset) >> 4) * 16 + 8;
            int y = Math.max(8, Math.min(232, ((int) Math.floor(returnY) >> 4) * 16 + 8));
            int z = ((int) Math.floor(returnZ) >> 4) * 16 + 8;
            for (int dx = -6; dx <= 6; dx++) for (int dz = -6; dz <= 12; dz++) {
                world.setBlockState(new BlockPos(x + dx, y - 1, z + dz), Blocks.STONE.getDefaultState(), 3);
                for (int dy = 0; dy <= 9; dy++) world.setBlockToAir(new BlockPos(x + dx, y + dy, z + dz));
            }
            BlockPos marker = new BlockPos(x, y, z);
            replaceCube(marker, Blocks.GOLD_BLOCK);
            Position next = new Position(marker, x + 0.5, y + 4, z + 10.5);
            EntityPlayerMP player = player(); player.capabilities.isFlying = true; player.sendPlayerAbilities();
            player.connection.setPlayerLocation(next.x, next.y, next.z, 180, 16);
            return next;
        });
    }
    private Future<?> replace(Block block) {
        BlockPos marker = position.marker;
        return server.callFromMainThread(() -> { requireWorld(); replaceCube(marker, block); return null; });
    }
    private void replaceCube(BlockPos marker, Block block) {
        for (int dy = 0; dy < 3; dy++) for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++)
            world.setBlockState(marker.add(dx, dy, dz), block.getDefaultState(), 3);
    }
    private boolean ready(Block block) {
        BlockPos pos = position.marker;
        for (int dy = 0; dy < 3; dy++) for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++)
            if (clientWorld.getBlockState(pos.add(dx, dy, dz)).getBlock() != block) return false;
        return events.terrainSectionReady(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
    }
    private boolean changed(Block block) { return ready(block) && revision() > revision; }
    private void baseline() { revision = revision(); }
    private long revision() {
        BlockPos pos = position.marker;
        return events.terrainSectionRevision(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
    }
    private void holdPose() {
        mc.player.setPositionAndRotation(position.x, position.y, position.z, 180, 16);
        mc.player.prevPosX = mc.player.lastTickPosX = position.x;
        mc.player.prevPosY = mc.player.lastTickPosY = position.y;
        mc.player.prevPosZ = mc.player.lastTickPosZ = position.z;
        mc.player.prevRotationYaw = 180; mc.player.prevRotationPitch = 16;
        mc.player.motionX = mc.player.motionY = mc.player.motionZ = 0; mc.player.capabilities.isFlying = true;
    }
    private boolean complete() throws Exception { if (!action.isDone()) return false; action.get(); return true; }
    private boolean settled(boolean ready, int frames) {
        if (!ready) { readyFrame = -1; return false; }
        if (readyFrame < 0) readyFrame = events.renderedFrames();
        return events.renderedFrames() - readyFrame >= frames;
    }
    private void requireWorld() {
        FixtureCompatibility.requireSmokeSave(save);
        if (!server.isCallingFromMinecraftThread() || !save.equals(server.getFolderName()) || server.getWorld(0) != world)
            throw new IllegalStateException("Terrain pressure fixture no longer owns the server world");
    }
    private EntityPlayerMP player() {
        EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(playerId);
        if (player == null || player.world != world) throw new IllegalStateException("Terrain pressure player unavailable"); return player;
    }
    private Future<?> restore() {
        resumeSimulation();
        if (mc.world == clientWorld && mc.getIntegratedServer() == server && mc.player != null
                && playerId.equals(mc.player.getUniqueID())) mc.player.capabilities.isFlying = originalFlying;
        return server.callFromMainThread(() -> {
            requireWorld(); EntityPlayerMP player = player(); player.capabilities.isFlying = originalFlying; player.sendPlayerAbilities();
            player.connection.setPlayerLocation(returnX, returnY, returnZ, returnYaw, returnPitch); return null;
        });
    }
    private void transition(Phase next) { phase = next; since = progress = System.nanoTime(); readyFrame = -1; log("PHASE"); }
    private void capture(String name) throws Exception {
        java.awt.image.BufferedImage image = output.image(); output.capture(name, image);
        if (!FixtureImageReadiness.evaluate(image).isReady()) throw new IllegalStateException("Terrain pressure image is blank");
    }
    private void log(String name) { output.log("TERRAIN_PRESSURE_" + name, status()); }
    String status() {
        return "phase=" + phase + " radius=" + events.terrainRadius() + " traveled=" + traveled + " paused=" + mc.isGamePaused()
                + " bytes=" + events.terrainCapturedBytes() + " budget=" + events.terrainMeshBudgetBytes()
                + " pending=" + events.terrainPendingSectionCount() + " deferred=" + events.terrainDeferredSectionCount()
                + " evicted=" + events.terrainEvictedSectionCount() + " retiring=" + events.terrainRetirementCount()
                + " published=" + events.terrainPublishedSectionCount() + " markerRevision=" + (position == null ? 0 : revision())
                + " " + events.terrainPressureDiagnostics();
    }
    @Override public void close() { if (!cleanupQueued) { restore(); cleanupQueued = true; } }
    private void resumeSimulation() {
        if (stabilityScreen != null && mc.currentScreen == stabilityScreen) mc.displayGuiScreen(null);
        stabilityScreen = null;
    }
    private static final class Position {
        final BlockPos marker;
        final double x, y, z;
        Position(BlockPos marker, double x, double y, double z) { this.marker = marker; this.x = x; this.y = y; this.z = z; }
    }
}
