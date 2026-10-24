/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

import com.nvidia.rtxremix.InstanceCategoryBit;
import com.nvidia.rtxremix.forge.item.ItemCaptureHooks;
import java.nio.FloatBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Matrix4f;

/** Owns the world-to-hand handoff; the existing vanilla draw remains the only model evaluation. */
public final class FirstPersonCapture {
    public static final int SHADOW_CATEGORY = InstanceCategoryBit.THIRD_PERSON_PLAYER_MODEL | InstanceCategoryBit.THIRD_PERSON_PLAYER_BODY;
    private static final FloatBuffer BUFFER = BufferUtils.createFloatBuffer(16);
    private static final Matrix4f WORLD_VIEW = new Matrix4f(), WORLD_INVERSE = new Matrix4f(), WORLD_PROJECTION = new Matrix4f();
    private static final Matrix4f HAND_PROJECTION = new Matrix4f(), CORRECTION = new Matrix4f(), ROOT = new Matrix4f();
    private static Object world;
    private static double x, y, z;
    private static boolean prepared, hand, shadow, submitted;
    private static Runnable submitCallback;

    private FirstPersonCapture() { }

    /** Called at RenderWorldLast, while its camera matrices are still live. */
    public static void prepareWorld(float partialTicks) {
        Minecraft mc = Minecraft.getMinecraft();
        Entity camera = mc.getRenderViewEntity();
        if (!EntityCaptureHooks.isArmed() || mc.world == null || camera == null) return;
        read(GL11.GL_MODELVIEW_MATRIX, WORLD_VIEW);
        if (Matrix4f.invert(WORLD_VIEW, WORLD_INVERSE) == null) return;
        read(GL11.GL_PROJECTION_MATRIX, WORLD_PROJECTION);
        x = camera.lastTickPosX + (camera.posX - camera.lastTickPosX) * partialTicks;
        y = camera.lastTickPosY + (camera.posY - camera.lastTickPosY) * partialTicks;
        z = camera.lastTickPosZ + (camera.posZ - camera.lastTickPosZ) * partialTicks;
        world = mc.world;
        prepared = true;
        if (mc.gameSettings.thirdPersonView == 0 && camera == mc.player && !mc.player.isSpectator()
                && !mc.player.isDead && !mc.player.isInvisible()) {
            shadow = true;
            try { EntityCaptureHooks.captureLocalPlayer(partialTicks); }
            finally { shadow = false; }
        }
    }

    /** Runs immediately before Forge's RenderHandEvent, after vanilla establishes the hand projection. */
    public static void beginHand() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (!prepared || submitted || world != mc.world || !EntityCaptureHooks.isArmed() || mc.player == null
                    || mc.gameSettings.thirdPersonView != 0 || mc.player.isPlayerSleeping() || mc.player.isSpectator() || mc.gameSettings.hideGUI) return;
            read(GL11.GL_PROJECTION_MATRIX, HAND_PROJECTION);
            if (!FirstPersonTransform.root(WORLD_INVERSE, WORLD_PROJECTION, HAND_PROJECTION, CORRECTION, ROOT)) return;
            hand = true;
            EntityCaptureHooks.beginFirstPerson(mc.player);
            ItemCaptureHooks.beginFirstPerson();
        } catch (RuntimeException | LinkageError error) { EntityCaptureHooks.fail(error); hand = false; }
    }

    public static void endHand() {
        if (!hand) return;
        EntityCaptureHooks.endFirstPerson();
        hand = false;
    }

    public static void setSubmitCallback(Runnable callback) { submitCallback = callback; }

    /** Before screen overlays, with a successful world-pass tail as the hidden-hand fallback. */
    public static void submitFrame() {
        endHand();
        if (!prepared || submitted) return;
        submitted = true;
        if (world == Minecraft.getMinecraft().world && submitCallback != null) submitCallback.run();
    }

    static void reset() { world = null; prepared = hand = shadow = submitted = false; }
    static boolean hand() { return hand; }
    static int category() { return hand ? InstanceCategoryBit.VIEW_MODEL : shadow ? SHADOW_CATEGORY : 0; }
    static void transform(Matrix4f modelView, Matrix4f scratch, float[] destination) {
        EntityTransform.world(ROOT, modelView, scratch, x, y, z, destination);
    }
    private static void read(int parameter, Matrix4f destination) {
        BUFFER.clear(); GL11.glGetFloat(parameter, BUFFER); destination.load(BUFFER);
    }
}
