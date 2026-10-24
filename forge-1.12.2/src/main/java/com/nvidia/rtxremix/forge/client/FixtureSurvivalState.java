/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.potion.PotionEffect;
import net.minecraft.world.GameType;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.INBTSerializable;
import net.minecraftforge.fml.common.Loader;

/** Exact owned-player restoration, including First Aid's independently stored limb health when installed. */
final class FixtureSurvivalState {
    final GameType gameType;
    final float health, absorption;
    final int selectedSlot;
    private final int hurtTime, maxHurtTime, hurtResistance;
    private final NBTTagList inventory;
    private final NBTTagCompound food = new NBTTagCompound(), abilities = new NBTTagCompound();
    private final List<PotionEffect> effects = new ArrayList<>();
    private final FirstAid firstAid;

    FixtureSurvivalState(EntityPlayerMP player) throws ReflectiveOperationException {
        gameType = player.interactionManager.getGameType(); health = player.getHealth(); absorption = player.getAbsorptionAmount();
        selectedSlot = player.inventory.currentItem; inventory = player.inventory.writeToNBT(new NBTTagList());
        hurtTime = player.hurtTime; maxHurtTime = player.maxHurtTime; hurtResistance = player.hurtResistantTime;
        player.getFoodStats().writeNBT(food); player.capabilities.writeCapabilitiesToNBT(abilities);
        for (PotionEffect effect : player.getActivePotionEffects()) effects.add(new PotionEffect(effect));
        firstAid = FirstAid.capture(player);
    }

    void prepare(EntityPlayerMP player) throws ReflectiveOperationException {
        player.resetActiveHand(); player.inventory.clear(); player.inventory.currentItem = selectedSlot;
        player.clearActivePotions(); player.setGameType(GameType.SURVIVAL);
        player.capabilities.isFlying = false; player.capabilities.disableDamage = false; player.sendPlayerAbilities();
        player.setAbsorptionAmount(0); player.setHealth(player.getMaxHealth());
        player.hurtTime = player.hurtResistantTime = 0;
        player.getFoodStats().setFoodLevel(8); player.getFoodStats().setFoodSaturationLevel(0);
        if (firstAid != null) firstAid.prepare();
        syncInventory(player); player.setPlayerHealthUpdated();
    }
    void restore(EntityPlayerMP player) throws ReflectiveOperationException {
        player.resetActiveHand(); player.inventory.readFromNBT(inventory); player.inventory.currentItem = selectedSlot;
        player.clearActivePotions(); for (PotionEffect effect : effects) player.addPotionEffect(new PotionEffect(effect));
        player.setGameType(gameType); player.capabilities.readCapabilitiesFromNBT(abilities); player.sendPlayerAbilities();
        player.setHealth(health); player.setAbsorptionAmount(absorption); player.getFoodStats().readNBT(food);
        player.hurtTime = hurtTime; player.maxHurtTime = maxHurtTime; player.hurtResistantTime = hurtResistance;
        try { if (firstAid != null) firstAid.restore(); }
        finally { syncInventory(player); player.setPlayerHealthUpdated(); }
    }
    static void syncInventory(EntityPlayerMP player) {
        player.inventory.markDirty(); player.inventoryContainer.detectAndSendChanges();
        player.sendContainerToPlayer(player.inventoryContainer);
    }
    static float damageHealth(EntityPlayer player) throws ReflectiveOperationException {
        Object model = FirstAid.model(player);
        if (model == null) return player.getHealth();
        float health = 0;
        for (Object part : (Iterable<?>) model) health += part.getClass().getField("currentHealth").getFloat(part);
        return health;
    }

    /** Public capability/NBT and resync APIs only; no dependency on First Aid implementation internals. */
    static final class FirstAid {
        private final INBTSerializable<NBTTagCompound> model;
        private final NBTTagCompound saved;
        private final Method resync;
        FirstAid(INBTSerializable<NBTTagCompound> model, Method resync) {
            this.model = model; this.resync = resync; saved = model.serializeNBT().copy();
        }
        @SuppressWarnings("unchecked")
        static FirstAid capture(EntityPlayer player) throws ReflectiveOperationException {
            Object model = model(player);
            return model == null ? null : new FirstAid((INBTSerializable<NBTTagCompound>) model,
                    model.getClass().getMethod("scheduleResync"));
        }
        static Object model(EntityPlayer player) throws ReflectiveOperationException {
            if (!Loader.isModLoaded("firstaid")) return null;
            Field field = Class.forName("ichttt.mods.firstaid.api.CapabilityExtendedHealthSystem").getField("INSTANCE");
            Capability<?> capability = (Capability<?>) field.get(null);
            Object model = capability == null ? null : player.getCapability(capability, null);
            if (!(model instanceof INBTSerializable) || !(model instanceof Iterable)) {
                throw new IllegalStateException("First Aid did not expose the fixture player's damage-model capability");
            }
            return model;
        }
        void prepare() throws ReflectiveOperationException {
            for (Object part : (Iterable<?>) model) {
                int maximum = ((Number) part.getClass().getMethod("getMaxHealth").invoke(part)).intValue();
                part.getClass().getField("currentHealth").setFloat(part, maximum);
            }
            model.getClass().getMethod("setAbsorption", float.class).invoke(model, 0f);
            resync.invoke(model);
        }
        void restore() throws ReflectiveOperationException { model.deserializeNBT(saved.copy()); resync.invoke(model); }
    }
}
