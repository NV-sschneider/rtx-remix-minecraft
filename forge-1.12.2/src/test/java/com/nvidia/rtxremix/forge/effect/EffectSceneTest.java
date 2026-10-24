/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.effect;

import com.nvidia.rtxremix.MaterialInfo;
import com.nvidia.rtxremix.MeshInfo;
import com.nvidia.rtxremix.TextureFromMemoryInfo;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import org.lwjgl.util.vector.Matrix4f;
import static org.junit.Assert.*;

public class EffectSceneTest {
    @Test public void damageIsAStaticDecalAndDisappearsOnTheFirstEmptyFrame() {
        Fake api = new Fake();
        EffectScene scene = new EffectScene(api);
        CapturedEffectBatch batch = batch();
        batch.damage = true;
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

    private static CapturedEffectBatch batch() {
        CapturedEffectBatch batch = new CapturedEffectBatch();
        batch.initialize(new Object(), 1, false, true,
                EffectBlend.key(true, 770, 771, 32774, 1, 0, 32774, true, 516, 1), 10, 20, 30);
        EffectVertexDecoderTest.decode(EffectVertexDecoderTest.quad(1), 4, new Matrix4f(), 1, batch);
        return batch;
    }
    private static final class Fake implements EffectScene.Backend {
        long next = 100;
        final Set<Long> meshes = new HashSet<Long>(), materials = new HashSet<Long>(), blends = new HashSet<Long>();
        int draws, category;
        boolean failDestroy;
        public long createMesh(MeshInfo info) { long handle = next++; meshes.add(handle); return handle; }
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
}
