/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import com.nvidia.rtxremix.forge.hud.MobHudHooks;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.passive.EntityCow;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

/** Exercises Neat's own visibility rules and visible health-bar pixels in an owned neutral arena. */
final class FixtureMobHud implements AutoCloseable {
    private enum Phase { SETUP, ARRIVE, VISIBLE, DISABLED, FOCUSED, UNFOCUSED, F1_HIDDEN, F1_VISIBLE, RETURN, DONE }
    private final Minecraft mc;
    private final ClientEvents events;
    private final IntegratedServer server;
    private final WorldServer world;
    private final WorldClient clientWorld;
    private final String save;
    private final Layout layout;
    private final FixtureTerrain.Output output;
    private final UUID playerId;
    private final int originalView;
    private final boolean originalHud, originalFlying;
    private final double returnX, returnY, returnZ;
    private final float returnYaw, returnPitch;
    private final Map<Field, Object> originalConfig = new LinkedHashMap<>();
    private Phase phase = Phase.SETUP;
    private Future<?> action;
    private EntityCow cow;
    private boolean cleanupQueued, serverSettingsChanged, serverFlying;
    private long since = System.nanoTime(), progress = since, frame = -1, captureBaseline, compositeBaseline;
    private float yaw = 180;
    private BufferedImage visible;
    private int visiblePixels, lastPixels, changedPixels;

    FixtureMobHud(Minecraft mc, ClientEvents events, IntegratedServer server, WorldServer world,
            String save, Layout layout, double x, double y, double z, float yaw, float pitch,
            FixtureTerrain.Output output) {
        FixtureCompatibility.requireSmokeSave(save);
        this.mc = mc; this.events = events; this.server = server; this.world = world;
        this.clientWorld = mc.world; this.save = save; this.layout = layout; this.output = output;
        playerId = mc.player.getUniqueID(); originalView = mc.gameSettings.thirdPersonView;
        originalHud = mc.gameSettings.hideGUI; originalFlying = mc.player.capabilities.isFlying;
        returnX = x; returnY = y; returnZ = z; returnYaw = yaw; returnPitch = pitch;
    }

    static Layout build(WorldServer world, int x, int y, int z) {
        FixtureCompatibility.requireSmokeSave(world.getMinecraftServer().getFolderName());
        if (!world.getMinecraftServer().isCallingFromMinecraftThread() || world.provider.getDimension() != 0)
            throw new IllegalStateException("Mob HUD layout requires the owned overworld server thread");
        for (int dx = -8; dx <= 8; dx++) for (int dz = 0; dz <= 16; dz++) for (int dy = 0; dy <= 7; dy++) {
            boolean wall = dx == -8 || dx == 8 || dz == 0 || dy == 0;
            BlockPos pos = new BlockPos(x + dx, y + dy, z + dz);
            if (wall) world.setBlockState(pos, Blocks.STONE.getDefaultState(), 3); else world.setBlockToAir(pos);
        }
        return new Layout(x, y, z);
    }

    boolean tick() throws Exception {
        if (mc.getIntegratedServer() != server || mc.world != clientWorld || mc.player == null
                || !events.isEnabled() || events.failureMessage() != null)
            throw new IllegalStateException("Mob HUD fixture renderer/world unavailable");
        if (System.nanoTime() - since > TimeUnit.SECONDS.toNanos(180)) {
            output.capture("mob-hud-timeout-" + phase.name().toLowerCase(Locale.ROOT), output.image());
            throw new IllegalStateException("Mob HUD fixture timed out: " + status());
        }
        if (System.nanoTime() - progress > TimeUnit.SECONDS.toNanos(10)) { log("PROGRESS"); progress = System.nanoTime(); }
        if (phase != Phase.SETUP && phase != Phase.RETURN && phase != Phase.DONE) holdPose();
        switch (phase) {
            case SETUP:
                configure(); mc.gameSettings.thirdPersonView = 0; mc.gameSettings.hideGUI = false;
                action = server.callFromMainThread(() -> { setup(); return null; }); transition(Phase.ARRIVE); break;
            case ARRIVE:
                if (complete()) transition(Phase.VISIBLE);
                break;
            case VISIBLE:
                if (settled()) {
                    visible = capture("mob-hud-visible"); visiblePixels = lastPixels;
                    requireVisible(); log("VISIBLE_PIXELS_PASS"); set("draw", false); transition(Phase.DISABLED);
                }
                break;
            case DISABLED:
                if (settled()) {
                    requireHidden(capture("mob-hud-disabled")); log("DRAW_DISABLED_PASS");
                    set("draw", true); set("showOnlyFocused", true); transition(Phase.FOCUSED);
                }
                break;
            case FOCUSED:
                if (settled()) {
                    capture("mob-hud-focused"); requireVisible(); log("FOCUSED_PASS"); yaw = 200; transition(Phase.UNFOCUSED);
                }
                break;
            case UNFOCUSED:
                if (settled()) {
                    requireHidden(capture("mob-hud-unfocused")); log("UNFOCUSED_PASS");
                    yaw = 180; set("showOnlyFocused", false); mc.gameSettings.hideGUI = true; transition(Phase.F1_HIDDEN);
                }
                break;
            case F1_HIDDEN:
                if (settled()) {
                    requireHidden(capture("mob-hud-f1-hidden")); log("F1_HIDDEN_PASS");
                    set("renderInF1", true); transition(Phase.F1_VISIBLE);
                }
                break;
            case F1_VISIBLE:
                if (settled()) {
                    capture("mob-hud-f1-visible"); requireVisible(); log("F1_VISIBLE_PASS");
                    restoreClient(); action = restoreServer(); cleanupQueued = true; transition(Phase.RETURN);
                }
                break;
            case RETURN:
                if (complete()) { visible = null; transition(Phase.DONE); log("PASS"); }
                break;
            default: break;
        }
        return phase == Phase.DONE;
    }

    private void configure() throws ReflectiveOperationException {
        Class<?> config = Class.forName("vazkii.neat.NeatConfig");
        String[] names = {"draw", "renderInF1", "showOnlyFocused", "colorByType", "maxDistance", "blacklist"};
        for (String name : names) { Field field = config.getField(name); originalConfig.put(field, field.get(null)); }
        set("draw", true); set("renderInF1", false); set("showOnlyFocused", false); set("colorByType", false);
        set("maxDistance", 16); set("blacklist", new ArrayList<String>());
    }
    private void set(String name, Object value) throws IllegalAccessException {
        for (Field field : originalConfig.keySet()) if (field.getName().equals(name)) { field.set(null, value); return; }
        throw new IllegalStateException("Unsaved Neat fixture setting: " + name);
    }
    private void setup() {
        requireWorld(); EntityPlayerMP player = player(); serverFlying = player.capabilities.isFlying; serverSettingsChanged = true;
        cow = new EntityCow(world); cow.setUniqueId(layout.id); cow.setCustomNameTag("Remix HUD Fixture");
        cow.setPositionAndRotation(layout.x + 0.5, layout.y + 1, layout.z + 6.5, 0, 0);
        cow.setNoAI(true); cow.enablePersistence(); cow.setNoGravity(true); cow.setSilent(true); cow.setEntityInvulnerable(true);
        if (!world.spawnEntity(cow)) throw new IllegalStateException("Could not spawn owned mob HUD cow");
        player.capabilities.isFlying = true; player.sendPlayerAbilities();
        player.connection.setPlayerLocation(layout.x + 0.5, layout.y + 1, layout.z + 11.5, 180, 10);
    }
    private boolean settled() {
        if (!events.entityCaptured(layout.id) || !arenaReady() || MobHudHooks.resourceCount() == 0) { frame = -1; return false; }
        if (frame < 0) frame = events.renderedFrames();
        return events.renderedFrames() - frame >= 30 && MobHudHooks.captures() > captureBaseline
                && MobHudHooks.composites() > compositeBaseline;
    }
    private boolean arenaReady() {
        for (int x = (layout.x - 8) >> 4; x <= (layout.x + 8) >> 4; x++)
            for (int y = layout.y >> 4; y <= (layout.y + 7) >> 4; y++)
                for (int z = layout.z >> 4; z <= (layout.z + 16) >> 4; z++)
                    if (!events.terrainSectionReady(x, y, z)) return false;
        return true;
    }
    private BufferedImage capture(String name) throws Exception {
        BufferedImage image = output.image(); lastPixels = barPixels(image);
        output.capture(name, image); return image;
    }
    private void requireVisible() {
        if (lastPixels < 40) throw new IllegalStateException("Mob HUD green health-bar pixels absent: " + status());
    }
    private void requireHidden(BufferedImage hidden) {
        if (hidden.getWidth() != visible.getWidth() || hidden.getHeight() != visible.getHeight())
            throw new IllegalStateException("Mob HUD comparison changed viewport size");
        changedPixels = 0;
        for (int y = visible.getHeight() / 12; y < visible.getHeight() * 2 / 3; y++)
            for (int x = visible.getWidth() / 10; x < visible.getWidth() * 9 / 10; x++)
                if (green(visible.getRGB(x, y)) && !green(hidden.getRGB(x, y))) changedPixels++;
        if (lastPixels > visiblePixels / 4 || changedPixels < 40)
            throw new IllegalStateException("Mob HUD disabled visibility still shows health-bar pixels: " + status());
    }
    static int barPixels(BufferedImage image) {
        int count = 0;
        for (int y = image.getHeight() / 12; y < image.getHeight() * 2 / 3; y++)
            for (int x = image.getWidth() / 10; x < image.getWidth() * 9 / 10; x++) if (green(image.getRGB(x, y))) count++;
        return count;
    }
    private static boolean green(int color) {
        int red = color >>> 16 & 255, green = color >>> 8 & 255, blue = color & 255;
        return green >= 120 && green - red >= 48 && green - blue >= 48;
    }
    private void holdPose() {
        double x = layout.x + 0.5, y = layout.y + 1, z = layout.z + 11.5;
        mc.player.setPositionAndRotation(x, y, z, yaw, 10);
        mc.player.prevPosX = mc.player.lastTickPosX = x; mc.player.prevPosY = mc.player.lastTickPosY = y;
        mc.player.prevPosZ = mc.player.lastTickPosZ = z; mc.player.prevRotationYaw = yaw; mc.player.prevRotationPitch = 10;
        mc.player.motionX = mc.player.motionY = mc.player.motionZ = 0; mc.player.capabilities.isFlying = true;
    }
    private EntityPlayerMP player() {
        EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(playerId);
        if (player == null) throw new IllegalStateException("Mob HUD fixture player unavailable"); return player;
    }
    private void requireWorld() {
        FixtureCompatibility.requireSmokeSave(save);
        if (!server.isCallingFromMinecraftThread() || !save.equals(server.getFolderName()) || server.getWorld(0) != world)
            throw new IllegalStateException("Mob HUD fixture no longer owns server world");
    }
    private Future<?> restoreServer() {
        return server.callFromMainThread(() -> {
            requireWorld(); if (cow != null && cow.world == world) cow.setDead();
            if (serverSettingsChanged) {
                EntityPlayerMP player = player(); player.capabilities.isFlying = serverFlying; player.sendPlayerAbilities();
                player.connection.setPlayerLocation(returnX, returnY, returnZ, returnYaw, returnPitch); serverSettingsChanged = false;
            }
            return null;
        });
    }
    private void restoreClient() {
        try {
            for (Map.Entry<Field, Object> saved : originalConfig.entrySet()) saved.getKey().set(null, saved.getValue());
            originalConfig.clear();
        } catch (IllegalAccessException error) { throw new IllegalStateException("Could not restore Neat fixture settings", error); }
        finally {
            mc.gameSettings.thirdPersonView = originalView; mc.gameSettings.hideGUI = originalHud;
        if (mc.world == clientWorld && mc.getIntegratedServer() == server
                && mc.player != null && playerId.equals(mc.player.getUniqueID())) mc.player.capabilities.isFlying = originalFlying;
        }
    }
    private boolean complete() throws Exception { if (!action.isDone()) return false; action.get(); return true; }
    private void transition(Phase next) {
        phase = next; since = progress = System.nanoTime(); frame = -1;
        captureBaseline = MobHudHooks.captures(); compositeBaseline = MobHudHooks.composites(); log("PHASE");
    }
    private void log(String name) { output.log("MOB_HUD_" + name, status()); }
    String status() {
        return "phase=" + phase + " captured=" + events.entityCaptured(layout.id) + " captures=" + MobHudHooks.captures()
                + " composites=" + MobHudHooks.composites() + " resources=" + MobHudHooks.resourceCount()
                + " greenPixels=" + lastPixels + " baselineGreen=" + visiblePixels + " changedPixels=" + changedPixels;
    }
    @Override public void close() {
        try { restoreClient(); } finally { if (!cleanupQueued) { cleanupQueued = true; restoreServer(); } }
    }
    static final class Layout {
        final int x, y, z;
        final UUID id = UUID.randomUUID();
        Layout(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
    }
}
