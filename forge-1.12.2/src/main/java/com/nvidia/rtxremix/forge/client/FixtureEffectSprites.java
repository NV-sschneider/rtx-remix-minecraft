/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import java.nio.FloatBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;

/** Original fixture art and custom ParticleManager draws; owns exactly one temporary texture. */
final class FixtureEffectSprites implements AutoCloseable {
    private final Minecraft mc;
    private final ResourceLocation location;
    private final DynamicTexture texture;
    private final Sprite[] sprites = new Sprite[3];
    private int ticks, revision;
    private boolean closed;

    FixtureEffectSprites(Minecraft mc, int x, int y, int z) {
        this.mc = mc;
        texture = new DynamicTexture(32, 32);
        ResourceLocation created;
        try {
            paint(); texture.updateDynamicTexture();
            created = mc.getTextureManager().getDynamicTextureLocation("rtxremix-effects-fixture", texture);
        } catch (RuntimeException | Error failure) {
            texture.deleteGlTexture(); throw failure;
        }
        location = created;
        try {
            for (int i = 0; i < sprites.length; i++) {
                sprites[i] = new Sprite(mc, location, x - 3 + i * 3, y + 5, z + 9, i);
                mc.effectRenderer.addEffect(sprites[i]);
            }
        } catch (RuntimeException | Error failure) {
            close(); throw failure;
        }
    }
    void tick() {
        if (!closed && ++ticks % 10 == 0) { revision++; paint(); texture.updateDynamicTexture(); }
    }
    int revision() { return revision; }
    private void paint() {
        int[] pixels = texture.getTextureData();
        for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++) {
            float dx = (x - 15.5f) / 15.5f, dy = (y - 15.5f) / 15.5f;
            int alpha = Math.max(0, Math.min(255, Math.round((1 - dx * dx - dy * dy) * 255)));
            int rgb = (revision & 1) == 0 ? 0x30c0ff : 0xff8040;
            pixels[y * 32 + x] = alpha << 24 | rgb;
        }
    }
    @Override public void close() {
        if (closed) return;
        for (Sprite sprite : sprites) if (sprite != null) sprite.setExpired();
        mc.getTextureManager().deleteTexture(location);
        closed = true;
    }

    private static final class Sprite extends Particle {
        private final Minecraft mc;
        private final ResourceLocation texture;
        private final int style;
        private final FloatBuffer color = BufferUtils.createFloatBuffer(16);
        Sprite(Minecraft mc, ResourceLocation texture, double x, double y, double z, int style) {
            super(mc.world, x, y, z);
            this.mc = mc; this.texture = texture; this.style = style;
            particleMaxAge = Integer.MAX_VALUE;
        }
        @Override public int getFXLayer() { return 3; }
        @Override public void onUpdate() { particleAge++; }
        @Override public void renderParticle(BufferBuilder buffer, Entity entity, float partial,
                float rotationX, float rotationZ, float rotationYZ, float rotationXY, float rotationXZ) {
            if (!isAlive()) return;
            int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
            int binding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            boolean blend = GL11.glIsEnabled(GL11.GL_BLEND), alpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
            boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST), depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
            boolean textured = GL11.glIsEnabled(GL11.GL_TEXTURE_2D), lighting = GL11.glIsEnabled(GL11.GL_LIGHTING);
            int src = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB), dst = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
            int srcA = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA), dstA = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
            int rgbEquation = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB), alphaEquation = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
            color.clear(); GL11.glGetFloat(GL11.GL_CURRENT_COLOR, color);
            try {
                GlStateManager.enableTexture2D(); GlStateManager.enableDepth(); GlStateManager.depthMask(false);
                GlStateManager.disableLighting(); GlStateManager.disableAlpha(); GlStateManager.enableBlend();
                GlStateManager.tryBlendFuncSeparate(style == 2 ? 1 : 770, style == 0 ? 771 : 1, 1, 0);
                GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);
                mc.getTextureManager().bindTexture(texture);
                buffer.begin(style == 0 ? GL11.GL_QUADS : style == 1 ? GL11.GL_TRIANGLE_FAN : GL11.GL_TRIANGLE_STRIP,
                        DefaultVertexFormats.POSITION_TEX_COLOR);
                double x = posX - interpPosX, y = posY - interpPosY, z = posZ - interpPosZ;
                float size = 1.1f + 0.08f * (float) Math.sin((particleAge + partial) * 0.1);
                vertex(buffer, x - size, y - size, z, 0, 0);
                vertex(buffer, x + size, y - size, z, 1, 0);
                if (style == 2) {
                    vertex(buffer, x - size, y + size, z, 0, 1);
                    vertex(buffer, x + size, y + size, z, 1, 1);
                } else {
                    vertex(buffer, x + size, y + size, z, 1, 1);
                    vertex(buffer, x - size, y + size, z, 0, 1);
                }
                Tessellator.getInstance().draw();
            } finally {
                GlStateManager.tryBlendFuncSeparate(src, dst, srcA, dstA);
                GL20.glBlendEquationSeparate(rgbEquation, alphaEquation);
                if (!blend) GlStateManager.disableBlend();
                if (alpha) GlStateManager.enableAlpha();
                if (!depth) GlStateManager.disableDepth();
                GlStateManager.depthMask(depthMask);
                if (!textured) GlStateManager.disableTexture2D();
                if (lighting) GlStateManager.enableLighting();
                GlStateManager.color(color.get(0), color.get(1), color.get(2), color.get(3));
                GlStateManager.bindTexture(binding); GlStateManager.setActiveTexture(active);
            }
        }
        private static void vertex(BufferBuilder buffer, double x, double y, double z, double u, double v) {
            buffer.pos(x, y, z).tex(u, v).color(255, 255, 255, 180).endVertex();
        }
    }
}
