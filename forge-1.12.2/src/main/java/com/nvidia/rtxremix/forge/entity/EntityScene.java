/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

import com.nvidia.rtxremix.InstanceInfoBlendEXT;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.RemixBridge;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.entity.EntityLivingBase;

/** Render-thread ownership of bounded rigid entity meshes and their retained scene occurrences. */
public final class EntityScene implements AutoCloseable {
    private static final int MAX_PARTS = 1024;
    private static final int MAX_OWNER_PARTS = 256;
    private static final int MAX_MESHES = 512;
    private static final int MAX_TEXTURES = 64;
    private static final long MAX_MESH_BYTES = 64L * 1024 * 1024;
    static final long MAX_TEXTURE_BYTES = 64L * 1024 * 1024;
    private static final AtomicLong HASHES = new AtomicLong(0x524C454E54490000L);
    private final RemixAPI api;
    private final Thread renderThread = Thread.currentThread();
    private final IdentityHashMap<EntityLivingBase, Owner> owners = new IdentityHashMap<EntityLivingBase, Owner>();
    private final Map<MeshKey, MeshAsset> meshes = new HashMap<MeshKey, MeshAsset>();
    private final List<EntityTextures.Asset> textures = new ArrayList<EntityTextures.Asset>();
    private final EntityMesh geometry = new EntityMesh();
    private final EntityTextures textureReader = new EntityTextures();
    private final MeshKey meshProbe = new MeshKey();
    private long frame;
    private long meshBytes;
    private long textureBytes;
    private int partCount;
    private boolean closed;

    public EntityScene(RemixAPI api) { this.api = api; }

    /** Consumes this frame's mutable capture records synchronously; none are retained. */
    public void sync(List<CapturedEntityPart> parts) {
        checkThread();
        if (closed) throw new IllegalStateException("Entity scene is closed");
        frame++;
        int count = Math.min(parts.size(), MAX_PARTS);
        for (int i = 0; i < count; i++) {
            CapturedEntityPart captured = parts.get(i);
            if (!valid(captured)) continue;
            Owner owner = owners.get(captured.owner);
            Part part = owner == null ? null : owner.parts[captured.occurrence];
            if (part != null) part.requested = frame;
        }
        // Release departed occurrences before admission so native handles also stay under the cap.
        retireParts(true);
        for (int i = 0; i < count; i++) {
            CapturedEntityPart captured = parts.get(i);
            if (!valid(captured)) continue;
            Owner owner = owners.get(captured.owner);
            Part part = owner == null ? null : owner.parts[captured.occurrence];
            if (part != null && part.lastSeen == frame) continue;
            EntityTextures.Asset texture = texture(captured.textureIdentity, captured.textureId);
            if (texture == null) continue;
            MeshAsset mesh = mesh(captured.modelPart, captured.scale, texture);
            if (mesh == null) continue;
            if (owner == null) {
                owner = new Owner(captured.owner.getUniqueID());
                owners.put(captured.owner, owner);
            }
            if (part == null) {
                part = new Part();
                owner.parts[captured.occurrence] = part;
            }
            submit(part, mesh, captured, owner);
            part.lastSeen = frame;
            mesh.lastSeen = frame;
            texture.lastSeen = frame;
        }
        retireParts(false);
        retireUnseenMeshes();
        retireUnseenTextures();
    }

    public int partCount() { checkThread(); return partCount; }

    public boolean contains(UUID ownerId) {
        checkThread();
        for (Owner owner : owners.values()) if (owner.id.equals(ownerId)) return true;
        return false;
    }

    public long transformUpdates(UUID ownerId) {
        checkThread();
        for (Owner owner : owners.values()) if (owner.id.equals(ownerId)) return owner.transformUpdates;
        return 0;
    }

    private static boolean valid(CapturedEntityPart part) {
        if (part.owner == null || part.modelPart == null || part.textureIdentity == null || part.textureId <= 0
                || part.occurrence < 0 || part.occurrence >= MAX_OWNER_PARTS || !Float.isFinite(part.scale)) return false;
        for (float value : part.transform) if (!Float.isFinite(value)) return false;
        return true;
    }

    private EntityTextures.Asset texture(Object identity, int textureId) {
        for (EntityTextures.Asset asset : textures) {
            if (asset.identity == identity && asset.textureId == textureId) return asset;
        }
        if (textures.size() >= MAX_TEXTURES || textureBytes >= MAX_TEXTURE_BYTES) return null;
        EntityTextures.Asset created = textureReader.upload(api, identity, textureId,
                MAX_TEXTURE_BYTES - textureBytes, HASHES.getAndAdd(2));
        if (created != null) {
            textures.add(created);
            textureBytes += created.bytes;
        }
        return created;
    }

    private MeshAsset mesh(ModelRenderer part, float scale, EntityTextures.Asset texture) {
        meshProbe.set(part, scale, texture);
        MeshAsset found = meshes.get(meshProbe);
        if (found != null) return found;
        if (meshes.size() >= MAX_MESHES || !geometry.prepare(part, scale, texture.material)) return null;
        long bytes = geometry.bytes();
        if (bytes > MAX_MESH_BYTES - meshBytes) return null;
        long handle = api.createMesh(geometry.info(HASHES.incrementAndGet()));
        MeshAsset created = new MeshAsset(handle, bytes);
        MeshKey key = new MeshKey();
        key.set(part, scale, texture);
        meshes.put(key, created);
        meshBytes += bytes;
        return created;
    }

    private void submit(Part part, MeshAsset mesh, CapturedEntityPart captured, Owner owner) {
        boolean first = part.instance == 0;
        boolean transformChanged = !Arrays.equals(part.transform, captured.transform);
        boolean blendChanged = first || part.argb != captured.argb || part.alphaBlend != captured.alphaBlend
                || part.alphaCompare != captured.alphaCompare || part.alphaReference != captured.alphaReference;
        if (!first && !transformChanged && !blendChanged && part.mesh == mesh) return;
        long blend = blendChanged ? blendState(captured.argb, captured.alphaBlend,
                captured.alphaCompare, captured.alphaReference).allocNative(0) : part.blend;
        try {
            if (first) {
                part.instance = api.sceneInstanceAdd(mesh.handle, captured.transform, 0, 1, blend);
                partCount++;
            } else {
                api.sceneInstanceUpdate(part.instance, mesh.handle, captured.transform, 0, 1, blend);
                if (transformChanged) owner.transformUpdates++;
            }
        } catch (RuntimeException failure) {
            if (blendChanged) RemixBridge.freeExt(blend);
            throw failure;
        }
        if (blendChanged) {
            if (part.blend != 0) RemixBridge.freeExt(part.blend);
            part.blend = blend;
            part.argb = captured.argb;
            part.alphaBlend = captured.alphaBlend;
            part.alphaCompare = captured.alphaCompare;
            part.alphaReference = captured.alphaReference;
        }
        part.mesh = mesh;
        System.arraycopy(captured.transform, 0, part.transform, 0, 12);
    }

    /** Draw-call alpha state belongs to the occurrence, so cutout and blended shells share a texture. */
    static InstanceInfoBlendEXT blendState(int argb, boolean alphaBlend, int alphaCompare, int alphaReference) {
        InstanceInfoBlendEXT ext = alphaBlend
                ? InstanceInfoBlendEXT.textureTimesVertexColorAlphaBlend(alphaReference)
                : InstanceInfoBlendEXT.textureTimesVertexColor();
        ext.alphaTestEnabled = alphaCompare == InstanceInfoBlendEXT.VK_COMPARE_OP_ALWAYS ? 0 : 1;
        ext.alphaTestCompareOp = alphaCompare;
        ext.alphaTestReferenceValue = (byte) alphaReference;
        ext.textureColorArg2Source = InstanceInfoBlendEXT.RT_TEXTURE_ARG_TFACTOR;
        ext.textureAlphaArg2Source = InstanceInfoBlendEXT.RT_TEXTURE_ARG_TFACTOR;
        ext.tFactor = argb;
        return ext;
    }

    private void retireParts(boolean unrequested) {
        Iterator<Owner> iterator = owners.values().iterator();
        while (iterator.hasNext()) {
            Owner owner = iterator.next();
            boolean live = false;
            for (int i = 0; i < owner.parts.length; i++) {
                Part part = owner.parts[i];
                if (part == null) continue;
                if ((unrequested ? part.requested : part.lastSeen) == frame) { live = true; continue; }
                remove(part);
                owner.parts[i] = null;
            }
            if (!live) iterator.remove();
        }
    }

    private void retireUnseenMeshes() {
        Iterator<MeshAsset> iterator = meshes.values().iterator();
        while (iterator.hasNext()) {
            MeshAsset asset = iterator.next();
            if (asset.lastSeen == frame) continue;
            api.destroyMesh(asset.handle);
            meshBytes -= asset.bytes;
            iterator.remove();
        }
    }

    private void retireUnseenTextures() {
        Iterator<EntityTextures.Asset> iterator = textures.iterator();
        while (iterator.hasNext()) {
            EntityTextures.Asset asset = iterator.next();
            if (asset.lastSeen == frame) continue;
            api.destroyMaterial(asset.material);
            api.destroyTexture(asset.texture);
            textureBytes -= asset.bytes;
            iterator.remove();
        }
    }

    private void remove(Part part) {
        if (part.instance != 0) {
            api.sceneInstanceRemove(part.instance);
            part.instance = 0;
            partCount--;
        }
        if (part.blend != 0) {
            RemixBridge.freeExt(part.blend);
            part.blend = 0;
        }
    }

    @Override public void close() {
        checkThread();
        if (closed) return;
        for (Owner owner : owners.values()) for (Part part : owner.parts) if (part != null) remove(part);
        owners.clear();
        for (MeshAsset asset : meshes.values()) api.destroyMesh(asset.handle);
        meshes.clear();
        for (EntityTextures.Asset asset : textures) {
            api.destroyMaterial(asset.material);
            api.destroyTexture(asset.texture);
        }
        textures.clear();
        meshBytes = textureBytes = 0;
        closed = true;
    }

    private void checkThread() {
        if (Thread.currentThread() != renderThread) {
            throw new IllegalStateException("Entity scene must run on its Minecraft render thread");
        }
    }

    private static final class Owner {
        final UUID id;
        final Part[] parts = new Part[MAX_OWNER_PARTS];
        long transformUpdates;
        Owner(UUID id) { this.id = id; }
    }

    private static final class Part {
        final float[] transform = new float[12];
        MeshAsset mesh;
        long instance;
        long blend;
        long lastSeen;
        long requested;
        int argb;
        boolean alphaBlend;
        int alphaCompare;
        int alphaReference;
    }

    private static final class MeshAsset {
        final long handle;
        final long bytes;
        long lastSeen;
        MeshAsset(long handle, long bytes) { this.handle = handle; this.bytes = bytes; }
    }

    /** The probe alone is mutable; keys stored in meshes are never changed. */
    private static final class MeshKey {
        ModelRenderer part;
        EntityTextures.Asset texture;
        int scale;
        void set(ModelRenderer part, float scale, EntityTextures.Asset texture) {
            this.part = part;
            this.texture = texture;
            this.scale = Float.floatToIntBits(scale);
        }
        @Override public int hashCode() {
            return 31 * (31 * System.identityHashCode(part) + System.identityHashCode(texture)) + scale;
        }
        @Override public boolean equals(Object value) {
            if (!(value instanceof MeshKey)) return false;
            MeshKey other = (MeshKey) value;
            return part == other.part && texture == other.texture && scale == other.scale;
        }
    }
}
