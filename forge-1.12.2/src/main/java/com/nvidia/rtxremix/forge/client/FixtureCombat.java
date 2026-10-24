/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import com.nvidia.rtxremix.forge.entity.EntityCaptureHooks;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.EntityCow;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.entity.projectile.EntitySmallFireball;
import net.minecraft.entity.projectile.EntitySnowball;
import net.minecraft.entity.projectile.EntityTippedArrow;
import net.minecraft.init.Blocks;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.WorldServer;

/** Server-owned projectiles and camera turns distinguish normal draws from supplementary capture. */
final class FixtureCombat implements AutoCloseable {
    private enum Phase { ARRIVE, READY, MOVE, MOVED, TURN, FACING_COW, BACK, FACING_PROJECTILES,
        RELOAD, INVENTORY, REMOVE, REMOVED, RETURN, DONE }
    private final Minecraft mc;
    private final ClientEvents events;
    private final IntegratedServer server;
    private final WorldServer world;
    private final String save;
    private final Layout layout;
    private final UUID playerId;
    private final FixtureTerrain.Output output;
    private final double returnX, returnY, returnZ;
    private final float returnYaw, returnPitch;
    private final long failureBaseline;
    private Phase phase = Phase.ARRIVE;
    private Future<?> action;
    private long since = System.nanoTime(), lastProgress = since, frame = -1;
    private long arrowUpdates, reloadBaseline;
    private float yaw = 180;
    private GuiScreen ownedScreen;
    private boolean cleanupQueued;
    private int stationaryFireballRepairs;

    FixtureCombat(Minecraft mc, ClientEvents events, IntegratedServer server, WorldServer world,
            String save, Layout layout, double x, double y, double z, float yaw, float pitch,
            FixtureTerrain.Output output) {
        this.mc = mc; this.events = events; this.server = server; this.world = world;
        this.save = save; this.layout = layout; this.output = output; playerId = mc.player.getUniqueID();
        returnX = x; returnY = y; returnZ = z; returnYaw = yaw; returnPitch = pitch;
        failureBaseline = EntityCaptureHooks.offscreenFailureCount();
        action = movePlayer(180);
        log("STARTED");
    }

    boolean tick() throws Exception {
        if (mc.getIntegratedServer() != server || mc.world == null || !events.isEnabled()
                || events.failureMessage() != null) throw new IllegalStateException("Combat fixture renderer/world unavailable");
        if (EntityCaptureHooks.offscreenFailureCount() != failureBaseline)
            throw new IllegalStateException("Combat fixture encountered a supplementary renderer failure");
        if (System.nanoTime() - since > TimeUnit.SECONDS.toNanos(180)) {
            capture("combat-timeout-" + phase.name().toLowerCase(Locale.ROOT));
            throw new IllegalStateException("Combat fixture timed out: " + phase);
        }
        if (System.nanoTime() - lastProgress > TimeUnit.SECONDS.toNanos(10)) {
            log("PROGRESS"); lastProgress = System.nanoTime();
        }
        if (phase != Phase.RETURN && phase != Phase.DONE) holdPose();
        repairStationaryFireball();
        switch (phase) {
            case ARRIVE:
                if (complete()) transition(Phase.READY);
                break;
            case READY:
                if (settled(ready(false), 30)) {
                    capture("combat-projectiles"); log("VISIBLE_AND_OFFSCREEN_PASS");
                    arrowUpdates = events.entityTransformUpdates(layout.arrow);
                    action = server.callFromMainThread(() -> {
                        requireWorld();
                        Entity arrow = world.getEntityFromUuid(layout.arrow);
                        if (arrow == null) throw new IllegalStateException("Fixture arrow is missing");
                        arrow.setPosition(layout.x - 1.5, layout.y + 2.8, layout.z + 10.5);
                        arrow.motionX = arrow.motionY = arrow.motionZ = 0;
                        return null;
                    });
                    transition(Phase.MOVE);
                }
                break;
            case MOVE:
                if (complete()) transition(Phase.MOVED);
                break;
            case MOVED:
                Entity arrow = mc.world.getEntityByID(layout.arrowEntityId);
                if (settled(ready(false) && arrow != null && Math.abs(arrow.posX - (layout.x - 1.5)) < 0.2
                        && events.entityTransformUpdates(layout.arrow) > arrowUpdates, 30)) {
                    capture("combat-arrow-moved"); log("PROJECTILE_MOTION_PASS");
                    action = movePlayer(0); transition(Phase.TURN);
                }
                break;
            case TURN:
                if (complete()) transition(Phase.FACING_COW);
                break;
            case FACING_COW:
                if (settled(ready(true), 30)) {
                    capture("combat-facing-cow"); log("OFFSCREEN_PROJECTILES_PASS");
                    action = movePlayer(180); transition(Phase.BACK);
                }
                break;
            case BACK:
                if (complete()) transition(Phase.FACING_PROJECTILES);
                break;
            case FACING_PROJECTILES:
                if (settled(ready(false), 30)) {
                    log("OFFSCREEN_RETURN_PASS"); reloadBaseline = events.textureReloadCount();
                    events.reload(); transition(Phase.RELOAD);
                }
                break;
            case RELOAD:
                if (settled(events.textureReloadCount() > reloadBaseline && ready(false), 30)) {
                    capture("combat-reloaded"); log("RELOAD_PASS");
                    ownedScreen = new GuiInventory(mc.player); mc.displayGuiScreen(ownedScreen);
                    ownedScreen = mc.currentScreen; transition(Phase.INVENTORY);
                }
                break;
            case INVENTORY:
                if (settled(mc.currentScreen == ownedScreen && ready(false), 30)) {
                    capture("combat-inventory"); log("INVENTORY_PASS");
                    mc.player.closeScreen(); ownedScreen = null;
                    action = server.callFromMainThread(() -> { requireWorld(); removeEntities(); return null; });
                    transition(Phase.REMOVE);
                }
                break;
            case REMOVE:
                if (complete()) transition(Phase.REMOVED);
                break;
            case REMOVED:
                if (settled(!events.entityCaptured(layout.arrow) && !events.entityCaptured(layout.snowball)
                        && !events.entityCaptured(layout.fireball) && !events.entityCaptured(layout.cow), 30)) {
                    capture("combat-removed"); log("REMOVAL_PASS");
                    action = server.callFromMainThread(() -> {
                        requireWorld(); player().connection.setPlayerLocation(returnX, returnY, returnZ, returnYaw, returnPitch);
                        return null;
                    });
                    transition(Phase.RETURN);
                }
                break;
            case RETURN:
                if (complete()) { transition(Phase.DONE); log("PASS"); }
                break;
            default: break;
        }
        return phase == Phase.DONE;
    }

    static Layout build(WorldServer world, int x, int y, int z) {
        Layout layout = new Layout(x, y, z);
        for (int dx = -9; dx <= 9; dx++) for (int dz = 2; dz <= 32; dz++) {
            world.setBlockState(new BlockPos(x + dx, y, z + dz),
                    ((dx + dz) & 1) == 0 ? Blocks.QUARTZ_BLOCK.getDefaultState() : Blocks.STONE.getDefaultState(), 3);
            for (int dy = 1; dy <= 12; dy++) world.setBlockToAir(new BlockPos(x + dx, y + dy, z + dz));
        }
        EntityTippedArrow arrow = new EntityTippedArrow(world);
        arrow.pickupStatus = EntityArrow.PickupStatus.DISALLOWED;
        spawnStationary(world, arrow, x - 3.5, y + 2.8, z + 10.5);
        EntitySnowball snowball = new EntitySnowball(world);
        spawnStationary(world, snowball, x + 0.5, y + 2.8, z + 10.5);
        EntitySmallFireball fireball = new EntitySmallFireball(world);
        fireball.accelerationX = fireball.accelerationY = fireball.accelerationZ = 0;
        spawnStationary(world, fireball, x + 4.5, y + 2.8, z + 10.5);
        EntityCow cow = new EntityCow(world);
        cow.setNoAI(true); cow.enablePersistence(); cow.setEntityInvulnerable(true);
        spawnStationary(world, cow, x + 0.5, y + 1, z + 28.5);
        layout.arrow = arrow.getUniqueID(); layout.snowball = snowball.getUniqueID();
        layout.fireball = fireball.getUniqueID(); layout.cow = cow.getUniqueID();
        layout.arrowEntityId = arrow.getEntityId();
        layout.fireballEntityId = fireball.getEntityId();
        return layout;
    }

    private static void spawnStationary(WorldServer world, Entity entity, double x, double y, double z) {
        entity.setPosition(x, y, z); entity.setNoGravity(true); entity.setEntityInvulnerable(true);
        entity.motionX = entity.motionY = entity.motionZ = 0;
        entity.setSilent(true);
        if (!world.spawnEntity(entity)) throw new IllegalStateException("Could not spawn fixture entity");
    }

    private boolean ready(boolean facingCow) {
        return events.entityCaptured(layout.arrow) && events.entityCaptured(layout.snowball)
                && events.entityCaptured(layout.fireball) && events.entityCaptured(layout.cow)
                && events.trianglePartCount(layout.arrow) > 0 && events.trianglePartCount(layout.snowball) > 0
                && events.trianglePartCount(layout.fireball) > 0
                && EntityCaptureHooks.normallyRendered(layout.cow) == facingCow
                && EntityCaptureHooks.normallyRendered(layout.arrow) != facingCow
                && EntityCaptureHooks.normallyRendered(layout.snowball) != facingCow
                && EntityCaptureHooks.normallyRendered(layout.fireball) != facingCow
                && EntityCaptureHooks.offscreenEntityCount() > 0;
    }

    private Future<?> movePlayer(float targetYaw) {
        yaw = targetYaw;
        return server.callFromMainThread(() -> {
            requireWorld(); player().connection.setPlayerLocation(layout.x + 0.5, layout.y + 4, layout.z + 18.5, targetYaw, 20);
            return null;
        });
    }
    private void holdPose() {
        double x = layout.x + 0.5, y = layout.y + 4, z = layout.z + 18.5;
        mc.player.setPositionAndRotation(x, y, z, yaw, 20);
        mc.player.prevPosX = mc.player.lastTickPosX = x; mc.player.prevPosY = mc.player.lastTickPosY = y;
        mc.player.prevPosZ = mc.player.lastTickPosZ = z;
        mc.player.prevRotationYaw = yaw; mc.player.prevRotationPitch = 20;
        mc.player.motionX = mc.player.motionY = mc.player.motionZ = 0; mc.player.capabilities.isFlying = true;
    }
    private void repairStationaryFireball() {
        Entity entity = mc.world.getEntityByID(layout.fireballEntityId);
        if (!(entity instanceof EntitySmallFireball) || !layout.fireball.equals(entity.getUniqueID())) return;
        EntitySmallFireball fireball = (EntitySmallFireball) entity;
        AxisAlignedBB box = fireball.getEntityBoundingBox();
        if (Double.isFinite(fireball.accelerationX) && Double.isFinite(fireball.accelerationY) && Double.isFinite(fireball.accelerationZ)
                && Double.isFinite(fireball.motionX) && Double.isFinite(fireball.motionY) && Double.isFinite(fireball.motionZ)
                && Double.isFinite(box.minX) && Double.isFinite(box.minY) && Double.isFinite(box.minZ)
                && Double.isFinite(box.maxX) && Double.isFinite(box.maxY) && Double.isFinite(box.maxZ)) return;
        // The vanilla spawn-packet constructor normalizes its acceleration vector,
        // so this deliberately stationary fixture's zero vector becomes 0/0 on
        // the client. World.updateEntity repairs positions but not that NaN AABB.
        // Repair only this UUID-owned specimen; no gameplay renderer or culling
        // setting changes, and the normal/offscreen assertions remain mandatory.
        fireball.accelerationX = fireball.accelerationY = fireball.accelerationZ = 0;
        fireball.motionX = fireball.motionY = fireball.motionZ = 0;
        double x = layout.x + 4.5, y = layout.y + 2.8, z = layout.z + 10.5;
        fireball.setPositionAndRotation(x, y, z, 0, 0);
        fireball.prevPosX = fireball.lastTickPosX = x; fireball.prevPosY = fireball.lastTickPosY = y;
        fireball.prevPosZ = fireball.lastTickPosZ = z;
        fireball.prevRotationYaw = fireball.prevRotationPitch = 0;
        stationaryFireballRepairs++;
        log("STATIONARY_FIREBALL_REPAIRED");
    }
    private void removeEntities() {
        for (UUID id : new UUID[] {layout.arrow, layout.snowball, layout.fireball, layout.cow}) {
            Entity entity = world.getEntityFromUuid(id); if (entity != null) entity.setDead();
        }
    }
    private EntityPlayerMP player() { return server.getPlayerList().getPlayerByUUID(playerId); }
    private void requireWorld() {
        if (!server.isCallingFromMinecraftThread() || !save.equals(server.getFolderName()) || server.getWorld(0) != world)
            throw new IllegalStateException("Combat fixture no longer owns the server world");
    }
    private boolean complete() throws Exception { if (!action.isDone()) return false; action.get(); return true; }
    private boolean settled(boolean ready, int frames) {
        if (!ready) { frame = -1; return false; }
        if (frame < 0) frame = events.renderedFrames();
        return events.renderedFrames() - frame >= frames;
    }
    private void transition(Phase next) { phase = next; since = lastProgress = System.nanoTime(); frame = -1; log("PHASE"); }
    private void capture(String name) throws Exception { output.capture(name, output.image()); }
    private void log(String event) { output.log("COMBAT_" + event, status()); }
    String status() {
        return "phase=" + phase + " arrow=" + events.entityCaptured(layout.arrow) + " snowball=" + events.entityCaptured(layout.snowball)
                + " fireball=" + events.entityCaptured(layout.fireball) + " cow=" + events.entityCaptured(layout.cow)
                + " arrowNormal=" + EntityCaptureHooks.normallyRendered(layout.arrow)
                + " snowballNormal=" + EntityCaptureHooks.normallyRendered(layout.snowball)
                + " fireballNormal=" + EntityCaptureHooks.normallyRendered(layout.fireball)
                + " cowNormal=" + EntityCaptureHooks.normallyRendered(layout.cow)
                + " supplementary=" + EntityCaptureHooks.offscreenEntityCount()
                + " fireballRepairs=" + stationaryFireballRepairs
                + " failures=" + EntityCaptureHooks.offscreenFailureCount()
                + " buffers=" + EntityCaptureHooks.nonlivingBufferCount();
    }
    @Override public void close() {
        if (ownedScreen != null && mc.currentScreen == ownedScreen && mc.player != null) mc.player.closeScreen();
        ownedScreen = null;
        if (!cleanupQueued) {
            cleanupQueued = true;
            server.callFromMainThread(() -> { requireWorld(); removeEntities(); return null; });
        }
    }
    static final class Layout {
        final int x, y, z;
        UUID arrow, snowball, fireball, cow;
        int arrowEntityId, fireballEntityId;
        Layout(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
    }
}
