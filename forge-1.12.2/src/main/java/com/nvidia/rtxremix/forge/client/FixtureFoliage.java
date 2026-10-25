/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.minecraft.block.BlockLeaves;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

/** Isolated foliage scene checks unlit material stability, resource reload, and native retirement. */
final class FixtureFoliage implements AutoCloseable {
    private static final long TIMEOUT = TimeUnit.SECONDS.toNanos(240), PROGRESS = TimeUnit.SECONDS.toNanos(10);
    private enum Phase { ARRIVE, AO_ON, AO_OFF, RELOAD, GRASS_FRONT, GRASS_ANGLED, REMOVE, REMOVED, RETURN, DONE }
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
    private final int originalAo;
    private Phase phase = Phase.ARRIVE;
    private Future<String> action, serverSample;
    private String actionName = "arrive", serverBlocks = "not-sampled";
    private boolean actionComplete;
    private long since = System.nanoTime(), lastProgress = since, readyFrame = -1;
    private long reloadBaseline, revisionBaseline, geometryHash, foliageVertices, overlayVertices;
    private boolean closed, sideView, angled;

    FixtureFoliage(Minecraft mc, ClientEvents events, IntegratedServer server, WorldServer world,
            String saveName, Layout layout, double x, double y, double z, float yaw, float pitch,
            FixtureTerrain.Output output) {
        if (!saveName.startsWith("rlcraft-remix-smoke-") || !saveName.equals(server.getFolderName())
                || mc.getIntegratedServer() != server || mc.world == null)
            throw new IllegalArgumentException("Foliage fixture requires its dedicated smoke world");
        if (!events.betterFoliageActive()) throw new IllegalStateException("Foliage fixture requires supported Better Foliage");
        this.mc = mc; this.events = events; this.server = server; this.world = world;
        clientWorld = mc.world; this.saveName = saveName; this.layout = layout; this.output = output;
        playerId = mc.player.getUniqueID(); returnX = x; returnY = y; returnZ = z; returnYaw = yaw; returnPitch = pitch;
        originalAo = mc.gameSettings.ambientOcclusion;
        action = server.callFromMainThread(() -> {
            requireWorld(); FixtureEnvironment.setSky(world, 9000L, false);
            player().connection.setPlayerLocation(cameraX(), cameraY(), cameraZ(), 180f, 12f);
            return describeBlocks(world);
        });
        log("STARTED");
    }

    boolean tick() throws Exception {
        if (closed || mc.world != clientWorld || mc.getIntegratedServer() != server
                || !events.isEnabled() || events.failureMessage() != null)
            throw new IllegalStateException("Foliage fixture renderer/world unavailable");
        if (serverSample != null && serverSample.isDone()) {
            serverBlocks = serverSample.get(); serverSample = null; log("SERVER_BLOCKS");
        }
        if (System.nanoTime() - since >= TIMEOUT) {
            capture("foliage-timeout-" + phase.name().toLowerCase(Locale.ROOT));
            throw new IllegalStateException("Foliage fixture timed out: " + status());
        }
        if (System.nanoTime() - lastProgress >= PROGRESS) {
            if (serverSample == null) serverSample = server.callFromMainThread(() -> {
                requireWorld(); return describeBlocks(world);
            });
            log("PROGRESS"); lastProgress = System.nanoTime();
        }
        if (phase != Phase.RETURN && phase != Phase.DONE) holdPose(cameraX(), cameraY(), cameraZ(), cameraYaw(), cameraPitch());
        switch (phase) {
            case ARRIVE:
                if (complete() && blocksReady()) {
                    FixtureEnvironment.setSky(clientWorld, 9000L, false); reloadWithAo(2); transition(Phase.AO_ON);
                }
                break;
            case AO_ON:
                if (settled(reloaded() && vertices() > 0 && overlays() > 0)) {
                    geometryHash = hash(); foliageVertices = vertices(); overlayVertices = overlays();
                    capture("foliage-unlit-ao-on"); log("GEOMETRY_PASS");
                    reloadWithAo(0); transition(Phase.AO_OFF);
                }
                break;
            case AO_OFF:
                if (settled(reloaded() && vertices() > 0 && overlays() > 0)) {
                    requireSameGeometry(); capture("foliage-unlit-ao-off"); log("AO_INVARIANCE_PASS");
                    reloadBaseline = events.textureReloadCount(); events.reload(); transition(Phase.RELOAD);
                }
                break;
            case RELOAD:
                if (settled(reloaded() && vertices() > 0 && overlays() > 0)) {
                    requireSameGeometry(); capture("foliage-reloaded"); log("RELOAD_PASS");
                    sideView = true; moveView("grass-front"); transition(Phase.GRASS_FRONT);
                }
                break;
            case GRASS_FRONT:
                if (settled(complete() && ready() && overlays() == overlayVertices)) {
                    capture("grass-side-overlay-front"); log("OVERLAY_FRONT_PASS");
                    angled = true; moveView("grass-angled"); transition(Phase.GRASS_ANGLED);
                }
                break;
            case GRASS_ANGLED:
                if (settled(complete() && ready() && overlays() == overlayVertices)) {
                    capture("grass-side-overlay-angled"); log("OVERLAY_ANGLED_PASS");
                    revisionBaseline = revision();
                    actionName = "clear-features"; actionComplete = false;
                    action = server.callFromMainThread(() -> {
                        requireWorld(); clearFeatures(world, layout); return describeBlocks(world);
                    });
                    transition(Phase.REMOVE);
                }
                break;
            case REMOVE:
                if (complete()) transition(Phase.REMOVED);
                break;
            case REMOVED:
                if (settled(ready() && revision() > revisionBaseline && vertices() == 0 && overlays() == 0 && blocksRemoved())) {
                    capture("foliage-removed"); log("REMOVAL_PASS"); restoreAo();
                    actionName = "restore-player"; actionComplete = false;
                    action = server.callFromMainThread(() -> {
                        requireWorld(); restorePlayer(); return describeBlocks(world);
                    });
                    transition(Phase.RETURN);
                }
                break;
            case RETURN:
                if (complete()) { holdPose(returnX, returnY, returnZ, returnYaw, returnPitch); transition(Phase.DONE); log("PASS"); }
                break;
            default: break;
        }
        return phase == Phase.DONE;
    }

    private void reloadWithAo(int ao) {
        mc.gameSettings.ambientOcclusion = ao; mc.renderGlobal.loadRenderers();
        reloadBaseline = events.textureReloadCount(); events.reload();
    }
    private void restoreAo() {
        if (mc.gameSettings.ambientOcclusion == originalAo) return;
        mc.gameSettings.ambientOcclusion = originalAo;
        if (mc.world == clientWorld && mc.renderGlobal != null) { mc.renderGlobal.loadRenderers(); events.reload(); }
    }
    private boolean reloaded() { return events.textureReloadCount() > reloadBaseline && ready(); }
    private void requireSameGeometry() {
        if (geometryHash != hash() || foliageVertices != vertices() || overlayVertices != overlays())
            throw new IllegalStateException("Foliage unlit geometry changed across AO/reload: " + status());
    }
    private boolean blocksReady() {
        return clientWorld.isBlockLoaded(layout.leaf) && clientWorld.isBlockLoaded(layout.cactus)
                && clientWorld.getBlockState(layout.leaf).getBlock() == Blocks.LEAVES
                && clientWorld.getBlockState(layout.grass).getBlock() == Blocks.GRASS
                && clientWorld.getBlockState(layout.grassSide).getBlock() == Blocks.GRASS
                && clientWorld.getBlockState(layout.grassSide.down()).getBlock() == Blocks.DIRT
                && clientWorld.getBlockState(layout.cactus).getBlock() == Blocks.CACTUS
                && clientWorld.getBlockState(layout.cactus.up(2)).getBlock() == Blocks.CACTUS
                && clientWorld.getBlockState(layout.sand).getBlock() == Blocks.SAND
                && clientWorld.getBlockState(layout.support).getBlock() == Blocks.QUARTZ_BLOCK;
    }
    private boolean blocksRemoved() {
        return clientWorld.isAirBlock(layout.leaf) && clientWorld.isAirBlock(layout.cactus) && clientWorld.isAirBlock(layout.grassSide)
                && clientWorld.getBlockState(layout.grass).getBlock() == Blocks.QUARTZ_BLOCK;
    }
    private boolean ready() { return events.terrainSectionReady(layout.x >> 4, layout.y >> 4, layout.z >> 4); }
    private long revision() { return events.terrainSectionRevision(layout.x >> 4, layout.y >> 4, layout.z >> 4); }
    private long vertices() { return events.sectionBetterFoliageVertices(layout.x >> 4, layout.y >> 4, layout.z >> 4); }
    private long overlays() { return events.terrainSectionOverlayVertices(layout.x >> 4, layout.y >> 4, layout.z >> 4); }
    private long hash() { return events.sectionGeometryHash(layout.x >> 4, layout.y >> 4, layout.z >> 4); }
    private boolean settled(boolean ready) {
        if (!ready) { readyFrame = -1; return false; }
        if (readyFrame < 0) readyFrame = events.renderedFrames();
        return events.renderedFrames() - readyFrame >= 30;
    }
    private boolean complete() throws Exception {
        if (actionComplete) return true;
        if (!action.isDone()) return false;
        serverBlocks = action.get(); actionComplete = true; return true;
    }
    private double cameraX() { return layout.x + (angled ? 12.0 : 8.0); }
    private double cameraY() { return layout.y + (sideView ? 1.05 : 4.0); }
    private double cameraZ() { return layout.z + 14.5; }
    private float cameraYaw() { return angled ? 140f : 180f; }
    private float cameraPitch() { return sideView ? 2f : 12f; }
    private void moveView(String name) {
        actionName = name; actionComplete = false;
        double x = cameraX(), y = cameraY(), z = cameraZ(); float yaw = cameraYaw(), pitch = cameraPitch();
        action = server.callFromMainThread(() -> {
            requireWorld(); player().connection.setPlayerLocation(x, y, z, yaw, pitch); return describeBlocks(world);
        });
    }
    private void holdPose(double x, double y, double z, float yaw, float pitch) {
        mc.player.setPositionAndRotation(x, y, z, yaw, pitch);
        mc.player.prevPosX = mc.player.lastTickPosX = x; mc.player.prevPosY = mc.player.lastTickPosY = y;
        mc.player.prevPosZ = mc.player.lastTickPosZ = z;
        mc.player.prevRotationYaw = mc.player.rotationYawHead = mc.player.prevRotationYawHead = yaw;
        mc.player.prevRotationPitch = pitch; mc.player.motionX = mc.player.motionY = mc.player.motionZ = 0;
        mc.player.capabilities.isFlying = true;
    }
    private void requireWorld() {
        if (!server.isCallingFromMinecraftThread() || !saveName.equals(server.getFolderName()) || server.getWorld(0) != world)
            throw new IllegalStateException("Foliage fixture no longer owns its server world");
    }
    private EntityPlayerMP player() {
        EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(playerId);
        if (player == null) throw new IllegalStateException("Foliage fixture player absent"); return player;
    }
    private void restorePlayer() { player().connection.setPlayerLocation(returnX, returnY, returnZ, returnYaw, returnPitch); }
    private void transition(Phase next) { phase = next; since = lastProgress = System.nanoTime(); readyFrame = -1; log("PHASE"); }
    private void capture(String name) throws Exception { output.log("FOLIAGE_CAPTURE", status() + " file=" + output.capture(name, output.image())); }
    private void log(String event) { output.log("FOLIAGE_" + event, status()); }
    String status() {
        return "foliagePhase=" + phase + " ao=" + mc.gameSettings.ambientOcclusion
                + " phaseSeconds=" + TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - since)
                + " section=" + (layout.x >> 4) + "," + (layout.y >> 4) + "," + (layout.z >> 4)
                + " ready=" + ready() + " blocksReady=" + blocksReady() + " blocksRemoved=" + blocksRemoved()
                + " action=" + actionName + " actionDone=" + action.isDone() + " actionCancelled=" + action.isCancelled()
                + " actionObserved=" + actionComplete + " samplePending=" + (serverSample != null)
                + " stableFrames=" + (readyFrame < 0 ? 0 : events.renderedFrames() - readyFrame)
                + " sectionVertices=" + vertices() + " sectionHash=" + Long.toHexString(hash())
                + " sectionOverlayVertices=" + overlays() + " sceneOverlayVertices=" + events.terrainOverlayVertexCount()
                + " sceneVertices=" + events.betterFoliageVertexCount() + " captures=" + events.betterFoliageCaptureCount()
                + " reloads=" + events.textureReloadCount() + " reloadBaseline=" + reloadBaseline
                + " sectionRevision=" + revision() + " revisionBaseline=" + revisionBaseline
                + " pending=" + events.terrainPendingSectionCount() + " retirement=" + events.terrainRetirementCount()
                + " capacityLimited=" + events.terrainCapacityLimited()
                + " clientBlocks={" + describeBlocks(clientWorld) + "} serverBlocks={" + serverBlocks + "}";
    }
    private String describeBlocks(World source) {
        return "tick=" + source.getTotalWorldTime() + " leaf=" + describeBlock(source, layout.leaf)
                + " grass=" + describeBlock(source, layout.grass) + " cactus=" + describeBlock(source, layout.cactus)
                + " grassSide=" + describeBlock(source, layout.grassSide)
                + " cactusMiddle=" + describeBlock(source, layout.cactus.up())
                + " cactusTop=" + describeBlock(source, layout.cactus.up(2))
                + " sand=" + describeBlock(source, layout.sand) + " support=" + describeBlock(source, layout.support);
    }
    private static String describeBlock(World source, BlockPos pos) {
        String location = "(" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + ")";
        if (!source.isBlockLoaded(pos)) return location + ":unloaded";
        IBlockState state = source.getBlockState(pos);
        return location + ":" + state.getBlock().getRegistryName() + "#" + state.getBlock().getMetaFromState(state)
                + state.getProperties();
    }
    @Override public void close() {
        if (closed) return; restoreAo();
        if (phase != Phase.DONE && mc.world == clientWorld && mc.getIntegratedServer() == server && mc.player != null
                && playerId.equals(mc.player.getUniqueID())) {
            holdPose(returnX, returnY, returnZ, returnYaw, returnPitch);
            server.callFromMainThread(() -> { requireWorld(); restorePlayer(); return null; });
        }
        closed = true;
    }

    static Layout build(WorldServer world, int x, int y, int z) {
        if (!world.getMinecraftServer().isCallingFromMinecraftThread()
                || !world.getMinecraftServer().getFolderName().startsWith("rlcraft-remix-smoke-"))
            throw new IllegalStateException("Foliage construction requires its owned server thread and smoke save");
        Layout layout = new Layout(x & ~15, floorHeight(y), z & ~15);
        clearFeatures(world, layout);
        for (int dx = 2; dx <= 4; dx++) for (int dy = 1; dy <= 3; dy++)
            world.setBlockState(new BlockPos(layout.x + dx, layout.y + dy, layout.z + 6),
                    Blocks.LEAVES.getDefaultState().withProperty(BlockLeaves.CHECK_DECAY, false).withProperty(BlockLeaves.DECAYABLE, false), 3);
        for (int dx = 6; dx <= 9; dx++) for (int dz = 5; dz <= 8; dz++)
            world.setBlockState(new BlockPos(layout.x + dx, layout.y, layout.z + dz), Blocks.GRASS.getDefaultState(), 3);
        // Expose the four grass/dirt side transitions to fixed front and angled views.
        for (int dx = 6; dx <= 9; dx++) {
            world.setBlockState(new BlockPos(layout.x + dx, layout.y + 1, layout.z + 9), Blocks.DIRT.getDefaultState(), 3);
            world.setBlockState(new BlockPos(layout.x + dx, layout.y + 2, layout.z + 9), Blocks.GRASS.getDefaultState(), 3);
        }
        // Sand uses scheduled ticks even when randomTickSpeed is zero. Give the elevated
        // fixture a permanent support block, including in a four-layer flat world.
        world.setBlockState(layout.support, Blocks.QUARTZ_BLOCK.getDefaultState(), 3);
        world.setBlockState(layout.sand, Blocks.SAND.getDefaultState(), 3);
        for (int dy = 0; dy < 3; dy++) world.setBlockState(layout.cactus.up(dy), Blocks.CACTUS.getDefaultState(), 3);
        return layout;
    }
    static int floorHeight(int groundY) {
        // Keep all observed foliage in one section above the source floor, with room
        // below its sand and for the entire cleared section below the build limit.
        if (groundY < 0 || groundY > 240) throw new IllegalArgumentException("Foliage ground outside fixture height range");
        return Math.max(16, (groundY + 15) & ~15);
    }
    private static void clearFeatures(WorldServer world, Layout layout) {
        for (int dx = 0; dx < 16; dx++) for (int dz = 0; dz < 16; dz++) {
            world.setBlockState(new BlockPos(layout.x + dx, layout.y, layout.z + dz), Blocks.QUARTZ_BLOCK.getDefaultState(), 3);
            for (int dy = 1; dy < 16; dy++) world.setBlockToAir(new BlockPos(layout.x + dx, layout.y + dy, layout.z + dz));
        }
    }
    static final class Layout {
        final int x, y, z;
        final BlockPos leaf, grass, grassSide, cactus, sand, support;
        Layout(int x, int y, int z) {
            this.x = x; this.y = y; this.z = z;
            leaf = new BlockPos(x + 3, y + 2, z + 6); grass = new BlockPos(x + 7, y, z + 6);
            grassSide = new BlockPos(x + 7, y + 2, z + 9);
            cactus = new BlockPos(x + 12, y + 1, z + 6);
            sand = cactus.down(); support = sand.down();
        }
    }
}
