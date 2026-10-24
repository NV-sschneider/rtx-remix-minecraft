/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

import com.nvidia.rtxremix.InstanceInfoBlendEXT;
import com.nvidia.rtxremix.InstanceCategoryBit;
import com.nvidia.rtxremix.MeshInfo;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.Test;
import sun.reflect.ReflectionFactory;
import static org.junit.Assert.*;

public class EntitySceneTest {
    @Test public void bodyAndHandsRetainSeparateInstancesAndRetireIndependently() {
        Fake api = new Fake();
        EntityScene scene = new EntityScene(api);
        List<CapturedEntityPart> parts = parts(2);
        CapturedEntityPart body = parts.get(0), hand = parts.get(1);
        body.categoryFlags = FirstPersonCapture.SHADOW_CATEGORY;
        hand.categoryFlags = InstanceCategoryBit.VIEW_MODEL;
        hand.occurrence = body.occurrence;
        hand.triangleGroup = body.triangleGroup;
        hand.transform[3] = 0.1f;
        scene.sync(parts);
        assertEquals(2, scene.partCount());
        assertEquals(1, api.meshCreates);
        assertEquals(1, scene.categoryCount(body.categoryFlags));
        assertEquals(1, scene.categoryCount(hand.categoryFlags));
        assertTrue(api.categories.containsValue(body.categoryFlags));
        assertTrue(api.categories.containsValue(hand.categoryFlags));
        long bodyInstance = 0;
        for (Map.Entry<Long, Integer> entry : api.categories.entrySet()) if (entry.getValue() == body.categoryFlags) bodyInstance = entry.getKey();
        scene.sync(parts);
        assertEquals("unchanged retained instances are not resubmitted", 2, api.instanceUpdates);
        parts.remove(hand); scene.sync(parts);
        assertEquals(1, scene.partCount());
        assertTrue(api.instances.containsKey(bodyInstance));
        assertEquals(0, scene.categoryCount(InstanceCategoryBit.VIEW_MODEL));
        scene.close(); api.assertEmpty();
    }
    @Test public void nonlivingOwnersRetainIdentityPoseAndRetireWhenAbsent() throws Exception {
        Fake api = new Fake();
        EntityScene scene = new EntityScene(api);
        List<CapturedEntityPart> parts = parts(1);
        // Entity's constructor boots Forge item registries. The ownership test only
        // needs its real type and UUID contract, and never reads simulation fields.
        Entity projectile = (Entity) ReflectionFactory.getReflectionFactory()
                .newConstructorForSerialization(TestProjectile.class, Object.class.getDeclaredConstructor()).newInstance();
        parts.get(0).owner = projectile;
        scene.sync(parts);
        assertTrue(scene.contains(projectile.getUniqueID()));
        assertEquals(1, scene.trianglePartCount(projectile.getUniqueID()));
        parts.get(0).transform[3] = 12;
        scene.sync(parts);
        assertEquals(1, scene.transformUpdates(projectile.getUniqueID()));
        parts.clear(); scene.sync(parts);
        assertFalse(scene.contains(projectile.getUniqueID()));
        assertEquals(0, scene.resourceCount());
        scene.close(); api.assertEmpty();
    }

    private static final class TestProjectile extends Entity {
        private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000123");
        private TestProjectile() { super(null); }
        @Override public UUID getUniqueID() { return ID; }
        protected void entityInit() { }
        protected void readEntityFromNBT(NBTTagCompound tag) { }
        protected void writeEntityToNBT(NBTTagCompound tag) { }
    }
    @Test public void deferredGeometryStillUpdatesEveryExistingPoseAndRetainsDependencies() {
        Fake api = new Fake();
        EntityScene scene = new EntityScene(api);
        List<CapturedEntityPart> parts = parts(9);
        scene.sync(parts);
        scene.sync(parts);
        assertEquals(9, scene.trianglePartCount());
        int before = api.meshCreates;
        for (CapturedEntityPart part : parts) { part.geometryFingerprint++; part.transform[3] = 42; }
        scene.sync(parts);
        assertEquals(8, api.meshCreates - before);
        assertEquals(1, scene.deferredTriangleUploadCount());
        for (float x : api.positions.values()) assertEquals(42, x, 0);
        assertEquals(9, api.instances.size());
        assertEquals(9, api.meshes.size());
        scene.close();
        assertEquals(0, scene.resourceCount());
        api.assertEmpty();
    }

    @Test public void closeRetriesOnlyFailedHandlesAndKeepsTheirDependencies() {
        Fake api = new Fake();
        EntityScene scene = new EntityScene(api);
        scene.sync(parts(2));
        api.failMesh = api.meshes.keySet().iterator().next();
        try { scene.close(); fail(); } catch (IllegalStateException expected) { }
        assertEquals(1, api.meshes.size());
        assertEquals(1, api.materials.size());
        assertEquals(1, api.textures.size());
        assertEquals(3, scene.resourceCount());
        scene.close();
        scene.close();
        api.assertEmpty();
    }

    @Test public void failedOldBlendReleaseKeepsNewInstanceMeshAndBothBlendsOwned() {
        Fake api = new Fake();
        EntityScene scene = new EntityScene(api);
        List<CapturedEntityPart> parts = parts(1);
        scene.sync(parts);
        parts.get(0).geometryFingerprint++;
        parts.get(0).alphaReference = 50;
        api.failBlend = api.blends.iterator().next();
        try { scene.sync(parts); fail(); } catch (IllegalStateException expected) { }
        assertEquals(2, api.meshes.size());
        assertEquals(2, api.blends.size());
        scene.close();
        api.assertEmpty();
    }

    @Test public void partiallyCreatedTextureStaysOwnedWhenMaterialCreationFails() {
        Fake api = new Fake();
        EntityScene scene = new EntityScene(api);
        api.failMaterialCreation = true;
        try { scene.sync(parts(1)); fail(); } catch (IllegalStateException expected) { }
        assertEquals(1, scene.resourceCount());
        scene.close();
        api.assertEmpty();
    }

    private static List<CapturedEntityPart> parts(int count) {
        EntityTriangles arena = new EntityTriangles();
        ByteBuffer source = EntityMesh.direct(3 * 28);
        for (int v = 0; v < 3; v++) {
            source.putFloat(v == 1 ? 1 : 0).putFloat(v == 2 ? 1 : 0).putFloat(0);
            source.putFloat(0).putFloat(0).putInt(-1);
            source.put((byte) 0).put((byte) 0).put((byte) 127).put((byte) 0);
        }
        source.flip();
        assertTrue(arena.append(source, 3, 28, 12, 20, 24));
        List<CapturedEntityPart> parts = new ArrayList<>();
        Object owner = new TileEntity() {}, texture = new Object();
        for (int i = 0; i < count; i++) {
            CapturedEntityPart part = new CapturedEntityPart();
            part.owner = owner; part.triangles = arena; part.triangleGroup = new Object();
            part.vertexCount = 3; part.geometryFingerprint = arena.fingerprint();
            part.occurrence = i; part.scale = 1; part.textureIdentity = texture; part.textureId = 1;
            part.argb = -1; part.alphaCompare = 7;
            part.transform[0] = part.transform[5] = part.transform[10] = 1;
            parts.add(part);
        }
        return parts;
    }

    private static final class Fake implements EntityScene.Backend {
        final Map<Long, Long> meshes = new HashMap<>(), instances = new HashMap<>(), materials = new HashMap<>();
        final Map<Long, Float> positions = new HashMap<>();
        final Map<Long, Integer> categories = new HashMap<>();
        final Set<Long> textures = new HashSet<>(), blends = new HashSet<>();
        long next = 1, failMesh, failBlend;
        int meshCreates, instanceUpdates;
        boolean failMaterialCreation;
        public long createMesh(MeshInfo mesh) {
            long handle = next++; meshes.put(handle, mesh.surfaces[0].material); meshCreates++; return handle;
        }
        public void destroyMesh(long handle) {
            assertFalse(instances.containsValue(handle));
            if (handle == failMesh) { failMesh = 0; throw new IllegalStateException("mesh release"); }
            assertNotNull(meshes.remove(handle));
        }
        public long createInstance(long mesh, float[] transform, int category, long blend) {
            long handle = next++; updateInstance(handle, mesh, transform, category, blend); return handle;
        }
        public void updateInstance(long instance, long mesh, float[] transform, int category, long blend) {
            assertTrue(meshes.containsKey(mesh)); assertTrue(blends.contains(blend));
            instances.put(instance, mesh); positions.put(instance, transform[3]);
            categories.put(instance, category); instanceUpdates++;
        }
        public void removeInstance(long handle) { assertNotNull(instances.remove(handle)); positions.remove(handle); categories.remove(handle); }
        public long createBlend(InstanceInfoBlendEXT blend) { long handle = next++; blends.add(handle); return handle; }
        public void freeBlend(long handle) {
            if (handle == failBlend) { failBlend = 0; throw new IllegalStateException("blend release"); }
            assertTrue(blends.remove(handle));
        }
        public boolean uploadTexture(EntityTextures.Asset asset, long budget, long hash) {
            asset.texture = next++; asset.bytes = 4; textures.add(asset.texture);
            if (failMaterialCreation) throw new IllegalStateException("material creation");
            asset.material = next++; materials.put(asset.material, asset.texture); return true;
        }
        public void destroyMaterial(long handle) {
            assertFalse(meshes.containsValue(handle)); assertNotNull(materials.remove(handle));
        }
        public void destroyTexture(long handle) { assertFalse(materials.containsValue(handle)); assertTrue(textures.remove(handle)); }
        void assertEmpty() {
            assertTrue(meshes.isEmpty()); assertTrue(instances.isEmpty()); assertTrue(materials.isEmpty());
            assertTrue(textures.isEmpty()); assertTrue(blends.isEmpty());
        }
    }
}
