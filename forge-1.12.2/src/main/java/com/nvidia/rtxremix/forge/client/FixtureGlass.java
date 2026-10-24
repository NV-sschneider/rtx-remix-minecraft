/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.minecraft.block.BlockStainedGlass;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

/** Full glass volumes, populated reload and removal evidence in an owned smoke scene. */
final class FixtureGlass implements AutoCloseable {
    private static final long TIMEOUT = TimeUnit.SECONDS.toNanos(300);
    private static final long PROGRESS = TimeUnit.SECONDS.toNanos(10);
    private static final int SETTLE_FRAMES = 20;
    private enum Phase { ARRIVE, FRONT, MOVE, ANGLED, RETURN_FRONT, BASELINE, RELOAD, REMOVE, REMOVED, RETURN, DONE }

    private final Minecraft mc;
    private final ClientEvents events;
    private final IntegratedServer server;
    private final WorldServer world;
    private final WorldClient clientWorld;
    private final String saveName;
    private final UUID playerId;
    private final Layout layout;
    private final FixtureTerrain.Output output;
    private final double returnX, returnY, returnZ;
    private final float returnYaw, returnPitch;
    private final long[] removalRevisions;
    private final PublicationSettlement settlement = new PublicationSettlement();
    private Phase phase = Phase.ARRIVE;
    private Future<?> action;
    private long since = System.nanoTime(), lastProgress = since;
    private long reloadBaseline, clearBaseline, stainedBaseline;
    private boolean angled, closed;

    FixtureGlass(Minecraft mc, ClientEvents events, IntegratedServer server, WorldServer world,
            String saveName, Layout layout, double x, double y, double z, float yaw, float pitch,
            FixtureTerrain.Output output) {
        if (!saveName.startsWith("rlcraft-remix-smoke-") || !saveName.equals(server.getFolderName())
                || mc.getIntegratedServer() != server || mc.world == null) {
            throw new IllegalArgumentException("Glass fixture requires its dedicated smoke world");
        }
        this.mc = mc; this.events = events; this.server = server; this.world = world;
        clientWorld = mc.world; this.saveName = saveName; this.layout = layout; this.output = output;
        playerId = mc.player.getUniqueID();
        returnX = x; returnY = y; returnZ = z; returnYaw = yaw; returnPitch = pitch;
        removalRevisions = new long[layout.sections.length];
        action = server.callFromMainThread(() -> {
            requireWorld();
            FixtureEnvironment.setSky(world, 6000L, false);
            movePlayer(false);
            return null;
        });
        log("STARTED");
    }

    boolean tick() throws Exception {
        if (closed || mc.world != clientWorld || mc.getIntegratedServer() != server
                || !events.isEnabled() || events.failureMessage() != null) {
            throw new IllegalStateException("Glass fixture renderer/world unavailable");
        }
        if (System.nanoTime() - since >= TIMEOUT) {
            log("TIMEOUT"); capture("glass-timeout-" + phase.name().toLowerCase(Locale.ROOT));
            throw new IllegalStateException("Glass fixture timed out: " + status());
        }
        if (System.nanoTime() - lastProgress >= PROGRESS) { log("PROGRESS"); lastProgress = System.nanoTime(); }
        if (phase != Phase.RETURN && phase != Phase.DONE) holdPose(cameraX(angled), cameraY(), cameraZ(), cameraYaw(angled), 3f);
        switch (phase) {
            case ARRIVE:
                if (complete()) {
                    FixtureEnvironment.setSky(clientWorld, 6000L, false);
                    transition(Phase.FRONT);
                }
                break;
            case FRONT:
                if (settled(sceneReady())) {
                    capture("glass-front"); log("CAPTURE_PASS");
                    angled = true;
                    action = server.callFromMainThread(() -> { requireWorld(); movePlayer(true); return null; });
                    transition(Phase.MOVE);
                }
                break;
            case MOVE:
                if (complete()) transition(Phase.ANGLED);
                break;
            case ANGLED:
                if (settled(sceneReady())) {
                    capture("glass-angled");
                    angled = false;
                    action = server.callFromMainThread(() -> { requireWorld(); movePlayer(false); return null; });
                    transition(Phase.RETURN_FRONT);
                }
                break;
            case RETURN_FRONT:
                if (complete()) transition(Phase.BASELINE);
                break;
            case BASELINE:
                if (settled(sceneReady())) {
                    reloadBaseline = events.textureReloadCount();
                    events.reload();
                    transition(Phase.RELOAD);
                }
                break;
            case RELOAD:
                if (settled(sceneReady() && events.textureReloadCount() > reloadBaseline)) {
                    capture("glass-reloaded"); log("RELOAD_PASS");
                    clearBaseline = events.terrainClearGlassVertexCount();
                    stainedBaseline = events.terrainStainedGlassVertexCount();
                    for (int i = 0; i < removalRevisions.length; i++) removalRevisions[i] = revision(layout.sections[i]);
                    action = server.callFromMainThread(() -> {
                        requireWorld();
                        for (BlockPos pos : layout.glass) world.setBlockToAir(pos);
                        return null;
                    });
                    transition(Phase.REMOVE);
                }
                break;
            case REMOVE:
                if (complete()) transition(Phase.REMOVED);
                break;
            case REMOVED:
                // Other fixture scenes and the surrounding world may still own glass.
                if (settled(removed() && events.terrainClearGlassVertexCount() < clearBaseline
                        && events.terrainStainedGlassVertexCount() < stainedBaseline)) {
                    capture("glass-removed"); log("REMOVAL_PASS");
                    action = server.callFromMainThread(() -> { requireWorld(); restorePlayer(); return null; });
                    transition(Phase.RETURN);
                }
                break;
            case RETURN:
                if (complete()) {
                    holdPose(returnX, returnY, returnZ, returnYaw, returnPitch);
                    transition(Phase.DONE); log("PASS");
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
            throw new IllegalStateException("Glass scene construction requires its owned server thread and smoke save");
        }
        Layout layout = new Layout(x, y, z);
        for (int dx = -9; dx <= 9; dx++) for (int dz = 2; dz <= 16; dz++) {
            world.setBlockState(new BlockPos(x + dx, y, z + dz),
                    ((dx + dz) & 1) == 0 ? Blocks.QUARTZ_BLOCK.getDefaultState() : Blocks.STONE.getDefaultState(), 3);
            for (int dy = 1; dy <= 8; dy++) world.setBlockToAir(new BlockPos(x + dx, y + dy, z + dz));
        }
        for (int i = 0; i < layout.glass.length; i++) {
            BlockPos glass = layout.glass[i];
            world.setBlockState(glass, glassState(i / 9), 3);
            world.setBlockState(layout.markers[i], markerState(i), 3);
            world.setBlockState(glass.north(4), Blocks.QUARTZ_BLOCK.getDefaultState(), 3);
        }
        return layout;
    }

    private static IBlockState glassState(int panel) {
        return panel == 0 ? Blocks.GLASS.getDefaultState() : Blocks.STAINED_GLASS.getDefaultState()
                .withProperty(BlockStainedGlass.COLOR, panel == 1 ? EnumDyeColor.RED : EnumDyeColor.BLUE);
    }
    private static IBlockState markerState(int index) {
        int stripe = (index / 3) % 3;
        return (stripe == 0 ? Blocks.REDSTONE_BLOCK : stripe == 1 ? Blocks.EMERALD_BLOCK : Blocks.LAPIS_BLOCK).getDefaultState();
    }
    private boolean sceneReady() {
        if (events.terrainClearGlassVertexCount() == 0 || events.terrainStainedGlassVertexCount() == 0) return false;
        for (int i = 0; i < layout.glass.length; i++) {
            if (!clientWorld.getBlockState(layout.glass[i]).equals(glassState(i / 9))) return false;
            BlockPos marker = layout.markers[i];
            if (!clientWorld.getBlockState(marker).equals(markerState(i))
                    || !events.terrainSectionReady(marker.getX() >> 4, marker.getY() >> 4, marker.getZ() >> 4)) return false;
        }
        for (BlockPos section : layout.sections) if (!sectionReady(section)) return false;
        return true;
    }
    private boolean removed() {
        for (BlockPos pos : layout.glass) if (!clientWorld.isAirBlock(pos)) return false;
        for (int i = 0; i < layout.sections.length; i++) {
            BlockPos section = layout.sections[i];
            if (!sectionReady(section) || revision(section) <= removalRevisions[i]) return false;
        }
        return true;
    }
    private boolean sectionReady(BlockPos section) { return events.terrainSectionReady(section.getX(), section.getY(), section.getZ()); }
    private long revision(BlockPos section) { return events.terrainSectionRevision(section.getX(), section.getY(), section.getZ()); }
    private boolean settled(boolean ready) {
        return settlement.update(ready, events.terrainPendingSectionCount(), events.terrainRetirementCount(),
                events.terrainClearGlassVertexCount(), events.terrainStainedGlassVertexCount(), events.renderedFrames());
    }
    private boolean complete() throws Exception { if (!action.isDone()) return false; action.get(); return true; }
    private double cameraX(boolean side) { return layout.x + (side ? 6.5 : 0.5); }
    private double cameraY() { return layout.y + 1.5; }
    private double cameraZ() { return layout.z + 20.5; }
    private float cameraYaw(boolean side) { return side ? 155f : 180f; }
    private void movePlayer(boolean side) {
        player().connection.setPlayerLocation(cameraX(side), cameraY(), cameraZ(), cameraYaw(side), 3f);
    }
    private void restorePlayer() { player().connection.setPlayerLocation(returnX, returnY, returnZ, returnYaw, returnPitch); }
    private void holdPose(double x, double y, double z, float yaw, float pitch) {
        mc.player.setPositionAndRotation(x, y, z, yaw, pitch);
        mc.player.prevPosX = mc.player.lastTickPosX = x; mc.player.prevPosY = mc.player.lastTickPosY = y;
        mc.player.prevPosZ = mc.player.lastTickPosZ = z;
        mc.player.prevRotationYaw = mc.player.rotationYawHead = mc.player.prevRotationYawHead = yaw;
        mc.player.prevRotationPitch = pitch;
        mc.player.motionX = mc.player.motionY = mc.player.motionZ = 0; mc.player.capabilities.isFlying = true;
    }
    private EntityPlayerMP player() {
        EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(playerId);
        if (player == null) throw new IllegalStateException("Glass fixture player is absent");
        return player;
    }
    private void requireWorld() {
        if (!server.isCallingFromMinecraftThread() || !saveName.equals(server.getFolderName()) || server.getWorld(0) != world)
            throw new IllegalStateException("Glass fixture no longer owns the server world");
    }
    private void transition(Phase next) { phase = next; since = lastProgress = System.nanoTime(); settlement.reset(); log("PHASE"); }
    private void capture(String name) throws Exception { output.log("GLASS_CAPTURE", status() + " file=" + output.capture(name, output.image())); }
    private void log(String event) { output.log("GLASS_" + event, status()); }
    String status() {
        return "glassPhase=" + phase + " clearVertices=" + events.terrainClearGlassVertexCount()
                + " stainedVertices=" + events.terrainStainedGlassVertexCount() + " clearBaseline=" + clearBaseline
                + " stainedBaseline=" + stainedBaseline + " pendingSections=" + events.terrainPendingSectionCount()
                + " retirement=" + events.terrainRetirementCount()
                + " ready=" + sceneReady() + " removed=" + removed() + " reloads=" + events.textureReloadCount();
    }

    /** Global glass totals are comparable only after unrelated terrain publication finishes. */
    static final class PublicationSettlement {
        private long readyFrame = -1, lastClear = -1, lastStained = -1;

        boolean update(boolean ready, int pending, int retiring, long clear, long stained, long frame) {
            boolean complete = ready && pending == 0 && retiring == 0;
            if (!complete || clear != lastClear || stained != lastStained) readyFrame = -1;
            lastClear = clear;
            lastStained = stained;
            if (!complete) return false;
            if (readyFrame < 0) readyFrame = frame;
            return frame - readyFrame >= SETTLE_FRAMES;
        }

        void reset() { readyFrame = -1; }
    }

    /** Parent cleanup restores time/weather after this fixture's queued pose restoration. */
    @Override public void close() {
        if (closed) return;
        if (phase != Phase.DONE && mc.world == clientWorld && mc.getIntegratedServer() == server && mc.player != null
                && playerId.equals(mc.player.getUniqueID())) {
            holdPose(returnX, returnY, returnZ, returnYaw, returnPitch);
            server.callFromMainThread(() -> { requireWorld(); restorePlayer(); return null; });
        }
        closed = true;
    }

    static final class Layout {
        final int x, y, z;
        final BlockPos[] glass = new BlockPos[27];
        final BlockPos[] markers = new BlockPos[27];
        final BlockPos[] sections;
        Layout(int x, int y, int z) {
            this.x = x; this.y = y; this.z = z;
            Set<BlockPos> touched = new LinkedHashSet<>();
            int index = 0;
            for (int panel = 0; panel < 3; panel++) for (int dx = -1; dx <= 1; dx++) for (int dy = 1; dy <= 3; dy++) {
                BlockPos pos = new BlockPos(x + (panel - 1) * 5 + dx, y + dy, z + 8);
                markers[index] = pos.north(3);
                glass[index++] = pos;
                touched.add(new BlockPos(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4));
            }
            sections = touched.toArray(new BlockPos[0]);
        }
    }
}
