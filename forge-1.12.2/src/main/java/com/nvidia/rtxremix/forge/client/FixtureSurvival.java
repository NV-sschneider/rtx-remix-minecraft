/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import com.nvidia.rtxremix.InstanceCategoryBit;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.projectile.EntitySnowball;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.server.SPacketEntityVelocity;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/** Genuine player combat and item-use packets in Survival, isolated to one authored smoke arena. */
final class FixtureSurvival implements AutoCloseable {
    private enum Phase { ARRIVE, READY, DAMAGE_ACTION, HURT, MELEE, EQUIP_THROW, THROW_READY,
        PROJECTILE, EQUIP_FOOD, FOOD_READY, EATING, RELOAD, RESTORE, RETIRED, DONE }
    private final Minecraft mc;
    private final ClientEvents events;
    private final IntegratedServer server;
    private final WorldServer world;
    private final WorldClient clientWorld;
    private final String save;
    private final Layout layout;
    private final FixtureTerrain.Output output;
    private final UUID playerId, zombieId = UUID.randomUUID();
    private final int originalView, useKey;
    private final boolean originalHud, originalUseDown;
    private final EnumDifficulty originalClientDifficulty;
    private final double returnX, returnY, returnZ;
    private final float returnYaw, returnPitch;
    private Phase phase = Phase.ARRIVE;
    private Future<?> action, cleanup;
    private FixtureSurvivalState saved;
    private EntityZombie zombie;
    private ProjectileOwner projectiles;
    private EnumDifficulty difficulty;
    private boolean difficultyLocked, settingsChanged, cleanupQueued;
    private String spawning;
    private volatile UUID projectileId;
    private volatile boolean serverDamageApplied, projectileFrozen;
    private volatile float healthBeforeDamage, zombieBeforeMelee;
    private long since = System.nanoTime(), progress = since, frame = -1, reloadBaseline, usePoseBaseline;
    private int readyTick, foodBefore, foodCountBefore;
    private float yaw = 180;
    private boolean started, hurtObserved, swingObserved, useObserved, eatingCaptured;

    FixtureSurvival(Minecraft mc, ClientEvents events, IntegratedServer server, WorldServer world,
            String save, Layout layout, double x, double y, double z, float yaw, float pitch,
            FixtureTerrain.Output output) {
        FixtureCompatibility.requireSmokeSave(save);
        this.mc = mc; this.events = events; this.server = server; this.world = world; this.save = save;
        this.layout = layout; this.output = output; clientWorld = mc.world; playerId = mc.player.getUniqueID();
        returnX = x; returnY = y; returnZ = z; returnYaw = yaw; returnPitch = pitch;
        originalView = mc.gameSettings.thirdPersonView; originalHud = mc.gameSettings.hideGUI;
        originalClientDifficulty = clientWorld.getDifficulty();
        useKey = mc.gameSettings.keyBindUseItem.getKeyCode(); originalUseDown = mc.gameSettings.keyBindUseItem.isKeyDown();
    }

    /** Start only after the controller owns this fixture and can close it on every failure. */
    private void start() {
        started = true;
        mc.gameSettings.thirdPersonView = 0; mc.gameSettings.hideGUI = false; KeyBinding.setKeyBindState(useKey, false);
        action = server.callFromMainThread(() -> {
            requireWorld(); EntityPlayerMP player = player(); saved = new FixtureSurvivalState(player);
            difficulty = world.getDifficulty(); difficultyLocked = world.getWorldInfo().isDifficultyLocked();
            spawning = world.getGameRules().getString("doMobSpawning"); settingsChanged = true;
            world.getWorldInfo().setDifficultyLocked(true); world.getWorldInfo().setDifficulty(EnumDifficulty.NORMAL);
            world.getGameRules().setOrCreateGameRule("doMobSpawning", "false");
            saved.prepare(player); equip(Items.WOODEN_SWORD);
            player.connection.setPlayerLocation(layout.x + 0.5, layout.y + 1, layout.z + 13, 180, 0);
            zombie = new EntityZombie(world); zombie.setUniqueId(zombieId); zombie.setNoAI(true); zombie.enablePersistence();
            zombie.setChild(false); zombie.setSilent(true); zombie.setCanPickUpLoot(false);
            zombie.setPositionAndRotation(layout.x + 0.5, layout.y + 1, layout.z + 10.5, 0, 0);
            if (!world.spawnEntity(zombie)) throw new IllegalStateException("Could not spawn the owned survival opponent");
            zombie.getEntityAttribute(SharedMonsterAttributes.MAX_HEALTH).setBaseValue(40); zombie.setHealth(40);
            zombie.getEntityAttribute(SharedMonsterAttributes.ATTACK_DAMAGE).setBaseValue(2);
            zombie.getEntityAttribute(SharedMonsterAttributes.KNOCKBACK_RESISTANCE).setBaseValue(1);
            for (EntityEquipmentSlot slot : EntityEquipmentSlot.values()) zombie.setItemStackToSlot(slot, ItemStack.EMPTY);
            projectiles = new ProjectileOwner(player); MinecraftForge.EVENT_BUS.register(projectiles);
            return null;
        });
        log("STARTED");
    }

    static Layout build(WorldServer world, int x, int y, int z) {
        FixtureCompatibility.requireSmokeSave(world.getMinecraftServer().getFolderName());
        if (!world.getMinecraftServer().isCallingFromMinecraftThread() || world.provider.getDimension() != 0)
            throw new IllegalStateException("Survival arena requires its owned Overworld server thread");
        for (int dx = -8; dx <= 20; dx++) for (int dz = 0; dz <= 22; dz++) for (int dy = 0; dy <= 6; dy++) {
            boolean shell = dx == -8 || dx == 20 || dz == 0 || dz == 22 || dy == 0 || dy == 6;
            BlockPos pos = new BlockPos(x + dx, y + dy, z + dz);
            if (shell) world.setBlockState(pos, Blocks.STONEBRICK.getDefaultState(), 3); else world.setBlockToAir(pos);
        }
        world.setBlockState(new BlockPos(x, y + 5, z + 12), Blocks.GLOWSTONE.getDefaultState(), 3);
        return new Layout(x, y, z);
    }

    boolean tick() throws Exception {
        if (mc.getIntegratedServer() != server || mc.world != clientWorld || mc.player == null
                || !events.isEnabled() || events.failureMessage() != null) throw new IllegalStateException("Survival fixture lost its renderer/world");
        if (!started) start();
        if (System.nanoTime() - since > TimeUnit.SECONDS.toNanos(120)) {
            capture("survival-timeout-" + phase.name().toLowerCase(Locale.ROOT));
            throw new IllegalStateException("Survival fixture timed out: " + phase);
        }
        if (System.nanoTime() - progress > TimeUnit.SECONDS.toNanos(10)) { log("PROGRESS"); progress = System.nanoTime(); }
        if (phase != Phase.RESTORE && phase != Phase.RETIRED && phase != Phase.DONE) holdPose();
        if (phase == Phase.HURT) hurtObserved |= mc.player.hurtTime > 0 || FixtureSurvivalState.damageHealth(mc.player) < healthBeforeDamage;
        if (phase == Phase.MELEE) swingObserved |= mc.player.isSwingInProgress && mc.player.swingProgress > 0;
        if (phase == Phase.EATING) useObserved |= mc.player.isHandActive() && mc.player.getItemInUseMaxCount() >= 10;
        switch (phase) {
            case ARRIVE:
                if (complete()) { clientWorld.getWorldInfo().setDifficulty(EnumDifficulty.NORMAL); transition(Phase.READY); }
                break;
            case READY:
                if (settled(ready() && holding(Items.WOODEN_SWORD) && mc.player.getCooledAttackStrength(0) > 0.99f, 30, 20)) {
                    capture("survival-ready");
                    action = server.callFromMainThread(() -> {
                        requireWorld(); EntityPlayerMP player = player(); healthBeforeDamage = FixtureSurvivalState.damageHealth(player);
                        player.hurtResistantTime = 0; zombie.setAttackTarget(player); zombie.swingArm(EnumHand.MAIN_HAND);
                        zombie.attackEntityAsMob(player);
                        serverDamageApplied = FixtureSurvivalState.damageHealth(player) < healthBeforeDamage;
                        zombieBeforeMelee = zombie.getHealth(); return null;
                    });
                    transition(Phase.DAMAGE_ACTION);
                }
                break;
            case DAMAGE_ACTION:
                if (complete()) transition(Phase.HURT);
                break;
            case HURT:
                if (settled(serverDamageApplied && hurtObserved && ready(), 4, 1)) {
                    capture("survival-damaged-hud"); log("HOSTILE_DAMAGE_PASS");
                    Entity target = clientEntity(zombieId);
                    if (target == null) throw new IllegalStateException("Survival target disappeared before attack");
                    mc.playerController.attackEntity(mc.player, target); mc.player.swingArm(EnumHand.MAIN_HAND);
                    transition(Phase.MELEE);
                }
                break;
            case MELEE:
                Entity target = clientEntity(zombieId);
                if (settled(target instanceof EntityZombie && ((EntityZombie) target).getHealth() < zombieBeforeMelee
                        && swingObserved && ready(), 15, 3)) {
                    capture("survival-melee"); log("PLAYER_ATTACK_PASS"); yaw = -90;
                    action = server.callFromMainThread(() -> {
                        requireWorld(); equip(Items.SNOWBALL); projectiles.armed = true;
                        player().connection.setPlayerLocation(layout.x + 0.5, layout.y + 1, layout.z + 13, -90, 0); return null;
                    });
                    transition(Phase.EQUIP_THROW);
                }
                break;
            case EQUIP_THROW:
                if (complete()) transition(Phase.THROW_READY);
                break;
            case THROW_READY:
                if (settled(holding(Items.SNOWBALL) && ready(), 20, 10)) {
                    if (mc.playerController.processRightClick(mc.player, clientWorld, EnumHand.MAIN_HAND) != EnumActionResult.SUCCESS)
                        throw new IllegalStateException("The actual survival snowball use was rejected");
                    mc.player.swingArm(EnumHand.MAIN_HAND); action = null; transition(Phase.PROJECTILE);
                }
                break;
            case PROJECTILE:
                if (action == null || complete()) action = server.callFromMainThread(() -> {
                    requireWorld();
                    EntitySnowball projectile = projectiles.entity;
                    if (projectile != null && projectile.ticksExisted >= 2 && !projectileFrozen) {
                        if (projectile.isDead) throw new IllegalStateException("The owned thrown projectile hit something before capture");
                        projectile.motionX = projectile.motionY = projectile.motionZ = 0; projectile.setNoGravity(true);
                        world.getEntityTracker().sendToTrackingAndSelf(projectile, new SPacketEntityVelocity(projectile));
                        projectileFrozen = true;
                    }
                    return null;
                });
                if (settled(projectileId != null && projectileFrozen && mc.player.getHeldItemMainhand().isEmpty()
                        && events.entityCaptured(projectileId) && events.trianglePartCount(projectileId) > 0 && ready(), 30, 10)) {
                    capture("survival-thrown-projectile"); log("PLAYER_PROJECTILE_PASS");
                    action = server.callFromMainThread(() -> { requireWorld(); equip(Items.BREAD); return null; }); transition(Phase.EQUIP_FOOD);
                }
                break;
            case EQUIP_FOOD:
                if (complete()) transition(Phase.FOOD_READY);
                break;
            case FOOD_READY:
                if (settled(holding(Items.BREAD) && mc.player.getFoodStats().getFoodLevel() < 20 && ready(), 20, 10)) {
                    foodBefore = mc.player.getFoodStats().getFoodLevel(); foodCountBefore = mc.player.getHeldItemMainhand().getCount();
                    usePoseBaseline = events.itemTransformUpdates(playerId);
                    KeyBinding.setKeyBindState(useKey, true);
                    if (mc.playerController.processRightClick(mc.player, clientWorld, EnumHand.MAIN_HAND) != EnumActionResult.SUCCESS)
                        throw new IllegalStateException("The actual survival food use was rejected");
                    transition(Phase.EATING);
                }
                break;
            case EATING:
                if (!eatingCaptured && useObserved && mc.player.isHandActive()
                        && events.itemTransformUpdates(playerId) > usePoseBaseline && ready()) {
                    capture("survival-eating"); eatingCaptured = true; log("USE_ANIMATION_PASS");
                }
                if (eatingCaptured && !mc.player.isHandActive() && mc.player.getHeldItemMainhand().getCount() < foodCountBefore
                        && mc.player.getFoodStats().getFoodLevel() > foodBefore && ready()) {
                    KeyBinding.setKeyBindState(useKey, false); capture("survival-food-consumed"); log("FOOD_USE_PASS");
                    reloadBaseline = events.textureReloadCount(); events.reload(); transition(Phase.RELOAD);
                }
                break;
            case RELOAD:
                if (settled(events.textureReloadCount() > reloadBaseline && ready() && events.entityCaptured(projectileId), 30, 10)) {
                    capture("survival-reloaded"); log("SCENE_CONTINUITY_PASS"); restoreClient();
                    cleanup = restoreServer(); action = cleanup; cleanupQueued = true; transition(Phase.RESTORE);
                }
                break;
            case RESTORE:
                if (complete()) transition(Phase.RETIRED);
                break;
            case RETIRED:
                if (settled(!events.entityCaptured(zombieId) && (projectileId == null || !events.entityCaptured(projectileId))
                        && mc.playerController.getCurrentGameType() == saved.gameType, 30, 10)) { transition(Phase.DONE); log("PASS"); }
                break;
            default: break;
        }
        return phase == Phase.DONE;
    }

    private boolean ready() {
        return mc.playerController.getCurrentGameType() == GameType.SURVIVAL && !mc.player.capabilities.disableDamage
                && mc.player.isEntityAlive() && events.entityCaptured(zombieId)
                && (events.itemCategoryCount(InstanceCategoryBit.VIEW_MODEL) > 0 || events.entityCategoryCount(InstanceCategoryBit.VIEW_MODEL) > 0)
                && events.terrainSectionReady(layout.x >> 4, layout.y >> 4, (layout.z + 12) >> 4)
                && events.blockLightCaptured(new BlockPos(layout.x, layout.y + 5, layout.z + 12));
    }
    private boolean holding(Item item) { return !mc.player.getHeldItemMainhand().isEmpty() && mc.player.getHeldItemMainhand().getItem() == item; }
    private void equip(Item item) {
        EntityPlayerMP player = player(); player.resetActiveHand(); player.inventory.currentItem = saved.selectedSlot;
        player.inventory.setInventorySlotContents(saved.selectedSlot, new ItemStack(item));
        player.setItemStackToSlot(EntityEquipmentSlot.OFFHAND, ItemStack.EMPTY); FixtureSurvivalState.syncInventory(player);
    }
    private void holdPose() {
        double x = layout.x + 0.5, y = layout.y + 1, z = layout.z + 13;
        mc.player.setPositionAndRotation(x, y, z, yaw, 0);
        mc.player.prevPosX = mc.player.lastTickPosX = x; mc.player.prevPosY = mc.player.lastTickPosY = y;
        mc.player.prevPosZ = mc.player.lastTickPosZ = z; mc.player.prevRotationYaw = yaw; mc.player.prevRotationPitch = 0;
        mc.player.motionX = mc.player.motionY = mc.player.motionZ = 0; mc.player.fallDistance = 0;
        if (saved != null) mc.player.inventory.currentItem = saved.selectedSlot;
    }
    private Entity clientEntity(UUID id) {
        for (Entity entity : clientWorld.loadedEntityList) if (id.equals(entity.getUniqueID())) return entity;
        return null;
    }
    private EntityPlayerMP player() {
        EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(playerId);
        if (player == null || player.world != world) throw new IllegalStateException("Survival fixture player unavailable"); return player;
    }
    private void requireWorld() {
        FixtureCompatibility.requireSmokeSave(save);
        if (!server.isCallingFromMinecraftThread() || !save.equals(server.getFolderName()) || server.getWorld(0) != world)
            throw new IllegalStateException("Survival fixture no longer owns its server world");
    }
    private Future<?> restoreServer() {
        return server.callFromMainThread(() -> {
            requireWorld();
            if (projectiles != null) { MinecraftForge.EVENT_BUS.unregister(projectiles); if (projectiles.entity != null) projectiles.entity.setDead(); }
            if (zombie != null) zombie.setDead();
            EntityPlayerMP player = player();
            try { if (saved != null) saved.restore(player); }
            finally {
                try { player.connection.setPlayerLocation(returnX, returnY, returnZ, returnYaw, returnPitch); }
                finally {
                    if (settingsChanged) {
                        world.getWorldInfo().setDifficulty(difficulty); world.getWorldInfo().setDifficultyLocked(difficultyLocked);
                        world.getGameRules().setOrCreateGameRule("doMobSpawning", spawning); settingsChanged = false;
                    }
                }
            }
            return null;
        });
    }
    private void restoreClient() {
        mc.gameSettings.thirdPersonView = originalView; mc.gameSettings.hideGUI = originalHud;
        clientWorld.getWorldInfo().setDifficulty(originalClientDifficulty);
        if (mc.player != null && playerId.equals(mc.player.getUniqueID())) mc.player.resetActiveHand();
        KeyBinding.setKeyBindState(useKey, originalUseDown);
    }
    private boolean complete() throws Exception { if (action == null || !action.isDone()) return false; action.get(); return true; }
    private boolean settled(boolean ready, int frames, int ticks) {
        if (!ready) { frame = -1; return false; }
        if (frame < 0) { frame = events.renderedFrames(); readyTick = mc.player.ticksExisted; }
        return events.renderedFrames() - frame >= frames && mc.player.ticksExisted - readyTick >= ticks;
    }
    private void transition(Phase next) { phase = next; since = progress = System.nanoTime(); frame = -1; log("PHASE"); }
    private void capture(String name) throws Exception { output.capture(name, output.image()); }
    private void log(String name) { output.log("SURVIVAL_" + name, status()); }
    String status() {
        Entity target = clientEntity(zombieId);
        return "phase=" + phase + " game=" + (mc.playerController == null ? "none" : mc.playerController.getCurrentGameType())
                + " health=" + (mc.player == null ? -1 : mc.player.getHealth())
                + " food=" + (mc.player == null ? -1 : mc.player.getFoodStats().getFoodLevel()) + " hurt=" + hurtObserved + " serverDamage=" + serverDamageApplied
                + " targetHealth=" + (target instanceof EntityZombie ? ((EntityZombie) target).getHealth() : -1)
                + " targetCaptured=" + events.entityCaptured(zombieId) + " swing=" + swingObserved
                + " projectile=" + projectileId + " frozen=" + projectileFrozen
                + " projectileCaptured=" + (projectileId != null && events.entityCaptured(projectileId))
                + " use=" + useObserved + " active=" + (mc.player != null && mc.player.isHandActive())
                + " useTicks=" + (mc.player == null ? 0 : mc.player.getItemInUseMaxCount())
                + " frames=" + events.renderedFrames();
    }
    @Override public void close() {
        restoreClient(); if (!cleanupQueued) { cleanupQueued = true; cleanup = restoreServer(); }
    }
    boolean cleanupComplete() throws Exception {
        if (cleanup == null || !cleanup.isDone()) return false;
        cleanup.get(); return mc.player != null && (saved == null || mc.playerController.getCurrentGameType() == saved.gameType);
    }

    public final class ProjectileOwner {
        final EntityPlayerMP player;
        boolean armed;
        EntitySnowball entity;
        ProjectileOwner(EntityPlayerMP player) { this.player = player; }
        @SubscribeEvent public void spawned(EntityJoinWorldEvent event) {
            if (!armed || event.getWorld() != world || !(event.getEntity() instanceof EntitySnowball)
                    || !server.isCallingFromMinecraftThread()) return;
            EntitySnowball candidate = (EntitySnowball) event.getEntity();
            if (candidate.getThrower() != player) return;
            entity = candidate; projectileId = candidate.getUniqueID(); armed = false;
        }
    }
    static final class Layout {
        final int x, y, z;
        Layout(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
    }
}
