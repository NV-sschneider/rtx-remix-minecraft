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
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

/** Readiness-driven travel, mutation, and radius probes in the fixture's own save. */
final class FixtureTerrain {
    static final int RADIUS = requestedRadius();
    private static final long PHASE_TIMEOUT = TimeUnit.SECONDS.toNanos(300);
    private static final long PROGRESS_INTERVAL = TimeUnit.SECONDS.toNanos(10);
    private static final int IMAGE_SETTLE_FRAMES = 20;

    interface Output {
        BufferedImage image();
        String capture(String name, BufferedImage image) throws Exception;
        void log(String event, String fields);
    }

    private enum Phase { ORIGIN, TRAVEL, ARRIVAL, MUTATE, MUTATION, SHRINK, EXPAND, RETURN, RETURNED, DONE }

    private final Minecraft mc;
    private final ClientEvents events;
    private final IntegratedServer server;
    private final WorldServer world;
    private final WorldClient clientWorld;
    private final String saveName;
    private final UUID playerId;
    private final Output output;
    private final Position origin;
    private Position position;
    private Position destination;
    private Phase phase = Phase.ORIGIN;
    private Future<Position> movement;
    private Future<?> mutation;
    private long phaseSince = System.nanoTime();
    private long readyFrame = -1;
    private long revisionBeforeMutation;
    private long retirementBaseline;
    private int targetRadius = RADIUS;
    private long capacityFrame = -1;
    private long capacityBytes;
    private long capacityPublications, capacityEvictions;
    private int capacityDeferred, capacityColumns;
    private long lastProgress = System.nanoTime();

    FixtureTerrain(Minecraft mc, ClientEvents events, IntegratedServer server, WorldServer world,
            String saveName, double x, double y, double z, Output output) {
        this.mc = mc;
        this.events = events;
        this.server = server;
        this.world = world;
        this.clientWorld = mc.world;
        this.saveName = saveName;
        this.playerId = mc.player.getUniqueID();
        this.output = output;
        this.origin = new Position(x, y + 8.0, z, null);
        this.position = origin;
        log("STARTED");
    }

    boolean tick() throws Exception {
        if (mc.world != clientWorld || mc.getIntegratedServer() != server) {
            throw new IllegalStateException("Terrain fixture no longer owns the client world");
        }
        if (!events.isEnabled() || events.failureMessage() != null) {
            throw new IllegalStateException("Terrain fixture renderer is unavailable");
        }
        if (System.nanoTime() - phaseSince > PHASE_TIMEOUT) {
            output.log("TERRAIN_TIMEOUT", status() + " " + readiness());
            String file = output.capture("terrain-timeout-" + phase.name().toLowerCase(Locale.ROOT), output.image());
            output.log("TERRAIN_TIMEOUT_IMAGE", "file=" + file);
            throw new IllegalStateException("Terrain fixture phase timed out");
        }
        holdPose();
        if (System.nanoTime() - lastProgress >= PROGRESS_INTERVAL) {
            output.log("TERRAIN_PROGRESS", status() + " " + readiness());
            lastProgress = System.nanoTime();
        }
        switch (phase) {
            case ORIGIN:
                if (recoverCapacity()) break;
                if (settled(convergedCoverage(origin))) {
                    capture("terrain-origin");
                    retirementBaseline = events.terrainRetiredSectionCount();
                    movement = move((2 * targetRadius + 4) * 16, true);
                    transition(Phase.TRAVEL);
                }
                break;
            case TRAVEL:
                if (movement.isDone()) {
                    position = destination = movement.get();
                    transition(Phase.ARRIVAL);
                }
                break;
            case ARRIVAL:
                if (settled(broadCoverage(destination) && !columnCaptured(origin, 0, 0)
                        && sectionReady(destination.marker)
                        && events.terrainRetirementCount() == 0
                        && events.terrainRetiredSectionCount() > retirementBaseline)) {
                    capture("terrain-arrival");
                    BlockPos block = destination.marker;
                    revisionBeforeMutation = revision(block);
                    mutation = server.callFromMainThread(() -> {
                        requireServerWorld();
                        world.setBlockState(block, Blocks.DIAMOND_BLOCK.getDefaultState(), 3);
                        return null;
                    });
                    transition(Phase.MUTATE);
                }
                break;
            case MUTATE:
                if (mutation.isDone()) {
                    mutation.get();
                    transition(Phase.MUTATION);
                }
                break;
            case MUTATION:
                if (settled(clientWorld.getBlockState(destination.marker).getBlock() == Blocks.DIAMOND_BLOCK
                        && sectionReady(destination.marker)
                        && revision(destination.marker) > revisionBeforeMutation)) {
                    capture("terrain-mutation");
                    output.log("TERRAIN_MUTATION_PASS", "revisionBefore=" + revisionBeforeMutation
                            + " revisionAfter=" + revision(destination.marker) + " clientBlock=diamond_block");
                    setRadius(mc, 2);
                    transition(Phase.SHRINK);
                }
                break;
            case SHRINK:
                if (settled(events.terrainRadius() == 2 && columnCaptured(destination, 0, 0)
                        && !columnCaptured(destination, 3, 0) && !columnCaptured(destination, -3, 0)
                        && events.terrainCapturedColumnCount() <= 25 && events.terrainRetirementCount() == 0)) {
                    log("SHRINK_PASS");
                    setRadius(mc, targetRadius);
                    transition(Phase.EXPAND);
                }
                break;
            case EXPAND:
                if (settled(broadCoverage(destination))) {
                    log("EXPAND_PASS");
                    movement = move(0, false);
                    transition(Phase.RETURN);
                }
                break;
            case RETURN:
                if (movement.isDone()) {
                    position = movement.get();
                    transition(Phase.RETURNED);
                }
                break;
            case RETURNED:
                if (settled(convergedCoverage(origin) && !columnCaptured(destination, 0, 0)
                        && events.terrainRetirementCount() == 0)) {
                    capture("terrain-return");
                    log("PASS");
                    transition(Phase.DONE);
                }
                break;
            default:
                break;
        }
        return phase == Phase.DONE;
    }

    String status() {
        return "phase=" + phase.name() + " radius=" + events.terrainRadius()
                + " requestedRadius=" + RADIUS + " targetRadius=" + targetRadius
                + " wantedColumns=" + ((2 * events.terrainRadius() + 1) * (2 * events.terrainRadius() + 1))
                + " chunkX=" + (int) Math.floor(position.x / 16.0)
                + " chunkZ=" + (int) Math.floor(position.z / 16.0)
                + " discoveredColumns=" + events.terrainColumnCount()
                + " capturedColumns=" + events.terrainCapturedColumnCount()
                + " sections=" + events.terrainSectionCount() + " pending=" + events.terrainPendingSectionCount()
                + " deferred=" + events.terrainDeferredSectionCount() + " evicted=" + events.terrainEvictedSectionCount()
                + " retiring=" + events.terrainRetirementCount()
                + " published=" + events.terrainPublishedSectionCount()
                + " retired=" + events.terrainRetiredSectionCount()
                + " discarded=" + events.terrainDiscardedBuildCount()
                + " resources=" + events.terrainResourceCount() + " bytes=" + events.terrainCapturedBytes()
                + " budget=" + events.terrainMeshBudgetBytes();
    }

    static void setRadius(Minecraft mc, int radius) {
        if (mc.gameSettings.renderDistanceChunks == radius) return;
        mc.gameSettings.renderDistanceChunks = radius;
        if (mc.world != null) mc.renderGlobal.loadRenderers();
        if (mc.player != null) mc.gameSettings.sendSettingsToServer();
    }

    private static int requestedRadius() {
        int radius = Integer.parseInt(System.getProperty("rlcraftremix.smoke.terrainRadius", "4"));
        if (radius < 4 || radius > 32) {
            throw new IllegalArgumentException("Terrain smoke radius must be between 4 and 32 chunks");
        }
        return radius;
    }

    private boolean broadCoverage(Position target) {
        return broadCoverage(coverage(target));
    }

    private boolean broadCoverage(Coverage coverage) {
        return events.terrainRadius() == targetRadius && coverage.capturedLoaded >= 25
                && coverage.centerCaptured && coverage.beyondOldRadius
                && coverage.farLoaded == coverage.farCaptured;
    }

    private boolean recoverCapacity() {
        long bytes = events.terrainCapturedBytes();
        long budget = events.terrainMeshBudgetBytes();
        if (bytes > budget) throw new IllegalStateException("Terrain mesh memory exceeded its budget");
        int deferred = events.terrainDeferredSectionCount();
        if (targetRadius <= 4 || budget <= 0 || deferred == 0 || events.terrainPendingSectionCount() != 0
                || events.terrainRetirementCount() != 0 || events.terrainRadius() != targetRadius) {
            capacityFrame = -1;
            return false;
        }
        long frame = events.renderedFrames();
        long publications = events.terrainPublishedSectionCount(), evictions = events.terrainEvictedSectionCount();
        int columns = events.terrainColumnCount();
        if (capacityFrame < 0 || bytes != capacityBytes || publications != capacityPublications
                || evictions != capacityEvictions || deferred != capacityDeferred || columns != capacityColumns) {
            capacityFrame = frame;
            capacityBytes = bytes;
            capacityPublications = publications; capacityEvictions = evictions;
            capacityDeferred = deferred; capacityColumns = columns;
            return false;
        }
        if (frame - capacityFrame < IMAGE_SETTLE_FRAMES) return false;
        output.log("TERRAIN_CAPACITY_PASS", status() + " stableFrames=" + (frame - capacityFrame)
                + " coverageComplete=false memoryBudget=" + budget + " recoveryRadius=4");
        targetRadius = 4;
        setRadius(mc, targetRadius);
        transition(Phase.ORIGIN);
        return true;
    }

    private boolean convergedCoverage(Position target) {
        return convergedCoverage(coverage(target));
    }

    private boolean convergedCoverage(Coverage coverage) {
        // Both fixture generators have solid ground in every loaded column. Requiring
        // all queued sections to finish also covers their full vertical extent.
        return broadCoverage(coverage) && coverage.capturedLoaded == coverage.loaded
                && events.terrainColumnCount() == coverage.loaded
                && events.terrainCapturedColumnCount() == coverage.loaded
                && events.terrainPendingSectionCount() == 0 && events.terrainRetirementCount() == 0
                && !events.terrainCapacityLimited();
    }

    private Coverage coverage(Position target) {
        int cx = (int) Math.floor(target.x / 16.0);
        int cz = (int) Math.floor(target.z / 16.0);
        int probe = Math.max(3, targetRadius - 1);
        Coverage result = new Coverage();
        // This is a bounded, opt-in fixture scan. Never request or generate missing chunks.
        for (int dz = -targetRadius; dz <= targetRadius; dz++) {
            for (int dx = -targetRadius; dx <= targetRadius; dx++) {
                if (clientWorld.getChunkProvider().getLoadedChunk(cx + dx, cz + dz) == null) continue;
                result.loaded++;
                boolean captured = events.terrainColumnCaptured(cx + dx, cz + dz);
                if (captured) {
                    result.capturedLoaded++;
                    if (Math.abs(dx) > 1 || Math.abs(dz) > 1) result.beyondOldRadius = true;
                    if (dx == 0 && dz == 0) result.centerCaptured = true;
                }
                int bit = dz == 0 ? (dx == probe ? 1 : dx == -probe ? 2 : 0)
                        : dx == 0 ? (dz == probe ? 4 : dz == -probe ? 8 : 0) : 0;
                result.farLoaded |= bit;
                if (captured) result.farCaptured |= bit;
            }
        }
        return result;
    }

    private String readiness() {
        Coverage coverage = coverage(position);
        boolean markerReady = destination != null && sectionReady(destination.marker);
        boolean markerChanged = destination != null
                && clientWorld.getBlockState(destination.marker).getBlock() == Blocks.DIAMOND_BLOCK;
        return "actualLoadedColumns=" + coverage.loaded + " capturedLoadedColumns=" + coverage.capturedLoaded
                + " broadReady=" + broadCoverage(coverage) + " convergedReady=" + convergedCoverage(coverage)
                + " beyondOldRadius=" + coverage.beyondOldRadius
                + " centerCaptured=" + coverage.centerCaptured
                + " farProbeXP=" + probeStatus(coverage, 1) + " farProbeXN=" + probeStatus(coverage, 2)
                + " farProbeZP=" + probeStatus(coverage, 4) + " farProbeZN=" + probeStatus(coverage, 8)
                + " originCaptured=" + columnCaptured(origin, 0, 0)
                + " destinationCaptured=" + (destination != null && columnCaptured(destination, 0, 0))
                + " markerReady=" + markerReady + " markerChanged=" + markerChanged
                + " mutationPublished=" + (destination != null && revision(destination.marker) > revisionBeforeMutation)
                + " retirementProgress=" + (events.terrainRetiredSectionCount() > retirementBaseline)
                + " capacityLimited=" + events.terrainCapacityLimited();
    }

    private static String probeStatus(Coverage coverage, int bit) {
        if ((coverage.farLoaded & bit) == 0) return "unloaded";
        return (coverage.farCaptured & bit) == 0 ? "loaded_uncaptured" : "loaded_captured";
    }

    private boolean columnCaptured(Position target, int dx, int dz) {
        return events.terrainColumnCaptured((int) Math.floor(target.x / 16.0) + dx,
                (int) Math.floor(target.z / 16.0) + dz);
    }

    private long revision(BlockPos block) {
        return events.terrainSectionRevision(block.getX() >> 4, block.getY() >> 4, block.getZ() >> 4);
    }

    private boolean sectionReady(BlockPos block) {
        return events.terrainSectionReady(block.getX() >> 4, block.getY() >> 4, block.getZ() >> 4);
    }

    private boolean settled(boolean ready) {
        if (!ready) { readyFrame = -1; return false; }
        long frame = events.renderedFrames();
        if (readyFrame < 0) readyFrame = frame;
        return frame - readyFrame >= IMAGE_SETTLE_FRAMES;
    }

    private Future<Position> move(int distance, boolean buildMarker) {
        return server.callFromMainThread(() -> {
            requireServerWorld();
            EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(playerId);
            if (player == null) throw new IllegalStateException("Terrain fixture player is absent");
            Position next = origin;
            if (buildMarker) {
                int x = (int) Math.floor(origin.x) + distance;
                int z = (int) Math.floor(origin.z);
                int floor = world.getTopSolidOrLiquidBlock(new BlockPos(x, 0, z - 8)).getY() - 1;
                for (int dx = -8; dx <= 8; dx++) {
                    for (int dz = -12; dz <= 2; dz++) {
                        for (int y = 1; y <= 16; y++) {
                            world.setBlockToAir(new BlockPos(x + dx, floor + y, z + dz));
                        }
                    }
                }
                // A marker beside the landing view makes the before/after images inspectable.
                BlockPos marker = new BlockPos(x - 2, floor + 3, z - 8);
                for (int y = 0; y <= 3; y++) {
                    for (int dx = -3; dx <= 3; dx++) {
                        world.setBlockState(new BlockPos(x + dx, floor + y, z - 8),
                                y == 0 ? Blocks.GOLD_BLOCK.getDefaultState() : Blocks.QUARTZ_BLOCK.getDefaultState(), 3);
                    }
                }
                next = new Position(x + 0.5, floor + 12.0, z + 0.5, marker);
            }
            player.connection.setPlayerLocation(next.x, next.y, next.z, 180f, 30f);
            return next;
        });
    }

    private void requireServerWorld() {
        if (!server.isCallingFromMinecraftThread() || !saveName.equals(server.getFolderName())
                || server.getWorld(0) != world) {
            throw new IllegalStateException("Unexpected server world during terrain fixture");
        }
    }

    private void holdPose() {
        mc.player.setPositionAndRotation(position.x, position.y, position.z, 180f, 30f);
        mc.player.prevPosX = mc.player.lastTickPosX = position.x;
        mc.player.prevPosY = mc.player.lastTickPosY = position.y;
        mc.player.prevPosZ = mc.player.lastTickPosZ = position.z;
        mc.player.prevRotationYaw = mc.player.rotationYawHead = mc.player.prevRotationYawHead = 180f;
        mc.player.prevRotationPitch = 30f;
        mc.player.motionX = mc.player.motionY = mc.player.motionZ = 0;
        mc.player.capabilities.isFlying = true;
    }

    private void capture(String name) throws Exception {
        BufferedImage image = output.image();
        FixtureImageReadiness.Result readiness = FixtureImageReadiness.evaluate(image);
        String file = output.capture(name, image);
        output.log("TERRAIN_CAPTURE", status() + " file=" + file + " " + readiness.describe());
        if (!readiness.isReady()) throw new IllegalStateException("Terrain fixture image is blank");
    }

    private void transition(Phase next) {
        phase = next;
        phaseSince = System.nanoTime();
        lastProgress = phaseSince;
        readyFrame = -1;
        log("PHASE");
    }

    private void log(String event) { output.log("TERRAIN_" + event, status()); }

    private static final class Position {
        final double x, y, z;
        final BlockPos marker;
        Position(double x, double y, double z, BlockPos marker) {
            this.x = x; this.y = y; this.z = z; this.marker = marker;
        }
    }

    private static final class Coverage {
        int loaded, capturedLoaded, farLoaded, farCaptured;
        boolean centerCaptured, beyondOldRadius;
    }
}
