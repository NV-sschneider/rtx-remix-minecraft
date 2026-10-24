/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import java.lang.reflect.InvocationTargetException;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.passive.EntityTameable;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

/** Public mod interfaces used by the opt-in RLCraft fixture; no mod is a compile dependency. */
final class FixtureCompatibilityEntities {
    static final String[] NAMES = {
        "iceandfire:firedragon", "iceandfire:icedragon", "lycanitesmobs:makaalpha", "lycanitesmobs:rahovart"
    };
    private FixtureCompatibilityEntities() { }

    static EntityLiving create(WorldServer world, int index, double x, double y, double z) throws Exception {
        Entity created = EntityList.createEntityByIDFromName(new ResourceLocation(NAMES[index]), world);
        if (!(created instanceof EntityLiving)) throw new IllegalStateException("Missing RLCraft fixture entity: " + NAMES[index]);
        EntityLiving entity = (EntityLiving) created;
        entity.setPositionAndRotation(x, y, z, 0, 0);
        entity.setNoAI(true); entity.enablePersistence(); entity.setNoGravity(true);
        entity.setSilent(true); entity.setEntityInvulnerable(true); entity.setAttackTarget(null);
        if (index < 2) {
            invoke(entity, "setAgeInDays", int.class, 75);
            invoke(entity, "setAgingDisabled", boolean.class, true);
            invoke(entity, "setHunger", int.class, 100);
            invoke(entity, "setVariant", int.class, index);
            invoke(entity, "setCommand", int.class, 1);
            ((EntityTameable) entity).setSitting(true);
            invoke(entity, "setSleeping", boolean.class, false);
            invoke(entity, "setFlying", boolean.class, false);
            invoke(entity, "setHovering", boolean.class, false);
            invoke(entity, "setBreathingFire", boolean.class, false);
        } else {
            // Complete the mod's public spawn initialization before pinning deterministic appearance.
            invoke(entity, "onFirstSpawn");
            invoke(entity, "applyLevel", int.class, 1);
            invoke(entity, "setSubspecies", int.class, 0);
            invoke(entity, "setVariant", int.class, 0);
            invoke(entity, "setSizeScale", double.class, 1.0);
            if (index == 3) invoke(entity, "setArenaCenter", BlockPos.class, new BlockPos(x, y, z));
        }
        entity.setHealth(entity.getMaxHealth());
        return entity;
    }

    static void animate(Entity entity) throws Exception {
        Object animation = entity.getClass().getField("ANIMATION_SPEAK").get(null);
        Class<?> handler = Class.forName("net.ilexiconn.llibrary.server.animation.AnimationHandler");
        call(handler.getMethod("sendAnimationMessage", Entity.class, animation.getClass()),
                handler.getField("INSTANCE").get(null), entity, animation);
    }

    static boolean speaking(Entity entity) throws Exception {
        return entity != null && invoke(entity, "getAnimation") == entity.getClass().getField("ANIMATION_SPEAK").get(null)
                && ((Number) invoke(entity, "getAnimationTick")).intValue() > 0;
    }

    static void move(Entity entity, int index, double x, double y, double z) throws Exception {
        if (index == 3) invoke(entity, "setArenaCenter", BlockPos.class, new BlockPos(x, y, z));
        entity.setPositionAndRotation(x, y, z, 35, 0);
        ((EntityLiving) entity).renderYawOffset = ((EntityLiving) entity).rotationYawHead = 35;
    }

    private static Object invoke(Object object, String name) throws Exception {
        return call(object.getClass().getMethod(name), object);
    }
    private static Object invoke(Object object, String name, Class<?> type, Object value) throws Exception {
        return call(object.getClass().getMethod(name, type), object, value);
    }
    private static Object call(java.lang.reflect.Method method, Object receiver, Object... arguments) throws Exception {
        try { return method.invoke(receiver, arguments); }
        catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw error;
        }
    }
}
