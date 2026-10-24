/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.sky;

import com.nvidia.rtxremix.LightInfo;
import com.nvidia.rtxremix.Float3D;
import com.nvidia.rtxremix.LightInfoDistantEXT;
import com.nvidia.rtxremix.LightInfoDomeEXT;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.TextureBatchEntry;
import com.nvidia.rtxremix.TextureFormat;
import com.nvidia.rtxremix.TextureFromMemoryInfo;
import com.nvidia.rtxremix.TexturePatch;
import com.nvidia.rtxremix.TexturePatchInfo;
import com.nvidia.rtxremix.forge.RemixForgeMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.util.math.Vec3d;

/** Owns one dome texture and its sky lights; every method runs on the render thread. */
public final class SkyScene implements AutoCloseable {
    private static final long HASH = 0x524c43534b590000L;
    private static final long UPDATE_NANOS = 250000000L;
    private final RemixAPI api;
    private final SkyDome image = new SkyDome();
    private final SkyState state = new SkyState();
    private final SkyCelestials celestials;
    private final TextureBatchEntry entry = new TextureBatchEntry();
    private final TexturePatchInfo patches = new TexturePatchInfo();
    private final LightInfo sunInfo = distant(HASH + 2);
    private final LightInfo moonInfo = distant(HASH + 3);
    private final long[] texture = new long[1];
    private final long[] lights = new long[3];
    private long nextUpdate;
    private long textureUpdates;
    private boolean closed;

    public SkyScene(RemixAPI api) {
        this.api = api;
        this.celestials = new SkyCelestials(api);
        TexturePatch patch = new TexturePatch();
        patch.width = SkyDome.WIDTH;
        patch.height = SkyDome.HEIGHT;
        patch.rowPitch = SkyDome.WIDTH * 4 * Float.BYTES;
        patch.format = TextureFormat.RGBA32_SFLOAT;
        patch.pixelData = image.pixels;
        entry.patches = new TexturePatch[] {patch};
        patches.entries = new TextureBatchEntry[] {entry};
    }

    /** Samples provider sky colors at most four times per second, then draws the live lights. */
    public void update(WorldClient world, float partialTicks, Float3D cameraPosition) {
        if (closed) throw new IllegalStateException("Sky scene is closed");
        long now = System.nanoTime();
        if (texture[0] == 0 || now - nextUpdate >= 0) {
            capture(world, partialTicks);
            if (state.kind == SkyState.NORMAL || texture[0] == 0) publish();
            nextUpdate = now + UPDATE_NANOS;
        }
        if (state.kind == SkyState.NORMAL) {
            // Keep shadows smooth even though the lower-resolution dome updates at 4 Hz.
            state.angle = world.getCelestialAngle(partialTicks);
            state.orient();
            state.rain = world.getRainStrength(partialTicks);
            state.thunder = world.getThunderStrength(partialTicks);
            state.moonBrightness = world.getCurrentMoonPhaseFactor();
            lights[0] = updateDistant(lights[0], sunInfo, -state.sunX, -state.sunY,
                    state.sunStrength(), 1.0f, 0.94f, 0.84f);
            lights[1] = updateDistant(lights[1], moonInfo, state.sunX, state.sunY,
                    state.moonStrength(), 0.7f, 0.8f, 1.0f);
        }
        api.drawLightInstance(lights[2]);
        if (state.sunStrength() > 0.0f) api.drawLightInstance(lights[0]);
        if (state.moonStrength() > 0.0f) api.drawLightInstance(lights[1]);
        // A Forge sky handler replaces vanilla's celestial draw pass, rather than adding to it.
        if (state.kind == SkyState.NORMAL && world.provider.getSkyRenderer() == null) celestials.draw(state, cameraPosition);
    }

    private void capture(WorldClient world, float partialTicks) {
        state.kind = world.provider.getDimensionType().getId() == 1 ? SkyState.END
                : world.provider.isSurfaceWorld() ? SkyState.NORMAL : SkyState.SKYLESS;
        if (state.kind != SkyState.NORMAL) return;
        state.angle = world.getCelestialAngle(partialTicks);
        state.orient();
        state.rain = world.getRainStrength(partialTicks);
        state.thunder = world.getThunderStrength(partialTicks);
        state.moonPhase = world.getMoonPhase();
        state.moonBrightness = world.getCurrentMoonPhaseFactor();
        state.stars = world.provider.getStarBrightness(partialTicks);
        Vec3d skyColor = world.getSkyColor(Minecraft.getMinecraft().getRenderViewEntity(), partialTicks);
        Vec3d fogColor = world.getFogColor(partialTicks);
        state.skyR = linear((float) skyColor.x);
        state.skyG = linear((float) skyColor.y);
        state.skyB = linear((float) skyColor.z);
        // Provider fog has no weather attenuation in vanilla 1.12; apply it to the horizon.
        float weather = (1.0f - state.rain * 0.5f) * (1.0f - state.thunder * 0.5f);
        state.fogR = linear((float) fogColor.x * weather);
        state.fogG = linear((float) fogColor.y * weather);
        state.fogB = linear((float) fogColor.z * weather);
        float[] sunset = world.provider.calcSunriseSunsetColors(state.angle, partialTicks);
        state.sunsetAlpha = sunset == null ? 0.0f : sunset[3];
        if (sunset != null) {
            state.sunsetR = linear(sunset[0]);
            state.sunsetG = linear(sunset[1]);
            state.sunsetB = linear(sunset[2]);
        }
    }

    private void publish() {
        image.generate(state);
        if (texture[0] == 0) {
            texture[0] = api.createTextureFromMemory(TextureFromMemoryInfo.builder().hash(HASH)
                    .pixelData(image.pixels).size(SkyDome.WIDTH, SkyDome.HEIGHT)
                    .format(TextureFormat.RGBA32_SFLOAT).build());
            entry.handle = texture[0];
            LightInfoDomeEXT extension = new LightInfoDomeEXT();
            extension.colorTexture = texture[0];
            lights[2] = api.createLight(LightInfo.builder().hash(HASH + 1).radiance(1, 1, 1)
                    .domeExt(extension).build());
        } else {
            api.updateTextureFromMemory(patches);
        }
        textureUpdates++;
    }

    private long updateDistant(long old, LightInfo info, float x, float y,
            float intensity, float r, float g, float b) {
        if (old != 0 && info.distantExt.direction.x == x && info.distantExt.direction.y == y
                && info.radiance.x == intensity * r && info.radiance.y == intensity * g
                && info.radiance.z == intensity * b) return old;
        info.distantExt.direction.x = x;
        info.distantExt.direction.y = y;
        info.radiance.x = intensity * r;
        info.radiance.y = intensity * g;
        info.radiance.z = intensity * b;
        long updated = api.createLight(info);
        if (old != 0 && old != updated) {
            try {
                api.destroyLight(old);
            } catch (RuntimeException failure) {
                // Same-hash creation may already have retired the replaced runtime handle.
                RemixForgeMod.LOG.warn("Could not release replaced sky light {}", old, failure);
            }
        }
        return updated;
    }

    private static LightInfo distant(long hash) {
        LightInfo info = new LightInfo();
        info.hash = hash;
        info.distantExt = new LightInfoDistantEXT();
        return info;
    }

    static float linear(float value) {
        if (!Float.isFinite(value) || value < 0.0f) return 0.0f;
        return value <= 0.04045f ? value / 12.92f
                : (float) Math.pow((value + 0.055f) / 1.055f, 2.4);
    }

    /** Number of currently owned native textures, lights, materials, meshes, and blend state. */
    public int resourceCount() {
        int count = texture[0] == 0 ? 0 : 1;
        for (long light : lights) if (light != 0) count++;
        return count + celestials.resourceCount();
    }

    /** Counts completed texture uploads, including the first publication. */
    public long textureUpdates() { return textureUpdates; }

    public String status() {
        return state.kind == SkyState.END ? "end" : state.kind == SkyState.SKYLESS ? "skyless" : "normal";
    }

    @Override public void close() {
        closed = true;
        SkyRelease.scene(lights, texture, celestials::close, api::destroyLight, api::destroyTexture);
    }
}
