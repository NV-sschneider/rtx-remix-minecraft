/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import com.nvidia.rtxremix.InstanceCategoryBit;
import com.nvidia.rtxremix.forge.entity.EntityCaptureHooks;
import com.nvidia.rtxremix.forge.entity.FirstPersonCapture;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

/** Owned-world checks for native hand/body categories and the vanilla equip/use animation path. */
final class FixtureFirstPerson implements AutoCloseable {
    private enum Phase { ARRIVE, EMPTY, EQUIP_SWORD, SWORD, VANILLA_SWORD, SWING, EQUIP_BOW, BOW, DRAW_START, DRAW,
        WIDE_FOV, THIRD_PERSON, FIRST_PERSON, HIDDEN_HUD, HUD_RESTORED, INVENTORY, RELOAD,
        DAMAGE_EARLY, DAMAGE_LATE, DAMAGE_RELOAD, DAMAGE_CANCELLED, RETURN, DONE }
    private static final int BREAKER_ID = Integer.MIN_VALUE + 7112;
    private static final Field EQUIPPED = ReflectionHelper.findField(ItemRenderer.class,
            new String[] {"equippedProgressMainHand", "field_187469_f"});
    private static final Field PREVIOUS_EQUIPPED = ReflectionHelper.findField(ItemRenderer.class,
            new String[] {"prevEquippedProgressMainHand", "field_187470_g"});
    private final Minecraft mc;
    private final ClientEvents events;
    private final IntegratedServer server;
    private final WorldServer world;
    private final String save;
    private final Layout layout;
    private final BlockPos damageBlock;
    private final FixtureTerrain.Output output;
    private final UUID playerId;
    private final double returnX, returnY, returnZ;
    private final float returnYaw, returnPitch, originalFov;
    private final int originalView, selectedSlot, useKey;
    private final boolean originalHud, originalFlying, originalUseDown;
    private final long failureBaseline;
    private Phase phase = Phase.ARRIVE;
    private Future<?> action;
    private long since = System.nanoTime(), progress = since, frame = -1, motionBaseline, reloadBaseline;
    private long damageBaseline;
    private int lastSwingTick = Integer.MIN_VALUE;
    private GuiScreen ownedScreen;
    private BufferedImage emptyImage;
    private String swordFailure;
    private int vanillaSwordTicks;
    private final BlockPos.MutableBlockPos arenaProbe = new BlockPos.MutableBlockPos();
    private boolean cleanupQueued;
    // Accessed only by queued server tasks, including cleanup after an early failure.
    private ItemStack originalMain, originalOff;

    FixtureFirstPerson(Minecraft mc, ClientEvents events, IntegratedServer server, WorldServer world,
            String save, Layout layout, double x, double y, double z, float yaw, float pitch,
            FixtureTerrain.Output output) {
        this.mc = mc; this.events = events; this.server = server; this.world = world;
        this.save = save; this.layout = layout; this.output = output;
        damageBlock = new BlockPos(layout.x, layout.y + 2, layout.z + 1);
        playerId = mc.player.getUniqueID(); selectedSlot = mc.player.inventory.currentItem;
        returnX = x; returnY = y; returnZ = z; returnYaw = yaw; returnPitch = pitch;
        originalView = mc.gameSettings.thirdPersonView; originalHud = mc.gameSettings.hideGUI;
        originalFov = mc.gameSettings.fovSetting; originalFlying = mc.player.capabilities.isFlying;
        useKey = mc.gameSettings.keyBindUseItem.getKeyCode(); originalUseDown = mc.gameSettings.keyBindUseItem.isKeyDown();
        failureBaseline = EntityCaptureHooks.offscreenFailureCount();
        mc.gameSettings.thirdPersonView = 0; mc.gameSettings.hideGUI = false;
        action = server.callFromMainThread(() -> {
            requireWorld();
            EntityPlayerMP player = player();
            originalMain = player.inventory.getStackInSlot(selectedSlot).copy();
            originalOff = player.getHeldItemOffhand().copy();
            equip(null);
            player.connection.setPlayerLocation(layout.x + 0.5, layout.y + 1, layout.z + 11.5, 180, 12);
            return null;
        });
        log("STARTED");
    }

    boolean tick() throws Exception {
        if (mc.getIntegratedServer() != server || mc.world == null || mc.player == null
                || !events.isEnabled() && phase != Phase.VANILLA_SWORD
                || events.failureMessage() != null) throw new IllegalStateException("First-person fixture renderer/world unavailable");
        if (EntityCaptureHooks.offscreenFailureCount() != failureBaseline)
            throw new IllegalStateException("First-person fixture supplementary renderer failed");
        if (System.nanoTime() - since > TimeUnit.SECONDS.toNanos(180)) {
            capture("firstperson-timeout-" + phase.name().toLowerCase(Locale.ROOT));
            throw new IllegalStateException("First-person fixture timed out: " + phase);
        }
        if (System.nanoTime() - progress > TimeUnit.SECONDS.toNanos(10)) { log("PROGRESS"); progress = System.nanoTime(); }
        if (phase != Phase.RETURN && phase != Phase.DONE) holdPose();
        switch (phase) {
            case ARRIVE:
                if (complete()) transition(Phase.EMPTY);
                break;
            case EMPTY:
                if (settled(arenaReady() && mc.player.getHeldItemMainhand().isEmpty()
                        && hands() > 0 && viewItems() == 0 && shadow(), 30)) {
                    emptyImage = output.image(); output.capture("firstperson-empty", emptyImage); log("EMPTY_HAND_PASS");
                    action = setItem(Items.DIAMOND_SWORD); transition(Phase.EQUIP_SWORD);
                }
                break;
            case EQUIP_SWORD:
                if (complete()) transition(Phase.SWORD);
                break;
            case SWORD:
                if (settled(arenaReady() && holding(Items.DIAMOND_SWORD) && equipped()
                        && viewItems() > 0 && shadowItems() > 0 && shadow(), 30)) {
                    if (!captureSword()) break;
                    log("EQUIP_PASS");
                    motionBaseline = events.itemTransformUpdates(playerId); transition(Phase.SWING);
                }
                break;
            case VANILLA_SWORD:
                if (++vanillaSwordTicks >= 40) {
                    IllegalStateException failure = new IllegalStateException(swordFailure);
                    try { capture("firstperson-sword-vanilla-control"); }
                    catch (Exception | LinkageError diagnostic) { failure.addSuppressed(diagnostic); }
                    finally {
                        try { events.setEnabled(true); }
                        catch (RuntimeException | LinkageError restoration) { failure.addSuppressed(restoration); }
                    }
                    throw failure;
                }
                break;
            case SWING:
                if (mc.player.ticksExisted - lastSwingTick >= 8 || lastSwingTick == Integer.MIN_VALUE) {
                    lastSwingTick = mc.player.ticksExisted; mc.player.swingArm(EnumHand.MAIN_HAND);
                }
                if (settled(viewItems() > 0 && shadow() && events.itemTransformUpdates(playerId) > motionBaseline, 30)
                        && mc.player.swingProgress > 0.15f && mc.player.swingProgress < 0.85f) {
                    capture("firstperson-swing"); log("SWING_PASS");
                    action = setItem(Items.BOW); transition(Phase.EQUIP_BOW);
                }
                break;
            case EQUIP_BOW:
                if (complete()) transition(Phase.BOW);
                break;
            case BOW:
                if (settled(holding(Items.BOW) && equipped() && viewItems() > 0 && shadowItems() > 0 && shadow(), 30)) {
                    capture("firstperson-bow"); motionBaseline = events.itemTransformUpdates(playerId);
                    // Minecraft.processKeyBinds otherwise immediately calls
                    // onStoppedUsingItem, even after setActiveHand succeeded.
                    KeyBinding.setKeyBindState(useKey, true);
                    action = server.callFromMainThread(() -> { requireWorld(); player().setActiveHand(EnumHand.MAIN_HAND); return null; });
                    mc.player.setActiveHand(EnumHand.MAIN_HAND); transition(Phase.DRAW_START);
                }
                break;
            case DRAW_START:
                if (complete()) transition(Phase.DRAW);
                break;
            case DRAW:
                if (settled(viewItems() > 0 && shadow() && mc.player.isHandActive() && mc.player.getItemInUseMaxCount() >= 15
                        && events.itemTransformUpdates(playerId) > motionBaseline, 30)) {
                    capture("firstperson-bow-drawn"); log("BOW_USE_PASS");
                    mc.gameSettings.fovSetting = 110; transition(Phase.WIDE_FOV);
                }
                break;
            case WIDE_FOV:
                if (settled(viewItems() > 0 && shadow(), 30)) {
                    capture("firstperson-wide-fov"); log("FOV_PASS");
                    mc.player.resetActiveHand();
                    action = server.callFromMainThread(() -> { requireWorld(); player().resetActiveHand(); return null; });
                    KeyBinding.setKeyBindState(useKey, false);
                    mc.gameSettings.fovSetting = originalFov; mc.gameSettings.thirdPersonView = 1;
                    transition(Phase.THIRD_PERSON);
                }
                break;
            case THIRD_PERSON:
                if (complete() && settled(hands() == 0 && viewItems() == 0 && shadowParts() == 0 && shadowItems() == 0
                        && events.entityCaptured(playerId) && events.itemCaptured(playerId)
                        && EntityCaptureHooks.normallyRendered(playerId), 30)) {
                    capture("firstperson-third-person"); log("THIRD_PERSON_RETIREMENT_PASS");
                    mc.gameSettings.thirdPersonView = 0; transition(Phase.FIRST_PERSON);
                }
                break;
            case FIRST_PERSON:
                if (settled(viewItems() > 0 && shadow(), 30)) {
                    mc.gameSettings.hideGUI = true; transition(Phase.HIDDEN_HUD);
                }
                break;
            case HIDDEN_HUD:
                if (settled(hands() == 0 && viewItems() == 0 && shadow(), 30)) {
                    capture("firstperson-hidden-hud"); log("HIDDEN_HAND_FALLBACK_PASS");
                    mc.gameSettings.hideGUI = false; transition(Phase.HUD_RESTORED);
                }
                break;
            case HUD_RESTORED:
                if (settled(viewItems() > 0 && shadow(), 30)) {
                    ownedScreen = new GuiInventory(mc.player); mc.displayGuiScreen(ownedScreen);
                    ownedScreen = mc.currentScreen; transition(Phase.INVENTORY);
                }
                break;
            case INVENTORY:
                if (settled(mc.currentScreen == ownedScreen && viewItems() > 0 && shadow(), 30)) {
                    capture("firstperson-inventory"); log("GUI_ISOLATION_PASS");
                    mc.player.closeScreen(); ownedScreen = null;
                    reloadBaseline = events.textureReloadCount(); events.reload(); transition(Phase.RELOAD);
                }
                break;
            case RELOAD:
                if (settled(events.textureReloadCount() > reloadBaseline && viewItems() > 0 && shadow(), 30)) {
                    capture("firstperson-reloaded"); log("RELOAD_PASS");
                    mc.renderGlobal.sendBlockBreakProgress(BREAKER_ID, damageBlock, 1); transition(Phase.DAMAGE_EARLY);
                }
                break;
            case DAMAGE_EARLY:
                if (settled(events.damageQuads() > 0, 30)) {
                    capture("damage-stage-1"); log("DAMAGE_EARLY_PASS");
                    damageBaseline = events.damageGeometryHash();
                    mc.renderGlobal.sendBlockBreakProgress(BREAKER_ID, damageBlock, 8); transition(Phase.DAMAGE_LATE);
                }
                break;
            case DAMAGE_LATE:
                if (settled(events.damageQuads() > 0 && events.damageGeometryHash() != damageBaseline, 30)) {
                    capture("damage-stage-8"); log("DAMAGE_STAGE_CHANGE_PASS");
                    reloadBaseline = events.textureReloadCount(); events.reload(); transition(Phase.DAMAGE_RELOAD);
                }
                break;
            case DAMAGE_RELOAD:
                if (settled(events.textureReloadCount() > reloadBaseline && events.damageQuads() > 0, 30)) {
                    capture("damage-reloaded"); log("DAMAGE_RELOAD_PASS");
                    mc.renderGlobal.sendBlockBreakProgress(BREAKER_ID, damageBlock, -1); transition(Phase.DAMAGE_CANCELLED);
                }
                break;
            case DAMAGE_CANCELLED:
                if (settled(events.damageQuads() == 0, 30)) {
                    capture("damage-cancelled"); log("DAMAGE_CANCEL_PASS");
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
        if (Boolean.getBoolean("rlcraftremix.smoke.firstPersonFar")) x += 16384;
        for (int dx = -8; dx <= 8; dx++) for (int dz = 0; dz <= 18; dz++) {
            world.setBlockState(new BlockPos(x + dx, y, z + dz),
                    ((dx + dz) & 1) == 0 ? Blocks.QUARTZ_BLOCK.getDefaultState() : Blocks.STONE.getDefaultState(), 3);
            for (int dy = 1; dy <= 9; dy++) world.setBlockToAir(new BlockPos(x + dx, y + dy, z + dz));
        }
        for (int dx = -6; dx <= 6; dx += 3) for (int dy = 1; dy <= 4; dy++) {
            world.setBlockState(new BlockPos(x + dx, y + dy, z + 1), Blocks.QUARTZ_BLOCK.getDefaultState(), 3);
        }
        return new Layout(x, y, z);
    }

    private boolean holding(Item item) { return mc.player.getHeldItemMainhand().getItem() == item; }
    private float equipProgress(Field field) {
        try { return field.getFloat(mc.entityRenderer.itemRenderer); }
        catch (IllegalAccessException error) { throw new IllegalStateException("Cannot observe vanilla equip progress", error); }
    }
    private boolean equipped() {
        // The newly captured item first appears while vanilla holds it below the viewport.
        // Render-frame counts do not prove that its tick-driven equip animation has finished.
        return equipProgress(EQUIPPED) >= 0.99f && equipProgress(PREVIOUS_EQUIPPED) >= 0.99f;
    }
    private int hands() { return events.entityCategoryCount(InstanceCategoryBit.VIEW_MODEL); }
    private int viewItems() { return events.itemCategoryCount(InstanceCategoryBit.VIEW_MODEL); }
    private int shadowParts() { return events.entityCategoryCount(FirstPersonCapture.SHADOW_CATEGORY); }
    private int shadowItems() { return events.itemCategoryCount(FirstPersonCapture.SHADOW_CATEGORY); }
    private boolean shadow() { return shadowParts() > 0 && !EntityCaptureHooks.normallyRendered(playerId); }
    private boolean arenaReady() {
        if (events.terrainPendingSectionCount() != 0 || events.terrainRetirementCount() != 0) return false;
        for (int dx = -8; dx <= 8; dx++) for (int dz = 0; dz <= 18; dz++) {
            arenaProbe.setPos(layout.x + dx, layout.y, layout.z + dz);
            if (mc.world.getBlockState(arenaProbe).getBlock() != (((dx + dz) & 1) == 0 ? Blocks.QUARTZ_BLOCK : Blocks.STONE))
                return false;
        }
        for (int x = (layout.x - 8) >> 4; x <= (layout.x + 8) >> 4; x++)
            for (int z = layout.z >> 4; z <= (layout.z + 18) >> 4; z++)
                if (!events.terrainSectionReady(x, layout.y >> 4, z)) return false;
        for (int dx = -6; dx <= 6; dx += 3) for (int dy = 1; dy <= 4; dy++) {
            arenaProbe.setPos(layout.x + dx, layout.y + dy, layout.z + 1);
            if (mc.world.getBlockState(arenaProbe).getBlock() != Blocks.QUARTZ_BLOCK
                    || !events.terrainSectionReady(arenaProbe.getX() >> 4, arenaProbe.getY() >> 4, arenaProbe.getZ() >> 4))
                return false;
        }
        return true;
    }
    private Future<?> setItem(Item item) { return server.callFromMainThread(() -> { requireWorld(); equip(item); return null; }); }
    private void equip(Item item) {
        EntityPlayerMP player = player(); player.resetActiveHand(); player.inventory.currentItem = selectedSlot;
        player.inventory.setInventorySlotContents(selectedSlot, item == null ? ItemStack.EMPTY : new ItemStack(item));
        player.setItemStackToSlot(EntityEquipmentSlot.OFFHAND, ItemStack.EMPTY);
        player.inventory.markDirty(); player.inventoryContainer.detectAndSendChanges();
    }
    private void holdPose() {
        double x = layout.x + 0.5, y = layout.y + 1, z = layout.z + 11.5;
        mc.player.setPositionAndRotation(x, y, z, 180, 12);
        mc.player.prevPosX = mc.player.lastTickPosX = x; mc.player.prevPosY = mc.player.lastTickPosY = y;
        mc.player.prevPosZ = mc.player.lastTickPosZ = z;
        mc.player.prevRotationYaw = 180; mc.player.prevRotationPitch = 12;
        mc.player.motionX = mc.player.motionY = mc.player.motionZ = 0; mc.player.capabilities.isFlying = true;
        mc.player.inventory.currentItem = selectedSlot;
    }
    private EntityPlayerMP player() {
        EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(playerId);
        if (player == null) throw new IllegalStateException("First-person fixture player unavailable");
        return player;
    }
    private void requireWorld() {
        if (!server.isCallingFromMinecraftThread() || !save.equals(server.getFolderName()) || server.getWorld(0) != world)
            throw new IllegalStateException("First-person fixture no longer owns server world");
    }
    private boolean complete() throws Exception { if (!action.isDone()) return false; action.get(); return true; }
    private boolean settled(boolean ready, int frames) {
        if (!ready) { frame = -1; return false; }
        if (frame < 0) frame = events.renderedFrames();
        return events.renderedFrames() - frame >= frames;
    }
    private void transition(Phase next) { phase = next; since = progress = System.nanoTime(); frame = -1; log("PHASE"); }
    private void capture(String name) throws Exception { output.capture(name, output.image()); }
    private boolean captureSword() throws Exception {
        BufferedImage image = output.image(); output.capture("firstperson-sword", image);
        FixtureFirstPersonImage.Result proof = FixtureFirstPersonImage.sword(emptyImage, image,
                new ScaledResolution(mc).getScaleFactor());
        output.log("FIRSTPERSON_SWORD_PIXELS", proof.describe() + " " + status());
        if (!proof.visible()) {
            swordFailure = "First-person diamond sword is not visibly present: " + proof.describe();
            if (!Boolean.getBoolean("rlcraftremix.viewModelDiagnostics")) throw new IllegalStateException(swordFailure);
            // A failed native image can be compared with the identical vanilla pose, without
            // accepting an invisible blade or changing the fixture's pass condition.
            transition(Phase.VANILLA_SWORD);
            events.setEnabled(false);
            return false;
        }
        emptyImage = null;
        return true;
    }
    private void log(String name) { output.log("FIRSTPERSON_" + name, status()); }
    String status() {
        return "phase=" + phase + " hands=" + hands() + " held=" + viewItems() + " shadow=" + shadowParts()
                + " equipped=" + equipProgress(EQUIPPED) + "/" + equipProgress(PREVIOUS_EQUIPPED)
                + " arena=" + layout.x + "," + layout.y + "," + layout.z
                + " shadowHeld=" + shadowItems() + " normal=" + EntityCaptureHooks.normallyRendered(playerId)
                + " active=" + mc.player.isHandActive() + " useTicks=" + mc.player.getItemInUseMaxCount()
                + " useKey=" + mc.gameSettings.keyBindUseItem.isKeyDown()
                + " damageQuads=" + events.damageQuads()
                + " pendingSections=" + events.terrainPendingSectionCount() + " retirement=" + events.terrainRetirementCount()
                + " itemUpdates=" + events.itemTransformUpdates(playerId) + " frames=" + events.renderedFrames();
    }
    private void restoreClient() {
        if (mc.getIntegratedServer() == server && mc.world != null)
            mc.renderGlobal.sendBlockBreakProgress(BREAKER_ID, damageBlock, -1);
        if (ownedScreen != null && mc.currentScreen == ownedScreen && mc.player != null) mc.player.closeScreen();
        ownedScreen = null;
        mc.gameSettings.thirdPersonView = originalView; mc.gameSettings.hideGUI = originalHud; mc.gameSettings.fovSetting = originalFov;
        if (mc.player != null && playerId.equals(mc.player.getUniqueID())) {
            mc.player.resetActiveHand(); mc.player.capabilities.isFlying = originalFlying;
        }
        KeyBinding.setKeyBindState(useKey, originalUseDown);
    }
    private Future<?> restoreServer() {
        return server.callFromMainThread(() -> {
            requireWorld(); EntityPlayerMP player = player(); player.resetActiveHand();
            if (originalMain != null) {
                player.inventory.setInventorySlotContents(selectedSlot, originalMain);
                player.setItemStackToSlot(EntityEquipmentSlot.OFFHAND, originalOff);
                player.inventory.markDirty(); player.inventoryContainer.detectAndSendChanges();
                originalMain = originalOff = null;
            }
            player.connection.setPlayerLocation(returnX, returnY, returnZ, returnYaw, returnPitch);
            return null;
        });
    }
    @Override public void close() {
        emptyImage = null;
        try {
            if (phase == Phase.VANILLA_SWORD && !events.isEnabled() && events.failureMessage() == null) events.setEnabled(true);
        } finally {
            restoreClient();
            if (!cleanupQueued) { cleanupQueued = true; restoreServer(); }
        }
    }
    static final class Layout {
        final int x, y, z;
        Layout(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
    }
}
