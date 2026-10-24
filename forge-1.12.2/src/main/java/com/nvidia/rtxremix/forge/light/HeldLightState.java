/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.light;

import net.minecraft.block.Block;
import net.minecraft.block.BlockRedstoneTorch;
import net.minecraft.block.BlockTorch;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;

/** One burning decision for the held analytic light and future flame/smoke emitters. */
public final class HeldLightState {
    private static boolean localLightsOff, flamesUnderwater;
    private HeldLightState() { }

    public static boolean isLit(EntityLivingBase holder, ItemStack stack) {
        IBlockState state = blockState(stack);
        return state != null && state.getLightValue() > 0 && isLit(holder == Minecraft.getMinecraft().player,
                localLightsOff, openFlame(state.getBlock()), holder.isInsideOfMaterial(Material.WATER), flamesUnderwater);
    }
    static boolean isLit(boolean local, boolean off, boolean flame, boolean submerged, boolean allowUnderwater) {
        return !(local && off) && !(flame && submerged && !allowUnderwater);
    }
    public static boolean toggleLocalHeldLights() { localLightsOff = !localLightsOff; return localLightsOff; }
    public static boolean localLightsOff() { return localLightsOff; }
    public static void setFlamesAllowedUnderwater(boolean allowed) { flamesUnderwater = allowed; }
    public static boolean flamesAllowedUnderwater() { return flamesUnderwater; }

    public static IBlockState blockState(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof ItemBlock)) return null;
        ItemBlock item = (ItemBlock) stack.getItem();
        return item.getBlock().getStateFromMeta(item.getMetadata(stack.getMetadata()));
    }
    public static boolean openFlame(Block block) { return block instanceof BlockTorch && !(block instanceof BlockRedstoneTorch); }
}
