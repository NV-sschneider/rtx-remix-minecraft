/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

import com.nvidia.rtxremix.InstanceInfoBlendEXT;
import com.nvidia.rtxremix.MeshInfo;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.RemixBridge;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;

/** Render-thread ownership of bounded rigid entity meshes and their retained scene occurrences. */
public final class EntityScene implements AutoCloseable {
    private static final int MAX_PARTS = EntityCaptureHooks.MAX_PARTS;
    private static final int MAX_OWNER_PARTS = 256;
    private static final int MAX_MESHES = 512;
    private static final int MAX_TEXTURES = 64;
    private static final long MAX_MESH_BYTES = 64L * 1024 * 1024;
    static final long MAX_TEXTURE_BYTES = 64L * 1024 * 1024;
    private static final AtomicLong HASHES = new AtomicLong(0x524C454E54490000L);
    interface Backend {
        long createMesh(MeshInfo mesh);
        void destroyMesh(long handle);
        long createInstance(long mesh, float[] transform, int category, long blend);
        void updateInstance(long instance, long mesh, float[] transform, int category, long blend);
        void removeInstance(long handle);
        long createBlend(InstanceInfoBlendEXT blend);
        void freeBlend(long handle);
        boolean uploadTexture(EntityTextures.Asset asset, long budget, long hash);
        void destroyMaterial(long handle);
        void destroyTexture(long handle);
    }
    private final Backend api;
    private final Thread renderThread = Thread.currentThread();
    private final Map<CaptureOwnerKey, Owner> owners = new HashMap<>();
    private final CaptureOwnerKey ownerProbe = new CaptureOwnerKey();
    private final Map<MeshKey, MeshAsset> meshes = new HashMap<MeshKey, MeshAsset>();
    private final List<EntityTextures.Asset> textures = new ArrayList<EntityTextures.Asset>();
    private final EntityMesh geometry = new EntityMesh();
    private final MeshKey meshProbe = new MeshKey();
    private final TriangleUploadBudget triangleBudget = new TriangleUploadBudget();
    private long frame;
    private long meshBytes;
    private long textureBytes;
    private int partCount;
    private int tilePartCount;
    private int trianglePartCount;
    private long triangleUploads;
    private int deferredTriangleUploads;
    private boolean closed;

    public EntityScene(RemixAPI api) { this(backend(api)); }
    EntityScene(Backend api) { this.api = api; }

    /** Consumes this frame's mutable capture records synchronously; none are retained. */
    public void sync(List<CapturedEntityPart> parts) {
        checkThread();
        if (closed) throw new IllegalStateException("Entity scene is closed");
        frame++;
        int count = Math.min(parts.size(), MAX_PARTS);
        int first = triangleBudget.beginFrame(parts, count);
        deferredTriangleUploads = 0;
        for (int i = 0; i < count; i++) {
            CapturedEntityPart captured = parts.get(i);
            if (!valid(captured)) continue;
            Owner owner = owners.get(ownerProbe.set(captured.owner, captured.categoryFlags));
            Part part = owner == null ? null : owner.parts[captured.occurrence];
            if (part != null) part.requested = frame;
        }
        // Release departed occurrences before admission so native handles also stay under the cap.
        retireParts(true);
        retireUnseenMeshes();
        retireUnseenTextures();
        for (int i = 0; i < count; i++) {
            CapturedEntityPart captured = parts.get((first + i) % count);
            if (!valid(captured)) continue;
            Owner owner = owners.get(ownerProbe.set(captured.owner, captured.categoryFlags));
            Part part = owner == null ? null : owner.parts[captured.occurrence];
            if (part != null && part.lastSeen == frame) continue;
            EntityTextures.Asset texture = texture(captured.textureIdentity, captured.textureId);
            if (texture == null) { keep(part, captured, owner); continue; }
            MeshAsset mesh = mesh(captured, texture);
            if (mesh == null) { keep(part, captured, owner); continue; }
            if (owner == null) {
                owner = new Owner(captured.owner instanceof Entity
                        ? ((Entity) captured.owner).getUniqueID() : null);
                owners.put(new CaptureOwnerKey().set(captured.owner, captured.categoryFlags), owner);
            }
            if (part == null) {
                part = new Part();
                owner.parts[captured.occurrence] = part;
            }
            submit(part, mesh, captured, owner);
            part.lastSeen = frame;
        }
        retireParts(false);
        retireUnseenMeshes();
        retireUnseenTextures();
    }

    public int partCount() { checkThread(); return partCount; }
    public int tilePartCount() { checkThread(); return tilePartCount; }
    public int trianglePartCount() { checkThread(); return trianglePartCount; }
    public long triangleUploadCount() { checkThread(); return triangleUploads; }
    public int deferredTriangleUploadCount() { checkThread(); return deferredTriangleUploads; }
    public int categoryCount(int category) {
        checkThread();
        int count = 0;
        for (Map.Entry<CaptureOwnerKey, Owner> entry : owners.entrySet()) {
            if (entry.getKey().category() != category) continue;
            for (Part part : entry.getValue().parts) if (part != null && part.instance != 0) count++;
        }
        return count;
    }

    public int trianglePartCount(UUID ownerId) {
        checkThread();
        for (Owner owner : owners.values()) if (ownerId.equals(owner.id)) {
            int count = 0;
            for (Part part : owner.parts) if (part != null && part.instance != 0 && part.mesh.triangles) count++;
            return count;
        }
        return 0;
    }

    public int resourceCount() {
        checkThread();
        int count = meshes.size();
        for (EntityTextures.Asset texture : textures) {
            if (texture.material != 0) count++;
            if (texture.texture != 0) count++;
        }
        for (Owner owner : owners.values()) for (Part part : owner.parts) if (part != null) {
            if (part.instance != 0) count++;
            if (part.blend != 0) count++;
            if (part.retiredBlend != 0) count++;
        }
        return count;
    }

    public long geometryUpdates(UUID ownerId) {
        checkThread();
        for (Owner owner : owners.values()) if (ownerId.equals(owner.id)) return owner.geometryUpdates;
        return 0;
    }

    public boolean containsTile(BlockPos position) {
        checkThread();
        for (CaptureOwnerKey key : owners.keySet()) {
            if (key.owner instanceof TileEntity && ((TileEntity) key.owner).getPos().equals(position)) return true;
        }
        return false;
    }

    public long tileTransformUpdates(BlockPos position) {
        checkThread();
        for (Map.Entry<CaptureOwnerKey, Owner> entry : owners.entrySet()) {
            if (entry.getKey().owner instanceof TileEntity
                    && ((TileEntity) entry.getKey().owner).getPos().equals(position)) return entry.getValue().transformUpdates;
        }
        return 0;
    }

    public int tileTrianglePartCount(BlockPos position) {
        checkThread();
        int count = 0;
        for (Map.Entry<CaptureOwnerKey, Owner> entry : owners.entrySet()) {
            if (!(entry.getKey().owner instanceof TileEntity)
                    || !((TileEntity) entry.getKey().owner).getPos().equals(position)) continue;
            for (Part part : entry.getValue().parts) if (part != null && part.instance != 0 && part.mesh.triangles) count++;
        }
        return count;
    }

    public long tileGeometryUpdates(BlockPos position) {
        checkThread();
        long count = 0;
        for (Map.Entry<CaptureOwnerKey, Owner> entry : owners.entrySet()) {
            if (entry.getKey().owner instanceof TileEntity
                    && ((TileEntity) entry.getKey().owner).getPos().equals(position)) count += entry.getValue().geometryUpdates;
        }
        return count;
    }

    public boolean contains(UUID ownerId) {
        checkThread();
        for (Owner owner : owners.values()) if (ownerId.equals(owner.id)) return true;
        return false;
    }

    public long transformUpdates(UUID ownerId) {
        checkThread();
        for (Owner owner : owners.values()) if (ownerId.equals(owner.id)) return owner.transformUpdates;
        return 0;
    }

    private static boolean valid(CapturedEntityPart part) {
        if (!(part.owner instanceof Entity || part.owner instanceof TileEntity)
                || part.modelPart == null && (part.triangles == null || part.triangleGroup == null || part.vertexCount <= 0)
                || part.textureIdentity == null || part.textureId <= 0
                || part.occurrence < 0 || part.occurrence >= MAX_OWNER_PARTS || !Float.isFinite(part.scale)) return false;
        for (float value : part.transform) if (!Float.isFinite(value)) return false;
        return true;
    }

    private EntityTextures.Asset texture(Object identity, int textureId) {
        for (EntityTextures.Asset asset : textures) {
            if (asset.identity == identity && asset.textureId == textureId) return asset;
        }
        if (textures.size() >= MAX_TEXTURES || textureBytes >= MAX_TEXTURE_BYTES) return null;
        EntityTextures.Asset created = new EntityTextures.Asset(identity, textureId, 0, 0, 0);
        textures.add(created);
        try {
            if (!api.uploadTexture(created, MAX_TEXTURE_BYTES - textureBytes, HASHES.getAndAdd(2))) {
                textures.remove(created);
                return null;
            }
            return created;
        } finally {
            // The scene owns staged handles even if material creation or GL restoration fails.
            textureBytes += created.bytes;
        }
    }

    private MeshAsset mesh(CapturedEntityPart captured, EntityTextures.Asset texture) {
        meshProbe.set(captured, texture);
        MeshAsset found = meshes.get(meshProbe);
        if (found != null) return found;
        boolean triangles = captured.triangles != null;
        if (meshes.size() >= MAX_MESHES) { if (triangles) deferredTriangleUploads++; return null; }
        if (!triangles && !geometry.prepare(captured.modelPart, captured.scale, texture.material)) return null;
        long bytes = triangles ? EntityTriangles.bytes(captured.vertexCount) : geometry.bytes();
        if (bytes > MAX_MESH_BYTES - meshBytes || triangles && !triangleBudget.admit(bytes)) {
            if (triangles) deferredTriangleUploads++;
            return null;
        }
        long handle = api.createMesh(triangles ? captured.triangles.info(captured.firstVertex, captured.vertexCount,
                texture.material, HASHES.incrementAndGet()) : geometry.info(HASHES.incrementAndGet()));
        MeshAsset created = new MeshAsset(handle, bytes, texture, triangles);
        MeshKey key = new MeshKey();
        key.set(captured, texture);
        meshes.put(key, created);
        meshBytes += bytes;
        texture.references++;
        if (triangles) triangleUploads++;
        return created;
    }

    /** A budgeted replacement keeps its previous native mesh alive until admission succeeds. */
    private void keep(Part part, CapturedEntityPart captured, Owner owner) {
        if (part == null || part.mesh == null) return;
        submit(part, part.mesh, captured, owner);
        part.lastSeen = frame;
    }

    private void submit(Part part, MeshAsset mesh, CapturedEntityPart captured, Owner owner) {
        boolean first = part.instance == 0;
        boolean transformChanged = !Arrays.equals(part.transform, captured.transform);
        boolean blendChanged = first || part.argb != captured.argb || part.alphaBlend != captured.alphaBlend
                || part.alphaCompare != captured.alphaCompare || part.alphaReference != captured.alphaReference
                || part.vertexColors != mesh.triangles;
        if (!first && !transformChanged && !blendChanged && part.mesh == mesh) return;
        long blend = blendChanged ? api.createBlend(blendState(captured.argb, captured.alphaBlend,
                captured.alphaCompare, captured.alphaReference, mesh.triangles)) : part.blend;
        try {
            if (first) {
                part.instance = api.createInstance(mesh.handle, captured.transform, captured.categoryFlags, blend);
                partCount++;
                part.tileEntity = captured.owner instanceof TileEntity;
                if (part.tileEntity) tilePartCount++;
            } else {
                api.updateInstance(part.instance, mesh.handle, captured.transform, captured.categoryFlags, blend);
                if (transformChanged) owner.transformUpdates++;
            }
        } catch (RuntimeException | LinkageError failure) {
            if (blendChanged) {
                part.retiredBlend = blend;
                try { api.freeBlend(blend); part.retiredBlend = 0; }
                catch (RuntimeException | LinkageError cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
        if (blendChanged) {
            part.retiredBlend = part.blend;
            part.blend = blend;
            part.argb = captured.argb;
            part.alphaBlend = captured.alphaBlend;
            part.alphaCompare = captured.alphaCompare;
            part.alphaReference = captured.alphaReference;
            part.vertexColors = mesh.triangles;
        }
        if (part.mesh != mesh) {
            if (part.mesh != null) {
                part.mesh.references--;
                if (part.mesh.triangles) trianglePartCount--;
                owner.geometryUpdates++;
            }
            mesh.references++;
            if (mesh.triangles) trianglePartCount++;
            part.mesh = mesh;
        }
        System.arraycopy(captured.transform, 0, part.transform, 0, 12);
        // Commit the native instance's new dependencies before fallible old-state cleanup.
        if (part.retiredBlend != 0) { api.freeBlend(part.retiredBlend); part.retiredBlend = 0; }
    }

    /** Draw-call alpha state belongs to the occurrence, so cutout and blended shells share a texture. */
    static InstanceInfoBlendEXT blendState(int argb, boolean alphaBlend, int alphaCompare, int alphaReference) {
        return blendState(argb, alphaBlend, alphaCompare, alphaReference, false);
    }

    static InstanceInfoBlendEXT blendState(int argb, boolean alphaBlend, int alphaCompare, int alphaReference, boolean vertexColors) {
        InstanceInfoBlendEXT ext = alphaBlend
                ? InstanceInfoBlendEXT.textureTimesVertexColorAlphaBlend(alphaReference)
                : InstanceInfoBlendEXT.textureTimesVertexColor();
        ext.alphaTestEnabled = alphaCompare == InstanceInfoBlendEXT.VK_COMPARE_OP_ALWAYS ? 0 : 1;
        ext.alphaTestCompareOp = alphaCompare;
        ext.alphaTestReferenceValue = (byte) alphaReference;
        ext.textureColorArg2Source = vertexColors ? InstanceInfoBlendEXT.RT_TEXTURE_ARG_VERTEX_COLOR : InstanceInfoBlendEXT.RT_TEXTURE_ARG_TFACTOR;
        ext.textureAlphaArg2Source = ext.textureColorArg2Source;
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
        Throwable failure = null;
        Iterator<MeshAsset> iterator = meshes.values().iterator();
        while (iterator.hasNext()) {
            MeshAsset asset = iterator.next();
            if (asset.references != 0) continue;
            try {
                api.destroyMesh(asset.handle);
                meshBytes -= asset.bytes;
                asset.texture.references--;
                iterator.remove();
            } catch (RuntimeException | LinkageError error) { failure = collect(failure, error); }
        }
        if (failure != null) throw new IllegalStateException("Cannot release entity meshes", failure);
    }

    private void retireUnseenTextures() {
        Throwable failure = null;
        Iterator<EntityTextures.Asset> iterator = textures.iterator();
        while (iterator.hasNext()) {
            EntityTextures.Asset asset = iterator.next();
            if (asset.references != 0) continue;
            if (asset.material != 0) {
                try { api.destroyMaterial(asset.material); asset.material = 0; }
                catch (RuntimeException | LinkageError error) { failure = collect(failure, error); }
            }
            if (asset.material == 0 && asset.texture != 0) {
                try { api.destroyTexture(asset.texture); asset.texture = 0; textureBytes -= asset.bytes; }
                catch (RuntimeException | LinkageError error) { failure = collect(failure, error); }
            }
            if (asset.material == 0 && asset.texture == 0) iterator.remove();
        }
        if (failure != null) throw new IllegalStateException("Cannot release entity textures", failure);
    }

    private void remove(Part part) {
        if (part.instance != 0) {
            api.removeInstance(part.instance);
            part.instance = 0;
            partCount--;
            if (part.tileEntity) tilePartCount--;
        }
        if (part.blend != 0) {
            api.freeBlend(part.blend);
            part.blend = 0;
        }
        if (part.retiredBlend != 0) {
            api.freeBlend(part.retiredBlend);
            part.retiredBlend = 0;
        }
        if (part.mesh != null) {
            part.mesh.references--;
            if (part.mesh.triangles) trianglePartCount--;
            part.mesh = null;
        }
    }

    @Override public void close() {
        checkThread();
        if (closed) return;
        Throwable failure = null;
        Iterator<Owner> iterator = owners.values().iterator();
        while (iterator.hasNext()) {
            Owner owner = iterator.next();
            boolean pending = false;
            for (int i = 0; i < owner.parts.length; i++) {
                Part part = owner.parts[i];
                if (part == null) continue;
                try { remove(part); owner.parts[i] = null; }
                catch (RuntimeException | LinkageError error) { failure = collect(failure, error); pending = true; }
            }
            if (!pending) iterator.remove();
        }
        try { retireUnseenMeshes(); } catch (RuntimeException | LinkageError error) { failure = collect(failure, error); }
        try { retireUnseenTextures(); } catch (RuntimeException | LinkageError error) { failure = collect(failure, error); }
        if (failure != null) throw new IllegalStateException("Cannot close entity scene", failure);
        closed = true;
    }

    private static Throwable collect(Throwable first, Throwable next) {
        if (first == null) return next;
        first.addSuppressed(next);
        return first;
    }

    private static Backend backend(final RemixAPI remix) {
        if (remix == null) throw new IllegalArgumentException("Entity scene requires Remix");
        return new Backend() {
            private final EntityTextures reader = new EntityTextures();
            public long createMesh(MeshInfo info) { return remix.createMesh(info); }
            public void destroyMesh(long handle) { remix.destroyMesh(handle); }
            public long createInstance(long mesh, float[] transform, int category, long blend) {
                return remix.sceneInstanceAdd(mesh, transform, category, 1, blend);
            }
            public void updateInstance(long instance, long mesh, float[] transform, int category, long blend) {
                remix.sceneInstanceUpdate(instance, mesh, transform, category, 1, blend);
            }
            public void removeInstance(long handle) { remix.sceneInstanceRemove(handle); }
            public long createBlend(InstanceInfoBlendEXT blend) { return blend.allocNative(0); }
            public void freeBlend(long handle) { RemixBridge.freeExt(handle); }
            public boolean uploadTexture(EntityTextures.Asset asset, long budget, long hash) {
                return reader.upload(remix, asset, budget, hash);
            }
            public void destroyMaterial(long handle) { remix.destroyMaterial(handle); }
            public void destroyTexture(long handle) { remix.destroyTexture(handle); }
        };
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
        long geometryUpdates;
        Owner(UUID id) { this.id = id; }
    }

    private static final class Part {
        final float[] transform = new float[12];
        MeshAsset mesh;
        long instance;
        long blend;
        long retiredBlend;
        long lastSeen;
        long requested;
        int argb;
        boolean alphaBlend;
        boolean tileEntity;
        boolean vertexColors;
        int alphaCompare;
        int alphaReference;
    }

    private static final class MeshAsset {
        final long handle;
        final long bytes;
        final EntityTextures.Asset texture;
        final boolean triangles;
        int references;
        MeshAsset(long handle, long bytes, EntityTextures.Asset texture, boolean triangles) {
            this.handle = handle; this.bytes = bytes; this.texture = texture; this.triangles = triangles;
        }
    }

    /** The probe alone is mutable; keys stored in meshes are never changed. */
    static final class MeshKey {
        Object part;
        EntityTextures.Asset texture;
        int scale;
        int displayList;
        long fingerprint;
        int vertexCount;
        void set(CapturedEntityPart captured, EntityTextures.Asset texture) {
            if (captured.triangles == null) set(captured.modelPart, captured.displayList, captured.scale, texture);
            else {
                part = captured.triangleGroup;
                this.texture = texture;
                scale = displayList = 0;
                fingerprint = captured.geometryFingerprint;
                vertexCount = captured.vertexCount;
            }
        }
        void set(ModelRenderer part, int displayList, float scale, EntityTextures.Asset texture) {
            this.part = part;
            this.texture = texture;
            this.scale = Float.floatToIntBits(scale);
            this.displayList = displayList;
            fingerprint = 0;
            vertexCount = 0;
        }
        @Override public int hashCode() {
            int result = 31 * (31 * (31 * System.identityHashCode(part) + System.identityHashCode(texture)) + scale) + displayList;
            return 31 * (31 * result + (int) (fingerprint ^ fingerprint >>> 32)) + vertexCount;
        }
        @Override public boolean equals(Object value) {
            if (!(value instanceof MeshKey)) return false;
            MeshKey other = (MeshKey) value;
            return part == other.part && texture == other.texture && scale == other.scale && displayList == other.displayList
                    && fingerprint == other.fingerprint && vertexCount == other.vertexCount;
        }
    }
}
