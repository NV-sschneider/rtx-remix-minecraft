/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import java.awt.image.BufferedImage;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

/** Actual grounded ItemPhysic sticks and blocks under direct and occluded daylight. */
public final class FixtureDroppedItems implements AutoCloseable {
    private enum Phase { SETUP, ARRIVE, EMPTY, SPAWN, ITEMS, RELOAD, REMOVE, REMOVED, SHADE, RETURN, DONE }
    private static final String[] NAMES = {"stick", "stone", "quartz"};
    private final Minecraft mc;
    private final ClientEvents events;
    private final IntegratedServer server;
    private final WorldServer world;
    private final WorldClient clientWorld;
    private final String save;
    private final Layout layout;
    private final FixtureTerrain.Output output;
    private final UUID playerId;
    private final double returnX, returnY, returnZ;
    private final float returnYaw, returnPitch, originalFov;
    private final int originalView;
    private final boolean originalHud, originalBobbing, originalFlying;
    private final FloatBuffer projection = BufferUtils.createFloatBuffer(16);
    private final ItemImageProbe[] measurements = new ItemImageProbe[3];
    private final UUID[] owned = new UUID[3]; // Written and read only on the server thread.
    private UUID[] items;
    private Phase phase = Phase.SETUP;
    private Future<?> action;
    private BufferedImage empty;
    private long since = System.nanoTime(), progress = since, stableSince, stableFrame = -1, reload;
    private float projectionX, projectionY;
    private boolean shade, registered, configured, setupQueued, cleanupQueued, closed;
    private boolean serverConfigured, serverFlying;

    FixtureDroppedItems(Minecraft mc, ClientEvents events, IntegratedServer server, WorldServer world, String save,
            Layout layout, double x, double y, double z, float yaw, float pitch, FixtureTerrain.Output output) {
        FixtureCompatibility.requireSmokeSave(save);
        if (mc.getIntegratedServer() != server || !save.equals(server.getFolderName()) || mc.world == null || mc.player == null)
            throw new IllegalStateException("Dropped-item fixture requires its current integrated smoke world");
        this.mc = mc; this.events = events; this.server = server; this.world = world; this.save = save;
        this.layout = layout; this.output = output; clientWorld = mc.world; playerId = mc.player.getUniqueID();
        returnX = x; returnY = y; returnZ = z; returnYaw = yaw; returnPitch = pitch;
        originalView = mc.gameSettings.thirdPersonView; originalHud = mc.gameSettings.hideGUI;
        originalFov = mc.gameSettings.fovSetting; originalBobbing = mc.gameSettings.viewBobbing;
        originalFlying = mc.player.capabilities.isFlying;
    }

    static Layout build(WorldServer world, int x, int y, int z) {
        FixtureCompatibility.requireSmokeSave(world.getMinecraftServer().getFolderName());
        if (!world.getMinecraftServer().isCallingFromMinecraftThread() || world.provider.getDimension() != 0 || y < 1 || y > 230)
            throw new IllegalStateException("Dropped-item arena requires its owned bounded overworld");
        for (int dx = -6; dx <= 6; dx++) for (int dz = -6; dz <= 6; dz++) {
            world.setBlockState(new BlockPos(x + dx, y, z + dz), Blocks.OBSIDIAN.getDefaultState(), 3);
            int top = Math.min(255, Math.max(y + 10, world.getHeight(new BlockPos(x + dx, y, z + dz)).getY()));
            for (int yy = y + 1; yy <= top; yy++) world.setBlockToAir(new BlockPos(x + dx, yy, z + dz));
        }
        return new Layout(x, y, z);
    }

    boolean tick() throws Exception {
        if (!ownsClient() || !events.isEnabled() || events.failureMessage() != null)
            throw new IllegalStateException("Dropped-item fixture lost its renderer/world");
        if (System.nanoTime() - since > TimeUnit.SECONDS.toNanos(150)) {
            output.capture("dropped-items-timeout-" + phase.name().toLowerCase(Locale.ROOT), output.image());
            throw new IllegalStateException("Dropped-item fixture timed out: " + status());
        }
        if (System.nanoTime() - progress > TimeUnit.SECONDS.toNanos(5)) { log("PROGRESS"); progress = System.nanoTime(); }
        if (phase != Phase.SETUP && phase != Phase.RETURN && phase != Phase.DONE) holdPose();
        switch (phase) {
            case SETUP:
                Class.forName("com.creativemd.itemphysic.physics.ClientPhysic");
                configured = true; mc.gameSettings.thirdPersonView = 0; mc.gameSettings.hideGUI = true;
                mc.gameSettings.fovSetting = 70; mc.gameSettings.viewBobbing = false;
                MinecraftForge.EVENT_BUS.register(this); registered = true; setupQueued = true;
                action = server.callFromMainThread(() -> {
                    requireWorld(); EntityPlayerMP player = player(); serverFlying = player.capabilities.isFlying; serverConfigured = true;
                    player.capabilities.isFlying = true; player.sendPlayerAbilities();
                    player.connection.setPlayerLocation(cameraX(), cameraY(), cameraZ(), 180, 50); return null;
                });
                transition(Phase.ARRIVE); break;
            case ARRIVE:
                if (complete()) transition(Phase.EMPTY);
                break;
            case EMPTY:
                if (settled(terrainReady())) {
                    empty = output.image(); output.capture("dropped-items-" + light() + "-empty", empty);
                    action = server.callFromMainThread(() -> {
                        requireWorld();
                        for (int i = 0; i < owned.length; i++) {
                            ItemStack stack = i == 0 ? new ItemStack(Items.STICK) : new ItemStack(i == 1 ? Blocks.STONE : Blocks.QUARTZ_BLOCK);
                            EntityItem item = new EntityItem(world, itemX(i), layout.y + 1.08, layout.z + .5, stack);
                            item.motionX = item.motionY = item.motionZ = 0; item.rotationYaw = 0;
                            item.setInfinitePickupDelay(); item.setNoDespawn();
                            if (!world.spawnEntity(item)) throw new IllegalStateException("Could not spawn owned dropped item");
                            owned[i] = item.getUniqueID();
                        }
                        return owned.clone();
                    });
                    transition(Phase.SPAWN);
                }
                break;
            case SPAWN:
                if (action.isDone()) { items = (UUID[]) action.get(); transition(Phase.ITEMS); }
                break;
            case ITEMS:
                if (settled(itemsReady())) {
                    captureAndCheck(false);
                    if (shade) { reload = events.textureReloadCount(); events.reload(); transition(Phase.RELOAD); }
                    else { action = removeItems(); transition(Phase.REMOVE); }
                }
                break;
            case RELOAD:
                if (settled(events.textureReloadCount() > reload && itemsReady())) {
                    captureAndCheck(true); action = removeItems(); transition(Phase.REMOVE);
                }
                break;
            case REMOVE:
                if (complete()) transition(Phase.REMOVED);
                break;
            case REMOVED:
                if (settled(itemsAbsent())) {
                    log("RETIREMENT_PASS"); empty = null;
                    if (!shade) {
                        action = server.callFromMainThread(() -> {
                            requireWorld();
                            for (int dx = -5; dx <= 5; dx++) for (int dz = -5; dz <= 5; dz++)
                                world.setBlockState(new BlockPos(layout.x + dx, layout.y + 5, layout.z + dz), Blocks.STONE.getDefaultState(), 3);
                            return null;
                        });
                        transition(Phase.SHADE);
                    } else {
                        restoreClient(); action = restoreServer(); cleanupQueued = true; transition(Phase.RETURN);
                    }
                }
                break;
            case SHADE:
                if (complete()) { shade = true; items = null; transition(Phase.EMPTY); }
                break;
            case RETURN:
                if (complete()) { transition(Phase.DONE); log("PASS"); }
                break;
            default: break;
        }
        return phase == Phase.DONE;
    }

    @SubscribeEvent public void frame(RenderWorldLastEvent event) {
        if (!registered || !ownsClient()) return;
        projection.clear(); GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, projection);
        projectionX = projection.get(0); projectionY = projection.get(5);
    }
    private void captureAndCheck(boolean reloaded) throws Exception {
        BufferedImage image = output.image();
        output.capture("dropped-items-" + light() + (reloaded ? "-reloaded" : "-grounded"), image);
        double sin = Math.sin(Math.toRadians(50)), cos = Math.cos(Math.toRadians(50));
        for (int i = 0; i < items.length; i++) {
            Entity entity = clientItem(items[i]);
            double dx = entity.posX - cameraX(), dy = entity.posY + .15 - cameraY() - mc.player.getEyeHeight(), dz = entity.posZ - cameraZ();
            double depth = -dy * sin - dz * cos, vertical = dy * cos - dz * sin;
            double sx = .5 + .5 * dx / depth * projectionX, sy = .5 - .5 * vertical / depth * projectionY;
            int radius = Math.max(6, (int) Math.ceil(.45 * image.getWidth() * projectionX / (2 * depth)));
            measurements[i] = ItemImageProbe.measure(empty, image, sx, sy, radius);
            if (!measurements[i].visible(shade))
                throw new IllegalStateException("Dropped " + NAMES[i] + " lacks a lit visible surface: " + status());
        }
        log((reloaded ? "RELOAD_" : "") + light().toUpperCase(Locale.ROOT) + "_LIGHTING_PASS");
    }
    private boolean terrainReady() {
        BlockPos floor = new BlockPos(layout.x, layout.y, layout.z), roof = floor.up(5);
        return Float.isFinite(projectionX) && projectionX > 0 && Float.isFinite(projectionY) && projectionY > 0
                && clientWorld.getBlockState(floor).getBlock() == Blocks.OBSIDIAN
                && clientWorld.getBlockState(roof).getBlock() == (shade ? Blocks.STONE : Blocks.AIR)
                && events.terrainSectionReady(floor.getX() >> 4, floor.getY() >> 4, floor.getZ() >> 4)
                && events.terrainSectionReady(roof.getX() >> 4, roof.getY() >> 4, roof.getZ() >> 4)
                && events.terrainPendingSectionCount() == 0 && events.terrainRetirementCount() == 0;
    }
    private boolean itemsReady() {
        if (items == null || !terrainReady()) return false;
        for (UUID id : items) {
            Entity entity = clientItem(id);
            if (!(entity instanceof EntityItem) || !entity.onGround || Math.abs(entity.motionY) > .05
                    || !events.itemCaptured(id) || !events.itemOrdinaryLighting(id)) return false;
        }
        return true;
    }
    private boolean itemsAbsent() {
        for (UUID id : items) if (clientItem(id) != null || events.itemCaptured(id)) return false;
        return true;
    }
    private Entity clientItem(UUID id) {
        for (Entity entity : clientWorld.loadedEntityList) if (!entity.isDead && id.equals(entity.getUniqueID())) return entity;
        return null;
    }
    private Future<?> removeItems() {
        return server.callFromMainThread(() -> { requireWorld(); removeOwned(); return null; });
    }
    private void removeOwned() {
        for (int i = 0; i < owned.length; i++) {
            Entity entity = owned[i] == null ? null : world.getEntityFromUuid(owned[i]);
            if (entity != null && entity.world == world) entity.setDead(); owned[i] = null;
        }
    }
    private Future<?> restoreServer() {
        return server.callFromMainThread(() -> {
            requireWorld(); removeOwned();
            if (serverConfigured) {
                EntityPlayerMP player = player(); player.capabilities.isFlying = serverFlying; player.sendPlayerAbilities();
                player.connection.setPlayerLocation(returnX, returnY, returnZ, returnYaw, returnPitch); serverConfigured = false;
            }
            return null;
        });
    }
    private void restoreClient() {
        if (registered) { MinecraftForge.EVENT_BUS.unregister(this); registered = false; }
        if (!configured) return;
        mc.gameSettings.thirdPersonView = originalView; mc.gameSettings.hideGUI = originalHud;
        mc.gameSettings.fovSetting = originalFov; mc.gameSettings.viewBobbing = originalBobbing;
        if (ownsClient()) mc.player.capabilities.isFlying = originalFlying; configured = false;
    }
    private EntityPlayerMP player() {
        EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(playerId);
        if (player == null || player.world != world) throw new IllegalStateException("Dropped-item fixture player unavailable");
        return player;
    }
    private void requireWorld() {
        FixtureCompatibility.requireSmokeSave(save);
        if (!server.isCallingFromMinecraftThread() || !save.equals(server.getFolderName()) || server.getWorld(0) != world)
            throw new IllegalStateException("Dropped-item fixture no longer owns server world");
    }
    private boolean ownsClient() {
        return mc.getIntegratedServer() == server && mc.world == clientWorld && mc.player != null && playerId.equals(mc.player.getUniqueID());
    }
    private double cameraX() { return layout.x + .5; }
    private double cameraY() { return layout.y + 2; }
    private double cameraZ() { return layout.z + 3.5; }
    private double itemX(int index) { return layout.x + .5 + (index - 1) * 1.5; }
    private String light() { return shade ? "shade" : "sun"; }
    private void holdPose() {
        mc.player.setPositionAndRotation(cameraX(), cameraY(), cameraZ(), 180, 50);
        mc.player.prevPosX = mc.player.lastTickPosX = cameraX(); mc.player.prevPosY = mc.player.lastTickPosY = cameraY();
        mc.player.prevPosZ = mc.player.lastTickPosZ = cameraZ(); mc.player.prevRotationYaw = 180; mc.player.prevRotationPitch = 50;
        mc.player.motionX = mc.player.motionY = mc.player.motionZ = 0; mc.player.capabilities.isFlying = true;
    }
    private boolean complete() throws Exception { if (!action.isDone()) return false; action.get(); return true; }
    private boolean settled(boolean ready) {
        if (!ready) { stableFrame = -1; return false; }
        if (stableFrame < 0) { stableFrame = events.renderedFrames(); stableSince = System.nanoTime(); }
        return events.renderedFrames() - stableFrame >= 40 && System.nanoTime() - stableSince >= TimeUnit.SECONDS.toNanos(2);
    }
    private void transition(Phase next) { phase = next; since = progress = System.nanoTime(); stableFrame = -1; log("PHASE"); }
    private void log(String name) { output.log("DROPPED_ITEM_" + name, status()); }
    String status() {
        StringBuilder lighting = new StringBuilder();
        if (items != null) for (UUID id : items) lighting.append(events.itemOrdinaryLighting(id)).append('/');
        return "phase=" + phase + " light=" + light() + " ordinary=" + lighting + " litPixels/mean=" + Arrays.toString(measurements)
                + " registered=" + registered + " resources=" + events.itemResourceCount();
    }
    @Override public void close() {
        if (closed) return; closed = true; empty = null;
        try { restoreClient(); } finally { if (setupQueued && !cleanupQueued) { cleanupQueued = true; restoreServer(); } }
    }
    static final class Layout {
        final int x, y, z;
        Layout(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
    }
}
