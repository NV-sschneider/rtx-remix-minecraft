/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.effect;

import com.nvidia.rtxremix.MaterialInfo;
import com.nvidia.rtxremix.MeshInfo;
import com.nvidia.rtxremix.TextureFromMemoryInfo;
import java.util.Collections;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import org.lwjgl.util.vector.Matrix4f;
import static org.junit.Assert.*;

public class EffectSceneTest {
    @Test public void damageIsAStaticDecalAndDisappearsOnTheFirstEmptyFrame() {
        Damage api = new Damage();
        EffectScene scene = new EffectScene(api);
        CapturedEffectBatch batch = DamageAtlasTest.damage(0, 0, 1, 1);
        scene.sync(Collections.singletonList(batch), 42);
        assertEquals(com.nvidia.rtxremix.InstanceCategoryBit.DECAL_STATIC, api.category);
        assertEquals(1, scene.damageQuads());
        assertEquals(0, scene.particleQuads());
        assertNotEquals(0, scene.damageGeometryHash());
        scene.sync(Collections.<CapturedEffectBatch>emptyList(), 42);
        assertEquals(0, scene.damageQuads());
        assertEquals(0, scene.damageGeometryHash());
        assertTrue(api.meshes.isEmpty());
        scene.close();
        assertEquals(0, scene.resourceCount());
        assertEquals(0, scene.retainedBytes());
        assertTrue(api.textures.isEmpty());
    }

    @Test public void damageOwnsOnlyItsDerivedTextureAndSharesNeitherMaterialNorUvsWithOrdinaryAtlas() {
        Damage api = new Damage(); EffectScene scene = new EffectScene(api);
        CapturedEffectBatch ordinary = batch(), damage = DamageAtlasTest.damage(0, 0, 1, 1);
        damage.textureIdentity = ordinary.textureIdentity;
        scene.sync(Arrays.asList(ordinary, damage), 42);
        assertEquals(2, scene.batchCount()); assertEquals(1, scene.damageQuads()); assertEquals(1, scene.particleQuads());
        assertEquals(2, api.materials.size()); assertEquals(1, api.textures.size()); assertEquals(1, api.loads);
        scene.sync(Arrays.asList(damage, ordinary), 42);
        assertEquals(2, scene.batchCount()); assertEquals(1, api.loads);
        assertEquals(0, damage.vertices.getFloat(24), 0);
        for (int i = 0; i < 121; i++) scene.sync(Collections.<CapturedEffectBatch>emptyList(), 42);
        assertTrue(api.textures.isEmpty());
        scene.sync(Collections.singletonList(damage), 42);
        assertEquals(1, api.loads); assertEquals(1, api.textures.size());
        scene.close(); assertEquals(0, scene.retainedBytes());
    }
    @Test public void replacesTransientMeshesButNeverOwnsTheBorrowedAtlas() {
        Fake api = new Fake();
        EffectScene scene = new EffectScene(api);
        CapturedEffectBatch batch = batch();
        scene.sync(Collections.singletonList(batch), 42);
        assertEquals(1, scene.particleQuads());
        assertEquals(1, scene.particleFrameCount());
        long geometry = scene.particleGeometryHash();
        assertEquals(3, scene.resourceCount());
        scene.sync(Collections.singletonList(batch), 42);
        assertEquals(2, api.draws);
        assertEquals(2, scene.particleFrameCount());
        assertEquals(geometry, scene.particleGeometryHash());
        assertEquals(1, api.meshes.size());
        scene.sync(Collections.<CapturedEffectBatch>emptyList(), 42);
        assertEquals(0, api.meshes.size());
        assertEquals(0, scene.particleQuads());
        assertEquals(0, scene.particleGeometryHash());
        scene.close();
        assertEquals(0, scene.resourceCount());
        assertEquals(0, scene.retainedBytes());
        assertTrue(api.materials.isEmpty()); assertTrue(api.blends.isEmpty());
    }
    @Test public void failedMeshRetirementRetainsItsMaterialForRetry() {
        Fake api = new Fake();
        EffectScene scene = new EffectScene(api);
        scene.sync(Collections.singletonList(batch()), 42);
        api.failDestroy = true;
        try { scene.close(); fail(); } catch (IllegalStateException expected) { }
        assertEquals(1, api.meshes.size());
        assertEquals(1, api.materials.size());
        api.failDestroy = false;
        scene.close();
        assertEquals(0, scene.resourceCount());
    }
    @Test public void refusesAtlasReplacementBeforeCloseWithoutLosingOwnership() {
        Fake api = new Fake();
        EffectScene scene = new EffectScene(api);
        scene.sync(Collections.singletonList(batch()), 42);
        try { scene.sync(Collections.<CapturedEffectBatch>emptyList(), 43); fail(); }
        catch (IllegalStateException expected) { }
        assertEquals(1, api.meshes.size());
        scene.close();
    }

    @Test public void additiveAndOrdinaryBatchesShareOneObservedTextureButUseDistinctMaterials() {
        Owned api = new Owned(); EffectScene scene = new EffectScene(api);
        CapturedEffectBatch ordinary = batch(), additive = batch();
        ordinary.blockAtlas = additive.blockAtlas = false;
        additive.textureIdentity = ordinary.textureIdentity;
        additive.blend = EffectBlend.key(true, 770, 1, 32774, 1, 0, 32774, false, 519, 0);
        scene.sync(Arrays.asList(ordinary, additive), 42);
        assertEquals(1, api.reads); assertEquals(1, api.observed); assertEquals(1, api.textures.size());
        assertEquals(2, api.materials.size()); assertEquals(1, scene.additivePrimitives());
        assertEquals(2, scene.particleQuads());
        api.binding.current = false;
        scene.sync(Arrays.asList(ordinary, additive), 42);
        assertEquals(0, scene.batchCount()); assertTrue(api.meshes.isEmpty());
        assertEquals(1, api.reads); // Pending patches never trigger a stale GPU fallback snapshot.
        api.binding.current = true;
        scene.sync(Arrays.asList(ordinary, additive), 42);
        assertEquals(2, scene.batchCount()); assertEquals(1, api.reads);
        scene.close(); assertEquals(0, scene.resourceCount()); assertEquals(0, scene.retainedBytes());
        assertEquals(1, api.released); assertTrue(api.textures.isEmpty()); assertTrue(api.closed);
    }

    @Test public void deletedTexturesAreRetiredBeforeRecycledIdsGetNewBindings() {
        Owned api = new Owned(); EffectScene scene = new EffectScene(api);
        CapturedEffectBatch old = batch(); old.blockAtlas = false;
        scene.sync(Collections.singletonList(old), 42);
        Binding deleted = api.binding; deleted.current = false; deleted.deleted = true;
        CapturedEffectBatch replacement = batch(); replacement.blockAtlas = false;
        scene.sync(Collections.singletonList(replacement), 42);
        assertEquals(2, api.reads); assertEquals(2, api.observed); assertEquals(1, api.released);
        assertNotSame(deleted, api.binding); assertEquals(1, api.textures.size());
        scene.sync(Collections.<CapturedEffectBatch>emptyList(), 42);
        assertEquals(0, scene.particleQuads());
        for (int i = 0; i < 121; i++) scene.sync(Collections.<CapturedEffectBatch>emptyList(), 42);
        assertTrue(api.textures.isEmpty()); assertEquals(2, api.released);
        scene.close();
    }

    @Test public void triangleMeshesUseThreeVerticesAndThreeIndicesWithoutDegeneratePadding() {
        Fake api = new Fake(); EffectScene scene = new EffectScene(api);
        CapturedEffectBatch batch = batch(); batch.quads = 0; batch.triangles = true;
        new EffectVertexDecoder().append(EffectVertexDecoderTest.quad(1), 4, 6, 28, 12, 20, -1, new Matrix4f(), 2, batch);
        scene.sync(Collections.singletonList(batch), 42);
        assertEquals(6, api.vertices); assertEquals(6, api.indices); assertEquals(2, scene.trianglePrimitives());
        scene.close();
    }

    private static CapturedEffectBatch batch() {
        CapturedEffectBatch batch = new CapturedEffectBatch();
        batch.initialize(new Object(), 1, false, true,
                EffectBlend.key(true, 770, 771, 32774, 1, 0, 32774, true, 516, 1), 10, 20, 30);
        EffectVertexDecoderTest.decode(EffectVertexDecoderTest.quad(1), 4, new Matrix4f(), 1, batch);
        return batch;
    }
    private static class Fake implements EffectScene.Backend {
        long next = 100;
        final Set<Long> meshes = new HashSet<Long>(), materials = new HashSet<Long>(), blends = new HashSet<Long>();
        int draws, category, vertices, indices;
        boolean failDestroy;
        public long createMesh(MeshInfo info) {
            vertices = info.surfaces[0].vertexCount; indices = info.surfaces[0].indexCount;
            long handle = next++; meshes.add(handle); return handle;
        }
        public void destroyMesh(long handle) {
            if (failDestroy) throw new IllegalStateException("retry mesh");
            assertTrue(meshes.remove(handle));
        }
        public void drawInstance(long mesh, float[] transform, int category, int doubleSided, long blend) {
            this.category = category;
            assertTrue(meshes.contains(mesh)); assertTrue(blends.contains(blend)); draws++;
        }
        public long createTextureFromMemory(TextureFromMemoryInfo info) { throw new AssertionError("Borrow the atlas"); }
        public void destroyTexture(long handle) { throw new AssertionError("Borrowed atlas must survive"); }
        public long createMaterial(MaterialInfo info) { assertEquals(42, info.albedoTexture); long handle = next++; materials.add(handle); return handle; }
        public void destroyMaterial(long handle) { assertTrue(meshes.isEmpty()); assertTrue(materials.remove(handle)); }
        public long createBlend(long key) { long handle = next++; blends.add(handle); return handle; }
        public void freeBlend(long handle) { assertTrue(blends.remove(handle)); }
    }
    private static final class Binding { boolean current = true, deleted; }
    private static final class Damage extends Fake {
        final Set<Long> textures = new HashSet<Long>();
        int loads;
        @Override public DamageAtlas damageAtlas() { loads++; return DamageAtlasTest.full(); }
        @Override public long createTextureFromMemory(TextureFromMemoryInfo info) {
            assertEquals(com.nvidia.rtxremix.TextureFormat.RGBA8_SRGB, info.format);
            assertTrue(info.pixelData.isDirect());
            long handle = next++; textures.add(handle); return handle;
        }
        @Override public void destroyTexture(long handle) { assertTrue(meshes.isEmpty()); assertTrue(textures.remove(handle)); }
        @Override public long createMaterial(MaterialInfo info) {
            assertTrue(info.albedoTexture == 42 || textures.contains(info.albedoTexture));
            assertEquals(info.albedoTexture == 42 ? 0 : 1, info.opaqueExt.metallicConstant, 0);
            assertEquals(0, info.emissiveTexture);
            assertEquals(1, info.opaqueExt.roughnessConstant, 0);
            assertEquals(1, info.opaqueExt.useDrawCallAlphaState);
            long handle = next++; materials.add(handle); return handle;
        }
    }
    private static final class Owned extends Fake {
        final Set<Long> textures = new HashSet<Long>();
        int reads, observed, released; boolean closed; Binding binding;
        public boolean readTexture(int id, long available, EffectTextures reader) {
            reads++; reader.width = reader.height = 1;
            reader.pixels.clear(); reader.pixels.putInt(-1).flip(); return available >= 4;
        }
        public long createTextureFromMemory(TextureFromMemoryInfo info) { long handle = next++; textures.add(handle); return handle; }
        public void destroyTexture(long handle) { assertTrue(meshes.isEmpty()); assertTrue(materials.isEmpty()); assertTrue(textures.remove(handle)); }
        public long createMaterial(MaterialInfo info) {
            assertTrue(textures.contains(info.albedoTexture));
            if (info.emissiveTexture != 0) assertEquals(info.albedoTexture, info.emissiveTexture);
            long handle = next++; materials.add(handle); return handle;
        }
        public Object observeTexture(int id, long texture, int width, int height) { observed++; return binding = new Binding(); }
        public boolean textureCurrent(Object value) { return value == null || ((Binding) value).current; }
        public boolean textureDeleted(Object value) { return value != null && ((Binding) value).deleted; }
        public void releaseTexture(Object value) { if (value != null) released++; }
        public void closeTextures() { closed = true; }
    }
}
