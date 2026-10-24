/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.cloud;

import com.nvidia.rtxremix.Float3D;
import com.nvidia.rtxremix.HardcodedVertex;
import com.nvidia.rtxremix.InstanceCategoryBit;
import com.nvidia.rtxremix.MaterialInfo;
import com.nvidia.rtxremix.MaterialInfoOpaqueEXT;
import com.nvidia.rtxremix.MeshInfo;
import com.nvidia.rtxremix.MeshInfoSurfaceTriangles;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.RemixBridge;
import com.nvidia.rtxremix.TextureFromMemoryInfo;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Owns bounded cloud/custom-sky assets on the render thread; unchanged meshes survive across frames. */
public final class CloudScene implements AutoCloseable {
    private static final int MAX_TEXTURES = 16;
    private static final long MAX_TEXTURE_BYTES = 32L * 1024 * 1024;
    private static final AtomicLong HASHES = new AtomicLong(0x524c434c4f550000L);
    interface Backend {
        long createMesh(MeshInfo info);
        void destroyMesh(long mesh);
        void draw(long mesh, float[] transform, int category, long blend);
        long createTexture(TextureFromMemoryInfo info);
        void destroyTexture(long texture);
        long createMaterial(MaterialInfo info);
        void destroyMaterial(long material);
        long createBlend(long key);
        void freeBlend(long blend);
    }
    interface TextureReader {
        boolean read(int id, long limit);
        ByteBuffer pixels();
        int width();
        int height();
        int wrapU();
        int wrapV();
        int filter();
    }

    private final Backend api;
    private final TextureReader reader;
    private final Thread thread = Thread.currentThread();
    private final Draw[] draws = new Draw[CloudCaptureHooks.MAX_BATCHES];
    private final Texture[] textures = new Texture[MAX_TEXTURES];
    private final ByteBuffer indices = CapturedCloudBatch.direct(CapturedCloudBatch.MAX_VERTICES * 4);
    private final MeshInfoSurfaceTriangles surface = new MeshInfoSurfaceTriangles();
    private final MeshInfo meshInfo = new MeshInfo();
    private long textureBytes, meshBytes, uploads, snapshots, skipped, geometryHash, placementHash;
    private int clouds, skies, batches, newTextures;
    private boolean closing, customSky;

    public CloudScene(RemixAPI api) { this(backend(api), new CloudTextures()); }

    CloudScene(Backend api, TextureReader reader) {
        if (api == null || reader == null) throw new IllegalArgumentException("Clouds require a renderer and texture reader");
        this.api = api; this.reader = reader;
        for (int i = 0; i < CapturedCloudBatch.MAX_VERTICES; i += 3) indices.putInt(i).putInt(i + 2).putInt(i + 1);
        indices.flip(); surface.indexBuffer = indices;
        meshInfo.surfaces = new MeshInfoSurfaceTriangles[] {surface};
        for (int i = 0; i < draws.length; i++) draws[i] = new Draw();
    }

    /** Consume after capture, before submit; the CPU payload is not retained. */
    public void sync(List<CapturedCloudBatch> captured) {
        checkThread();
        if (closing) throw new IllegalStateException("Cloud scene is closing");
        clouds = skies = batches = newTextures = 0;
        geometryHash = placementHash = 0;
        skipped = CloudCaptureHooks.skippedDrawCount();
        customSky = CloudCaptureHooks.customSkyHandlerActive();
        int vertices = 0;
        for (int i = 0; i < draws.length; i++) {
            Draw draw = draws[i];
            if (i >= captured.size()) { retire(draw); continue; }
            CapturedCloudBatch batch = captured.get(i);
            if (batch.count <= 0 || batch.count % 3 != 0 || batch.count > CloudCaptureHooks.MAX_VERTICES - vertices) {
                retire(draw); skipped++; continue;
            }
            vertices += batch.count;
            Texture texture = texture(batch);
            if (texture == null) { retire(draw); skipped++; continue; }
            if (draw.mesh != 0 && (draw.hash != batch.hash || draw.count != batch.count || draw.texture != texture)) retireMesh(draw);
            if (draw.blend != 0 && draw.blendKey != batch.blend) { api.freeBlend(draw.blend); draw.blend = 0; }
            if (draw.blend == 0) { draw.blend = api.createBlend(batch.blend); draw.blendKey = batch.blend; }
            if (draw.mesh == 0) {
                batch.vertices.limit(batch.count * HardcodedVertex.STRIDE).position(0);
                indices.limit(batch.count * 4).position(0);
                surface.vertexBuffer = batch.vertices; surface.vertexCount = surface.indexCount = batch.count;
                surface.material = texture.material; surface.flags = 0;
                meshInfo.hash = HASHES.incrementAndGet();
                draw.mesh = api.createMesh(meshInfo);
                draw.texture = texture; draw.hash = batch.hash; draw.count = batch.count;
                draw.bytes = (long) batch.count * (HardcodedVertex.STRIDE + 4); meshBytes += draw.bytes; uploads++;
            }
            // SKY draws are composited into the runtime sky; clouds stay world geometry with shadows.
            api.draw(draw.mesh, batch.transform, batch.sky ? InstanceCategoryBit.SKY : 0, draw.blend);
            if (batch.sky) skies += batch.count / 3;
            else {
                clouds += batch.count / 3;
                geometryHash = CapturedCloudBatch.mix(geometryHash, batch.hash);
                for (float value : batch.transform) placementHash = CapturedCloudBatch.mix(placementHash, Float.floatToIntBits(value));
            }
            batches++;
        }
        if (captured.size() > draws.length) skipped += captured.size() - draws.length;
    }

    private Texture texture(CapturedCloudBatch batch) {
        int free = -1;
        for (int i = 0; i < textures.length; i++) {
            Texture existing = textures[i];
            if (existing == null) { if (free < 0) free = i; continue; }
            if (existing.identity == batch.textureIdentity && existing.glId == batch.textureId && existing.sky == batch.sky) return existing;
        }
        if (free < 0) return null;
        if (batch.textureId != 0 && (newTextures != 0 || !reader.read(batch.textureId, MAX_TEXTURE_BYTES - textureBytes))) return null;
        Texture texture = new Texture(batch.textureIdentity, batch.textureId, batch.sky);
        textures[free] = texture;
        if (batch.textureId != 0) {
            texture.handle = api.createTexture(TextureFromMemoryInfo.builder().hash(HASHES.incrementAndGet())
                    .pixelData(reader.pixels()).size(reader.width(), reader.height()).build());
            texture.bytes = (long) reader.width() * reader.height() * 4;
            textureBytes += texture.bytes; newTextures++; snapshots++;
        }
        MaterialInfo material = new MaterialInfo(HASHES.incrementAndGet());
        material.albedoTexture = texture.handle;
        material.filterMode = (byte) (batch.textureId == 0 ? 0 : reader.filter());
        material.wrapModeU = (byte) (batch.textureId == 0 ? MaterialInfo.WRAP_MODE_REPEAT : reader.wrapU());
        material.wrapModeV = (byte) (batch.textureId == 0 ? MaterialInfo.WRAP_MODE_REPEAT : reader.wrapV());
        material.opaqueExt = MaterialInfoOpaqueEXT.builder().albedoConstant(1, 1, 1)
                .roughnessConstant(1).metallicConstant(0).useDrawCallAlphaState(true).build();
        if (batch.sky) {
            material.emissiveTexture = texture.handle;
            material.emissiveColorConstant = new Float3D(1, 1, 1);
            material.emissiveIntensity = 1;
        }
        texture.material = api.createMaterial(material);
        return texture;
    }

    private void retireMesh(Draw draw) {
        if (draw.mesh == 0) return;
        api.destroyMesh(draw.mesh); draw.mesh = 0; draw.texture = null; draw.count = 0;
        meshBytes -= draw.bytes; draw.bytes = 0;
    }

    private void retire(Draw draw) {
        retireMesh(draw);
        if (draw.blend != 0) { api.freeBlend(draw.blend); draw.blend = 0; }
    }

    public int cloudTriangles() { return clouds; }
    public int meshCount() {
        int count = 0;
        for (Draw draw : draws) if (draw.mesh != 0) count++;
        return count;
    }
    public int cloudMeshCount() {
        int count = 0;
        for (Draw draw : draws) if (draw.mesh != 0 && !draw.texture.sky) count++;
        return count;
    }
    public long geometryHash() { return geometryHash; }
    public long placementHash() { return placementHash; }
    public int customSkyTriangles() { return skies; }
    public boolean customSkyHandlerActive() { return customSky; }
    /** True when a provider replaced vanilla sky but no supported native sky draw was captured. */
    public boolean customSkyUncaptured() { return customSky && skies == 0; }
    public int batchCount() { return batches; }
    public long meshUploadCount() { return uploads; }
    public long textureSnapshotCount() { return snapshots; }
    public long skippedDrawCount() { return skipped; }
    public long retainedBytes() { return textureBytes + meshBytes; }
    public int resourceCount() {
        int count = 0;
        for (Draw draw : draws) { if (draw.mesh != 0) count++; if (draw.blend != 0) count++; }
        for (Texture texture : textures) if (texture != null) { if (texture.handle != 0) count++; if (texture.material != 0) count++; }
        return count;
    }

    @Override public void close() {
        checkThread(); closing = true;
        RuntimeException failure = null;
        for (Draw draw : draws) {
            try { retire(draw); }
            catch (RuntimeException | LinkageError error) { failure = append(failure, error); }
        }
        for (int i = 0; i < textures.length; i++) {
            Texture texture = textures[i];
            if (texture == null || referenced(texture)) continue;
            if (texture.material != 0) {
                try { api.destroyMaterial(texture.material); texture.material = 0; }
                catch (RuntimeException | LinkageError error) { failure = append(failure, error); }
            }
            if (texture.material == 0 && texture.handle != 0) {
                try { api.destroyTexture(texture.handle); texture.handle = 0; textureBytes -= texture.bytes; texture.bytes = 0; }
                catch (RuntimeException | LinkageError error) { failure = append(failure, error); }
            }
            if (texture.material == 0 && texture.handle == 0) textures[i] = null;
        }
        if (failure != null) throw failure;
        clouds = skies = batches = 0; customSky = false; geometryHash = placementHash = 0;
    }

    private boolean referenced(Texture texture) {
        for (Draw draw : draws) if (draw.mesh != 0 && draw.texture == texture) return true;
        return false;
    }

    private void checkThread() {
        if (Thread.currentThread() != thread) throw new IllegalStateException("Cloud resources require their render thread");
    }

    private static RuntimeException append(RuntimeException first, Throwable next) {
        RuntimeException error = next instanceof RuntimeException ? (RuntimeException) next : new IllegalStateException("Cloud cleanup failed", next);
        if (first == null) return error;
        first.addSuppressed(error); return first;
    }

    private static Backend backend(final RemixAPI api) {
        if (api == null) throw new IllegalArgumentException("Clouds require Remix");
        return new Backend() {
            public long createMesh(MeshInfo info) { return api.createMesh(info); }
            public void destroyMesh(long mesh) { api.destroyMesh(mesh); }
            public void draw(long mesh, float[] transform, int category, long blend) { api.drawInstance(mesh, transform, category, 1, blend); }
            public long createTexture(TextureFromMemoryInfo info) { return api.createTextureFromMemory(info); }
            public void destroyTexture(long texture) { api.destroyTexture(texture); }
            public long createMaterial(MaterialInfo info) { return api.createMaterial(info); }
            public void destroyMaterial(long material) { api.destroyMaterial(material); }
            public long createBlend(long key) { return CloudBlend.extension(key).allocNative(0); }
            public void freeBlend(long blend) { RemixBridge.freeExt(blend); }
        };
    }

    private static final class Draw { long mesh, blend, blendKey, hash, bytes; int count; Texture texture; }
    private static final class Texture {
        final Object identity;
        final int glId;
        final boolean sky;
        long handle, material, bytes;
        Texture(Object identity, int glId, boolean sky) { this.identity = identity; this.glId = glId; this.sky = sky; }
    }
}
