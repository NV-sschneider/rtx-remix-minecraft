/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.texture;

import com.nvidia.rtxremix.Float3D;
import com.nvidia.rtxremix.MaterialInfo;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.TextureBatchEntry;
import com.nvidia.rtxremix.TextureFormat;
import com.nvidia.rtxremix.TextureFromMemoryInfo;
import com.nvidia.rtxremix.TexturePatch;
import com.nvidia.rtxremix.TexturePurpose;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Bounded, render-thread-owned material atlases. Materials borrow these handles and
 * must be destroyed before close. Sidecars resolve through the active resource packs.
 */
public final class AtlasMaterialMaps implements AutoCloseable {
    static final int[] FORMATS = {TextureFormat.RGBA8_UNORM, TextureFormat.R8_UNORM,
            TextureFormat.R8_UNORM, TextureFormat.RGBA8_SRGB};
    private static final int[] PURPOSES = {TexturePurpose.NORMAL_DX, TexturePurpose.DATA,
            TexturePurpose.DATA, TexturePurpose.COLOR};
    private static final long MAX_ATLAS_BYTES = 128L * 1024 * 1024;
    private static final long MAX_SOURCE_BYTES = 64L * 1024 * 1024;
    private static final long MAX_PATCH_BYTES = 32L * 1024 * 1024;
    private static final int MAX_SPRITES = 1024;
    private static final int MAX_ANIMATED_PIXELS = 256 * 256;
    private static final AtomicLong HASHES = new AtomicLong(0x524c504252000000L);
    private static final Logger LOGGER = LogManager.getLogger("RLCraftRemixMaterials");
    private static final Field UPLOADED = ReflectionHelper.findField(TextureMap.class,
            new String[] {"mapUploadedSprites", "field_94252_e"});
    interface Backend {
        long create(TextureFromMemoryInfo info);
        void destroy(long handle);
    }

    private final Backend backend;
    private final Thread thread = Thread.currentThread();
    private final List<SpriteMaterial> sprites = new ArrayList<SpriteMaterial>();
    private final Long2ObjectOpenHashMap<SpriteMaterial> rectangles = new Long2ObjectOpenHashMap<SpriteMaterial>();
    private final TextureBatchEntry[] channels = new TextureBatchEntry[4];
    private final TexturePatch[][][] patchArrays = new TexturePatch[4][AtlasPatchQueue.PATCHES_PER_FLUSH + 1][];
    private final TextureBatchEntry[][] entryArrays = new TextureBatchEntry[5][];
    private long sourceBytes, patchBytes, updates;
    private int skipped;
    private boolean closed;

    public AtlasMaterialMaps(RemixAPI api) {
        this(new Backend() {
            @Override public long create(TextureFromMemoryInfo info) { return api.createTextureFromMemory(info); }
            @Override public void destroy(long handle) { api.destroyTexture(handle); }
        });
    }
    AtlasMaterialMaps(Backend backend) {
        this.backend = backend;
        for (int i = 0; i < channels.length; i++) channels[i] = new TextureBatchEntry();
    }

    /** Initializes once, after OpenGL albedo readback, while this owner is already retained. */
    public void initialize(Minecraft mc, ByteBuffer albedo, int width, int height) {
        checkThread();
        TextureMap atlas = mc.getTextureMapBlocks();
        List<TextureAtlasSprite> uploaded = new ArrayList<TextureAtlasSprite>(uploaded(atlas));
        uploaded.sort(Comparator.comparing(TextureAtlasSprite::getIconName));
        for (TextureAtlasSprite sprite : uploaded) {
            ResourceLocation id = new ResourceLocation(sprite.getIconName());
            MaterialImage normal = load(mc.getResourceManager(), atlas.getBasePath(), id, "_n", sprite);
            MaterialImage specular = load(mc.getResourceManager(), atlas.getBasePath(), id, "_s", sprite);
            if (normal == null && specular == null) continue;
            if (!SpriteMaterial.supported(sprite)) { omitted(id, "rotated or custom animated sprite"); continue; }
            if (sprite.getOriginX() < 0 || sprite.getOriginY() < 0
                    || sprite.getOriginX() + sprite.getIconWidth() > width
                    || sprite.getOriginY() + sprite.getIconHeight() > height) {
                omitted(id, "sprite outside atlas"); continue;
            }
            SpriteMaterial material = new SpriteMaterial(sprite, normal, specular);
            if (!addSprite(material)) {
                omitted(id, "material source or animation budget exceeded"); continue;
            }
        }
        upload(albedo, width, height);
        LOGGER.info("Captured material maps for {} atlas sprites, {} textures, {} source bytes; {} sidecars omitted",
                sprites.size(), resourceCount(), sourceBytes, skipped);
    }

    MaterialImage load(IResourceManager resources, String base, ResourceLocation id,
            String suffix, TextureAtlasSprite sprite) {
        ResourceLocation location = new ResourceLocation(id.getNamespace(), base + "/" + id.getPath() + suffix + ".png");
        try (IResource resource = resources.getResource(location)) {
            if (resource.getMetadata("animation") != null) {
                omitted(location, "sidecar animation metadata must be omitted; the albedo owns timing"); return null;
            }
            return MaterialImage.read(resource.getInputStream(), sprite.getIconWidth(), sprite.getIconHeight(), sprite.getFrameCount());
        } catch (FileNotFoundException missing) { return null; }
        catch (IOException | RuntimeException malformed) {
            omitted(location, "invalid PNG or incompatible frame dimensions"); return null;
        }
    }

    boolean addSprite(SpriteMaterial material) {
        TextureAtlasSprite sprite = material.sprite;
        long bytes = material.bytes();
        long patchSize = (long) sprite.getIconWidth() * sprite.getIconHeight() * 10;
        if (sprites.size() == MAX_SPRITES || bytes > MAX_SOURCE_BYTES - sourceBytes
                || sprite.hasAnimationMetadata() && ((long) sprite.getIconWidth() * sprite.getIconHeight() > MAX_ANIMATED_PIXELS
                || patchSize > MAX_PATCH_BYTES - patchBytes)) return false;
        sprites.add(material);
        sourceBytes += bytes;
        if (sprite.hasAnimationMetadata()) {
            patchBytes += patchSize;
            rectangles.put(AtlasPatchQueue.rectangleKey(sprite.getOriginX(), sprite.getOriginY(),
                    sprite.getIconWidth(), sprite.getIconHeight()), material);
        }
        return true;
    }

    void upload(ByteBuffer albedo, int width, int height) {
        boolean[] needed = new boolean[4];
        for (SpriteMaterial sprite : sprites) {
            needed[0] |= sprite.normal != null;
            needed[1] |= sprite.specular != null;
            needed[2] |= sprite.specular != null;
            needed[3] |= sprite.emits();
        }
        long bytes = 0;
        for (int i = 0; i < needed.length; i++) if (needed[i]) bytes += (long) width * height * LabPbrPixels.STRIDES[i];
        if (bytes > MAX_ATLAS_BYTES) {
            skipped += sprites.size(); sprites.clear(); rectangles.clear(); sourceBytes = patchBytes = 0;
            LOGGER.warn("Material atlases exceed the 128 MiB limit; using the base atlas materials");
            return;
        }
        ByteBuffer pixels = null;
        for (int channel = 0; channel < needed.length; channel++) {
            if (!needed[channel]) continue;
            int size = width * height * LabPbrPixels.STRIDES[channel];
            if (pixels == null || pixels.capacity() < size) pixels = ByteBuffer.allocateDirect(size);
            pixels.clear(); pixels.limit(size);
            LabPbrPixels.defaults(pixels, channel);
            for (SpriteMaterial sprite : sprites) sprite.blit(pixels, width, sprite.sprite.getOriginX(),
                    sprite.sprite.getOriginY(), channel, albedo, width, sprite.sprite.getOriginX(), sprite.sprite.getOriginY());
            channels[channel].handle = backend.create(TextureFromMemoryInfo.builder().hash(HASHES.incrementAndGet())
                    .size(width, height).pixelData(pixels).format(FORMATS[channel]).purpose(PURPOSES[channel]).build());
        }
    }

    /** Applies atlas maps to an ordinary opaque/cutout/blended terrain material. */
    public void apply(MaterialInfo material) {
        normal(material);
        if (material.opaqueExt == null) return;
        material.opaqueExt.roughnessTexture = channels[LabPbrPixels.ROUGHNESS].handle;
        material.opaqueExt.metallicTexture = channels[LabPbrPixels.METALLIC].handle;
        if (material.opaqueExt.roughnessTexture != 0) material.opaqueExt.roughnessConstant = 1;
        if (material.opaqueExt.metallicTexture != 0) material.opaqueExt.metallicConstant = 1;
        if (channels[LabPbrPixels.EMISSIVE].handle != 0) {
            material.emissiveTexture = channels[LabPbrPixels.EMISSIVE].handle;
            material.emissiveIntensity = 4;
            material.emissiveColorConstant = new Float3D(1, 1, 1);
        }
    }
    /** Water, lava and glass retain their physical material rules and accept authored normals. */
    public void normal(MaterialInfo material) { material.normalTexture = channels[LabPbrPixels.NORMAL].handle; }
    public int spriteCount() { return sprites.size(); }
    public int resourceCount() { int count = 0; for (TextureBatchEntry channel : channels) if (channel.handle != 0) count++; return count; }
    public long updateCount() { return updates; }
    public int skippedCount() { return skipped; }

    void observed(int x, int y, int width, int height) {
        SpriteMaterial sprite = rectangles.get(AtlasPatchQueue.rectangleKey(x, y, width, height));
        if (sprite != null) sprite.snapshot();
    }

    TextureBatchEntry[] batch(TextureBatchEntry albedo) {
        int entries = 1;
        for (int channel = 0; channel < channels.length; channel++) {
            if (channels[channel].handle == 0) continue;
            int count = 0;
            for (TexturePatch patch : albedo.patches) {
                SpriteMaterial sprite = rectangles.get(AtlasPatchQueue.rectangleKey(patch.dstX, patch.dstY, patch.width, patch.height));
                if (sprite != null && sprite.has(channel)) count++;
            }
            if (count == 0) { channels[channel].patches = null; continue; }
            TexturePatch[] result = patchArrays[channel][count];
            if (result == null) result = patchArrays[channel][count] = new TexturePatch[count];
            int index = 0;
            for (TexturePatch patch : albedo.patches) {
                SpriteMaterial sprite = rectangles.get(AtlasPatchQueue.rectangleKey(patch.dstX, patch.dstY, patch.width, patch.height));
                if (sprite != null && sprite.has(channel)) result[index++] = sprite.patch(channel, patch);
            }
            channels[channel].patches = result; entries++;
        }
        TextureBatchEntry[] result = entryArrays[entries - 1];
        if (result == null) result = entryArrays[entries - 1] = new TextureBatchEntry[entries];
        result[0] = albedo;
        int index = 1;
        for (TextureBatchEntry channel : channels) if (channel.handle != 0 && channel.patches != null) result[index++] = channel;
        return result;
    }
    void published(TextureBatchEntry[] entries) { if (entries.length > 1) updates++; }

    @Override public void close() {
        checkThread();
        if (closed) return;
        Throwable failure = null;
        for (TextureBatchEntry channel : channels) {
            if (channel.handle == 0) continue;
            try { backend.destroy(channel.handle); channel.handle = 0; channel.patches = null; }
            catch (RuntimeException | LinkageError error) {
                if (failure == null) failure = error; else if (failure != error) failure.addSuppressed(error);
            }
        }
        if (failure != null) throw new IllegalStateException("Cannot release material atlases", failure);
        sprites.clear(); rectangles.clear(); sourceBytes = patchBytes = 0;
        for (int i = 0; i < patchArrays.length; i++) for (int j = 0; j < patchArrays[i].length; j++) patchArrays[i][j] = null;
        for (int i = 0; i < entryArrays.length; i++) entryArrays[i] = null;
        closed = true;
    }

    private void omitted(ResourceLocation id, String reason) {
        if (++skipped <= 8) LOGGER.warn("Omitting material sidecar {}: {}", id, reason);
    }
    @SuppressWarnings("unchecked")
    private static Collection<TextureAtlasSprite> uploaded(TextureMap atlas) {
        try { return ((Map<String, TextureAtlasSprite>) UPLOADED.get(atlas)).values(); }
        catch (IllegalAccessException error) { throw new IllegalStateException("Cannot read stitched atlas sprites", error); }
    }
    private void checkThread() {
        if (Thread.currentThread() != thread) throw new IllegalStateException("Material maps require their render thread");
    }
}
