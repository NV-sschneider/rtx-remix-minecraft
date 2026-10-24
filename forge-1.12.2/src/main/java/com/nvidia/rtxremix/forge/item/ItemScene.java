/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.item;

import com.nvidia.rtxremix.InstanceInfoBlendEXT;
import com.nvidia.rtxremix.MaterialInfo;
import com.nvidia.rtxremix.MaterialInfoOpaqueEXT;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.RemixBridge;
import com.nvidia.rtxremix.forge.entity.CaptureOwnerKey;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Retained dropped and held items borrowing the terrain's live animated atlas. */
public final class ItemScene implements AutoCloseable {
    private static final int MAX_MESHES = 256;
    private static final long MAX_MESH_BYTES = 32L * 1024 * 1024;
    private static final AtomicLong HASHES = new AtomicLong(0x524C4954454D0000L);
    private final RemixAPI api;
    private final Thread renderThread = Thread.currentThread();
    private final Map<CaptureOwnerKey, Owner> owners = new HashMap<>();
    private final CaptureOwnerKey ownerProbe = new CaptureOwnerKey();
    private final Map<MeshKey, Mesh> meshes = new HashMap<>();
    private final MeshKey probe = new MeshKey();
    private long frame, atlas, material, meshBytes;
    private int count;
    private boolean closed;

    public ItemScene(RemixAPI api) { this.api = api; }

    /** Caller must close this scene before releasing or replacing the borrowed atlas. */
    public void sync(List<CapturedItem> captured, long atlasTexture) {
        checkThread();
        if (closed) throw new IllegalStateException("Item scene is closed");
        if (atlasTexture == 0) throw new IllegalArgumentException("Item capture requires a live block atlas");
        if (atlas != 0 && atlas != atlasTexture) throw new IllegalStateException("Close item scene before replacing its atlas");
        if (material == 0 && !captured.isEmpty()) {
            MaterialInfo info = new MaterialInfo(HASHES.incrementAndGet());
            info.albedoTexture = atlasTexture;
            info.filterMode = 0;
            info.wrapModeU = MaterialInfo.WRAP_MODE_CLAMP;
            info.wrapModeV = MaterialInfo.WRAP_MODE_CLAMP;
            info.opaqueExt = MaterialInfoOpaqueEXT.builder().albedoConstant(1, 1, 1)
                    .roughnessConstant(1).metallicConstant(0).useDrawCallAlphaState(true).build();
            material = api.createMaterial(info);
            atlas = atlasTexture;
        }
        frame++;
        int limit = Math.min(ItemCaptureHooks.MAX_PARTS, captured.size());
        // Mark requested assets first, so leaving items free capacity before new admission.
        for (int i = 0; i < limit; i++) {
            CapturedItem item = captured.get(i);
            if (!valid(item)) continue;
            Owner owner = owners.get(ownerProbe.set(item.owner, item.categoryFlags));
            if (owner != null && owner.parts[item.occurrence] != null) owner.parts[item.occurrence].requested = frame;
            probe.set(item);
            Mesh mesh = meshes.get(probe);
            if (mesh != null) mesh.requested = frame;
        }
        retireParts(true);
        // Requested old instances may still use a mesh whose geometry changed this frame.
        // Keep those assets alive until all replacements are submitted below.
        retireMeshes(true);
        for (int i = 0; i < limit; i++) {
            CapturedItem item = captured.get(i);
            if (!valid(item)) continue;
            Mesh mesh = mesh(item);
            if (mesh == null) continue;
            Owner owner = owners.get(ownerProbe.set(item.owner, item.categoryFlags));
            if (owner == null) {
                owner = new Owner(item.owner.getUniqueID());
                owners.put(new CaptureOwnerKey().set(item.owner, item.categoryFlags), owner);
            }
            Part part = owner.parts[item.occurrence];
            if (part == null) { part = new Part(); owner.parts[item.occurrence] = part; }
            if (part.lastSeen == frame) continue;
            submit(part, owner, mesh, item);
            part.lastSeen = frame;
            mesh.lastSeen = frame;
        }
        retireParts(false);
        retireMeshes(false);
    }

    public int count() { checkThread(); return count; }
    public boolean containsOccurrence(Object holder, int category, int occurrence) {
        checkThread();
        if (occurrence < 0 || occurrence >= ItemCaptureHooks.MAX_OWNER_PARTS) return false;
        Owner owner = owners.get(ownerProbe.set(holder, category));
        Part part = owner == null ? null : owner.parts[occurrence];
        return part != null && part.instance != 0 && part.lastSeen == frame;
    }
    public int categoryCount(int category) {
        checkThread();
        int count = 0;
        for (Map.Entry<CaptureOwnerKey, Owner> entry : owners.entrySet()) {
            if (entry.getKey().category() != category) continue;
            for (Part part : entry.getValue().parts) if (part != null && part.instance != 0) count++;
        }
        return count;
    }
    public int meshCount() { checkThread(); return meshes.size(); }
    public long meshBytes() { checkThread(); return meshBytes; }
    public int resourceCount() {
        checkThread();
        int resources = meshes.size() + (material == 0 ? 0 : 1);
        for (Owner owner : owners.values()) for (Part part : owner.parts) if (part != null) {
            if (part.instance != 0) resources++;
            if (part.blend != 0) resources++;
        }
        return resources;
    }
    public boolean contains(UUID id) { checkThread(); for (Owner owner : owners.values()) if (id.equals(owner.id)) return true; return false; }
    public long transformUpdates(UUID id) { checkThread(); for (Owner owner : owners.values()) if (id.equals(owner.id)) return owner.updates; return 0; }

    private static boolean valid(CapturedItem item) {
        if (item.owner == null || item.model == null || item.stack == null || item.geometry == null || item.vertexCount == 0
                || item.occurrence < 0 || item.occurrence >= ItemCaptureHooks.MAX_OWNER_PARTS) return false;
        for (float value : item.transform) if (!Float.isFinite(value)) return false;
        return true;
    }
    private Mesh mesh(CapturedItem item) {
        probe.set(item);
        Mesh existing = meshes.get(probe);
        if (existing != null) return existing;
        long bytes = ItemGeometry.bytes(item.vertexCount);
        if (meshes.size() >= MAX_MESHES || bytes > MAX_MESH_BYTES - meshBytes) return null;
        long handle = api.createMesh(item.geometry.info(item.firstVertex, item.vertexCount, material, HASHES.incrementAndGet()));
        Mesh created = new Mesh(handle, bytes);
        MeshKey key = new MeshKey(); key.set(item);
        meshes.put(key, created);
        meshBytes += bytes;
        return created;
    }
    private void submit(Part part, Owner owner, Mesh mesh, CapturedItem item) {
        boolean first = part.instance == 0;
        boolean moved = !Arrays.equals(part.transform, item.transform);
        boolean changedBlend = first || part.alphaBlend != item.alphaBlend
                || part.alphaCompare != item.alphaCompare || part.alphaReference != item.alphaReference;
        if (!first && !moved && !changedBlend && part.mesh == mesh) return;
        long blend = changedBlend ? blend(item).allocNative(0) : part.blend;
        try {
            if (first) { part.instance = api.sceneInstanceAdd(mesh.handle, item.transform, item.categoryFlags, 1, blend); count++; }
            else { api.sceneInstanceUpdate(part.instance, mesh.handle, item.transform, item.categoryFlags, 1, blend); if (moved) owner.updates++; }
        } catch (RuntimeException | LinkageError ex) {
            if (changedBlend) RemixBridge.freeExt(blend);
            throw ex;
        }
        if (changedBlend) {
            if (part.blend != 0) RemixBridge.freeExt(part.blend);
            part.blend = blend;
            part.alphaBlend = item.alphaBlend;
            part.alphaCompare = item.alphaCompare; part.alphaReference = item.alphaReference;
        }
        if (part.mesh != mesh) {
            if (part.mesh != null) part.mesh.references--;
            mesh.references++;
            part.mesh = mesh;
        }
        System.arraycopy(item.transform, 0, part.transform, 0, 12);
    }
    static InstanceInfoBlendEXT blend(CapturedItem item) {
        InstanceInfoBlendEXT ext = item.alphaBlend
                ? InstanceInfoBlendEXT.textureTimesVertexColorAlphaBlend(item.alphaReference)
                : InstanceInfoBlendEXT.textureTimesVertexColor();
        ext.alphaTestEnabled = item.alphaCompare == InstanceInfoBlendEXT.VK_COMPARE_OP_ALWAYS ? 0 : 1;
        ext.alphaTestCompareOp = item.alphaCompare;
        ext.alphaTestReferenceValue = (byte) item.alphaReference;
        // The enabled vertex-color array supersedes GL_CURRENT_COLOR and already contains the item tint.
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
    private void retireMeshes(boolean unrequested) {
        Iterator<Mesh> iterator = meshes.values().iterator();
        while (iterator.hasNext()) {
            Mesh mesh = iterator.next();
            if (mesh.references != 0 || (unrequested ? mesh.requested : mesh.lastSeen) == frame) continue;
            api.destroyMesh(mesh.handle);
            meshBytes -= mesh.bytes;
            iterator.remove();
        }
    }
    private void remove(Part part) {
        if (part.instance != 0) { api.sceneInstanceRemove(part.instance); part.instance = 0; count--; }
        if (part.blend != 0) { RemixBridge.freeExt(part.blend); part.blend = 0; }
        if (part.mesh != null) { part.mesh.references--; part.mesh = null; }
    }

    @Override public void close() {
        checkThread();
        if (closed) return;
        Throwable failure = null;
        for (Owner owner : owners.values()) for (Part part : owner.parts) if (part != null) {
            try { remove(part); } catch (RuntimeException | LinkageError ex) { failure = collect(failure, ex); }
        }
        Iterator<Mesh> iterator = meshes.values().iterator();
        while (iterator.hasNext()) {
            Mesh mesh = iterator.next();
            if (mesh.references != 0) continue;
            try { api.destroyMesh(mesh.handle); meshBytes -= mesh.bytes; iterator.remove(); }
            catch (RuntimeException | LinkageError ex) { failure = collect(failure, ex); }
        }
        if (meshes.isEmpty() && material != 0) {
            try { api.destroyMaterial(material); material = 0; } catch (RuntimeException | LinkageError ex) { failure = collect(failure, ex); }
        }
        if (failure != null) throw new IllegalStateException("Could not release dropped-item scene", failure);
        owners.clear();
        closed = true;
    }
    private static Throwable collect(Throwable previous, Throwable next) { if (previous == null) return next; previous.addSuppressed(next); return previous; }
    private void checkThread() {
        if (Thread.currentThread() != renderThread) throw new IllegalStateException("Item scene must run on its Minecraft render thread");
    }
    private static final class Owner {
        final UUID id;
        final Part[] parts = new Part[ItemCaptureHooks.MAX_OWNER_PARTS];
        long updates;
        Owner(UUID id) { this.id = id; }
    }
    private static final class Part {
        final float[] transform = new float[12];
        Mesh mesh;
        long instance, blend, requested, lastSeen;
        int alphaCompare, alphaReference;
        boolean alphaBlend;
    }
    private static final class Mesh {
        final long handle, bytes;
        int references;
        long requested, lastSeen;
        Mesh(long handle, long bytes) { this.handle = handle; this.bytes = bytes; }
    }
    private static final class MeshKey {
        Object model, stack;
        long fingerprint;
        int vertices;
        void set(CapturedItem item) { model = item.model; stack = item.stack; fingerprint = item.fingerprint; vertices = item.vertexCount; }
        @Override public int hashCode() {
            return 31 * (31 * System.identityHashCode(model) + System.identityHashCode(stack)) + (int) (fingerprint ^ fingerprint >>> 32) + vertices;
        }
        @Override public boolean equals(Object value) {
            if (!(value instanceof MeshKey)) return false;
            MeshKey other = (MeshKey) value;
            return model == other.model && stack == other.stack && fingerprint == other.fingerprint && vertices == other.vertices;
        }
    }
}
