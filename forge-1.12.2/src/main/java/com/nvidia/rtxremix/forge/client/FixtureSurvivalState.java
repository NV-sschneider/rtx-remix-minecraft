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
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.potion.PotionEffect;
import net.minecraft.world.GameType;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.INBTSerializable;
import net.minecraftforge.fml.common.Loader;

/** Owned-player snapshot; First Aid restoration completes before the original game mode returns. */
final class FixtureSurvivalState {
    final GameType gameType;
    final float health, absorption;
    final int selectedSlot;
    private final int hurtTime, maxHurtTime, hurtResistance;
    private final NBTTagList inventory;
    private final NBTTagCompound food = new NBTTagCompound(), abilities = new NBTTagCompound();
    private final List<PotionEffect> effects = new ArrayList<>();
    private final FirstAid firstAid;
    private int restoreTick;

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
    void beginRestore(EntityPlayerMP player) throws ReflectiveOperationException {
        // First Aid only ticks in Survival/Adventure. Protect the owned player while
        // its normal ticks recompute vanilla health and deliver the three-tick resync.
        player.setGameType(GameType.SURVIVAL);
        player.capabilities.disableDamage = true;
        player.capabilities.allowFlying = player.capabilities.isFlying = true;
        player.sendPlayerAbilities();
        player.resetActiveHand(); player.inventory.readFromNBT(inventory); player.inventory.currentItem = selectedSlot;
        player.clearActivePotions(); for (PotionEffect effect : effects) player.addPotionEffect(new PotionEffect(effect));
        player.setHealth(health); player.getFoodStats().readNBT(food);
        if (firstAid == null) player.setAbsorptionAmount(absorption);
        player.hurtTime = hurtTime; player.maxHurtTime = maxHurtTime; player.hurtResistantTime = hurtResistance;
        restoreTick = player.ticksExisted;
        try { if (firstAid != null) firstAid.restore(() -> player.setAbsorptionAmount(0)); }
        finally { syncInventory(player); player.setPlayerHealthUpdated(); }
    }
    void finishRestore(EntityPlayerMP player) {
        player.setGameType(gameType); player.capabilities.readCapabilitiesFromNBT(abilities); player.sendPlayerAbilities();
        player.setPlayerHealthUpdated();
    }

    /** Called only on the server thread; the returned values are safe to publish through a Future. */
    Verification verify(EntityPlayerMP player) throws ReflectiveOperationException {
        int elapsed = Math.max(0, player.ticksExisted - restoreTick);
        boolean body = bodyMatches(player, elapsed) && (firstAid == null || elapsed >= 4);
        return new Verification(body, body && player.interactionManager.getGameType() == gameType && abilitiesMatch(player),
                player.getHealth(), damageHealth(player), elapsed);
    }
    boolean clientMatches(EntityPlayer player, GameType mode, Verification server, boolean finalMode)
            throws ReflectiveOperationException {
        return clientMatches(player, mode, server, finalMode, 0);
    }
    boolean clientMatches(EntityPlayer player, GameType mode, Verification server, boolean finalMode, int elapsedAfterCheck)
            throws ReflectiveOperationException {
        return server != null && server.body && bodyMatches(player, server.elapsed + Math.max(0, elapsedAfterCheck))
                && (!finalMode || mode == gameType && abilitiesMatch(player));
    }
    private boolean bodyMatches(EntityPlayer player, int elapsed) throws ReflectiveOperationException {
        return near(player.getHealth(), health) && near(player.getAbsorptionAmount(), absorption)
                && player.getFoodStats().getFoodLevel() == food.getInteger("foodLevel")
                && near(player.getFoodStats().getSaturationLevel(), food.getFloat("foodSaturationLevel"))
                && player.inventory.currentItem == selectedSlot
                && inventory.equals(player.inventory.writeToNBT(new NBTTagList()))
                && effectsMatch(player, elapsed)
                && (firstAid == null || firstAid.matches(FirstAid.model(player)));
    }
    private boolean abilitiesMatch(EntityPlayer player) {
        PlayerCapabilities expected = new PlayerCapabilities(); expected.readCapabilitiesFromNBT(abilities);
        PlayerCapabilities actual = player.capabilities;
        return actual.disableDamage == expected.disableDamage && actual.isFlying == expected.isFlying
                && actual.allowFlying == expected.allowFlying && actual.isCreativeMode == expected.isCreativeMode
                && actual.allowEdit == expected.allowEdit && near(actual.getFlySpeed(), expected.getFlySpeed())
                && near(actual.getWalkSpeed(), expected.getWalkSpeed());
    }
    private boolean effectsMatch(EntityPlayer player, int elapsed) {
        int remaining = 0;
        for (PotionEffect expected : effects) {
            PotionEffect actual = player.getActivePotionEffect(expected.getPotion());
            if (expected.getDuration() <= elapsed && actual == null) continue;
            remaining++;
            if (actual == null || actual.getAmplifier() != expected.getAmplifier()
                    || actual.getIsAmbient() != expected.getIsAmbient() || actual.doesShowParticles() != expected.doesShowParticles()
                    || actual.getDuration() > expected.getDuration() + 2
                    || actual.getDuration() < Math.max(0, expected.getDuration() - elapsed - 2)) return false;
        }
        return player.getActivePotionEffects().size() == remaining;
    }
    String expectedStatus() { return "savedHealth=" + health + " savedLimbHealth=" + (firstAid == null ? health : firstAid.health)
            + " savedFood=" + food.getInteger("foodLevel"); }
    private static boolean near(float value, float expected) { return Math.abs(value - expected) <= 0.0001f; }
    static final class Verification {
        final boolean body, complete;
        final float health, limbHealth;
        final int elapsed;
        Verification(boolean body, boolean complete, float health, float limbHealth, int elapsed) {
            this.body = body; this.complete = complete; this.health = health; this.limbHealth = limbHealth; this.elapsed = elapsed;
        }
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
        private final List<PartState> parts = new ArrayList<>();
        final float health;
        FirstAid(INBTSerializable<NBTTagCompound> model, Method resync) throws ReflectiveOperationException {
            float total = 0;
            for (Object part : (Iterable<?>) model) {
                // This cohort owns a fresh smoke player, not an in-progress bandage timer.
                if (part.getClass().getField("activeHealer").get(part) != null)
                    throw new IllegalStateException("Survival smoke cannot preserve an active First Aid healer timer");
                PartState state = new PartState(part); parts.add(state); total += state.health;
            }
            health = total;
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
        void restore(Runnable clearAbsorption) throws ReflectiveOperationException {
            // The server player's public setter sends First Aid's zero-absorption
            // packet before the delayed full sync. Its client NBT reader also
            // retains omitted zero fields, so server-only normalization is insufficient.
            clearAbsorption.run();
            // First Aid omits zero absorption/null healers from NBT and does not
            // clear those fields when reading an absent key. Reset their public
            // state before loading the saved distribution, not a redistributed total.
            for (Object part : (Iterable<?>) model) {
                part.getClass().getMethod("setAbsorption", float.class).invoke(part, 0f);
                part.getClass().getField("activeHealer").set(part, null);
            }
            model.deserializeNBT(saved.copy()); resync.invoke(model);
        }
        boolean matches(Object value) throws ReflectiveOperationException {
            if (!(value instanceof Iterable)) return false;
            int index = 0;
            for (Object part : (Iterable<?>) value) {
                if (index == parts.size() || !parts.get(index++).matches(part)) return false;
            }
            return index == parts.size();
        }
        private static final class PartState {
            final float health, absorption;
            final int maximum;
            PartState(Object part) throws ReflectiveOperationException {
                health = part.getClass().getField("currentHealth").getFloat(part);
                absorption = ((Number) part.getClass().getMethod("getAbsorption").invoke(part)).floatValue();
                maximum = ((Number) part.getClass().getMethod("getMaxHealth").invoke(part)).intValue();
            }
            boolean matches(Object part) throws ReflectiveOperationException {
                return near(health, part.getClass().getField("currentHealth").getFloat(part))
                        && near(absorption, ((Number) part.getClass().getMethod("getAbsorption").invoke(part)).floatValue())
                        && maximum == ((Number) part.getClass().getMethod("getMaxHealth").invoke(part)).intValue()
                        && part.getClass().getField("activeHealer").get(part) == null;
            }
        }
    }
}
