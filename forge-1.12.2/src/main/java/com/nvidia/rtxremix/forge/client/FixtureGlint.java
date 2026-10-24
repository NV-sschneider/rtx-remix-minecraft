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
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.monster.EntityEnderman;
import net.minecraft.entity.monster.EntitySpider;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Enchantments;
import net.minecraft.init.Items;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.WorldServer;

/** Existing item glint and mob eye passes are observed in an owned dark chamber. */
final class FixtureGlint implements AutoCloseable {
    private enum Phase { ARRIVE, LIT, ANIMATED, THIRD_PERSON, FIRST_PERSON, RELOAD,
        PLAIN_ACTION, PLAIN, REMOVE_ACTION, REMOVED, RETURN, DONE }
    private final Minecraft mc;
    private final ClientEvents events;
    private final IntegratedServer server;
    private final WorldServer world;
    private final WorldClient clientWorld;
    private final String save;
    private final Layout layout;
    private final FixtureTerrain.Output output;
    private final UUID playerId;
    private final int selectedSlot, originalView;
    private final boolean originalHud, originalFlying;
    private final EnumDifficulty originalClientDifficulty;
    private final double returnX, returnY, returnZ;
    private final float returnYaw, returnPitch;
    private ItemStack savedMain, savedOff;
    private Phase phase = Phase.ARRIVE;
    private Future<?> action;
    private long since = System.nanoTime(), progress = since, frame = -1, reloadBaseline;
    private int readyTick, animationSample;
    private long nextAnimationSample;
    private boolean cleanupQueued;

    FixtureGlint(Minecraft mc, ClientEvents events, IntegratedServer server, WorldServer world,
            String save, Layout layout, double x, double y, double z, float yaw, float pitch,
            FixtureTerrain.Output output) {
        this.mc = mc; this.events = events; this.server = server; this.world = world; this.save = save;
        this.layout = layout; this.output = output; playerId = mc.player.getUniqueID(); clientWorld = mc.world;
        selectedSlot = mc.player.inventory.currentItem; originalView = mc.gameSettings.thirdPersonView;
        originalHud = mc.gameSettings.hideGUI; originalFlying = mc.player.capabilities.isFlying;
        originalClientDifficulty = clientWorld.getDifficulty();
        returnX = x; returnY = y; returnZ = z; returnYaw = yaw; returnPitch = pitch;
        mc.gameSettings.thirdPersonView = 0; mc.gameSettings.hideGUI = false;
        clientWorld.getWorldInfo().setDifficulty(EnumDifficulty.NORMAL);
        action = server.callFromMainThread(() -> {
            requireWorld(); EntityPlayerMP player = player();
            savedMain = player.inventory.getStackInSlot(selectedSlot).copy(); savedOff = player.getHeldItemOffhand().copy();
            world.getWorldInfo().setDifficulty(EnumDifficulty.NORMAL);
            spawnEntities(world, layout);
            equip(true); player.connection.setPlayerLocation(layout.x + 0.5, layout.y + 1, layout.z + 17.5, 180, 8);
            return null;
        });
        log("STARTED");
    }

    boolean tick() throws Exception {
        if (mc.getIntegratedServer() != server || mc.world != clientWorld || mc.player == null || !events.isEnabled()
                || events.failureMessage() != null) throw new IllegalStateException("Glint fixture renderer/world unavailable");
        if (System.nanoTime() - since > TimeUnit.SECONDS.toNanos(180)) {
            capture("glint-timeout-" + phase.name().toLowerCase(Locale.ROOT));
            throw new IllegalStateException("Glint fixture timed out: " + phase);
        }
        if (System.nanoTime() - progress > TimeUnit.SECONDS.toNanos(10)) { log("PROGRESS"); progress = System.nanoTime(); }
        if (phase != Phase.RETURN && phase != Phase.DONE) holdPose();
        switch (phase) {
            case ARRIVE:
                if (complete()) transition(Phase.LIT);
                break;
            case LIT:
                if (settled(ready(true), 45)) {
                    capture("glint-held-dropped-eyes"); log("CAPTURE_PASS"); transition(Phase.ANIMATED);
                    nextAnimationSample = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(750);
                }
                break;
            case ANIMATED:
                // Real wall-clock phases span both vanilla glint scroll periods (3 s and 4.873 s).
                if (ready(true) && System.nanoTime() >= nextAnimationSample && animationSample < 7) {
                    capture("glint-phase-" + ++animationSample);
                    nextAnimationSample = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(750);
                }
                if (settled(animationSample == 7 && ready(true), 30)) {
                    capture("glint-animated"); log("ANIMATION_FRAMES_PASS"); mc.gameSettings.thirdPersonView = 1; transition(Phase.THIRD_PERSON);
                }
                break;
            case THIRD_PERSON:
                if (settled(ready(false) && events.itemCategoryCount(InstanceCategoryBit.VIEW_MODEL) == 0, 45)) {
                    capture("glint-third-person"); log("THIRD_PERSON_PASS"); mc.gameSettings.thirdPersonView = 0; transition(Phase.FIRST_PERSON);
                }
                break;
            case FIRST_PERSON:
                if (settled(ready(true), 30)) { reloadBaseline = events.textureReloadCount(); events.reload(); transition(Phase.RELOAD); }
                break;
            case RELOAD:
                if (settled(events.textureReloadCount() > reloadBaseline && ready(true), 45)) {
                    capture("glint-reloaded"); log("RELOAD_PASS");
                    action = server.callFromMainThread(() -> {
                        requireWorld(); equip(false); Entity entity = world.getEntityFromUuid(layout.drop);
                        if (!(entity instanceof EntityItem)) throw new IllegalStateException("Fixture item disappeared");
                        ((EntityItem) entity).setItem(sword(false)); return null;
                    });
                    transition(Phase.PLAIN_ACTION);
                }
                break;
            case PLAIN_ACTION:
                if (complete()) transition(Phase.PLAIN);
                break;
            case PLAIN:
                if (settled(!mc.player.getHeldItemMainhand().isItemEnchanted()
                        && events.entityEmissivePartCount(playerId) == 0 && events.entityEmissivePartCount(layout.drop) == 0
                        && events.itemCaptured(layout.drop) && events.itemCategoryCount(InstanceCategoryBit.VIEW_MODEL) > 0
                        && eyes(), 45)) {
                    capture("glint-removed-eyes-retained"); log("PLAIN_ITEM_PASS");
                    action = server.callFromMainThread(() -> { requireWorld(); removeEntities(); return null; }); transition(Phase.REMOVE_ACTION);
                }
                break;
            case REMOVE_ACTION:
                if (complete()) transition(Phase.REMOVED);
                break;
            case REMOVED:
                if (settled(!events.entityCaptured(layout.enderman) && !events.entityCaptured(layout.spider)
                        && !events.entityCaptured(layout.armor) && !events.itemCaptured(layout.drop)
                        && events.entityEmissivePartCount(layout.drop) == 0, 30)) {
                    capture("glint-owners-removed"); log("RETIREMENT_PASS");
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

    static Layout build(WorldServer world, int x, int y, int z) {
        Layout layout = new Layout(x, y, z, world.getWorldInfo().getDifficulty());
        for (int dx = -8; dx <= 8; dx++) for (int dz = 0; dz <= 21; dz++) for (int dy = 0; dy <= 7; dy++) {
            boolean shell = dx == -8 || dx == 8 || dz == 0 || dz == 21 || dy == 0 || dy == 7;
            BlockPos pos = new BlockPos(x + dx, y + dy, z + dz);
            if (shell) world.setBlockState(pos, Blocks.STONE.getDefaultState(), 3); else world.setBlockToAir(pos);
        }
        return layout;
    }
    private static void spawnEntities(WorldServer world, Layout layout) {
        int x = layout.x, y = layout.y, z = layout.z;
        EntityEnderman enderman = new EntityEnderman(world); enderman.setUniqueId(layout.enderman);
        spawn(world, enderman, x - 2.5, y + 1, z + 8.5);
        EntitySpider spider = new EntitySpider(world); spider.setUniqueId(layout.spider);
        spawn(world, spider, x + 3.5, y + 1, z + 8.5);
        EntityArmorStand armor = new EntityArmorStand(world, x + 0.5, y + 1, z + 7.5);
        armor.setUniqueId(layout.armor);
        ItemStack chest = new ItemStack(Items.DIAMOND_CHESTPLATE); chest.addEnchantment(Enchantments.UNBREAKING, 1);
        armor.setItemStackToSlot(EntityEquipmentSlot.CHEST, chest); spawn(world, armor, armor.posX, armor.posY, armor.posZ);
        EntityItem drop = new EntityItem(world, x + 0.5, y + 2.2, z + 11.5, sword(true));
        drop.setUniqueId(layout.drop);
        drop.setInfinitePickupDelay(); drop.setNoDespawn(); drop.hoverStart = 0; spawn(world, drop, drop.posX, drop.posY, drop.posZ);
    }
    private static ItemStack sword(boolean enchanted) {
        ItemStack stack = new ItemStack(Items.DIAMOND_SWORD);
        if (enchanted) stack.addEnchantment(Enchantments.UNBREAKING, 1);
        return stack;
    }
    private static void spawn(WorldServer world, Entity entity, double x, double y, double z) {
        entity.setPositionAndRotation(x, y, z, 0, 0); entity.setNoGravity(true); entity.setEntityInvulnerable(true);
        entity.motionX = entity.motionY = entity.motionZ = 0; entity.setSilent(true);
        if (entity instanceof EntityLiving) { ((EntityLiving) entity).setNoAI(true); ((EntityLiving) entity).enablePersistence(); }
        if (!world.spawnEntity(entity)) throw new IllegalStateException("Could not spawn glint fixture entity");
    }
    private boolean eyes() { return events.entityEmissivePartCount(layout.enderman) > 0 && events.entityEmissivePartCount(layout.spider) > 0
            && events.entityEmissivePartCount(layout.armor) > 0; }
    private boolean ready(boolean firstPerson) {
        return chamberReady() && mc.player.getHeldItemMainhand().isItemEnchanted()
                && events.entityEmissivePartCount(playerId) >= (firstPerson ? 4 : 2)
                && events.entityEmissivePartCount(layout.drop) >= 2 && events.itemCaptured(layout.drop) && eyes()
                && (!firstPerson || events.itemCategoryCount(InstanceCategoryBit.VIEW_MODEL) > 0);
    }
    private boolean chamberReady() {
        for (int x = (layout.x - 8) >> 4; x <= (layout.x + 8) >> 4; x++) {
            for (int y = layout.y >> 4; y <= (layout.y + 7) >> 4; y++) {
                for (int z = layout.z >> 4; z <= (layout.z + 21) >> 4; z++) {
                    if (!events.terrainSectionReady(x, y, z)) return false;
                }
            }
        }
        return true;
    }
    private void equip(boolean enchanted) {
        EntityPlayerMP player = player(); player.resetActiveHand(); player.inventory.currentItem = selectedSlot;
        player.inventory.setInventorySlotContents(selectedSlot, sword(enchanted)); player.setItemStackToSlot(EntityEquipmentSlot.OFFHAND, ItemStack.EMPTY);
        player.inventory.markDirty(); player.inventoryContainer.detectAndSendChanges();
    }
    private void holdPose() {
        double x = layout.x + 0.5, y = layout.y + 1, z = layout.z + 17.5;
        mc.player.setPositionAndRotation(x, y, z, 180, 8);
        mc.player.prevPosX = mc.player.lastTickPosX = x; mc.player.prevPosY = mc.player.lastTickPosY = y;
        mc.player.prevPosZ = mc.player.lastTickPosZ = z; mc.player.prevRotationYaw = 180; mc.player.prevRotationPitch = 8;
        mc.player.motionX = mc.player.motionY = mc.player.motionZ = 0; mc.player.capabilities.isFlying = true;
        mc.player.inventory.currentItem = selectedSlot;
    }
    private EntityPlayerMP player() {
        EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(playerId);
        if (player == null) throw new IllegalStateException("Glint fixture player unavailable"); return player;
    }
    private void requireWorld() {
        if (!server.isCallingFromMinecraftThread() || !save.equals(server.getFolderName()) || server.getWorld(0) != world)
            throw new IllegalStateException("Glint fixture no longer owns server world");
    }
    private void removeEntities() {
        for (UUID id : new UUID[] {layout.enderman, layout.spider, layout.drop, layout.armor}) {
            Entity entity = world.getEntityFromUuid(id); if (entity != null) entity.setDead();
        }
    }
    private boolean complete() throws Exception { if (!action.isDone()) return false; action.get(); return true; }
    private boolean settled(boolean ready, int frames) {
        if (!ready) { frame = -1; return false; }
        if (frame < 0) { frame = events.renderedFrames(); readyTick = mc.player.ticksExisted; }
        return events.renderedFrames() - frame >= frames && mc.player.ticksExisted - readyTick >= 10;
    }
    private void transition(Phase next) { phase = next; since = progress = System.nanoTime(); frame = -1; log("PHASE"); }
    private void capture(String name) throws Exception { output.capture(name, output.image()); }
    private void log(String name) { output.log("GLINT_" + name, status()); }
    String status() {
        return "phase=" + phase + " playerGlow=" + events.entityEmissivePartCount(playerId)
                + " droppedGlow=" + events.entityEmissivePartCount(layout.drop) + " endermanGlow=" + events.entityEmissivePartCount(layout.enderman)
                + " spiderGlow=" + events.entityEmissivePartCount(layout.spider) + " viewItems=" + events.itemCategoryCount(InstanceCategoryBit.VIEW_MODEL)
                + " armorGlow=" + events.entityEmissivePartCount(layout.armor)
                + " chamberReady=" + chamberReady()
                + " frames=" + events.renderedFrames();
    }
    private void restoreClient() {
        mc.gameSettings.thirdPersonView = originalView; mc.gameSettings.hideGUI = originalHud;
        clientWorld.getWorldInfo().setDifficulty(originalClientDifficulty);
        if (mc.player != null && playerId.equals(mc.player.getUniqueID())) { mc.player.resetActiveHand(); mc.player.capabilities.isFlying = originalFlying; }
    }
    private Future<?> restoreServer() {
        return server.callFromMainThread(() -> {
            requireWorld(); removeEntities(); world.getWorldInfo().setDifficulty(layout.difficulty);
            EntityPlayerMP player = player(); player.resetActiveHand();
            if (savedMain != null) {
                player.inventory.setInventorySlotContents(selectedSlot, savedMain); player.setItemStackToSlot(EntityEquipmentSlot.OFFHAND, savedOff);
                player.inventory.markDirty(); player.inventoryContainer.detectAndSendChanges(); savedMain = savedOff = null;
            }
            player.connection.setPlayerLocation(returnX, returnY, returnZ, returnYaw, returnPitch); return null;
        });
    }
    @Override public void close() { restoreClient(); if (!cleanupQueued) { cleanupQueued = true; restoreServer(); } }
    static final class Layout {
        final int x, y, z;
        final EnumDifficulty difficulty;
        final UUID enderman = UUID.randomUUID(), spider = UUID.randomUUID(), drop = UUID.randomUUID(), armor = UUID.randomUUID();
        Layout(int x, int y, int z, EnumDifficulty difficulty) { this.x = x; this.y = y; this.z = z; this.difficulty = difficulty; }
    }
}
