/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.sky;

import com.nvidia.rtxremix.Float3D;
import com.nvidia.rtxremix.InstanceCategoryBit;
import com.nvidia.rtxremix.InstanceInfoBlendEXT;
import com.nvidia.rtxremix.MaterialInfo;
import com.nvidia.rtxremix.MaterialInfoOpaqueEXT;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.TextureFormat;
import com.nvidia.rtxremix.TextureFromMemoryInfo;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IResource;
import net.minecraft.util.ResourceLocation;

/** Crisp celestial sky geometry, independent of the dome's illumination texture resolution. */
final class SkyCelestials implements AutoCloseable {
    private static final long HASH = 0x524c43534b591000L;
    private static final float[] RADIANCE = {8.0f, 0.2f, 1.0f};
    private final RemixAPI api;
    private final long[] textures = new long[2];
    private final long[] materials = new long[3];
    private final long[] meshes = new long[10];
    private final MaterialInfo[] materialInfo = new MaterialInfo[3];
    private final float[] opacity = {-1.0f, -1.0f, -1.0f};
    private final float[] transform = new float[12];
    private final InstanceInfoBlendEXT blend = additiveBlend();
    private long blendPointer;
    private boolean initialized;

    SkyCelestials(RemixAPI api) { this.api = api; }

    void draw(SkyState state, Float3D camera) {
        if (!initialized) initialize();
        float weather = state.celestialVisibility();
        float sunOpacity = visibility(state.sunY) * weather;
        float moonOpacity = visibility(-state.sunY) * weather;
        float starOpacity = Math.min(1.0f, state.stars * 2.0f) * weather;
        if (starOpacity > 0.0f) {
            updateOpacity(2, starOpacity);
            SkyGeometry.starTransform(transform, state, camera.x, camera.y, camera.z);
            drawMesh(meshes[9]);
        }
        if (sunOpacity > 0.0f) {
            updateOpacity(0, sunOpacity);
            SkyGeometry.transform(transform, state, camera.x, camera.y, camera.z, false);
            drawMesh(meshes[0]);
        }
        if (moonOpacity > 0.0f) {
            updateOpacity(1, moonOpacity);
            SkyGeometry.transform(transform, state, camera.x, camera.y, camera.z, true);
            drawMesh(meshes[1 + (state.moonPhase & 7)]);
        }
    }

    private void initialize() {
        textures[0] = texture("textures/environment/sun.png", HASH);
        textures[1] = texture("textures/environment/moon_phases.png", HASH + 1);
        for (int index = 0; index < materials.length; index++) {
            MaterialInfo info = materialInfo[index] = new MaterialInfo(HASH + 2 + index);
            if (index < 2) info.albedoTexture = info.emissiveTexture = textures[index];
            else info.emissiveColorConstant = new Float3D(1, 1, 1);
            info.emissiveIntensity = RADIANCE[index];
            info.filterMode = 0;
            info.wrapModeU = info.wrapModeV = MaterialInfo.WRAP_MODE_CLAMP;
            info.opaqueExt = MaterialInfoOpaqueEXT.builder().albedoConstant(1, 1, 1)
                    .roughnessConstant(1).metallicConstant(0).useDrawCallAlphaState(true).build();
            materials[index] = api.createMaterial(info);
            opacity[index] = 1.0f;
        }
        meshes[0] = api.createMesh(SkyGeometry.quad(HASH + 5, materials[0], 0, 0, 1, 1));
        for (int phase = 0; phase < 8; phase++) {
            float u = (phase & 3) / 4.0f;
            float v = phase / 4 / 2.0f;
            meshes[1 + phase] = api.createMesh(SkyGeometry.quad(HASH + 6 + phase,
                    materials[1], u, v, u + 0.25f, v + 0.5f));
        }
        meshes[9] = api.createMesh(SkyGeometry.stars(HASH + 14, materials[2]));
        blendPointer = blend.allocNative(0);
        initialized = true;
    }

    private void drawMesh(long mesh) {
        // SKY instances are rasterized into the runtime sky each frame, not retained in the TLAS.
        api.drawInstance(mesh, transform, InstanceCategoryBit.SKY, 1, blendPointer);
    }

    private void updateOpacity(int index, float value) {
        if (Math.abs(opacity[index] - value) < 1.0f / 255.0f) return;
        MaterialInfo info = materialInfo[index];
        info.opaqueExt.albedoConstant.x = value;
        info.opaqueExt.albedoConstant.y = value;
        info.opaqueExt.albedoConstant.z = value;
        info.opaqueExt.opacityConstant = value;
        info.emissiveIntensity = RADIANCE[index] * value;
        api.updateMaterial(materials[index], info);
        opacity[index] = value;
    }

    private long texture(String path, long hash) {
        try (IResource resource = Minecraft.getMinecraft().getResourceManager()
                .getResource(new ResourceLocation(path))) {
            BufferedImage image = ImageIO.read(resource.getInputStream());
            if (image == null) throw new IOException("Unsupported image format");
            int width = image.getWidth(), height = image.getHeight();
            if ((long) width * height > 4096L * 4096L) {
                throw new IOException("Celestial image exceeds 4096 squared pixels");
            }
            ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder());
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int argb = image.getRGB(x, y);
                pixels.put((byte) (argb >>> 16)).put((byte) (argb >>> 8))
                        .put((byte) argb).put((byte) (argb >>> 24));
            }
            pixels.flip();
            return api.createTextureFromMemory(TextureFromMemoryInfo.builder().hash(hash)
                    .pixelData(pixels).size(width, height).format(TextureFormat.RGBA8_SRGB).build());
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read celestial texture " + path, failure);
        }
    }

    private static InstanceInfoBlendEXT additiveBlend() {
        InstanceInfoBlendEXT result = InstanceInfoBlendEXT.textureTimesVertexColorAlphaTest(1);
        result.alphaBlendEnabled = 1;
        result.srcColorBlendFactor = InstanceInfoBlendEXT.VK_BLEND_FACTOR_ONE;
        result.dstColorBlendFactor = InstanceInfoBlendEXT.VK_BLEND_FACTOR_ONE;
        result.srcAlphaBlendFactor = InstanceInfoBlendEXT.VK_BLEND_FACTOR_ZERO;
        result.dstAlphaBlendFactor = InstanceInfoBlendEXT.VK_BLEND_FACTOR_ONE;
        return result;
    }

    private static float visibility(float height) {
        return Math.min(1.0f, Math.max(0.0f, (height + 0.07f) / 0.07f));
    }

    int resourceCount() {
        return count(textures) + count(materials) + count(meshes) + (blendPointer == 0 ? 0 : 1);
    }

    private static int count(long[] handles) {
        int result = 0;
        for (long handle : handles) if (handle != 0) result++;
        return result;
    }

    @Override public void close() {
        RuntimeException failure = null;
        for (int kind = 0; kind < 3; kind++) {
            long[] handles = kind == 0 ? meshes : kind == 1 ? materials : textures;
            for (int index = 0; index < handles.length; index++) {
                if (handles[index] == 0) continue;
                try {
                    if (kind == 0) api.destroyMesh(handles[index]);
                    else if (kind == 1) api.destroyMaterial(handles[index]);
                    else api.destroyTexture(handles[index]);
                } catch (RuntimeException problem) {
                    if (failure == null) failure = problem;
                    else failure.addSuppressed(problem);
                } finally {
                    handles[index] = 0;
                }
            }
        }
        try {
            blend.freeNative();
        } catch (RuntimeException problem) {
            if (failure == null) failure = problem;
            else failure.addSuppressed(problem);
        }
        blendPointer = 0;
        initialized = false;
        if (failure != null) throw failure;
    }
}
