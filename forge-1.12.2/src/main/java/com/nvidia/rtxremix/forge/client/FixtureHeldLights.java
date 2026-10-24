/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import com.nvidia.rtxremix.InstanceCategoryBit;
import com.nvidia.rtxremix.forge.light.HeldLightState;
import com.nvidia.rtxremix.forge.entity.FirstPersonCapture;
import com.nvidia.rtxremix.forge.item.HeldItemLights;
import java.lang.reflect.Field;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

/** An owned dark chamber exercises native held lights, both hands, water, collisions and retirement. */
final class FixtureHeldLights implements AutoCloseable {
    private static final Field DRAWN_MAIN = ReflectionHelper.findField(ItemRenderer.class, new String[] {"itemStackMainHand", "field_187467_d"});
    private static final Field DRAWN_OFF = ReflectionHelper.findField(ItemRenderer.class, new String[] {"itemStackOffHand", "field_187468_e"});
    private enum Phase { ARRIVE, LIT, DUAL_ACTION, DUAL, OFF, ON, UNLIT_ACTION, UNLIT, WATER_ACTION,
        WATER, ALLOWED_WATER, ELECTRIC_ACTION, ELECTRIC, WALL_ACTION, WALL, THIRD_PERSON, FIRST_PERSON, RELOAD, RETURN, DONE }
    private final Minecraft mc;
    private final ClientEvents events;
    private final IntegratedServer server;
    private final WorldServer world;
    private final String save;
    private final Layout layout;
    private final FixtureTerrain.Output output;
    private final UUID playerId;
    private final Item litTorch, unlitTorch, vanillaTorch = Item.getItemFromBlock(Blocks.TORCH);
    private final int selectedSlot, originalView;
    private final boolean originalHud, originalFlying, originalOff, originalUnderwater;
    private final double returnX, returnY, returnZ;
    private final float returnYaw, returnPitch;
    private double targetX, targetY, targetZ;
    private ItemStack savedMain, savedOff;
    private Phase phase = Phase.ARRIVE;
    private Future<?> action;
    private long since = System.nanoTime(), progress = since, frame = -1, reloadBaseline;
    private boolean cleanupQueued;

    FixtureHeldLights(Minecraft mc, ClientEvents events, IntegratedServer server, WorldServer world,
            String save, Layout layout, double x, double y, double z, float yaw, float pitch,
            FixtureTerrain.Output output) {
        this.mc = mc; this.events = events; this.server = server; this.world = world; this.save = save;
        this.layout = layout; this.output = output; playerId = mc.player.getUniqueID();
        selectedSlot = mc.player.inventory.currentItem; originalView = mc.gameSettings.thirdPersonView;
        originalHud = mc.gameSettings.hideGUI; originalFlying = mc.player.capabilities.isFlying;
        originalOff = HeldLightState.localLightsOff(); originalUnderwater = HeldLightState.flamesAllowedUnderwater();
        returnX = x; returnY = y; returnZ = z; returnYaw = yaw; returnPitch = pitch;
        litTorch = registered("realistictorches:torch_lit", vanillaTorch);
        unlitTorch = registered("realistictorches:torch_unlit", Items.STICK);
        mc.gameSettings.thirdPersonView = 0; mc.gameSettings.hideGUI = false;
        if (originalOff) HeldLightState.toggleLocalHeldLights();
        HeldLightState.setFlamesAllowedUnderwater(false);
        targetX = layout.x + 0.5; targetY = layout.y + 1; targetZ = layout.z + 11.5;
        action = server.callFromMainThread(() -> {
            requireWorld(); EntityPlayerMP player = player();
            savedMain = player.inventory.getStackInSlot(selectedSlot).copy(); savedOff = player.getHeldItemOffhand().copy();
            equip(litTorch, null); teleport(); return null;
        });
        log("STARTED");
    }

    boolean tick() throws Exception {
        if (mc.getIntegratedServer() != server || mc.world == null || mc.player == null || !events.isEnabled()
                || events.failureMessage() != null) throw new IllegalStateException("Held light fixture renderer/world unavailable");
        if (System.nanoTime() - since > TimeUnit.SECONDS.toNanos(180)) {
            capture("held-light-timeout-" + phase.name().toLowerCase(Locale.ROOT));
            throw new IllegalStateException("Held light fixture timed out: " + phase);
        }
        if (System.nanoTime() - progress > TimeUnit.SECONDS.toNanos(10)) { log("PROGRESS"); progress = System.nanoTime(); }
        if (phase != Phase.RETURN && phase != Phase.DONE) holdPose();
        switch (phase) {
            case ARRIVE:
                if (complete()) transition(Phase.LIT);
                break;
            case LIT:
                if (settled(holding(litTorch) && lights(1, 1) && viewItems() > 0, 45)) {
                    capture("held-light-lit"); log("LIT_PASS"); action = change(litTorch, litTorch); transition(Phase.DUAL_ACTION);
                }
                break;
            case DUAL_ACTION:
                if (complete()) transition(Phase.DUAL);
                break;
            case DUAL:
                if (settled(lights(2, 2) && events.heldLightCaptured(playerId, 1) && viewItems() >= 2, 45)) {
                    capture("held-light-both-hands"); log("DEDUPLICATION_PASS");
                    HeldLightState.toggleLocalHeldLights(); transition(Phase.OFF);
                }
                break;
            case OFF:
                if (settled(events.heldLightCount() == 0 && viewItems() >= 2, 45)) {
                    capture("held-light-off"); log("TOGGLE_OFF_PASS"); HeldLightState.toggleLocalHeldLights(); transition(Phase.ON);
                }
                break;
            case ON:
                if (settled(lights(2, 2), 30)) {
                    log("TOGGLE_ON_PASS"); action = change(unlitTorch, null); transition(Phase.UNLIT_ACTION);
                }
                break;
            case UNLIT_ACTION:
                if (complete()) transition(Phase.UNLIT);
                break;
            case UNLIT:
                if (settled(holding(unlitTorch) && events.heldLightCount() == 0 && viewItems() > 0, 45)) {
                    capture("held-light-unlit"); log("UNLIT_ITEM_PASS");
                    targetX = layout.x + 4.5; targetZ = layout.z + 11.5;
                    // Use the vanilla torch here so the test isolates the shared water
                    // policy from mod gameplay that may replace wet torch stacks.
                    action = change(vanillaTorch, null); transition(Phase.WATER_ACTION);
                }
                break;
            case WATER_ACTION:
                if (complete()) transition(Phase.WATER);
                break;
            case WATER:
                if (settled(mc.player.isInsideOfMaterial(Material.WATER) && holding(vanillaTorch)
                        && events.heldLightCount() == 0 && viewItems() > 0, 45)) {
                    capture("held-light-underwater"); log("FLAME_WATER_PASS");
                    HeldLightState.setFlamesAllowedUnderwater(true); transition(Phase.ALLOWED_WATER);
                }
                break;
            case ALLOWED_WATER:
                if (settled(mc.player.isInsideOfMaterial(Material.WATER) && lights(1, 1), 45)) {
                    capture("held-light-water-override"); log("WATER_OVERRIDE_PASS");
                    HeldLightState.setFlamesAllowedUnderwater(false);
                    action = change(Item.getItemFromBlock(Blocks.GLOWSTONE), null); transition(Phase.ELECTRIC_ACTION);
                }
                break;
            case ELECTRIC_ACTION:
                if (complete()) transition(Phase.ELECTRIC);
                break;
            case ELECTRIC:
                if (settled(mc.player.isInsideOfMaterial(Material.WATER) && holding(Item.getItemFromBlock(Blocks.GLOWSTONE)) && lights(1, 1), 45)) {
                    capture("held-light-glowstone-water"); log("NONFLAME_WATER_PASS");
                    targetX = layout.x + 0.5; targetZ = layout.z + 1.4;
                    action = change(litTorch, null); transition(Phase.WALL_ACTION);
                }
                break;
            case WALL_ACTION:
                if (complete()) transition(Phase.WALL);
                break;
            case WALL:
                double distance = events.heldLightLocalDistance(playerId, 0);
                if (settled(lights(1, 1) && distance >= 0 && distance < 0.4, 45)) {
                    capture("held-light-wall-clearance"); log("COLLISION_PASS");
                    targetZ = layout.z + 11.5; action = change(litTorch, null);
                    mc.gameSettings.thirdPersonView = 1; transition(Phase.THIRD_PERSON);
                }
                break;
            case THIRD_PERSON:
                if (complete() && settled(lights(1, 0) && viewItems() == 0 && events.itemCaptured(playerId), 45)) {
                    capture("held-light-third-person"); log("WORLD_POSE_PASS");
                    mc.gameSettings.thirdPersonView = 0; transition(Phase.FIRST_PERSON);
                }
                break;
            case FIRST_PERSON:
                if (settled(lights(1, 1) && viewItems() > 0, 45)) {
                    reloadBaseline = events.textureReloadCount(); events.reload(); transition(Phase.RELOAD);
                }
                break;
            case RELOAD:
                if (settled(events.textureReloadCount() > reloadBaseline && lights(1, 1), 45)) {
                    capture("held-light-reloaded"); log("RELOAD_PASS");
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
        for (int dx = -7; dx <= 7; dx++) for (int dz = 0; dz <= 18; dz++) for (int dy = 0; dy <= 6; dy++) {
            boolean shell = dx == -7 || dx == 7 || dz == 0 || dz == 18 || dy == 0 || dy == 6;
            BlockPos pos = new BlockPos(x + dx, y + dy, z + dz);
            if (shell) world.setBlockState(pos, Blocks.STONE.getDefaultState(), 3); else world.setBlockToAir(pos);
        }
        for (int dx = 2; dx <= 6; dx++) for (int dz = 8; dz <= 13; dz++) for (int dy = 1; dy <= 5; dy++) {
            boolean wall = dx == 2 || dx == 6 || dz == 8 || dz == 13;
            world.setBlockState(new BlockPos(x + dx, y + dy, z + dz), wall ? Blocks.STONE.getDefaultState() : Blocks.WATER.getDefaultState(), 3);
        }
        return new Layout(x, y, z);
    }
    private static Item registered(String id, Item fallback) {
        ResourceLocation name = new ResourceLocation(id); return Item.REGISTRY.containsKey(name) ? Item.REGISTRY.getObject(name) : fallback;
    }
    private boolean holding(Item item) { return mc.player.getHeldItemMainhand().getItem() == item; }
    private int viewItems() { return events.itemCategoryCount(InstanceCategoryBit.VIEW_MODEL); }
    private boolean lights(int total, int local) {
        return events.heldLightCount() == total && events.localHeldLightCount() == local && events.heldLightCaptured(playerId, 0);
    }
    private Future<?> change(Item main, Item off) { return server.callFromMainThread(() -> { requireWorld(); equip(main, off); teleport(); return null; }); }
    private void equip(Item main, Item off) {
        EntityPlayerMP player = player(); player.resetActiveHand(); player.inventory.currentItem = selectedSlot;
        player.inventory.setInventorySlotContents(selectedSlot, main == null ? ItemStack.EMPTY : new ItemStack(main));
        player.setItemStackToSlot(EntityEquipmentSlot.OFFHAND, off == null ? ItemStack.EMPTY : new ItemStack(off));
        player.inventory.markDirty(); player.inventoryContainer.detectAndSendChanges();
    }
    private void teleport() { player().connection.setPlayerLocation(targetX, targetY, targetZ, 180, 12); }
    private void holdPose() {
        mc.player.setPositionAndRotation(targetX, targetY, targetZ, 180, 12);
        mc.player.prevPosX = mc.player.lastTickPosX = targetX; mc.player.prevPosY = mc.player.lastTickPosY = targetY;
        mc.player.prevPosZ = mc.player.lastTickPosZ = targetZ; mc.player.prevRotationYaw = 180; mc.player.prevRotationPitch = 12;
        mc.player.motionX = mc.player.motionY = mc.player.motionZ = 0; mc.player.capabilities.isFlying = true;
        mc.player.inventory.currentItem = selectedSlot;
    }
    private EntityPlayerMP player() {
        EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(playerId);
        if (player == null) throw new IllegalStateException("Held light fixture player unavailable"); return player;
    }
    private void requireWorld() {
        if (!server.isCallingFromMinecraftThread() || !save.equals(server.getFolderName()) || server.getWorld(0) != world)
            throw new IllegalStateException("Held light fixture no longer owns server world");
    }
    private boolean complete() throws Exception { if (!action.isDone()) return false; action.get(); return true; }
    private boolean settled(boolean ready, int frames) {
        if (!ready) { frame = -1; return false; }
        if (frame < 0) frame = events.renderedFrames(); return events.renderedFrames() - frame >= frames;
    }
    private void transition(Phase next) { phase = next; since = progress = System.nanoTime(); frame = -1; log("PHASE"); }
    private void capture(String name) throws Exception { output.capture(name, output.image()); }
    private void log(String name) { output.log("HELDLIGHT_" + name, status()); }
    String status() {
        return "phase=" + phase + " lights=" + events.heldLightCount() + " local=" + events.localHeldLightCount()
                + " viewItems=" + viewItems() + " localDistance=" + events.heldLightLocalDistance(playerId, 0)
                + " updates=" + events.heldLightUpdates() + " litItem=" + litTorch.getRegistryName()
                + " unlitItem=" + unlitTorch.getRegistryName() + " frames=" + events.renderedFrames() + hands();
    }
    private String hands() {
        if (mc.player == null) return " player=unavailable";
        return " main=" + itemName(mc.player.getHeldItemMainhand()) + " off=" + itemName(mc.player.getHeldItemOffhand())
                + " drawnMain=" + drawn(DRAWN_MAIN) + " drawnOff=" + drawn(DRAWN_OFF)
                + " active=" + mc.player.isHandActive() + " useKey=" + mc.gameSettings.keyBindUseItem.isKeyDown()
                + " inventoryContainer=" + (mc.player.openContainer == mc.player.inventoryContainer)
                + " shadowItems=" + events.itemCategoryCount(FirstPersonCapture.SHADOW_CATEGORY) + " samples=" + samples();
    }
    private String drawn(Field field) {
        try { return itemName((ItemStack) field.get(mc.getItemRenderer())); }
        catch (IllegalAccessException error) { throw new IllegalStateException("Cannot inspect fixture hand stack", error); }
    }
    private static String itemName(ItemStack stack) { return stack.isEmpty() ? "empty" : String.valueOf(stack.getItem().getRegistryName()); }
    private String samples() {
        StringBuilder result = new StringBuilder();
        for (HeldItemLights.Sample sample : HeldItemLights.samples()) if (sample.owner == mc.player) {
            if (result.length() != 0) result.append(',');
            result.append(sample.hand).append('/').append(sample.category).append('/').append(sample.occurrence);
        }
        return result.toString();
    }
    private void restoreClient() {
        mc.gameSettings.thirdPersonView = originalView; mc.gameSettings.hideGUI = originalHud;
        if (HeldLightState.localLightsOff() != originalOff) HeldLightState.toggleLocalHeldLights();
        HeldLightState.setFlamesAllowedUnderwater(originalUnderwater);
        if (mc.player != null && playerId.equals(mc.player.getUniqueID())) { mc.player.resetActiveHand(); mc.player.capabilities.isFlying = originalFlying; }
    }
    private Future<?> restoreServer() {
        return server.callFromMainThread(() -> {
            requireWorld(); EntityPlayerMP player = player(); player.resetActiveHand();
            if (savedMain != null) {
                player.inventory.setInventorySlotContents(selectedSlot, savedMain); player.setItemStackToSlot(EntityEquipmentSlot.OFFHAND, savedOff);
                player.inventory.markDirty(); player.inventoryContainer.detectAndSendChanges(); savedMain = savedOff = null;
            }
            player.connection.setPlayerLocation(returnX, returnY, returnZ, returnYaw, returnPitch); return null;
        });
    }
    @Override public void close() { restoreClient(); if (!cleanupQueued) { cleanupQueued = true; restoreServer(); } }
    static final class Layout { final int x, y, z; Layout(int x, int y, int z) { this.x = x; this.y = y; this.z = z; } }
}
