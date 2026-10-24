/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.EntityCow;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

/** Real server dimension roundtrip while native rendering remains enabled throughout the transfer. */
final class FixtureDimensions implements AutoCloseable {
    private static final int[] ROUTE = {0, -1, 1, 0};
    private enum Phase { TRAVEL, ARRIVAL, RESTORE, RETURNED, DONE }
    private final Minecraft mc;
    private final ClientEvents events;
    private final IntegratedServer server;
    private final WorldServer overworld;
    private final String save;
    private final UUID playerId;
    private final FixtureTerrain.Output output;
    private final int originalView, originalDimension;
    private final boolean originalHud, originalFlying;
    private final double returnX, returnY, returnZ;
    private final float returnYaw, returnPitch;
    private final List<Landing> owned = new ArrayList<>();
    private final List<Landing> observed = new ArrayList<>();
    private Phase phase = Phase.TRAVEL;
    private Future<?> action;
    private Landing landing;
    private WorldClient previousClientWorld;
    private long since = System.nanoTime(), progress = since, readyFrame = -1;
    private int leg;
    private boolean cleanupQueued, serverFlying, serverStateCaptured;

    FixtureDimensions(Minecraft mc, ClientEvents events, IntegratedServer server, WorldServer overworld,
            String save, double x, double y, double z, float yaw, float pitch, FixtureTerrain.Output output) {
        FixtureCompatibility.requireSmokeSave(save);
        if (mc.world == null || mc.player == null || mc.world.provider.getDimension() != 0)
            throw new IllegalStateException("Dimension fixture must start in the owned overworld");
        this.mc = mc; this.events = events; this.server = server; this.overworld = overworld;
        this.save = save; this.output = output; playerId = mc.player.getUniqueID();
        originalDimension = mc.world.provider.getDimension(); originalView = mc.gameSettings.thirdPersonView;
        originalHud = mc.gameSettings.hideGUI; originalFlying = mc.player.capabilities.isFlying;
        returnX = x; returnY = y; returnZ = z; returnYaw = yaw; returnPitch = pitch;
        mc.gameSettings.thirdPersonView = 0; mc.gameSettings.hideGUI = true;
        previousClientWorld = mc.world; action = travel(0); log("STARTED");
    }

    /** Called on client ticks, including frames where a transfer temporarily has no WorldClient. */
    boolean tick() throws Exception {
        if (mc.getIntegratedServer() != server || !save.equals(server.getFolderName()) || !events.isEnabled()
                || events.failureMessage() != null) throw new IllegalStateException("Dimension fixture renderer/server unavailable");
        if (System.nanoTime() - since > TimeUnit.SECONDS.toNanos(300)) {
            capture("dimensions-timeout-" + phase.name().toLowerCase(Locale.ROOT));
            throw new IllegalStateException("Dimension fixture timed out: " + status());
        }
        if (System.nanoTime() - progress > TimeUnit.SECONDS.toNanos(10)) { log("PROGRESS"); progress = System.nanoTime(); }
        switch (phase) {
            case TRAVEL:
                if (action.isDone()) {
                    landing = (Landing) action.get(); observed.add(landing); transition(Phase.ARRIVAL);
                }
                break;
            case ARRIVAL:
                if (atDestination()) holdPose();
                if (settled(arrived(), 45)) {
                    capture("dimensions-" + leg + "-" + dimensionName(landing.dimension)); log("LEG_PASS");
                    if (++leg < ROUTE.length) {
                        previousClientWorld = mc.world; action = travel(leg); transition(Phase.TRAVEL);
                    } else {
                        restoreClient(); action = restoreServer(); cleanupQueued = true; transition(Phase.RESTORE);
                    }
                }
                break;
            case RESTORE:
                if (action.isDone()) { action.get(); transition(Phase.RETURNED); }
                break;
            case RETURNED:
                if (settled(returned(), 30)) { capture("dimensions-roundtrip-restored"); transition(Phase.DONE); log("PASS"); }
                break;
            default: break;
        }
        return phase == Phase.DONE;
    }

    private Future<Landing> travel(int index) {
        return server.callFromMainThread(() -> {
            requireServer(); EntityPlayerMP player = player();
            if (!serverStateCaptured) { serverFlying = player.capabilities.isFlying; serverStateCaptured = true; }
            WorldServer destination = server.getWorld(ROUTE[index]);
            if (destination == null) throw new IllegalStateException("Fixture dimension is unavailable: " + ROUTE[index]);
            Landing next = new Landing(ROUTE[index], 1024 + index * 160, 80, 1024, index);
            owned.add(next); buildLanding(destination, next);
            player.capabilities.isFlying = true; player.sendPlayerAbilities();
            movePlayer(player, destination, next.x + 0.5, next.y + 3, next.z + 6.5, 180, 12);
            return next;
        });
    }

    private static void buildLanding(WorldServer world, Landing landing) {
        Block floor = landing.dimension == -1 ? Blocks.NETHER_BRICK : landing.dimension == 1 ? Blocks.END_STONE : Blocks.STONEBRICK;
        for (int dx = -8; dx <= 8; dx++) for (int dz = -8; dz <= 8; dz++) {
            world.setBlockState(new BlockPos(landing.x + dx, landing.y, landing.z + dz), floor.getDefaultState(), 3);
            for (int dy = 1; dy <= 12; dy++) world.setBlockToAir(new BlockPos(landing.x + dx, landing.y + dy, landing.z + dz));
        }
        world.setBlockState(landing.marker, landing.markerBlock().getDefaultState(), 3);
        world.setBlockState(landing.light, Blocks.TORCH.getDefaultState(), 3);
        EntityCow cow = new EntityCow(world); cow.setUniqueId(landing.owner);
        cow.setPositionAndRotation(landing.x + 0.5, landing.y + 1, landing.z - 3.5, 0, 0);
        cow.setNoAI(true); cow.enablePersistence(); cow.setNoGravity(true); cow.setSilent(true); cow.setEntityInvulnerable(true);
        landing.entity = cow;
        if (!world.spawnEntity(cow)) throw new IllegalStateException("Could not spawn dimension fixture marker");
    }

    private void movePlayer(EntityPlayerMP player, WorldServer destination, double x, double y, double z, float yaw, float pitch) {
        if (player.dimension != destination.provider.getDimension()) {
            server.getPlayerList().transferPlayerToDimension(player, destination.provider.getDimension(),
                    new FixtureTeleporter(destination, player, x, y, z, yaw, pitch));
        } else player.connection.setPlayerLocation(x, y, z, yaw, pitch);
        player.motionX = player.motionY = player.motionZ = 0; player.fallDistance = 0;
    }

    private boolean atDestination() {
        return mc.world != null && mc.player != null && mc.world.provider.getDimension() == landing.dimension
                && mc.player.dimension == landing.dimension && (leg == 0 || mc.world != previousClientWorld);
    }
    private boolean arrived() {
        if (!atDestination() || mc.world.getBlockState(landing.marker).getBlock() != landing.markerBlock()
                || !events.entityCaptured(landing.owner) || !events.blockLightCaptured(landing.light)
                || !events.terrainSectionReady(landing.marker.getX() >> 4, landing.marker.getY() >> 4, landing.marker.getZ() >> 4)
                || events.skyResourceCount() == 0 || events.cameraMediumUnderwater()) return false;
        for (Landing previous : observed) {
            if (previous == landing) continue;
            if (events.entityCaptured(previous.owner) || events.blockLightCaptured(previous.light)) return false;
        }
        return true;
    }
    private boolean returned() {
        if (mc.world == null || mc.player == null || mc.world.provider.getDimension() != originalDimension
                || mc.player.getDistanceSq(returnX, returnY, returnZ) > 1 || events.cameraMediumUnderwater()) return false;
        for (Landing previous : observed) if (events.entityCaptured(previous.owner)) return false;
        return true;
    }
    private void holdPose() {
        double x = landing.x + 0.5, y = landing.y + 3, z = landing.z + 6.5;
        mc.player.setPositionAndRotation(x, y, z, 180, 12);
        mc.player.prevPosX = mc.player.lastTickPosX = x; mc.player.prevPosY = mc.player.lastTickPosY = y;
        mc.player.prevPosZ = mc.player.lastTickPosZ = z; mc.player.prevRotationYaw = 180; mc.player.prevRotationPitch = 12;
        mc.player.motionX = mc.player.motionY = mc.player.motionZ = 0; mc.player.capabilities.isFlying = true;
    }
    private EntityPlayerMP player() {
        EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(playerId);
        if (player == null) throw new IllegalStateException("Dimension fixture player unavailable"); return player;
    }
    private void requireServer() {
        FixtureCompatibility.requireSmokeSave(save);
        if (!server.isCallingFromMinecraftThread() || !save.equals(server.getFolderName()) || server.getWorld(0) != overworld)
            throw new IllegalStateException("Dimension fixture no longer owns the server");
    }
    private Future<?> restoreServer() {
        return server.callFromMainThread(() -> {
            requireServer();
            for (Landing previous : owned) {
                WorldServer destination = server.getWorld(previous.dimension);
                // A dimension can unload while unoccupied. Load only our marker's chunk and
                // remove its UUID, including a new object reconstructed from saved NBT.
                destination.getChunk(previous.x >> 4, (previous.z - 4) >> 4);
                Entity live = destination.getEntityFromUuid(previous.owner);
                if (live != null) live.setDead();
                if (previous.entity != null) previous.entity.setDead();
            }
            EntityPlayerMP player = player();
            movePlayer(player, server.getWorld(originalDimension), returnX, returnY, returnZ, returnYaw, returnPitch);
            if (serverStateCaptured) player.capabilities.isFlying = serverFlying;
            player.sendPlayerAbilities(); return null;
        });
    }
    private void restoreClient() {
        mc.gameSettings.thirdPersonView = originalView; mc.gameSettings.hideGUI = originalHud;
        if (mc.player != null && playerId.equals(mc.player.getUniqueID())) mc.player.capabilities.isFlying = originalFlying;
    }
    private boolean settled(boolean ready, int frames) {
        if (!ready) { readyFrame = -1; return false; }
        if (readyFrame < 0) readyFrame = events.renderedFrames(); return events.renderedFrames() - readyFrame >= frames;
    }
    private void transition(Phase next) { phase = next; since = progress = System.nanoTime(); readyFrame = -1; log("PHASE"); }
    private void capture(String name) throws Exception { output.capture(name, output.image()); }
    private void log(String name) { output.log("DIMENSIONS_" + name, status()); }
    String status() {
        return "phase=" + phase + " leg=" + leg + " target=" + (landing == null ? ROUTE[0] : landing.dimension)
                + " client=" + (mc.world == null ? "none" : mc.world.provider.getDimension())
                + " world=" + (mc.world == null ? 0 : System.identityHashCode(mc.world))
                + " newWorld=" + (mc.world != null && mc.world != previousClientWorld)
                + " owner=" + (landing != null && events.entityCaptured(landing.owner))
                + " light=" + (landing != null && events.blockLightCaptured(landing.light))
                + " sky=" + events.skyResourceCount() + " terrain=" + events.terrainSectionCount()
                + " medium=" + events.cameraMediumUnderwater() + " frames=" + events.renderedFrames();
    }
    private static String dimensionName(int dimension) { return dimension == -1 ? "nether" : dimension == 1 ? "end" : "overworld"; }
    /** Allows SmokeController to finish a failed transfer before checking/restoring its overworld scope. */
    boolean cleanupComplete() throws Exception {
        if (!cleanupQueued || !action.isDone()) return false;
        action.get();
        return mc.world != null && mc.player != null && mc.world.provider.getDimension() == originalDimension
                && mc.player.getDistanceSq(returnX, returnY, returnZ) <= 1;
    }
    @Override public void close() {
        restoreClient();
        if (!cleanupQueued) { cleanupQueued = true; action = restoreServer(); }
    }

    private static final class Landing {
        final int dimension, x, y, z, index;
        final UUID owner = UUID.randomUUID();
        final BlockPos marker, light;
        Entity entity;
        Landing(int dimension, int x, int y, int z, int index) {
            this.dimension = dimension; this.x = x; this.y = y; this.z = z; this.index = index;
            marker = new BlockPos(x + 4, y + 1, z - 4); light = new BlockPos(x - 4, y + 1, z - 4);
        }
        Block markerBlock() {
            switch (index) {
                case 0: return Blocks.GOLD_BLOCK;
                case 1: return Blocks.DIAMOND_BLOCK;
                case 2: return Blocks.EMERALD_BLOCK;
                default: return Blocks.LAPIS_BLOCK;
            }
        }
    }
}
