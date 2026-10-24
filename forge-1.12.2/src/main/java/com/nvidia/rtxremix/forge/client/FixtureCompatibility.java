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
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.WorldServer;

/** Actual installed dragons, animated custom mobs, alpha cutouts, and a boss in the dedicated smoke save. */
final class FixtureCompatibility implements AutoCloseable {
    private enum Phase { ARRIVE, VISIBLE, VANILLA_COMPARISON, COMPARISON_REENABLED, ANIMATE_ACTION, ANIMATE, MOVE_ACTION, MOVED, OFFSCREEN,
        VISIBLE_AGAIN, RELOAD, REMOVE_ACTION, REMOVED, RETURN, DONE }
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
    private final EnumDifficulty originalClientDifficulty;
    private final double returnX, returnY, returnZ;
    private final float returnYaw, returnPitch;
    private final long[] transforms = new long[FixtureCompatibilityEntities.NAMES.length];
    private final boolean[] movedNative = new boolean[FixtureCompatibilityEntities.NAMES.length];
    private final boolean[] spoke = new boolean[2];
    private final boolean vanillaComparison = Boolean.getBoolean("rlcraftremix.compatibilityVanillaComparison");
    private final Entity[] comparisonEntities = new Entity[FixtureCompatibilityEntities.NAMES.length];
    private final double[] comparisonPositions = new double[FixtureCompatibilityEntities.NAMES.length * 3];
    private Phase phase = Phase.ARRIVE;
    private Future<?> action;
    private long since = System.nanoTime(), progress = since, frame = -1, reloadBaseline, failureBaseline;
    private boolean cleanupQueued, serverFlying, settingsChanged, difficultyLocked;
    private boolean comparisonSuspended;
    private int comparisonTicks;
    private EnumDifficulty difficulty;
    private String griefing, fireTick;
    private float yaw = 180;

    FixtureCompatibility(Minecraft mc, ClientEvents events, IntegratedServer server, WorldServer world,
            String save, Layout layout, double x, double y, double z, float yaw, float pitch,
            FixtureTerrain.Output output) {
        this.mc = mc; this.events = events; this.server = server; this.world = world; this.save = save;
        this.layout = layout; this.output = output; clientWorld = mc.world; playerId = mc.player.getUniqueID();
        originalView = mc.gameSettings.thirdPersonView; originalHud = mc.gameSettings.hideGUI;
        originalFlying = mc.player.capabilities.isFlying;
        originalClientDifficulty = clientWorld.getDifficulty();
        returnX = x; returnY = y; returnZ = z; returnYaw = yaw; returnPitch = pitch;
        mc.gameSettings.thirdPersonView = 0; mc.gameSettings.hideGUI = true;
        failureBaseline = EntityCaptureHooks.offscreenFailureCount();
        action = server.callFromMainThread(() -> { setup(); return null; });
        log("STARTED");
    }

    static Layout build(WorldServer world, int x, int y, int z) {
        requireSmokeSave(world.getMinecraftServer().getFolderName());
        if (!world.getMinecraftServer().isCallingFromMinecraftThread() || world.provider.getDimension() != 0)
            throw new IllegalStateException("Compatibility layout requires the owned overworld server thread");
        for (int dx = -30; dx <= 30; dx++) for (int dz = -28; dz <= 32; dz++) {
            world.setBlockState(new BlockPos(x + dx, y, z + dz), Blocks.STONEBRICK.getDefaultState(), 3);
            for (int dy = 1; dy <= 24; dy++) world.setBlockToAir(new BlockPos(x + dx, y + dy, z + dz));
        }
        return new Layout(x, y, z);
    }

    static void requireSmokeSave(String save) {
        if (save == null || !save.startsWith("rlcraft-remix-smoke-") || save.length() <= "rlcraft-remix-smoke-".length())
            throw new IllegalStateException("Compatibility fixtures require a dedicated smoke save");
    }

    private void setup() throws Exception {
        requireWorld(); EntityPlayerMP player = player(); serverFlying = player.capabilities.isFlying;
        difficulty = world.getWorldInfo().getDifficulty(); griefing = world.getGameRules().getString("mobGriefing");
        difficultyLocked = world.getWorldInfo().isDifficultyLocked();
        fireTick = world.getGameRules().getString("doFireTick"); settingsChanged = true;
        // IntegratedServer follows client difficulty while unlocked. Hold the authoritative value
        // until the client sees the completed setup, then restore both sides in the opposite order.
        world.getWorldInfo().setDifficultyLocked(true);
        world.getWorldInfo().setDifficulty(EnumDifficulty.NORMAL);
        world.getGameRules().setOrCreateGameRule("mobGriefing", "false");
        world.getGameRules().setOrCreateGameRule("doFireTick", "false");
        try {
            for (int i = 0; i < layout.ids.length; i++) {
                EntityLiving entity = FixtureCompatibilityEntities.create(world, i, layout.entityX(i), layout.y + 1, layout.entityZ(i));
                layout.entities[i] = entity; layout.ids[i] = entity.getUniqueID();
                if (!world.spawnEntity(entity)) throw new IllegalStateException("Could not spawn " + FixtureCompatibilityEntities.NAMES[i]);
            }
            player.capabilities.isFlying = true; player.sendPlayerAbilities();
            player.connection.setPlayerLocation(layout.x + 0.5, layout.y + 9, layout.z + 25.5, 180, 8);
        } catch (Exception | Error error) {
            removeEntities(); restoreSettings(); throw error;
        }
    }

    boolean tick() throws Exception {
        boolean vanilla = phase == Phase.VANILLA_COMPARISON && comparisonSuspended;
        if (mc.getIntegratedServer() != server || mc.world != clientWorld || mc.player == null
                || events.isEnabled() == vanilla || events.failureMessage() != null)
            throw new IllegalStateException("Compatibility fixture renderer/world unavailable");
        if (EntityCaptureHooks.offscreenFailureCount() != failureBaseline)
            throw new IllegalStateException("A compatibility renderer failed supplementary capture: " + status());
        if (System.nanoTime() - since > TimeUnit.SECONDS.toNanos(180)) {
            capture("compatibility-timeout-" + phase.name().toLowerCase(Locale.ROOT));
            throw new IllegalStateException("Compatibility fixture timed out: " + status());
        }
        if (System.nanoTime() - progress > TimeUnit.SECONDS.toNanos(10)) { log("PROGRESS"); progress = System.nanoTime(); }
        if (phase != Phase.RETURN && phase != Phase.DONE) holdPose();
        switch (phase) {
            case ARRIVE:
                if (complete()) { clientWorld.getWorldInfo().setDifficulty(EnumDifficulty.NORMAL); transition(Phase.VISIBLE); }
                break;
            case VISIBLE:
                if (settled(captured() && normal(true), 45)) {
                    snapshotComparisonEntities();
                    capture("compatibility-dragons-maka-rahovart-arisaurs"); log("VISIBLE_PASS");
                    if (vanillaComparison) {
                        log("COMPARISON_NATIVE");
                        comparisonSuspended = true;
                        events.setEnabled(false);
                        transition(Phase.VANILLA_COMPARISON);
                    } else beginAnimation();
                }
                break;
            case VANILLA_COMPARISON:
                // Native frame counters stop while disabled. Count actual client ticks;
                // the same no-AI cohort and held camera remain in this owned scene.
                if (++comparisonTicks >= 40) {
                    captureVanillaComparison();
                    transition(Phase.COMPARISON_REENABLED);
                }
                break;
            case COMPARISON_REENABLED:
                if (settled(captured() && normal(true), 45)) {
                    requireComparisonEntities(); log("COMPARISON_REENABLED_PASS");
                    beginAnimation();
                }
                break;
            case ANIMATE_ACTION:
                observeAnimation();
                if (complete()) transition(Phase.ANIMATE);
                break;
            case ANIMATE:
                observeAnimation();
                if (spoke[0] && spoke[1] && events.entityTransformUpdates(layout.ids[0]) > transforms[0]
                        && events.entityTransformUpdates(layout.ids[1]) > transforms[1] && captured()) {
                    capture("compatibility-dragon-speech-animation"); log("ANIMATION_PASS"); baseline();
                    action = server.callFromMainThread(() -> {
                        requireWorld();
                        for (int i = 0; i < layout.ids.length; i++) FixtureCompatibilityEntities.move(ownedEntity(i), i,
                                layout.entityX(i) + 2, layout.y + 1, layout.entityZ(i));
                        return null;
                    });
                    transition(Phase.MOVE_ACTION);
                }
                break;
            case MOVE_ACTION:
                if (complete()) transition(Phase.MOVED);
                break;
            case MOVED:
                if (settled(moved() && captured(), 30)) {
                    capture("compatibility-articulated-pose"); log("POSE_PASS"); yaw = 0; transition(Phase.OFFSCREEN);
                }
                break;
            case OFFSCREEN:
                if (settled(captured() && offscreenReady(), 45)) {
                    capture("compatibility-offscreen-retained"); log("OFFSCREEN_PASS"); yaw = 180; transition(Phase.VISIBLE_AGAIN);
                }
                break;
            case VISIBLE_AGAIN:
                if (settled(captured() && normal(true), 30)) {
                    reloadBaseline = events.textureReloadCount(); events.reload(); transition(Phase.RELOAD);
                }
                break;
            case RELOAD:
                if (settled(events.textureReloadCount() > reloadBaseline && captured() && normal(true), 45)) {
                    capture("compatibility-reloaded"); log("RELOAD_PASS");
                    action = server.callFromMainThread(() -> { requireWorld(); removeEntities(); return null; });
                    transition(Phase.REMOVE_ACTION);
                }
                break;
            case REMOVE_ACTION:
                if (complete()) transition(Phase.REMOVED);
                break;
            case REMOVED:
                if (settled(retired(), 30)) {
                    capture("compatibility-owners-removed"); log("RETIREMENT_PASS");
                    restoreClient(); action = restoreServer(); cleanupQueued = true; transition(Phase.RETURN);
                }
                break;
            case RETURN:
                if (complete()) { transition(Phase.DONE); log("PASS"); }
                break;
            default: break;
        }
        return phase == Phase.DONE;
    }

    private void beginAnimation() {
        // Disabling destroys native owners, so take the original animation baselines
        // only after the comparison has restored and settled the complete cohort.
        baseline();
        action = server.callFromMainThread(() -> {
            requireWorld();
            for (int i = 0; i < 2; i++) FixtureCompatibilityEntities.animate(ownedEntity(i));
            return null;
        });
        transition(Phase.ANIMATE_ACTION);
    }
    private void snapshotComparisonEntities() {
        if (!vanillaComparison) return;
        for (int i = 0; i < layout.ids.length; i++) {
            Entity entity = clientEntity(layout.ids[i]);
            if (entity == null) throw new IllegalStateException("Compatibility comparison entity is unavailable");
            comparisonEntities[i] = entity;
            comparisonPositions[i * 3] = entity.posX;
            comparisonPositions[i * 3 + 1] = entity.posY;
            comparisonPositions[i * 3 + 2] = entity.posZ;
        }
    }
    private void requireComparisonEntities() {
        for (int i = 0; i < layout.ids.length; i++) {
            Entity entity = clientEntity(layout.ids[i]);
            if (entity != comparisonEntities[i] || entity == null
                    || Math.abs(entity.posX - comparisonPositions[i * 3]) > 0.01
                    || Math.abs(entity.posY - comparisonPositions[i * 3 + 1]) > 0.01
                    || Math.abs(entity.posZ - comparisonPositions[i * 3 + 2]) > 0.01)
                throw new IllegalStateException("Compatibility comparison cohort moved or was replaced: " + i);
        }
    }
    private void captureVanillaComparison() throws Exception {
        Throwable failure = null;
        try {
            requireComparisonEntities();
            capture("compatibility-vanilla-dragons-maka-rahovart-arisaurs");
            log("COMPARISON_VANILLA");
        } catch (Exception | Error error) {
            failure = error; throw error;
        } finally {
            try { restoreComparisonRenderer(); }
            catch (RuntimeException | Error cleanup) {
                if (failure != null) failure.addSuppressed(cleanup); else throw cleanup;
            }
        }
    }
    private void restoreComparisonRenderer() {
        if (!comparisonSuspended) return;
        if (!events.isEnabled() && events.failureMessage() == null) events.setEnabled(true);
        comparisonSuspended = false;
    }

    private void observeAnimation() throws Exception {
        for (int i = 0; i < 2; i++) if (FixtureCompatibilityEntities.speaking(clientEntity(layout.ids[i]))) spoke[i] = true;
    }
    private void baseline() {
        for (int i = 0; i < transforms.length; i++) {
            transforms[i] = events.entityTransformUpdates(layout.ids[i]); movedNative[i] = false;
        }
    }
    private boolean captured() throws Exception {
        for (int i = 0; i < layout.ids.length; i++) {
            Entity entity = layout.ids[i] == null ? null : clientEntity(layout.ids[i]);
            if (entity == null || !events.entityCaptured(layout.ids[i])) return false;
            if (i >= 2 && events.trianglePartCount(layout.ids[i]) == 0) return false;
            if (i >= 4 && !FixtureCompatibilityEntities.arisaurTexture(entity, i == 5)) return false;
        }
        return true;
    }
    private boolean normal(boolean expected) {
        for (UUID id : layout.ids) if (id == null || EntityCaptureHooks.normallyRendered(id) != expected) return false;
        return true;
    }
    private boolean offscreenReady() {
        // Ice and Fire dragons opt out of frustum culling. Installed RLMixins bounds
        // Rahovart's render box, so every Lycanites model must survive supplementary capture.
        for (int i = 0; i < layout.ids.length; i++)
            if (EntityCaptureHooks.normallyRendered(layout.ids[i]) != (i < 2)) return false;
        return EntityCaptureHooks.offscreenEntityCount() >= layout.ids.length - 2;
    }
    private boolean moved() {
        boolean ready = true;
        for (int i = 0; i < layout.ids.length; i++) {
            Entity entity = clientEntity(layout.ids[i]);
            long updates = events.entityTransformUpdates(layout.ids[i]);
            // A material/part replacement can recreate the scene owner and reset its counter.
            // Require a subsequent real update, then retain that evidence independently for each mob.
            if (updates < transforms[i]) transforms[i] = updates;
            boolean atTarget = entity != null && entity.posX >= layout.entityX(i) + 1.5;
            if (atTarget && updates > transforms[i]) movedNative[i] = true;
            ready &= atTarget && movedNative[i];
        }
        return ready;
    }
    private boolean retired() {
        for (UUID id : layout.ids) if (events.entityCaptured(id) || clientEntity(id) != null) return false;
        return true;
    }
    private Entity clientEntity(UUID id) {
        for (Entity entity : clientWorld.loadedEntityList) if (!entity.isDead && entity.getUniqueID().equals(id)) return entity;
        return null;
    }
    private Entity ownedEntity(int index) {
        Entity entity = world.getEntityFromUuid(layout.ids[index]);
        if (entity != layout.entities[index] || entity.isDead) throw new IllegalStateException("Fixture entity lost ownership");
        return entity;
    }
    private void holdPose() {
        double x = layout.x + 0.5, y = layout.y + 9, z = layout.z + 25.5;
        mc.player.setPositionAndRotation(x, y, z, yaw, 8);
        mc.player.prevPosX = mc.player.lastTickPosX = x; mc.player.prevPosY = mc.player.lastTickPosY = y;
        mc.player.prevPosZ = mc.player.lastTickPosZ = z; mc.player.prevRotationYaw = yaw; mc.player.prevRotationPitch = 8;
        mc.player.motionX = mc.player.motionY = mc.player.motionZ = 0; mc.player.capabilities.isFlying = true;
    }
    private EntityPlayerMP player() {
        EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(playerId);
        if (player == null) throw new IllegalStateException("Compatibility fixture player unavailable"); return player;
    }
    private void requireWorld() {
        requireSmokeSave(save);
        if (!server.isCallingFromMinecraftThread() || !save.equals(server.getFolderName()) || server.getWorld(0) != world)
            throw new IllegalStateException("Compatibility fixture no longer owns server world");
    }
    private void removeEntities() {
        // Dragon.setDead owns its multipart children; never sweep unrelated world entities.
        for (Entity entity : layout.entities) if (entity != null && entity.world == world) entity.setDead();
    }
    private void restoreSettings() {
        if (!settingsChanged) return;
        world.getWorldInfo().setDifficulty(difficulty);
        world.getWorldInfo().setDifficultyLocked(difficultyLocked);
        world.getGameRules().setOrCreateGameRule("mobGriefing", griefing);
        world.getGameRules().setOrCreateGameRule("doFireTick", fireTick); settingsChanged = false;
    }
    private Future<?> restoreServer() {
        return server.callFromMainThread(() -> {
            requireWorld(); removeEntities(); restoreSettings(); EntityPlayerMP player = player();
            player.capabilities.isFlying = serverFlying; player.sendPlayerAbilities();
            player.connection.setPlayerLocation(returnX, returnY, returnZ, returnYaw, returnPitch); return null;
        });
    }
    private void restoreClient() {
        mc.gameSettings.thirdPersonView = originalView; mc.gameSettings.hideGUI = originalHud;
        clientWorld.getWorldInfo().setDifficulty(originalClientDifficulty);
        if (mc.world == clientWorld && mc.getIntegratedServer() == server
                && mc.player != null && playerId.equals(mc.player.getUniqueID())) mc.player.capabilities.isFlying = originalFlying;
    }
    private boolean complete() throws Exception { if (!action.isDone()) return false; action.get(); return true; }
    private boolean settled(boolean ready, int frames) {
        if (!ready) { frame = -1; return false; }
        if (frame < 0) frame = events.renderedFrames(); return events.renderedFrames() - frame >= frames;
    }
    private void transition(Phase next) { phase = next; since = progress = System.nanoTime(); frame = -1; log("PHASE"); }
    private void capture(String name) throws Exception { output.capture(name, output.image()); }
    private void log(String name) { output.log("COMPATIBILITY_" + name, status()); }
    String status() {
        StringBuilder result = new StringBuilder("phase=").append(phase);
        for (int i = 0; i < layout.ids.length; i++) {
            UUID id = layout.ids[i];
            result.append(' ').append(FixtureCompatibilityEntities.NAMES[i]).append("={captured=").append(id != null && events.entityCaptured(id))
                    .append(",flowersaur=").append(i == 5)
                    .append(",normal=").append(id != null && EntityCaptureHooks.normallyRendered(id))
                    .append(",triangles=").append(id == null ? 0 : events.trianglePartCount(id))
                    .append(",updates=").append(id == null ? 0 : events.entityTransformUpdates(id)).append('}');
        }
        return result.append(" spoke=").append(spoke[0]).append(',').append(spoke[1])
                .append(" comparison=").append(vanillaComparison).append(" comparisonTicks=").append(comparisonTicks)
                .append(" supplementary=").append(EntityCaptureHooks.offscreenEntityCount())
                .append(" failures=").append(EntityCaptureHooks.offscreenFailureCount()).toString();
    }
    @Override public void close() {
        try { restoreComparisonRenderer(); }
        finally { restoreClient(); if (!cleanupQueued) { cleanupQueued = true; restoreServer(); } }
    }

    static final class Layout {
        final int x, y, z;
        final UUID[] ids = new UUID[FixtureCompatibilityEntities.NAMES.length];
        final Entity[] entities = new Entity[FixtureCompatibilityEntities.NAMES.length];
        Layout(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
        double entityX(int i) { return x + (i == 0 ? -14.5 : i == 1 ? 14.5 : i == 4 ? -7.5 : i == 5 ? 7.5 : 0.5); }
        double entityZ(int i) { return z + (i < 2 ? -8.5 : i == 2 ? 6.5 : i == 3 ? -13.5 : 10.5); }
    }
}
