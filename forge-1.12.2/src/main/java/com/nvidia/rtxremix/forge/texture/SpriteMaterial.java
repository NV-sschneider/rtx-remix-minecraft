/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.texture;

import com.nvidia.rtxremix.TexturePatch;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.data.AnimationMetadataSection;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

/** A sidecar strip uses the actual albedo frame sequence, including repeated frames. */
final class SpriteMaterial {
    private static final Field FRAME = ReflectionHelper.findField(TextureAtlasSprite.class,
            new String[] {"frameCounter", "field_110973_g"});
    private static final Field TICK = ReflectionHelper.findField(TextureAtlasSprite.class,
            new String[] {"tickCounter", "field_110983_h"});
    private static final Field METADATA = ReflectionHelper.findField(TextureAtlasSprite.class,
            new String[] {"animationMetadata", "field_110982_k"});
    private static final Field ROTATED = ReflectionHelper.findField(TextureAtlasSprite.class,
            new String[] {"rotated", "field_130222_e"});
    final TextureAtlasSprite sprite;
    final MaterialImage normal, specular;
    final TexturePatch[] patches = new TexturePatch[4];
    final Sampling sampling = new Sampling();

    SpriteMaterial(TextureAtlasSprite sprite, MaterialImage normal, MaterialImage specular) {
        this.sprite = sprite; this.normal = normal; this.specular = specular;
        snapshot();
    }

    static String unsupportedReason(TextureAtlasSprite sprite) {
        try {
            if (ROTATED.getBoolean(sprite)) return "rotated atlas sprite";
            return sprite.hasAnimationMetadata() ? SpriteAnimationContract.unsupported(sprite) : null;
        } catch (IllegalAccessException error) { throw new IllegalStateException("Cannot read atlas sprite orientation", error); }
    }

    void snapshot() {
        try {
            sample((AnimationMetadataSection) METADATA.get(sprite), sprite.getFrameCount(),
                    FRAME.getInt(sprite), TICK.getInt(sprite), sampling);
        } catch (IllegalAccessException error) { throw new IllegalStateException("Cannot read atlas animation frame", error); }
    }

    static void sample(AnimationMetadataSection metadata, int frames, int frame, int tick, Sampling result) {
        result.first = result.second = 0; result.mix = 0;
        if (metadata == null) return;
        int count = metadata.getFrameCount() == 0 ? frames : metadata.getFrameCount();
        if (count <= 0 || frame < 0 || frame >= count) throw new IllegalStateException("Invalid atlas animation sequence");
        result.first = metadata.getFrameCount() == 0 ? frame : metadata.getFrameIndex(frame);
        result.second = metadata.getFrameCount() == 0 ? (frame + 1) % count : metadata.getFrameIndex((frame + 1) % count);
        if (result.first < 0 || result.first >= frames || result.second < 0 || result.second >= frames) {
            throw new IllegalStateException("Atlas animation references a missing image frame");
        }
        if (metadata.isInterpolate() && result.first != result.second) {
            int duration = metadata.getFrameTimeSingle(frame);
            if (duration <= 0 || tick < 0 || tick >= duration) throw new IllegalStateException("Invalid atlas animation time");
            result.mix = (float) tick / duration;
        }
    }

    long bytes() {
        return (normal == null ? 0 : normal.bytes()) + (specular == null ? 0 : specular.bytes());
    }
    boolean has(int channel) { return channel == LabPbrPixels.NORMAL ? normal != null : specular != null; }
    boolean emits() {
        if (specular != null) for (int pixel : specular.pixels) if (LabPbrPixels.emission(pixel) != 0) return true;
        return false;
    }

    void blit(ByteBuffer target, int strideWidth, int originX, int originY,
            int channel, ByteBuffer albedo, int albedoWidth, int albedoX, int albedoY) {
        MaterialImage image = channel == LabPbrPixels.NORMAL ? normal : specular;
        if (image == null) return;
        for (int y = 0; y < image.height; y++) {
            for (int x = 0; x < image.width; x++) {
                int first = image.pixel(sampling.first, x, y), second = image.pixel(sampling.second, x, y);
                int ai = ((albedoY + y) * albedoWidth + albedoX + x) * 4;
                int rgb = (albedo.get(ai) & 255) << 16 | (albedo.get(ai + 1) & 255) << 8 | albedo.get(ai + 2) & 255;
                LabPbrPixels.write(target, ((originY + y) * strideWidth + originX + x) * LabPbrPixels.STRIDES[channel],
                        channel, first, second, sampling.mix, rgb);
            }
        }
    }

    TexturePatch patch(int channel, TexturePatch albedo) {
        TexturePatch patch = patches[channel];
        if (patch == null) {
            patch = patches[channel] = new TexturePatch();
            patch.dstX = albedo.dstX; patch.dstY = albedo.dstY;
            patch.width = albedo.width; patch.height = albedo.height;
            patch.rowPitch = patch.width * LabPbrPixels.STRIDES[channel];
            patch.format = AtlasMaterialMaps.FORMATS[channel];
            patch.pixelData = ByteBuffer.allocateDirect(patch.rowPitch * patch.height);
        }
        blit(patch.pixelData, patch.width, 0, 0, channel, albedo.pixelData, albedo.width, 0, 0);
        return patch;
    }

    static final class Sampling { int first, second; float mix; }
}
