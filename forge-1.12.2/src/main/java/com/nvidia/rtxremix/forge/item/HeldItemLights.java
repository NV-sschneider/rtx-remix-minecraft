/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.item;

import com.nvidia.rtxremix.InstanceCategoryBit;
import com.nvidia.rtxremix.forge.light.HeldLightState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.EnumHandSide;

/** Bounded CPU records from successfully observed held-item model draws; no native work occurs here. */
public final class HeldItemLights {
    public static final int MAX_SAMPLES = 64;
    private static final List<Sample> SAMPLES = new ArrayList<>(MAX_SAMPLES);
    private static final List<Sample> VIEW = Collections.unmodifiableList(SAMPLES);
    private static final Sample[] POOL = new Sample[MAX_SAMPLES];
    private static Object sideOwner;
    private static int sideDepth, sideHand;
    static { for (int i = 0; i < POOL.length; i++) POOL[i] = new Sample(); }
    private HeldItemLights() { }

    public static void reset() {
        for (int i = 0; i < SAMPLES.size(); i++) { Sample sample = SAMPLES.get(i); sample.owner = null; sample.id = null; }
        SAMPLES.clear();
        sideOwner = null; sideDepth = 0;
    }
    public static List<Sample> samples() { return VIEW; }
    public static int checkpoint() { return SAMPLES.size(); }
    public static void truncate(int size) {
        for (int i = SAMPLES.size() - 1; i >= size; i--) { Sample sample = SAMPLES.remove(i); sample.owner = null; sample.id = null; }
        sideOwner = null; sideDepth = 0;
    }
    public static void beginSide(Object holder, boolean left) {
        if (++sideDepth != 1) return;
        if (!(holder instanceof EntityLivingBase)) return;
        sideOwner = holder;
        sideHand = hand(((EntityLivingBase) holder).getPrimaryHand() == EnumHandSide.LEFT, left);
    }
    public static void endSide() { if (sideDepth > 0 && --sideDepth == 0) sideOwner = null; }
    static int hand(boolean primaryLeft, boolean renderedLeft) { return primaryLeft == renderedLeft ? 0 : 1; }

    /** Call after adding the successful item occurrence, while its mutable frame snapshot is live. */
    public static void record(EntityLivingBase owner, ItemStack stack, int category, int occurrence, float[] transform) {
        int limit = category == InstanceCategoryBit.VIEW_MODEL ? MAX_SAMPLES : MAX_SAMPLES - 4;
        if (SAMPLES.size() >= limit || sideDepth != 1 || sideOwner != owner || !HeldLightState.isLit(owner, stack)) return;
        IBlockState state = HeldLightState.blockState(stack);
        if (state == null) return;
        Sample sample = POOL[SAMPLES.size()];
        sample.owner = owner; sample.id = owner.getUniqueID(); sample.hand = sideHand;
        sample.category = category; sample.occurrence = occurrence;
        sample.localFirstPerson = owner == Minecraft.getMinecraft().player && Minecraft.getMinecraft().gameSettings.thirdPersonView == 0;
        sample.yaw = owner.rotationYaw;
        float height = HeldLightState.openFlame(state.getBlock()) ? 0.72f : 0.5f;
        sample.x = transform[0] * 0.5f + transform[1] * height + transform[2] * 0.5f + transform[3];
        sample.y = transform[4] * 0.5f + transform[5] * height + transform[6] * 0.5f + transform[7];
        sample.z = transform[8] * 0.5f + transform[9] * height + transform[10] * 0.5f + transform[11];
        float level = Math.min(15, Math.max(0, state.getLightValue())) / 15.0f;
        boolean flame = HeldLightState.openFlame(state.getBlock());
        sample.radius = flame ? 0.10f : 0.20f;
        float power = (flame ? 90 : 12) * level * level;
        sample.red = power; sample.green = power * 0.65f; sample.blue = power * 0.28f;
        ResourceLocation registry = state.getBlock().getRegistryName();
        String name = registry == null ? "" : registry.getPath();
        if (name.contains("redstone")) { sample.green = power * 0.08f; sample.blue = power * 0.02f; }
        else if (name.contains("sea_lantern") || name.contains("end_rod")) {
            sample.red = power * 0.65f; sample.green = power * 0.85f; sample.blue = power;
        }
        SAMPLES.add(sample);
    }

    public static final class Sample {
        public Object owner;
        public UUID id;
        public int hand, category, occurrence;
        public boolean localFirstPerson;
        public float x, y, z, yaw, radius, red, green, blue;
    }
}
