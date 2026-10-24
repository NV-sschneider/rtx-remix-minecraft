/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.cloud;

import com.nvidia.rtxremix.InstanceCategoryBit;
import com.nvidia.rtxremix.MaterialInfo;
import com.nvidia.rtxremix.MeshInfo;
import com.nvidia.rtxremix.TextureFromMemoryInfo;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import org.lwjgl.util.vector.Matrix4f;
import static org.junit.Assert.*;

public class CloudSceneTest {
    @Test public void retainsUnchangedMeshWhileFollowingTheCloudTransform() {
        Fake api = new Fake(); Reader reader = new Reader(); CloudScene scene = new CloudScene(api, reader);
        CapturedCloudBatch batch = batch(false, 0);
        scene.sync(Collections.singletonList(batch));
        long geometry = scene.geometryHash(), placement = scene.placementHash();
        assertEquals(1, scene.meshCount()); assertEquals(1, scene.cloudMeshCount());
        batch.transform[3] += 5;
        scene.sync(Collections.singletonList(batch));
        assertEquals(geometry, scene.geometryHash()); assertNotEquals(placement, scene.placementHash());
        assertEquals(1, scene.meshUploadCount()); assertEquals(2, api.draws); assertEquals(15, api.x, 0);
        assertEquals(2, scene.cloudTriangles()); assertEquals(0, api.category);
        assertEquals(0, reader.reads);
        batch.hash++;
        scene.sync(Collections.singletonList(batch)); assertEquals(2, scene.meshUploadCount());
        assertEquals(1, api.meshes.size());
        scene.sync(Collections.<CapturedCloudBatch>emptyList());
        assertTrue(api.meshes.isEmpty()); assertTrue(api.blends.isEmpty()); assertEquals(0, scene.cloudTriangles());
        assertEquals(0, scene.meshCount()); assertEquals(0, scene.geometryHash()); assertEquals(0, scene.placementHash());
        scene.close(); scene.close(); assertEquals(0, scene.resourceCount()); assertEquals(0, scene.retainedBytes());
    }

    @Test public void customSkyUsesSkyCategoryAndEmissiveMaterial() {
        Fake api = new Fake(); CloudScene scene = new CloudScene(api, new Reader());
        scene.sync(Collections.singletonList(batch(true, 0)));
        assertEquals(InstanceCategoryBit.SKY, api.category); assertEquals(2, scene.customSkyTriangles());
        assertEquals(1, scene.meshCount()); assertEquals(0, scene.cloudMeshCount());
        assertEquals(1, api.emission, 0); scene.close();
    }

    @Test public void snapshotsAtMostOneNewTexturePerFrameAndKeepsCachedTextures() {
        Fake api = new Fake(); Reader reader = new Reader(); CloudScene scene = new CloudScene(api, reader);
        CapturedCloudBatch cloud = batch(false, 1), sky = batch(true, 2);
        scene.sync(Arrays.asList(cloud, sky));
        assertEquals(1, reader.reads); assertEquals(1, scene.batchCount()); assertEquals(1, scene.skippedDrawCount());
        scene.sync(Arrays.asList(cloud, sky));
        assertEquals(2, reader.reads); assertEquals(2, scene.batchCount()); assertEquals(2, scene.textureSnapshotCount());
        scene.sync(Arrays.asList(cloud, sky)); assertEquals(2, reader.reads);
        scene.close(); assertEquals(0, scene.resourceCount()); assertTrue(api.textures.isEmpty());
    }

    @Test public void retriesFailedMeshAndMaterialCleanupWithoutDestroyingDependencies() {
        Fake api = new Fake(); CloudScene scene = new CloudScene(api, new Reader());
        scene.sync(Collections.singletonList(batch(false, 1)));
        api.meshFailure = true;
        try { scene.close(); fail(); } catch (IllegalStateException expected) { }
        assertEquals(1, api.meshes.size()); assertEquals(1, api.materials.size()); assertEquals(1, api.textures.size());
        api.meshFailure = false; api.materialFailure = true;
        try { scene.close(); fail(); } catch (IllegalStateException expected) { }
        assertTrue(api.meshes.isEmpty()); assertTrue(api.blends.isEmpty()); assertEquals(1, api.textures.size());
        api.materialFailure = false; scene.close(); assertEquals(0, scene.resourceCount());
    }

    @Test public void partialCreationStillOwnsTheTextureForCleanup() {
        Fake api = new Fake(); CloudScene scene = new CloudScene(api, new Reader()); api.createFailure = true;
        try { scene.sync(Collections.singletonList(batch(false, 1))); fail(); } catch (IllegalStateException expected) { }
        assertEquals(1, api.textures.size()); assertEquals(1, scene.resourceCount());
        scene.close(); assertEquals(0, scene.resourceCount());
    }

    @Test public void rejectsCrossThreadNativeMutations() throws Exception {
        final CloudScene scene = new CloudScene(new Fake(), new Reader()); final Throwable[] caught = new Throwable[1];
        Thread other = new Thread(() -> { try { scene.close(); } catch (Throwable error) { caught[0] = error; } });
        other.start(); other.join(); assertTrue(caught[0] instanceof IllegalStateException); scene.close();
    }

    private static CapturedCloudBatch batch(boolean sky, int texture) {
        CapturedCloudBatch result = CloudGeometryTest.batch(); result.sky = sky; result.textureId = texture;
        result.textureIdentity = texture == 0 ? null : new Object();
        CloudGeometryTest.decode(CloudGeometryTest.quad(), 4, 7, new Matrix4f(), 100, result); return result;
    }
    private static final class Reader implements CloudScene.TextureReader {
        int reads;
        public boolean read(int id, long limit) { reads++; assertTrue(limit >= 4); return true; }
        public ByteBuffer pixels() { return CapturedCloudBatch.direct(4); }
        public int width() { return 1; } public int height() { return 1; }
        public int wrapU() { return 1; } public int wrapV() { return 0; } public int filter() { return 0; }
    }
    private static final class Fake implements CloudScene.Backend {
        long next = 100;
        final Set<Long> meshes = new HashSet<Long>(), materials = new HashSet<Long>(), textures = new HashSet<Long>(), blends = new HashSet<Long>();
        boolean meshFailure, materialFailure, createFailure;
        int draws, category;
        float x, emission;
        public long createMesh(MeshInfo info) { assertTrue(materials.contains(info.surfaces[0].material)); long handle = next++; meshes.add(handle); return handle; }
        public void destroyMesh(long mesh) { if (meshFailure) throw new IllegalStateException("mesh retry"); assertTrue(meshes.remove(mesh)); }
        public void draw(long mesh, float[] transform, int category, long blend) {
            assertTrue(meshes.contains(mesh)); assertTrue(blends.contains(blend)); draws++; x = transform[3]; this.category = category;
        }
        public long createTexture(TextureFromMemoryInfo info) { long handle = next++; textures.add(handle); return handle; }
        public void destroyTexture(long texture) { assertTrue(textures.remove(texture)); }
        public long createMaterial(MaterialInfo info) {
            if (createFailure) throw new IllegalStateException("create failed");
            assertTrue(info.albedoTexture == 0 || textures.contains(info.albedoTexture));
            emission = info.emissiveIntensity; long handle = next++; materials.add(handle); return handle;
        }
        public void destroyMaterial(long material) { if (materialFailure) throw new UnsatisfiedLinkError("material retry"); assertTrue(meshes.isEmpty()); assertTrue(materials.remove(material)); }
        public long createBlend(long key) { long handle = next++; blends.add(handle); return handle; }
        public void freeBlend(long blend) { assertTrue(blends.remove(blend)); }
    }
}
