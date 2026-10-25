/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.fog;

import java.nio.FloatBuffer;
import net.minecraft.client.Minecraft;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GLContext;

/** Reads final GL values after vanilla and Forge fog callbacks, without replaying either. */
public final class FogCaptureHooks {
    private static final int FOG_DISTANCE_MODE_NV = 0x855A, EYE_RADIAL_NV = 0x855B;
    private static final int EYE_PLANE_ABSOLUTE_NV = 0x855C;
    private static final FloatBuffer COLOR = BufferUtils.createFloatBuffer(16);
    private static final FogState STATE = new FogState();
    private static Thread owner;
    private static Object world;
    private static boolean armed;
    private static Throwable failure;
    private static long captures, unsupported;

    private FogCaptureHooks() {}

    public static void beginFrame(boolean enabled) {
        discard(); owner = Thread.currentThread(); armed = enabled;
        world = enabled ? Minecraft.getMinecraft().world : null;
    }

    /** Called only on successful setupFog returns; sky pass -1 must not replace terrain fog. */
    public static void afterSetup(int pass) {
        if (!armed || pass != 0 || Thread.currentThread() != owner || world == null) return;
        try {
            if (Minecraft.getMinecraft().world != world) { STATE.clear(); return; }
            int coordinate = GL11.glGetInteger(GL14.GL_FOG_COORDINATE_SOURCE);
            int distanceMode = GLContext.getCapabilities().GL_NV_fog_distance
                    ? GL11.glGetInteger(FOG_DISTANCE_MODE_NV) : EYE_PLANE_ABSOLUTE_NV;
            if (coordinate != GL14.GL_FRAGMENT_DEPTH
                    || distanceMode != EYE_RADIAL_NV && distanceMode != EYE_PLANE_ABSOLUTE_NV) {
                STATE.clear(); unsupported++; return;
            }
            COLOR.clear(); GL11.glGetFloat(GL11.GL_FOG_COLOR, COLOR);
            boolean accepted = STATE.set(GL11.glIsEnabled(GL11.GL_FOG), GL11.glGetInteger(GL11.GL_FOG_MODE),
                    distanceMode == EYE_RADIAL_NV, COLOR.get(0), COLOR.get(1), COLOR.get(2),
                    GL11.glGetFloat(GL11.GL_FOG_DENSITY), GL11.glGetFloat(GL11.GL_FOG_START), GL11.glGetFloat(GL11.GL_FOG_END));
            if (accepted) captures++; else unsupported++;
        } catch (Throwable error) { STATE.clear(); failure = error; }
    }

    /** Borrowed until beginFrame/discard. Missing or unsupported capture never reuses a previous frame. */
    public static FogState finishFrame() {
        armed = false;
        if (owner != Thread.currentThread() || world == null || Minecraft.getMinecraft().world != world) {
            discard(); return null;
        }
        if (failure != null) throw new IllegalStateException("World fog observation failed", failure);
        return STATE.valid() ? STATE : null;
    }

    public static void discard() { armed = false; world = null; failure = null; STATE.clear(); }
    public static long captureCount() { return captures; }
    public static long unsupportedCount() { return unsupported; }
}
