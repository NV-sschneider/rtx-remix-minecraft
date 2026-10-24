/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;

/** Optional Neat adapter; its original selection, health, names, and visibility rules still draw once. */
public final class MobHudHooks {
    private static MobHudFrame frame;
    private static Object world;
    private static boolean armed;
    private MobHudHooks() { }

    public static void beginFrame(boolean enabled) {
        if (frame != null) frame.discard();
        armed = enabled;
        world = enabled ? Minecraft.getMinecraft().world : null;
    }
    public static void begin() {
        Minecraft mc = Minecraft.getMinecraft();
        if (!armed || world == null || world != mc.world) return;
        if (frame == null) frame = new MobHudFrame();
        frame.begin(mc.displayWidth, mc.displayHeight);
    }
    public static void end(boolean success) { if (frame != null) frame.end(success); }
    public static void blendFunc(int source, int destination) {
        if (frame != null && frame.active()) frame.blend(source, destination);
        else GlStateManager.blendFunc(source, destination);
    }
    public static void composite() {
        Minecraft mc = Minecraft.getMinecraft();
        if (frame != null && world == mc.world) frame.composite(mc.displayWidth, mc.displayHeight);
    }
    public static void discard() { if (frame != null) frame.discard(); armed = false; world = null; }
    public static void close() { discard(); if (frame != null) { frame.close(); frame = null; } }
    public static long captures() { return frame == null ? 0 : frame.captures(); }
    public static long composites() { return frame == null ? 0 : frame.composites(); }
    public static int resourceCount() { return frame == null ? 0 : frame.resourceCount(); }
}
