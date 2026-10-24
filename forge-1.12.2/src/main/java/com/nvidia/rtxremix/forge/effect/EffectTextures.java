/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.effect;

import com.nvidia.rtxremix.MaterialInfo;
import java.nio.ByteBuffer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;

/** Initial effect texture snapshots and sampler modes, with touched GL state restored. */
final class EffectTextures {
    private static final int[] PACK = {GL11.GL_PACK_ALIGNMENT, GL11.GL_PACK_ROW_LENGTH,
            GL11.GL_PACK_SKIP_ROWS, GL11.GL_PACK_SKIP_PIXELS};
    private final int[] saved = new int[PACK.length];
    ByteBuffer pixels = CapturedEffectBatch.direct(65536);
    int width, height;
    byte wrapU = MaterialInfo.WRAP_MODE_REPEAT, wrapV = MaterialInfo.WRAP_MODE_REPEAT, filter;

    boolean read(int texture, long available) {
        int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int packBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        for (int i = 0; i < PACK.length; i++) saved[i] = GL11.glGetInteger(PACK[i]);
        try {
            GlStateManager.bindTexture(texture);
            width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            long pixelsRequired = (long) width * height;
            if (width <= 0 || height <= 0 || pixelsRequired > available / 4 || pixelsRequired > Integer.MAX_VALUE / 4) return false;
            long bytes = pixelsRequired * 4;
            if (pixels.capacity() < bytes) pixels = CapturedEffectBatch.direct((int) bytes);
            pixels.clear(); pixels.limit((int) bytes);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            for (int name : PACK) GL11.glPixelStorei(name, name == GL11.GL_PACK_ALIGNMENT ? 1 : 0);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            wrapU = wrap(GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S));
            wrapV = wrap(GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T));
            filter = (byte) (GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER) == GL11.GL_LINEAR ? 1 : 0);
            return true;
        } finally {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, packBuffer);
            for (int i = 0; i < PACK.length; i++) GL11.glPixelStorei(PACK[i], saved[i]);
            GlStateManager.bindTexture(previous);
            GlStateManager.setActiveTexture(active);
        }
    }
    private static byte wrap(int mode) {
        return mode == GL11.GL_REPEAT ? MaterialInfo.WRAP_MODE_REPEAT
                : mode == GL14.GL_MIRRORED_REPEAT ? MaterialInfo.WRAP_MODE_MIRRORED_REPEAT : MaterialInfo.WRAP_MODE_CLAMP;
    }
}
