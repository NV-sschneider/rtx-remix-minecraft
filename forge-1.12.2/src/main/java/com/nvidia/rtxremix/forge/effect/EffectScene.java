/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.effect;

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

/**
 * Bounded render-thread transient effects. Call sync once before each submit, after the
 * previous frame was submitted. Close before a borrowed terrain atlas is replaced/destroyed.
 * It owns meshes/materials/static texture snapshots, but never destroys the borrowed atlas.
 */
public final class EffectScene implements AutoCloseable {
    private static final int MAX_TEXTURES = 16;
    private static final long MAX_TEXTURE_BYTES = 32L * 1024 * 1024;
    private static final AtomicLong HASHES = new AtomicLong(0x524c454646580000L);
    interface Backend {
        long createMesh(MeshInfo info);
        void destroyMesh(long handle);
        void drawInstance(long mesh, float[] transform, int category, int doubleSided, long blend);
        long createTextureFromMemory(TextureFromMemoryInfo info);
        void destroyTexture(long handle);
        long createMaterial(MaterialInfo info);
        void destroyMaterial(long handle);
        long createBlend(long key);
        void freeBlend(long handle);
    }
    private final Backend api;
    private final Thread renderThread = Thread.currentThread();
    private final Draw[] draws = new Draw[EffectCaptureHooks.MAX_BATCHES];
    private final Texture[] textures = new Texture[MAX_TEXTURES];
    private final EffectTextures reader = new EffectTextures();
    private final ByteBuffer indices = CapturedEffectBatch.direct(CapturedEffectBatch.MAX_QUADS * 6 * 4);
    private final MeshInfoSurfaceTriangles surface = new MeshInfoSurfaceTriangles();
    private final MeshInfo meshInfo = new MeshInfo();
    private long atlas, textureBytes, meshBytes, skippedBatches, skippedQuads;
    private long particleHash, weatherHash, particleFrames, weatherFrames, textureSnapshots;
    private int batches, particles, precipitation, damage, snapshots;
    private long damageHash;
    private boolean closed;

    public EffectScene(RemixAPI api) { this(backend(api)); }

    EffectScene(Backend api) {
        if (api == null) throw new IllegalArgumentException("Effects require Remix");
        this.api = api;
        for (int i = 0; i < CapturedEffectBatch.MAX_QUADS * 4; i += 4) {
            indices.putInt(i).putInt(i + 2).putInt(i + 1).putInt(i).putInt(i + 3).putInt(i + 2);
        }
        indices.flip();
        surface.indexBuffer = indices;
        meshInfo.surfaces = new MeshInfoSurfaceTriangles[] {surface};
        for (int i = 0; i < draws.length; i++) draws[i] = new Draw();
    }

    public void sync(List<CapturedEffectBatch> captured, long blockAtlasTexture) {
        checkThread();
        if (closed) throw new IllegalStateException("Effect scene is closed");
        if (atlas != 0 && atlas != blockAtlasTexture) throw new IllegalStateException("Close effects before replacing the terrain atlas");
        retireMeshes();
        batches = particles = precipitation = damage = snapshots = 0;
        damageHash = 0;
        particleHash = weatherHash = 0;
        skippedBatches = EffectCaptureHooks.skippedBatchCount(); skippedQuads = EffectCaptureHooks.skippedQuadCount();
        int total = 0;
        for (int i = 0; i < captured.size(); i++) {
            CapturedEffectBatch batch = captured.get(i);
            if (batch.quads == 0) continue;
            if (batches == draws.length || batch.quads < 0 || batch.quads > CapturedEffectBatch.MAX_QUADS
                    || batch.quads > EffectCaptureHooks.MAX_QUADS - total) { omit(batch.quads); continue; }
            Texture texture = texture(batch, blockAtlasTexture);
            if (texture == null) { omit(batch.quads); continue; }
            Draw draw = draws[batches];
            if (draw.blend == 0 || draw.blendKey != batch.blend) {
                if (draw.blend != 0) { api.freeBlend(draw.blend); draw.blend = 0; }
                draw.blend = api.createBlend(batch.blend);
                draw.blendKey = batch.blend;
            }
            batch.vertices.limit(batch.quads * 4 * HardcodedVertex.STRIDE).position(0);
            indices.limit(batch.quads * 6 * 4).position(0);
            surface.vertexBuffer = batch.vertices; surface.vertexCount = batch.quads * 4;
            surface.indexCount = batch.quads * 6; surface.material = texture.material;
            surface.flags = batch.weather ? 0 : MeshInfoSurfaceTriangles.TEXCOORD_BOUNDS;
            meshInfo.hash = HASHES.incrementAndGet();
            draw.mesh = api.createMesh(meshInfo);
            draw.texture = texture;
            draw.bytes = (long) batch.quads * (4 * HardcodedVertex.STRIDE + 6 * 4);
            meshBytes += draw.bytes;
            // These are per-frame draws. No retained scene instances can outlive their meshes.
            int category = batch.damage ? InstanceCategoryBit.DECAL_STATIC
                    : batch.blockAtlas ? 0 : InstanceCategoryBit.PARTICLE;
            api.drawInstance(draw.mesh, batch.transform, category, 1, draw.blend);
            batches++; total += batch.quads;
            if (batch.damage) {
                damage += batch.quads;
                damageHash = CapturedEffectBatch.mix(damageHash, batch.contentHash);
            } else if (batch.weather) {
                precipitation += batch.quads;
                weatherHash = CapturedEffectBatch.mix(weatherHash, batch.contentHash);
            } else {
                particles += batch.quads;
                particleHash = CapturedEffectBatch.mix(particleHash, batch.contentHash);
            }
        }
        if (particles != 0) particleFrames++;
        if (precipitation != 0) weatherFrames++;
    }

    private Texture texture(CapturedEffectBatch batch, long blockAtlasTexture) {
        int free = -1;
        for (int i = 0; i < textures.length; i++) {
            Texture texture = textures[i];
            if (texture == null) { if (free < 0) free = i; continue; }
            if (texture.identity == batch.textureIdentity && texture.glId == batch.textureId
                    && texture.weather == batch.weather) return texture;
        }
        if (free < 0 || batch.blockAtlas && blockAtlasTexture == 0) return null;
        // Bound first-use GPU readbacks as well as retained texture memory.
        if (!batch.blockAtlas && (snapshots != 0 || !reader.read(batch.textureId, MAX_TEXTURE_BYTES - textureBytes))) return null;
        Texture texture = new Texture(batch.textureIdentity, batch.textureId, batch.weather);
        textures[free] = texture;
        if (batch.blockAtlas) {
            atlas = blockAtlasTexture;
            texture.handle = blockAtlasTexture;
        } else {
            texture.handle = api.createTextureFromMemory(TextureFromMemoryInfo.builder().hash(HASHES.incrementAndGet())
                    .pixelData(reader.pixels).size(reader.width, reader.height).build());
            texture.owned = true;
            texture.bytes = (long) reader.width * reader.height * 4;
            textureBytes += texture.bytes;
            snapshots++;
            textureSnapshots++;
        }
        MaterialInfo material = new MaterialInfo(HASHES.incrementAndGet());
        material.albedoTexture = texture.handle;
        material.filterMode = 0;
        material.wrapModeU = material.wrapModeV = MaterialInfo.WRAP_MODE_REPEAT;
        material.opaqueExt = MaterialInfoOpaqueEXT.builder().albedoConstant(1, 1, 1)
                .roughnessConstant(1).metallicConstant(0).useDrawCallAlphaState(true).build();
        texture.material = api.createMaterial(material);
        return texture;
    }

    private void retireMeshes() {
        RuntimeException failure = null;
        for (Draw draw : draws) {
            if (draw.mesh == 0) continue;
            try {
                api.destroyMesh(draw.mesh); draw.mesh = 0; draw.texture = null;
                meshBytes -= draw.bytes; draw.bytes = 0;
            } catch (RuntimeException error) { failure = append(failure, error); }
        }
        if (failure != null) throw failure;
    }

    public int batchCount() { return batches; }
    public int particleQuads() { return particles; }
    public int weatherQuads() { return precipitation; }
    public int damageQuads() { return damage; }
    public long damageGeometryHash() { return damageHash; }
    public long skippedBatchCount() { return skippedBatches; }
    public long skippedQuadCount() { return skippedQuads; }
    public long retainedBytes() { return textureBytes + meshBytes; }
    public long particleGeometryHash() { return particleHash; }
    public long weatherGeometryHash() { return weatherHash; }
    public long particleFrameCount() { return particleFrames; }
    public long weatherFrameCount() { return weatherFrames; }
    public long textureSnapshotCount() { return textureSnapshots; }
    public int resourceCount() {
        int result = 0;
        for (Draw draw : draws) { if (draw.mesh != 0) result++; if (draw.blend != 0) result++; }
        for (Texture texture : textures) if (texture != null) {
            if (texture.material != 0) result++;
            if (texture.owned && texture.handle != 0) result++;
        }
        return result;
    }

    @Override public void close() {
        checkThread();
        if (closed) return;
        RuntimeException failure = null;
        try { retireMeshes(); } catch (RuntimeException error) { failure = error; }
        for (Draw draw : draws) {
            if (draw.blend == 0) continue;
            try { api.freeBlend(draw.blend); draw.blend = 0; }
            catch (RuntimeException error) { failure = append(failure, error); }
        }
        for (int i = 0; i < textures.length; i++) {
            Texture texture = textures[i];
            if (texture == null || referenced(texture)) continue;
            if (texture.material != 0) {
                try { api.destroyMaterial(texture.material); texture.material = 0; }
                catch (RuntimeException error) { failure = append(failure, error); }
            }
            if (texture.material == 0 && texture.owned && texture.handle != 0) {
                try {
                    api.destroyTexture(texture.handle); texture.handle = 0;
                    textureBytes -= texture.bytes; texture.bytes = 0;
                } catch (RuntimeException error) { failure = append(failure, error); }
            }
            if (texture.material == 0 && (!texture.owned || texture.handle == 0)) textures[i] = null;
        }
        if (failure != null) throw failure;
        closed = true; atlas = 0; batches = particles = precipitation = damage = 0;
        damageHash = 0;
        particleHash = weatherHash = 0;
    }
    private boolean referenced(Texture texture) {
        for (Draw draw : draws) if (draw.mesh != 0 && draw.texture == texture) return true;
        return false;
    }
    private void omit(int count) { skippedBatches++; skippedQuads += Math.max(0, count); }
    private void checkThread() {
        if (Thread.currentThread() != renderThread) throw new IllegalStateException("Effects require their render thread");
    }
    private static RuntimeException append(RuntimeException first, RuntimeException next) {
        if (first == null) return next;
        first.addSuppressed(next); return first;
    }
    private static Backend backend(final RemixAPI remix) {
        if (remix == null) throw new IllegalArgumentException("Effects require Remix");
        return new Backend() {
            public long createMesh(MeshInfo info) { return remix.createMesh(info); }
            public void destroyMesh(long handle) { remix.destroyMesh(handle); }
            public void drawInstance(long mesh, float[] transform, int category, int doubleSided, long blend) {
                remix.drawInstance(mesh, transform, category, doubleSided, blend);
            }
            public long createTextureFromMemory(TextureFromMemoryInfo info) { return remix.createTextureFromMemory(info); }
            public void destroyTexture(long handle) { remix.destroyTexture(handle); }
            public long createMaterial(MaterialInfo info) { return remix.createMaterial(info); }
            public void destroyMaterial(long handle) { remix.destroyMaterial(handle); }
            public long createBlend(long key) { return EffectBlend.extension(key).allocNative(0); }
            public void freeBlend(long handle) { RemixBridge.freeExt(handle); }
        };
    }
    private static final class Draw { long mesh, blend, blendKey, bytes; Texture texture; }
    private static final class Texture {
        final Object identity;
        final int glId;
        final boolean weather;
        long handle, material, bytes;
        boolean owned;
        Texture(Object identity, int glId, boolean weather) { this.identity = identity; this.glId = glId; this.weather = weather; }
    }
}
