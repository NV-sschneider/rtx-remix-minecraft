/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.item;

import com.nvidia.rtxremix.InstanceCategoryBit;
import com.nvidia.rtxremix.CameraInfoParameterizedEXT;
import com.nvidia.rtxremix.InstanceInfoBlendEXT;
import com.nvidia.rtxremix.MaterialInfo;
import com.nvidia.rtxremix.MeshInfo;
import com.nvidia.rtxremix.forge.entity.FirstPersonCapture;
import com.nvidia.rtxremix.forge.texture.SpriteOpacity;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.Test;
import sun.reflect.ReflectionFactory;
import static org.junit.Assert.*;

public class ItemSceneTest {
    @Test public void onlyViewModelMeshesDependOnLocalPoseAndStoredKeysRemainImmutable() {
        prepareCamera();
        CapturedItem item = new CapturedItem(); item.fingerprint = 37; item.vertexCount = 4;
        item.transform[0] = item.transform[5] = item.transform[10] = 1;
        ItemScene.MeshKey world = new ItemScene.MeshKey(), view = new ItemScene.MeshKey(), probe = new ItemScene.MeshKey();
        world.set(item); item.transform[3] = 15000; probe.set(item); assertEquals(world, probe);
        item.categoryFlags = InstanceCategoryBit.VIEW_MODEL; item.transform[3] = 0.02f; view.set(item);
        assertNotEquals(world, view);
        Map<ItemScene.MeshKey, String> meshes = new HashMap<>(); meshes.put(view, "pose");
        probe.set(item); assertEquals("pose", meshes.get(probe));
        item.transform[3] += 0.000004f; probe.set(item); assertNull(meshes.get(probe));
        assertEquals("pose", meshes.get(view));
        item.categoryFlags = 0; probe.set(item); assertEquals(world, probe);
    }

    @Test public void failedOldBlendReleaseKeepsTheNewMeshOwnedUntilCleanup() throws Exception {
        prepareCamera(); Fake api = new Fake(); ItemScene scene = new ItemScene(api);
        List<CapturedItem> items = items(1); scene.sync(items, 73);
        api.failBlend = api.blends.iterator().next();
        items.get(0).transform[3] = 0.01f; items.get(0).alphaReference = 12;
        try { scene.sync(items, 73); fail("injected old blend release failure"); }
        catch (IllegalStateException expected) { assertEquals("blend release", expected.getMessage()); }
        assertEquals(2, api.blends.size()); assertEquals(1, api.instances.size());
        scene.close(); api.assertEmpty();
    }

    @Test public void posedItemsBeyondTheUploadAllowanceConvergeWithoutGrowingRetainedResources() throws Exception {
        prepareCamera(); Fake api = new Fake(); ItemScene scene = new ItemScene(api);
        List<CapturedItem> items = items(9);
        for (int frame = 1; frame <= 24; frame++) {
            for (int i = 0; i < items.size(); i++) items.get(i).transform[3] = i + frame * 0.00001f;
            scene.sync(items, 73); assertTrue(scene.meshCount() <= 9);
            if (frame < 2) continue;
            assertEquals(9, scene.count());
            for (long mesh : api.instances.values()) {
                float position = api.positions.get(mesh);
                assertTrue("animated held items beyond the first eight must progress", position >= (int) position + (frame - 1) * 0.00001f);
            }
        }
        scene.close(); api.assertEmpty();
    }

    @Test public void retainedDroppedItemChangesLightingWhenItsCurrentSourceProofChanges() throws Exception {
        Fake api = new Fake(); ItemScene scene = new ItemScene(api); List<CapturedItem> items = items(1);
        CapturedItem item = items.get(0); item.categoryFlags = 0; item.alphaBlend = true;
        item.alphaCompare = InstanceInfoBlendEXT.VK_COMPARE_OP_GREATER; item.alphaReference = 26;
        item.coverage = SpriteOpacity.OPAQUE; item.spriteBounds = true;
        scene.sync(items, 73); long instance = api.instances.keySet().iterator().next(), mesh = api.instances.get(instance);
        assertTrue(scene.ordinaryLighting(item.owner.getUniqueID())); assertEquals(0, api.currentAlpha(instance));
        // CPU sprite revisions are re-proven by capture, even when every geometry byte is retained.
        item.coverage = SpriteOpacity.UNKNOWN; scene.sync(items, 73);
        assertEquals(mesh, (long) api.instances.get(instance)); assertEquals(1, api.currentAlpha(instance));
        assertFalse(scene.ordinaryLighting(item.owner.getUniqueID()));
        item.coverage = SpriteOpacity.CUTOUT; scene.sync(items, 73);
        assertEquals(0, api.currentAlpha(instance)); assertTrue(scene.ordinaryLighting(item.owner.getUniqueID()));
        item.alphaCompare = InstanceInfoBlendEXT.VK_COMPARE_OP_ALWAYS; scene.sync(items, 73);
        assertEquals(1, api.currentAlpha(instance)); assertFalse(scene.ordinaryLighting(item.owner.getUniqueID()));
        scene.close(); api.assertEmpty();
    }

    private static void prepareCamera() {
        FirstPersonCapture.prepareCamera(CameraInfoParameterizedEXT.builder()
                .forward(0, 0, -1).right(1, 0, 0).up(0, 1, 0).build());
    }

    private static List<CapturedItem> items(int count) throws Exception {
        Entity owner = (Entity) ReflectionFactory.getReflectionFactory()
                .newConstructorForSerialization(TestEntity.class, Object.class.getDeclaredConstructor()).newInstance();
        ItemGeometry geometry = new ItemGeometry(); ByteBuffer source = ItemGeometry.direct(4 * 28);
        for (int i = 0; i < 4; i++) {
            source.putFloat(i == 1 || i == 2 ? 1 : 0).putFloat(i >= 2 ? 1 : 0).putFloat(0);
            source.putFloat(0).putFloat(0).putInt(-1).put((byte) 0).put((byte) 0).put((byte) 127).put((byte) 0);
        }
        source.flip(); assertTrue(geometry.append(source, 0, 28, 12, 20, 24, 0, 0, 1, 1));
        List<CapturedItem> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            CapturedItem item = new CapturedItem(); item.owner = owner; item.model = new Object(); item.stack = new Object();
            item.geometry = geometry; item.vertexCount = 4; item.fingerprint = geometry.fingerprint(0, 4);
            item.categoryFlags = InstanceCategoryBit.VIEW_MODEL; item.occurrence = i; item.alphaCompare = 7;
            item.transform[0] = item.transform[5] = item.transform[10] = 1; result.add(item);
        }
        return result;
    }

    private static final class TestEntity extends Entity {
        private TestEntity() { super(null); }
        public UUID getUniqueID() { return new UUID(1, 2); }
        protected void entityInit() { }
        protected void readEntityFromNBT(NBTTagCompound tag) { }
        protected void writeEntityToNBT(NBTTagCompound tag) { }
    }

    private static final class Fake implements ItemScene.Backend {
        final Map<Long, Long> meshes = new HashMap<>(), instances = new HashMap<>();
        final Map<Long, Float> positions = new HashMap<>();
        final Set<Long> blends = new HashSet<>(), materials = new HashSet<>();
        final Map<Long, Integer> alphaStates = new HashMap<>();
        final Map<Long, Long> instanceBlends = new HashMap<>();
        long next = 1, failBlend;
        public long createMesh(MeshInfo info) {
            long handle = next++; meshes.put(handle, info.surfaces[0].material);
            positions.put(handle, info.surfaces[0].vertexBuffer.getFloat(0)); return handle;
        }
        public void destroyMesh(long handle) { assertFalse(instances.containsValue(handle)); assertNotNull(meshes.remove(handle)); positions.remove(handle); }
        public long createMaterial(MaterialInfo info) { long handle = next++; materials.add(handle); return handle; }
        public void destroyMaterial(long handle) { assertFalse(meshes.containsValue(handle)); assertTrue(materials.remove(handle)); }
        public long add(long mesh, float[] transform, int category, long blend) { long handle = next++; update(handle, mesh, transform, category, blend); return handle; }
        public void update(long instance, long mesh, float[] transform, int category, long blend) {
            assertTrue(meshes.containsKey(mesh)); assertTrue(blends.contains(blend)); instances.put(instance, mesh);
            instanceBlends.put(instance, blend);
        }
        public void remove(long instance) { assertNotNull(instances.remove(instance)); instanceBlends.remove(instance); }
        public long blend(InstanceInfoBlendEXT info) { long handle = next++; blends.add(handle); alphaStates.put(handle, info.alphaBlendEnabled); return handle; }
        public void freeBlend(long handle) {
            if (handle == failBlend) { failBlend = 0; throw new IllegalStateException("blend release"); }
            assertTrue(blends.remove(handle)); alphaStates.remove(handle);
        }
        int currentAlpha(long instance) { return alphaStates.get(instanceBlends.get(instance)); }
        void assertEmpty() { assertTrue(meshes.isEmpty()); assertTrue(instances.isEmpty()); assertTrue(blends.isEmpty()); assertTrue(materials.isEmpty()); assertTrue(alphaStates.isEmpty()); assertTrue(instanceBlends.isEmpty()); }
    }
}
