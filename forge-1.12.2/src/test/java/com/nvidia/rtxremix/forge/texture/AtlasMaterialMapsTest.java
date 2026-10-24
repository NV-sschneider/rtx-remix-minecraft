/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.texture;

import com.nvidia.rtxremix.MaterialInfo;
import com.nvidia.rtxremix.MaterialInfoOpaqueEXT;
import com.nvidia.rtxremix.TextureFromMemoryInfo;
import com.nvidia.rtxremix.TexturePurpose;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.data.AnimationFrame;
import net.minecraft.client.resources.data.AnimationMetadataSection;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import org.junit.Test;
import static org.junit.Assert.*;

public class AtlasMaterialMapsTest {
    @Test public void stitchedMapsKeepDefaultsOutsideTheSpriteAndUseCorrectTexturePurposes() {
        RecordingBackend backend = new RecordingBackend();
        try (AtlasMaterialMaps maps = new AtlasMaterialMaps(backend)) {
            maps.addSprite(material(1, 0xff8080ff, 0xfeffe600));
            maps.upload(albedo(), 2, 1);
            assertEquals(1, maps.spriteCount());
            assertEquals(4, maps.resourceCount());
            assertEquals(Arrays.asList(TexturePurpose.NORMAL_DX, TexturePurpose.DATA, TexturePurpose.DATA, TexturePurpose.COLOR), backend.purposes);
            assertEquals(255, backend.images.get(1)[0] & 255);
            assertEquals(0, backend.images.get(1)[1] & 255);
            assertEquals(0, backend.images.get(2)[0] & 255);
            assertEquals(255, backend.images.get(2)[1] & 255);
            assertEquals(0, backend.images.get(3)[0] & 255);
            assertEquals(0x44, backend.images.get(3)[4] & 255);
            MaterialInfo opaque = MaterialInfo.builder().opaqueExt(MaterialInfoOpaqueEXT.builder().build()).build();
            maps.apply(opaque);
            assertNotEquals(0, opaque.normalTexture);
            assertEquals(1, opaque.opaqueExt.metallicConstant, 0);
            assertEquals(4, opaque.emissiveIntensity, 0);
        }
        assertEquals(4, backend.destroyed.size());
    }

    @Test public void absentSidecarsKeepNoNativeOwnersAndPreserveSimpleMaterials() {
        RecordingBackend backend = new RecordingBackend();
        try (AtlasMaterialMaps maps = new AtlasMaterialMaps(backend)) {
            IResourceManager missing = new IResourceManager() {
                @Override public Set<String> getResourceDomains() { return Collections.emptySet(); }
                @Override public IResource getResource(ResourceLocation location) throws IOException { throw new FileNotFoundException(location.toString()); }
                @Override public List<IResource> getAllResources(ResourceLocation location) throws IOException { throw new FileNotFoundException(); }
            };
            assertNull(maps.load(missing, "textures", new ResourceLocation("minecraft:blocks/stone"), "_n", new Sprite(0)));
            maps.upload(albedo(), 2, 1);
            MaterialInfo opaque = MaterialInfo.builder().opaqueExt(MaterialInfoOpaqueEXT.builder()
                    .roughnessConstant(0.7f).metallicConstant(0).build()).build();
            maps.apply(opaque);
            assertEquals(0.7f, opaque.opaqueExt.roughnessConstant, 0);
            assertEquals(0, opaque.opaqueExt.metallicConstant, 0);
            assertEquals(0, maps.resourceCount());
            assertEquals(0, maps.skippedCount());
        }
    }

    @Test public void partialUploadFailureLeavesEverySuccessfulHandleOwnedForCleanup() {
        RecordingBackend backend = new RecordingBackend();
        backend.failCreate = 3;
        AtlasMaterialMaps maps = new AtlasMaterialMaps(backend);
        maps.addSprite(material(0, 0xff8080ff, 0xfeffe600));
        try { maps.upload(albedo(), 2, 1); fail("Create must fail"); }
        catch (IllegalStateException expected) { assertEquals("create", expected.getMessage()); }
        assertEquals(2, maps.resourceCount());
        maps.close();
        assertEquals(Arrays.asList(1L, 2L), backend.destroyed);
    }

    @Test public void failedTextureDestroyCanRetryWithoutDestroyingSuccessfulHandlesAgain() {
        RecordingBackend backend = new RecordingBackend();
        AtlasMaterialMaps maps = new AtlasMaterialMaps(backend);
        maps.addSprite(material(0, 0xff8080ff, 0xfeffe600));
        maps.upload(albedo(), 2, 1);
        backend.failDestroy = 2;
        try { maps.close(); fail("Destroy must fail"); }
        catch (IllegalStateException expected) { assertEquals("Cannot release material atlases", expected.getMessage()); }
        assertEquals(1, maps.resourceCount());
        assertEquals(Arrays.asList(1L, 3L, 4L), backend.destroyed);
        backend.failDestroy = 0;
        maps.close(); maps.close();
        assertEquals(Arrays.asList(1L, 3L, 4L, 2L), backend.destroyed);
    }

    @Test public void latestObservedFrameAndItsAlbedoPublishInOneBatch() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        Object atlas = new Object();
        Sprite sprite = new Sprite(1);
        sprite.setFramesTextureData(Arrays.asList(new int[][] {{0xff000000}}, new int[][] {{0xffffffff}}));
        ReflectionHelper.findField(TextureAtlasSprite.class, new String[] {"animationMetadata", "field_110982_k"})
                .set(sprite, new AnimationMetadataSection(Arrays.asList(new AnimationFrame(1, 4), new AnimationFrame(0, 4)), 1, 1, 4, true));
        try (AtlasMaterialMaps maps = new AtlasMaterialMaps(backend)) {
            maps.addSprite(new SpriteMaterial(sprite, new MaterialImage(1, 1, 1, new int[] {0xff8080ff}),
                    new MaterialImage(1, 1, 2, new int[] {0x00000000, 0xfeffe600})));
            maps.upload(albedo(), 2, 1);
            final int[] calls = {0};
            try (AtlasAnimations animations = new AtlasAnimations(info -> {
                calls[0]++;
                assertEquals(5, info.entries.length);
                assertEquals(42, info.entries[0].handle);
                assertEquals(0x80, info.entries[0].patches[0].pixelData.get(0) & 255);
                assertEquals(127, info.entries[2].patches[0].pixelData.get(0) & 255);
                assertEquals(127, info.entries[3].patches[0].pixelData.get(0) & 255);
                assertEquals(92, info.entries[4].patches[0].pixelData.get(0) & 255);
            }, atlas, 42, 2, 1)) {
                animations.materialMaps(maps);
                AtlasUploadHooks.beginAtlas(atlas);
                AtlasUploadHooks.uploaded(3553, 0, 1, 0, 1, 1, 32993, 33639, IntBuffer.wrap(new int[] {0xffffffff}));
                sprite.frame(1, 2);
                AtlasUploadHooks.uploaded(3553, 0, 1, 0, 1, 1, 32993, 33639, IntBuffer.wrap(new int[] {0xff804020}));
                AtlasUploadHooks.endAtlas();
                animations.flush(); animations.flush();
                assertEquals(1, calls[0]); assertEquals(1, maps.updateCount());
            }
        }
    }

    private static SpriteMaterial material(int x, int normal, int specular) {
        return new SpriteMaterial(new Sprite(x), new MaterialImage(1, 1, 1, new int[] {normal}),
                new MaterialImage(1, 1, 1, new int[] {specular}));
    }
    private static ByteBuffer albedo() {
        ByteBuffer pixels = ByteBuffer.allocateDirect(8);
        pixels.put(new byte[] {0x11, 0x22, 0x33, (byte) 255, 0x44, 0x55, 0x66, (byte) 255}).flip();
        return pixels;
    }
    private static final class Sprite extends TextureAtlasSprite {
        Sprite(int x) { super("minecraft:blocks/stone"); setIconWidth(1); setIconHeight(1); initSprite(2, 1, x, 0, false); }
        void frame(int frame, int tick) { frameCounter = frame; tickCounter = tick; }
    }
    private static final class RecordingBackend implements AtlasMaterialMaps.Backend {
        final List<byte[]> images = new ArrayList<byte[]>();
        final List<Integer> purposes = new ArrayList<Integer>();
        final List<Long> destroyed = new ArrayList<Long>();
        int creates, failCreate;
        long failDestroy;
        @Override public long create(TextureFromMemoryInfo info) {
            if (++creates == failCreate) throw new IllegalStateException("create");
            assertTrue(info.pixelData.isDirect());
            byte[] pixels = new byte[info.pixelData.remaining()];
            info.pixelData.duplicate().get(pixels);
            images.add(pixels); purposes.add(info.purpose);
            return creates;
        }
        @Override public void destroy(long handle) {
            if (handle == failDestroy) throw new LinkageError("destroy");
            assertFalse(destroyed.contains(handle)); destroyed.add(handle);
        }
    }
}
