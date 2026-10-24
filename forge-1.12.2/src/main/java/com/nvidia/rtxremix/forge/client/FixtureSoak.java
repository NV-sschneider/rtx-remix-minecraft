/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import java.awt.image.BufferedImage;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.minecraft.block.BlockColored;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

/** Sustained converged streaming and visible native/vanilla transitions in a disposable save. */
final class FixtureSoak implements AutoCloseable {
    private enum Phase { MOVE, READY, HOLD, IMAGE, VANILLA, REENABLE, RESTORE, RETURN, DONE }
    private static final int[][] ROUTE = {{0, 0}, {384, 0}, {384, 384}, {0, 384}};
    private static final int SOAK_RADIUS = 8;
    private static final long MAX_TERRAIN_BYTES = 512L * 1024 * 1024;
    private final Minecraft mc;
    private final ClientEvents events;
    private final IntegratedServer server;
    private final WorldServer world;
    private final WorldClient clientWorld;
    private final String save;
    private final UUID playerId;
    private final FixtureTerrain.Output output;
    private final double originX, originY, originZ;
    private final float originYaw, originPitch;
    private final int radius;
    private final boolean flying;
    private final long started = System.nanoTime(), duration;
    // Constructed and cached only by the server; Future.get publishes one immutable landing to the client.
    private final Landing[] landings = new Landing[ROUTE.length];
    private final CycleMetrics cycle = new CycleMetrics();
    private long since = started, progress = started, lastFrame, frameAtVanilla, readyFrame = -1;
    private long cycleStarted, cycleFirstFrame, pendingReload = -1;
    private long peakHeap, peakTerrainBytes, peakEffectBytes, peakVboBytes;
    private int cycles, reloads, toggles, vanillaTicks, routeIndex, loadedColumns, capturedColumns, stableColumns = -1;
    private Phase phase = Phase.MOVE;
    private Future<Landing> action;
    private Landing position;
    private boolean cleanupQueued, closed, settingsChanged;
    // These two fields are read and written only in the server's ordered task queue.
    private boolean serverStateCaptured, serverFlying;

    static int seconds() {
        int value = Integer.getInteger("rlcraftremix.smoke.soakSeconds", 1800);
        if (value < 60 || value > 7200) throw new IllegalArgumentException("Soak duration must be 60 through 7200 seconds");
        return value;
    }

    FixtureSoak(Minecraft mc, ClientEvents events, IntegratedServer server, WorldServer world, String save,
            double x, double y, double z, float yaw, float pitch, FixtureTerrain.Output output) {
        FixtureCompatibility.requireSmokeSave(save);
        if (mc.world == null || mc.player == null || mc.getIntegratedServer() != server
                || mc.world.provider.getDimension() != 0) throw new IllegalStateException("Soak requires its owned overworld");
        this.mc = mc; this.events = events; this.server = server; this.world = world; this.save = save; this.output = output;
        clientWorld = mc.world; playerId = mc.player.getUniqueID(); originX = x; originY = y; originZ = z;
        originYaw = yaw; originPitch = pitch; radius = mc.gameSettings.renderDistanceChunks;
        flying = mc.player.capabilities.isFlying; duration = TimeUnit.SECONDS.toNanos(seconds());
        // No mutation or queued task before the controller owns this object and can close it on failure.
        log("STARTED");
    }

    boolean tick() throws Exception {
        if (closed || !ownsClient() || events.failureMessage() != null)
            throw new IllegalStateException("Soak fixture lost its renderer or owned world");
        long now = System.nanoTime();
        if (now - since > TimeUnit.SECONDS.toNanos(180)) throw new IllegalStateException("Soak phase timed out: " + status());
        if (phase != Phase.VANILLA && !events.isEnabled()) throw new IllegalStateException("Soak renderer unexpectedly disabled");
        if (events.terrainCapturedBytes() > MAX_TERRAIN_BYTES || events.terrainCapacityLimited())
            throw new IllegalStateException("Soak terrain capacity prevented complete coverage: " + status());
        if (now - progress >= TimeUnit.SECONDS.toNanos(30)) { sample(); log("PROGRESS"); progress = now; }
        if (position != null && phase != Phase.RESTORE && phase != Phase.RETURN && phase != Phase.DONE) holdPose();
        switch (phase) {
            case MOVE:
                if (action == null) {
                    settingsChanged = true;
                    FixtureTerrain.setRadius(mc, SOAK_RADIUS);
                    startMove();
                }
                if (action.isDone()) { position = action.get(); action = null; transition(Phase.READY); }
                break;
            case READY:
                if (settled(ready(), 60)) {
                    if (pendingReload >= 0) {
                        if (events.textureReloadCount() <= pendingReload) break;
                        pendingReload = -1; reloads++; log("RELOAD_PASS");
                    }
                    transition(Phase.HOLD);
                }
                break;
            case HOLD:
                if (!ready()) { transition(Phase.READY); break; }
                if (now - since >= TimeUnit.SECONDS.toNanos(20)) {
                    if (events.renderedFrames() <= lastFrame + 60) throw new IllegalStateException("Soak native frame progress stopped");
                    transition(Phase.IMAGE);
                }
                break;
            case IMAGE:
                if (settled(ready(), 30)) {
                    BufferedImage image = visibleImage();
                    cycle.record(cycleFirstFrame, events.renderedFrames(), cycleStarted, now);
                    cycles++; sample(); log("CYCLE_PASS");
                    if (cycles <= ROUTE.length || cycles % 10 == 0) output.capture("soak-cycle-" + cycles, image);
                    if (canFinish(now - started, duration, cycles, reloads, toggles)) finishRun(image);
                    else if (cycles % ROUTE.length == 0) {
                        frameAtVanilla = events.renderedFrames(); events.setEnabled(false); vanillaTicks = 0; transition(Phase.VANILLA);
                    } else {
                        if (cycles % 2 == 0) { pendingReload = events.textureReloadCount(); events.reload(); }
                        startMove(); transition(Phase.MOVE);
                    }
                }
                break;
            case VANILLA:
                if (events.renderedFrames() != frameAtVanilla) throw new IllegalStateException("Native world frames advanced while disabled");
                if (released()) vanillaTicks++; else vanillaTicks = 0;
                if (vanillaTicks >= 20) { log("RELEASE_PASS"); events.setEnabled(true); transition(Phase.REENABLE); }
                break;
            case REENABLE:
                if (settled(ready(), 60)) {
                    BufferedImage image = visibleImage(); toggles++; log("REENABLE_PASS");
                    if (canFinish(now - started, duration, cycles, reloads, toggles)) finishRun(image);
                    else { startMove(); transition(Phase.MOVE); }
                }
                break;
            case RESTORE:
                if (action.isDone()) { action.get(); action = null; transition(Phase.RETURN); }
                break;
            case RETURN:
                if (mc.player.getDistanceSq(originX, originY, originZ) <= 1 && events.renderedFrames() > lastFrame + 30) {
                    transition(Phase.DONE); log("PASS");
                }
                break;
            default: break;
        }
        if ((phase == Phase.READY || phase == Phase.HOLD || phase == Phase.IMAGE)
                && now - cycleStarted > TimeUnit.SECONDS.toNanos(300))
            throw new IllegalStateException("Soak cycle never maintained converged coverage: " + status());
        return phase == Phase.DONE;
    }

    static boolean canFinish(long elapsed, long requested, int cycles, int reloads, int toggles) {
        return elapsed >= requested && cycles >= ROUTE.length && reloads > 0 && toggles > 0;
    }

    private boolean ready() {
        if (position == null || events.terrainRadius() != SOAK_RADIUS || events.skyResourceCount() == 0) return false;
        int cx = (int) Math.floor(position.x / 16), cz = (int) Math.floor(position.z / 16);
        loadedColumns = capturedColumns = 0;
        for (int dz = -SOAK_RADIUS; dz <= SOAK_RADIUS; dz++) for (int dx = -SOAK_RADIUS; dx <= SOAK_RADIUS; dx++) {
            if (clientWorld.getChunkProvider().getLoadedChunk(cx + dx, cz + dz) == null) continue;
            loadedColumns++;
            if (events.terrainColumnCaptured(cx + dx, cz + dz)) capturedColumns++;
        }
        BlockPos marker = position.marker;
        return loadedColumns >= 25 && loadedColumns == capturedColumns
                && events.terrainColumnCount() == loadedColumns && events.terrainCapturedColumnCount() == loadedColumns
                && events.terrainPendingSectionCount() == 0 && events.terrainRetirementCount() == 0
                && events.terrainSectionReady(marker.getX() >> 4, marker.getY() >> 4, marker.getZ() >> 4)
                && clientWorld.getBlockState(marker).getBlock() == Blocks.WOOL;
    }

    private boolean settled(boolean ready, int frames) {
        if (!ready || loadedColumns != stableColumns) { readyFrame = -1; stableColumns = loadedColumns; }
        if (!ready) return false;
        if (readyFrame < 0) readyFrame = events.renderedFrames();
        return events.renderedFrames() - readyFrame >= frames;
    }

    private BufferedImage visibleImage() {
        BufferedImage image = output.image();
        FixtureImageReadiness.Result result = FixtureImageReadiness.evaluate(image);
        output.log("SOAK_IMAGE", status() + " " + result.describe());
        if (!result.isReady()) throw new IllegalStateException("Soak fixture image is blank or lacks the colored landing marker");
        return image;
    }

    private void finishRun(BufferedImage image) throws Exception {
        output.capture("soak-final", image); closeOwnedState(); transition(Phase.RESTORE);
    }

    private boolean released() {
        return events.terrainResourceCount() == 0 && events.terrainCapturedBytes() == 0
                && events.entityResourceCount() == 0 && events.entityTexturePatchBytes() == 0 && events.dirtyEntityTextures() == 0
                && events.itemResourceCount() == 0 && events.effectResourceCount() == 0 && events.effectRetainedBytes() == 0
                && events.effectDirtyTextureCount() == 0 && events.cloudResourceCount() == 0 && events.cloudRetainedBytes() == 0
                && events.skyResourceCount() == 0 && events.pbrTextureCount() == 0 && events.vboCacheBytes() == 0
                && events.cameraMediumResourceCount() == 0 && events.cameraMediumHandle() == 0
                && events.blockLightResourceCount() == 0 && events.heldLightCount() == 0
                && events.terrainFallbackResources() == 0 && events.terrainFallbackBytes() == 0;
    }

    private void sample() {
        Runtime runtime = Runtime.getRuntime();
        peakHeap = Math.max(peakHeap, runtime.totalMemory() - runtime.freeMemory());
        peakTerrainBytes = Math.max(peakTerrainBytes, events.terrainCapturedBytes());
        peakEffectBytes = Math.max(peakEffectBytes, events.effectRetainedBytes()); peakVboBytes = Math.max(peakVboBytes, events.vboCacheBytes());
    }

    private void startMove() {
        routeIndex = cycles % ROUTE.length;
        cycleStarted = System.nanoTime(); cycleFirstFrame = events.renderedFrames();
        final int waypoint = routeIndex;
        action = server.callFromMainThread(() -> {
            EntityPlayerMP player = ownedPlayer();
            if (!serverStateCaptured) { serverFlying = player.capabilities.isFlying; serverStateCaptured = true; }
            Landing landing = landings[waypoint];
            if (landing == null) landings[waypoint] = landing = buildLanding(waypoint);
            player.capabilities.isFlying = true; player.sendPlayerAbilities();
            player.connection.setPlayerLocation(landing.x, landing.y, landing.z, 180, 32);
            return landing;
        });
    }

    private Landing buildLanding(int waypoint) {
        int x = (int) Math.floor(originX + ROUTE[waypoint][0]), z = (int) Math.floor(originZ + ROUTE[waypoint][1]);
        int floor = Math.max(1, Math.min(235, world.getTopSolidOrLiquidBlock(new BlockPos(x, 0, z)).getY() - 1));
        for (int dx = -8; dx <= 8; dx++) for (int dz = -14; dz <= 2; dz++) {
            world.setBlockState(new BlockPos(x + dx, floor, z + dz), Blocks.WOOL.getDefaultState()
                    .withProperty(BlockColored.COLOR, ((dx + dz) & 1) == 0 ? EnumDyeColor.ORANGE : EnumDyeColor.LIGHT_BLUE), 3);
            for (int dy = 1; dy <= 12; dy++) world.setBlockToAir(new BlockPos(x + dx, floor + dy, z + dz));
        }
        for (int dx = -6; dx <= 6; dx++) for (int dy = 1; dy <= 4; dy++) {
            world.setBlockState(new BlockPos(x + dx, floor + dy, z - 10), Blocks.WOOL.getDefaultState()
                    .withProperty(BlockColored.COLOR, (dx & 1) == 0 ? EnumDyeColor.RED : EnumDyeColor.YELLOW), 3);
        }
        return new Landing(x + 0.5, floor + 7.5, z + 0.5, new BlockPos(x, floor + 2, z - 10));
    }

    private EntityPlayerMP ownedPlayer() {
        if (!server.isCallingFromMinecraftThread() || !save.equals(server.getFolderName()) || server.getWorld(0) != world)
            throw new IllegalStateException("Soak fixture no longer owns its server");
        EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(playerId);
        if (player == null || player.getServerWorld() != world) throw new IllegalStateException("Soak fixture player left its overworld");
        return player;
    }

    private boolean ownsClient() {
        return mc.world == clientWorld && mc.getIntegratedServer() == server && mc.player != null
                && playerId.equals(mc.player.getUniqueID());
    }

    private void holdPose() {
        float yaw = phase == Phase.HOLD ? 180 + (float) ((System.nanoTime() - since) / 1_000_000_000.0 * 4) : 180;
        mc.player.setPositionAndRotation(position.x, position.y, position.z, yaw, 32);
        mc.player.prevPosX = mc.player.lastTickPosX = position.x; mc.player.prevPosY = mc.player.lastTickPosY = position.y;
        mc.player.prevPosZ = mc.player.lastTickPosZ = position.z; mc.player.prevRotationYaw = yaw; mc.player.prevRotationPitch = 32;
        mc.player.motionX = mc.player.motionY = mc.player.motionZ = 0; mc.player.capabilities.isFlying = true;
    }

    private void transition(Phase next) {
        phase = next; since = System.nanoTime(); lastFrame = events.renderedFrames(); readyFrame = -1; stableColumns = -1;
    }

    private void closeOwnedState() {
        boolean owned = ownsClient();
        if (settingsChanged) {
            boolean changed = mc.gameSettings.renderDistanceChunks != radius;
            mc.gameSettings.renderDistanceChunks = radius;
            settingsChanged = false;
            // Restore the global preference even after disconnect, without reloading somebody else's world.
            if (changed && owned) { mc.renderGlobal.loadRenderers(); mc.gameSettings.sendSettingsToServer(); }
        }
        if (owned && !cleanupQueued) {
            mc.player.capabilities.isFlying = flying;
            action = server.callFromMainThread(() -> {
                EntityPlayerMP player = ownedPlayer();
                if (serverStateCaptured) { player.capabilities.isFlying = serverFlying; player.sendPlayerAbilities(); }
                player.connection.setPlayerLocation(originX, originY, originZ, originYaw, originPitch);
                return new Landing(originX, originY, originZ, null);
            });
            cleanupQueued = true;
        }
    }

    private void log(String name) { output.log("SOAK_" + name, status()); }
    String status() {
        return "phase=" + phase + " seconds=" + TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)
                + " requestedSeconds=" + TimeUnit.NANOSECONDS.toSeconds(duration) + " cycles=" + cycles
                + " reloads=" + reloads + " toggles=" + toggles + " frames=" + events.renderedFrames()
                + " sections=" + events.terrainSectionCount() + " loadedColumns=" + loadedColumns + " capturedColumns=" + capturedColumns
                + " pending=" + events.terrainPendingSectionCount() + " retirement=" + events.terrainRetirementCount()
                + " terrainBytes=" + events.terrainCapturedBytes() + " entities=" + events.entityResourceCount() + " effects=" + events.effectResourceCount()
                + " cycleFrames=" + cycle.frames + " cycleMillis=" + TimeUnit.NANOSECONDS.toMillis(cycle.nanos)
                + " cycleFramesPerSecond=" + String.format(Locale.ROOT, "%.2f", cycle.framesPerSecond())
                + " sampledPeakHeap=" + peakHeap + " sampledPeakTerrainBytes=" + peakTerrainBytes + " sampledPeakEffectBytes=" + peakEffectBytes
                + " sampledPeakVboBytes=" + peakVboBytes + " capacityLimited=" + events.terrainCapacityLimited()
                + " heapStabilityValidated=false";
    }

    @Override public void close() { if (!closed) { closeOwnedState(); closed = true; } }

    private static final class Landing {
        final double x, y, z;
        final BlockPos marker;
        Landing(double x, double y, double z, BlockPos marker) { this.x = x; this.y = y; this.z = z; this.marker = marker; }
    }

    static final class CycleMetrics {
        long frames, nanos;
        void record(long firstFrame, long lastFrame, long start, long end) {
            if (lastFrame <= firstFrame || end <= start) throw new IllegalStateException("Soak cycle made no measurable frame progress");
            frames = lastFrame - firstFrame; nanos = end - start;
        }
        double framesPerSecond() { return nanos == 0 ? 0 : frames * 1_000_000_000.0 / nanos; }
    }
}
