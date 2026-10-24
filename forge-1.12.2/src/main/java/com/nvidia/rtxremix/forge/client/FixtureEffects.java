/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.particle.Particle;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Biomes;
import net.minecraft.network.play.server.SPacketChunkData;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;

/** Observes real particle-manager and precipitation draws in a launcher-owned smoke world. */
final class FixtureEffects implements AutoCloseable {
    private static final long TIMEOUT = TimeUnit.SECONDS.toNanos(180);
    private static final long PROGRESS = TimeUnit.SECONDS.toNanos(10);
    private static final int SETTLE_FRAMES = 20;
    private enum Phase { ARRIVE, BASELINE, PARTICLES, PARTICLE_MOTION, CUSTOM, REVISIONS, DELETED, REPLACED, RAIN, RAIN_READY, RAIN_MOTION,
        MOVE, MOVED, RELOAD, RELOADED, CLEAR, DRY, RETURN, DONE }
    private final Minecraft mc;
    private final ClientEvents events;
    private final IntegratedServer server;
    private final WorldServer world;
    private final WorldClient clientWorld;
    private final String saveName;
    private final UUID playerId;
    private final Layout layout;
    private final FixtureTerrain.Output output;
    private final List<Particle> seeded = new ArrayList<>();
    private final int originalParticleSetting;
    private final double returnX, returnY, returnZ;
    private final float returnYaw, returnPitch;
    private Phase phase = Phase.ARRIVE;
    private Future<?> action;
    private long since = System.nanoTime(), lastProgress = since;
    private long readyFrame = -1, particleFrames, weatherFrames, particleHash, weatherHash;
    private long reloadBaseline;
    private long revisionBaseline;
    private FixtureEffectSprites custom;
    private int ticks, bursts;
    private boolean moved, closed;

    FixtureEffects(Minecraft mc, ClientEvents events, IntegratedServer server, WorldServer world,
            String saveName, Layout layout, double x, double y, double z, float yaw, float pitch,
            FixtureTerrain.Output output) {
        if (!saveName.startsWith("rlcraft-remix-smoke-") || !saveName.equals(server.getFolderName())
                || mc.getIntegratedServer() != server || mc.world == null) {
            throw new IllegalArgumentException("Effect fixture requires its dedicated smoke world");
        }
        this.mc = mc; this.events = events; this.server = server; this.world = world;
        this.clientWorld = mc.world; this.saveName = saveName; this.layout = layout; this.output = output;
        this.playerId = mc.player.getUniqueID();
        returnX = x; returnY = y; returnZ = z; returnYaw = yaw; returnPitch = pitch;
        originalParticleSetting = mc.gameSettings.particleSetting;
        action = server.callFromMainThread(() -> {
            requireWorld();
            prepareRainBiome();
            FixtureEnvironment.setSky(world, 9000L, false);
            player().connection.setPlayerLocation(cameraX(false), cameraY(), cameraZ(), 180f, 16f);
            return null;
        });
        mc.gameSettings.particleSetting = 0;
        log("STARTED");
    }

    boolean tick() throws Exception {
        if (closed || mc.world != clientWorld || mc.getIntegratedServer() != server
                || !events.isEnabled() || events.failureMessage() != null) {
            throw new IllegalStateException("Effect fixture renderer/world unavailable");
        }
        if (System.nanoTime() - since >= TIMEOUT) {
            log("TIMEOUT"); capture("effects-timeout-" + phase.name().toLowerCase(Locale.ROOT));
            throw new IllegalStateException("Effect fixture timed out: " + status());
        }
        if (System.nanoTime() - lastProgress >= PROGRESS) { log("PROGRESS"); lastProgress = System.nanoTime(); }
        if (phase != Phase.RETURN && phase != Phase.DONE) holdPose();
        if (custom != null) custom.tick();
        if (emitting() && ++ticks % 20 == 0) seedParticles();
        switch (phase) {
            case ARRIVE:
                if (complete()) {
                    FixtureEnvironment.setSky(clientWorld, 9000L, false);
                    transition(Phase.BASELINE);
                }
                break;
            case BASELINE:
                clearParticles();
                if (settled(dry() && markerReady())) {
                    capture("effects-dry-baseline"); log("BASELINE_PASS");
                    mc.gameSettings.particleSetting = 0;
                    seedParticles();
                    transition(Phase.PARTICLES);
                }
                break;
            case PARTICLES:
                if (settled(events.particleQuads() > 0 && events.weatherQuads() == 0 && liveSeeded() > 0)) {
                    particleFrames = events.particleFrameCount(); particleHash = events.particleGeometryHash();
                    transition(Phase.PARTICLE_MOTION);
                }
                break;
            case PARTICLE_MOTION:
                if (settled(events.particleQuads() > 0 && liveSeeded() > 0
                        && events.particleFrameCount() >= particleFrames + SETTLE_FRAMES
                        && events.particleGeometryHash() != particleHash)) {
                    capture("effects-particles"); log("PARTICLE_PASS");
                    clearParticles();
                    mc.gameSettings.particleSetting = 0;
                    custom = new FixtureEffectSprites(mc, layout.x, layout.y, layout.z);
                    transition(Phase.CUSTOM);
                }
                break;
            case CUSTOM:
                if (settled(customReady())) {
                    capture("effects-additive-triangles"); log("CUSTOM_PASS");
                    revisionBaseline = events.effectTextureUpdateCount();
                    transition(Phase.REVISIONS);
                }
                break;
            case REVISIONS:
                if (settled(customReady() && events.effectTextureUpdateCount() > revisionBaseline + 1
                        && events.effectTexturePatchCount() > 0 && events.effectTextureDroppedUpdateCount() == 0)) {
                    capture("effects-dynamic-revisions"); log("REVISION_PASS");
                    custom.close(); custom = null;
                    transition(Phase.DELETED);
                }
                break;
            case DELETED:
                if (settled(events.effectAdditivePrimitives() == 0 && events.effectTrianglePrimitives() == 0
                        && events.effectDirtyTextureCount() == 0)) {
                    capture("effects-dynamic-deleted"); log("DELETE_PASS");
                    custom = new FixtureEffectSprites(mc, layout.x, layout.y, layout.z);
                    transition(Phase.REPLACED);
                }
                break;
            case REPLACED:
                if (settled(customReady())) {
                    capture("effects-dynamic-replaced"); log("REPLACE_PASS");
                    seedParticles();
                    action = server.callFromMainThread(() -> {
                        requireWorld(); FixtureEnvironment.setSky(world, 9000L, true); return null;
                    });
                    transition(Phase.RAIN);
                }
                break;
            case RAIN:
                if (complete()) {
                    FixtureEnvironment.setSky(clientWorld, 9000L, true);
                    transition(Phase.RAIN_READY);
                }
                break;
            case RAIN_READY:
                if (settled(wet() && events.particleQuads() > 0)) {
                    weatherFrames = events.weatherFrameCount(); weatherHash = events.weatherGeometryHash();
                    transition(Phase.RAIN_MOTION);
                }
                break;
            case RAIN_MOTION:
                if (settled(wet() && events.weatherFrameCount() >= weatherFrames + SETTLE_FRAMES
                        && events.weatherGeometryHash() != weatherHash)) {
                    capture("effects-rain"); log("WEATHER_PASS");
                    particleFrames = events.particleFrameCount(); particleHash = events.particleGeometryHash();
                    weatherFrames = events.weatherFrameCount(); weatherHash = events.weatherGeometryHash();
                    action = server.callFromMainThread(() -> {
                        requireWorld();
                        player().connection.setPlayerLocation(cameraX(true), cameraY(), cameraZ(), 165f, 16f);
                        return null;
                    });
                    transition(Phase.MOVE);
                }
                break;
            case MOVE:
                if (complete()) { moved = true; transition(Phase.MOVED); }
                break;
            case MOVED:
                if (settled(wet() && events.particleQuads() > 0 && markerReady()
                        && Math.abs(mc.player.posX - cameraX(true)) < 0.1
                        && events.weatherFrameCount() >= weatherFrames + SETTLE_FRAMES
                        && events.particleFrameCount() >= particleFrames + SETTLE_FRAMES
                        && events.weatherGeometryHash() != weatherHash && events.particleGeometryHash() != particleHash)) {
                    capture("effects-rain-camera-moved"); log("CAMERA_PASS");
                    reloadBaseline = events.textureReloadCount();
                    events.reload();
                    transition(Phase.RELOAD);
                }
                break;
            case RELOAD:
                if (settled(events.textureReloadCount() > reloadBaseline && wet() && markerReady()
                        && events.particleQuads() > 0 && events.effectTextureSnapshotCount() > 0 && customReady()
                        && events.effectTextureUpdateCount() > 0)) {
                    particleFrames = events.particleFrameCount(); particleHash = events.particleGeometryHash();
                    weatherFrames = events.weatherFrameCount(); weatherHash = events.weatherGeometryHash();
                    transition(Phase.RELOADED);
                }
                break;
            case RELOADED:
                if (settled(wet() && events.particleQuads() > 0
                        && events.particleFrameCount() >= particleFrames + SETTLE_FRAMES
                        && events.weatherFrameCount() >= weatherFrames + SETTLE_FRAMES
                        && events.particleGeometryHash() != particleHash && events.weatherGeometryHash() != weatherHash)) {
                    capture("effects-rain-reloaded"); log("RELOAD_PASS");
                    custom.close(); custom = null;
                    action = server.callFromMainThread(() -> {
                        requireWorld(); FixtureEnvironment.setSky(world, 9000L, false); return null;
                    });
                    transition(Phase.CLEAR);
                }
                break;
            case CLEAR:
                if (complete()) {
                    FixtureEnvironment.setSky(clientWorld, 9000L, false);
                    transition(Phase.DRY);
                }
                break;
            case DRY:
                clearParticles();
                if (settled(dry())) {
                    capture("effects-cleared"); log("DRY_PASS");
                    action = server.callFromMainThread(() -> {
                        requireWorld();
                        FixtureEnvironment.setSky(world, 6000L, false);
                        player().connection.setPlayerLocation(returnX, returnY, returnZ, returnYaw, returnPitch);
                        return null;
                    });
                    transition(Phase.RETURN);
                }
                break;
            case RETURN:
                if (complete()) {
                    FixtureEnvironment.setSky(clientWorld, 6000L, false);
                    transition(Phase.DONE); log("PASS"); close();
                }
                break;
            default: break;
        }
        return phase == Phase.DONE;
    }

    /** Must run in SmokeController's existing owned-world server setup task. */
    static Layout build(WorldServer world, int x, int y, int z) {
        if (!world.getMinecraftServer().isCallingFromMinecraftThread()
                || !world.getMinecraftServer().getFolderName().startsWith("rlcraft-remix-smoke-")) {
            throw new IllegalStateException("Effect scene construction requires its owned server thread and smoke save");
        }
        Layout layout = new Layout(x, y, z);
        for (int dx = -12; dx <= 12; dx++) for (int dz = 0; dz <= 22; dz++) {
            world.setBlockState(new BlockPos(x + dx, y, z + dz),
                    ((dx + dz) & 1) == 0 ? Blocks.QUARTZ_BLOCK.getDefaultState() : Blocks.STONE.getDefaultState(), 3);
            int top = Math.max(y + 16, world.getHeight(new BlockPos(x + dx, y, z + dz)).getY());
            for (int clearY = y + 1; clearY <= top; clearY++) world.setBlockToAir(new BlockPos(x + dx, clearY, z + dz));
        }
        for (int dy = 1; dy <= 3; dy++) {
            world.setBlockState(new BlockPos(x - 5, y + dy, z + 8), Blocks.LAPIS_BLOCK.getDefaultState(), 3);
            world.setBlockState(new BlockPos(x + 5, y + dy, z + 8), Blocks.GOLD_BLOCK.getDefaultState(), 3);
        }
        world.setBlockState(layout.marker, Blocks.DIAMOND_BLOCK.getDefaultState(), 3);
        return layout;
    }

    private void seedParticles() {
        // These use the current ParticleManager factory, including a mod replacement. Its
        // ordinary update/render passes remain responsible for every captured draw.
        for (int i = seeded.size() - 1; i >= 0; i--) if (!seeded.get(i).isAlive()) seeded.remove(i);
        for (int i = 0; i < 12; i++) {
            double row = (i % 4 - 1.5) * 0.35, height = (i / 4) * 0.35;
            remember(mc.effectRenderer.spawnEffectParticle(EnumParticleTypes.SMOKE_NORMAL.getParticleID(),
                    layout.x - 3 + row, layout.y + 2 + height, layout.z + 10.5, 0, 0.005, 0));
            remember(mc.effectRenderer.spawnEffectParticle(EnumParticleTypes.FLAME.getParticleID(),
                    layout.x + 3 + row, layout.y + 2 + height, layout.z + 10.5, 0, 0.002, 0));
            remember(mc.effectRenderer.spawnEffectParticle(EnumParticleTypes.BLOCK_DUST.getParticleID(),
                    layout.x + row, layout.y + 3 + height, layout.z + 10.5, 0, 0.002, 0,
                    Block.getStateId(Blocks.REDSTONE_BLOCK.getDefaultState())));
        }
        bursts++;
        if (seeded.isEmpty()) throw new IllegalStateException("ParticleManager rejected every fixture particle factory");
    }
    private void remember(Particle particle) {
        if (particle == null) return;
        if (seeded.size() == 192) seeded.remove(0).setExpired();
        particle.setMaxAge(60);
        seeded.add(particle);
    }
    private int liveSeeded() { int count = 0; for (Particle particle : seeded) if (particle.isAlive()) count++; return count; }
    private boolean emitting() {
        return phase == Phase.PARTICLES || phase == Phase.PARTICLE_MOTION || phase == Phase.RAIN
                || phase == Phase.RAIN_READY || phase == Phase.RAIN_MOTION || phase == Phase.MOVE || phase == Phase.MOVED
                || phase == Phase.RELOAD || phase == Phase.RELOADED;
    }
    private void clearParticles() {
        mc.gameSettings.particleSetting = 2;
        for (Particle particle : seeded) particle.setExpired();
        seeded.clear();
        // The unique smoke save contains no user particle state. Clearing at END tick
        // makes the empty-frame proof deterministic despite ambient mod particle sources.
        mc.effectRenderer.clearEffects(clientWorld);
    }
    private boolean wet() { return clientWorld.getRainStrength(1) > 0.95f && events.weatherQuads() > 0; }
    private boolean customReady() {
        return custom != null && events.effectAdditivePrimitives() >= 4 && events.effectTrianglePrimitives() >= 4;
    }
    private boolean dry() {
        return clientWorld.getRainStrength(1) < 0.01f && events.particleQuads() == 0
                && events.weatherQuads() == 0 && events.effectBatchCount() == 0;
    }
    private boolean markerReady() {
        BlockPos marker = layout.marker;
        return clientWorld.getBlockState(marker).getBlock() == Blocks.DIAMOND_BLOCK
                && clientWorld.getBiome(marker) == Biomes.PLAINS
                && events.terrainSectionReady(marker.getX() >> 4, marker.getY() >> 4, marker.getZ() >> 4);
    }
    private void prepareRainBiome() {
        // Only the dedicated scene's precipitation footprint is standardized. Full chunk
        // packets also update clients that received these columns before fixture setup.
        int biome = Biome.getIdForBiome(Biomes.PLAINS);
        for (int x = (layout.x - 16) >> 4; x <= ((layout.x + 20) >> 4); x++) {
            for (int z = layout.z >> 4; z <= ((layout.z + 36) >> 4); z++) {
                Chunk chunk = world.getChunk(x, z);
                Arrays.fill(chunk.getBiomeArray(), (byte) biome);
                chunk.markDirty();
                player().connection.sendPacket(new SPacketChunkData(chunk, 65535));
            }
        }
    }
    private void requireWorld() {
        if (!server.isCallingFromMinecraftThread() || !saveName.equals(server.getFolderName()) || server.getWorld(0) != world)
            throw new IllegalStateException("Effect fixture no longer owns the server world");
    }
    private EntityPlayerMP player() { return server.getPlayerList().getPlayerByUUID(playerId); }
    private boolean complete() throws Exception { if (!action.isDone()) return false; action.get(); return true; }
    private boolean settled(boolean ready) {
        if (!ready) { readyFrame = -1; return false; }
        if (readyFrame < 0) readyFrame = events.renderedFrames();
        return events.renderedFrames() - readyFrame >= SETTLE_FRAMES;
    }
    private double cameraX(boolean displaced) { return layout.x + (displaced ? 4.5 : 0.5); }
    private double cameraY() { return layout.y + 5.0; }
    private double cameraZ() { return layout.z + 20.5; }
    private void holdPose() {
        double x = cameraX(moved), y = cameraY(), z = cameraZ(); float yaw = moved ? 165f : 180f;
        mc.player.setPositionAndRotation(x, y, z, yaw, 16);
        mc.player.prevPosX = mc.player.lastTickPosX = x; mc.player.prevPosY = mc.player.lastTickPosY = y;
        mc.player.prevPosZ = mc.player.lastTickPosZ = z; mc.player.prevRotationYaw = yaw; mc.player.prevRotationPitch = 16;
        mc.player.motionX = mc.player.motionY = mc.player.motionZ = 0; mc.player.capabilities.isFlying = true;
    }
    private void transition(Phase next) { phase = next; since = lastProgress = System.nanoTime(); readyFrame = -1; log("PHASE"); }
    private void capture(String name) throws Exception { output.capture(name, output.image()); }
    private void log(String event) { output.log("EFFECT_" + event, status()); }
    String status() {
        return "phase=" + phase + " batches=" + events.effectBatchCount() + " particleQuads=" + events.particleQuads()
                + " weatherQuads=" + events.weatherQuads() + " resources=" + events.effectResourceCount()
                + " bytes=" + events.effectRetainedBytes() + " particleFrames=" + events.particleFrameCount()
                + " weatherFrames=" + events.weatherFrameCount() + " particleHash=" + events.particleGeometryHash()
                + " weatherHash=" + events.weatherGeometryHash() + " liveSeeded=" + liveSeeded() + " bursts=" + bursts
                + " textureSnapshots=" + events.effectTextureSnapshotCount() + " reloads=" + events.textureReloadCount()
                + " skippedQuads=" + events.effectSkippedQuads()
                + " additive=" + events.effectAdditivePrimitives() + " triangles=" + events.effectTrianglePrimitives()
                + " textureUpdates=" + events.effectTextureUpdateCount() + " texturePatches=" + events.effectTexturePatchCount()
                + " dirtyTextures=" + events.effectDirtyTextureCount() + " droppedUpdates=" + events.effectTextureDroppedUpdateCount()
                + " authoredRevision=" + (custom == null ? -1 : custom.revision())
                + " rain=" + clientWorld.getRainStrength(1) + " cameraMoved=" + moved;
    }

    /** Call on every controller cleanup path, including fixture failure and world loss. */
    @Override public void close() {
        if (closed) return;
        try {
            if (custom != null) { custom.close(); custom = null; }
        } finally {
            for (Particle particle : seeded) particle.setExpired();
            seeded.clear();
            try {
                if (mc.world == clientWorld && mc.getIntegratedServer() == server) mc.effectRenderer.clearEffects(clientWorld);
            } finally {
                mc.gameSettings.particleSetting = originalParticleSetting;
                closed = true;
            }
        }
    }
    static final class Layout {
        final int x, y, z;
        final BlockPos marker;
        Layout(int x, int y, int z) { this.x = x; this.y = y; this.z = z; marker = new BlockPos(x, y + 1, z + 8); }
    }
}
