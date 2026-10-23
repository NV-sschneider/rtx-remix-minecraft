/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

import com.nvidia.rtxremix.MaterialInfo;
import com.nvidia.rtxremix.MaterialInfoOpaqueEXT;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.TextureFromMemoryInfo;
import java.nio.ByteBuffer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;

/** Static entity texture snapshots, read with Minecraft's GL state restored exactly. */
final class EntityTextures {
    private static final int[] PACK_NAMES = {GL11.GL_PACK_ALIGNMENT, GL11.GL_PACK_ROW_LENGTH,
            GL11.GL_PACK_SKIP_ROWS, GL11.GL_PACK_SKIP_PIXELS};
    private final int[] packValues = new int[PACK_NAMES.length];
    private ByteBuffer pixels = EntityMesh.direct(64 * 64 * 4);

    boolean upload(RemixAPI api, Asset asset, long byteBudget, long hash) {
        int oldActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        int oldTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int oldPackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        for (int i = 0; i < PACK_NAMES.length; i++) packValues[i] = GL11.glGetInteger(PACK_NAMES[i]);
        try {
            GlStateManager.bindTexture(asset.textureId);
            int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            if (width <= 0 || height <= 0 || (long) width * height > byteBudget / 4) return false;
            long bytes = (long) width * height * 4;
            if (bytes > Integer.MAX_VALUE) return false;
            if (pixels.capacity() < bytes) pixels = EntityMesh.direct((int) Math.max(bytes,
                    Math.min(EntityScene.MAX_TEXTURE_BYTES, (long) pixels.capacity() * 2)));
            pixels.clear();
            pixels.limit((int) bytes);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            for (int name : PACK_NAMES) GL11.glPixelStorei(name, name == GL11.GL_PACK_ALIGNMENT ? 1 : 0);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            asset.texture = api.createTextureFromMemory(TextureFromMemoryInfo.builder().hash(hash)
                    .pixelData(pixels).size(width, height).build());
            asset.bytes = bytes;
                MaterialInfo material = new MaterialInfo(hash + 1);
                material.albedoTexture = asset.texture;
                material.filterMode = 0;
                material.wrapModeU = wrapMode(GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S));
                material.wrapModeV = wrapMode(GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T));
                material.opaqueExt = MaterialInfoOpaqueEXT.builder().albedoConstant(1, 1, 1)
                        .roughnessConstant(1).metallicConstant(0).useDrawCallAlphaState(true).build();
                asset.material = api.createMaterial(material);
                return true;
        } finally {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, oldPackBuffer);
            for (int i = 0; i < PACK_NAMES.length; i++) GL11.glPixelStorei(PACK_NAMES[i], packValues[i]);
            GlStateManager.bindTexture(oldTexture);
            GlStateManager.setActiveTexture(oldActive);
        }
    }

    static byte wrapMode(int glMode) {
        return glMode == GL11.GL_REPEAT ? MaterialInfo.WRAP_MODE_REPEAT
                : glMode == GL14.GL_MIRRORED_REPEAT ? MaterialInfo.WRAP_MODE_MIRRORED_REPEAT : MaterialInfo.WRAP_MODE_CLAMP;
    }

    static final class Asset {
        final Object identity;
        final int textureId;
        long texture;
        long material;
        long bytes;
        int references;
        Asset(Object identity, int textureId, long texture, long material, long bytes) {
            this.identity = identity;
            this.textureId = textureId;
            this.texture = texture;
            this.material = material;
            this.bytes = bytes;
        }
    }
}
