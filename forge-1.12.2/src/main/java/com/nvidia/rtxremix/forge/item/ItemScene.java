/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.item;

import com.nvidia.rtxremix.InstanceInfoBlendEXT;
import com.nvidia.rtxremix.MaterialInfo;
import com.nvidia.rtxremix.MaterialInfoOpaqueEXT;
import com.nvidia.rtxremix.MeshInfo;
import com.nvidia.rtxremix.HardcodedVertex;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.RemixBridge;
import com.nvidia.rtxremix.forge.entity.CaptureOwnerKey;
import com.nvidia.rtxremix.forge.entity.FirstPersonCapture;
import com.nvidia.rtxremix.forge.entity.ViewModelGeometry;
import com.nvidia.rtxremix.forge.entity.ViewModelUploadBudget;
import com.nvidia.rtxremix.forge.texture.SpriteOpacity;
import com.nvidia.rtxremix.forge.RemixForgeMod;
import java.nio.ByteBuffer;
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
    interface Backend {
        long createMesh(MeshInfo info);
        void destroyMesh(long handle);
        long createMaterial(MaterialInfo info);
        void destroyMaterial(long handle);
        long add(long mesh, float[] transform, int category, long blend);
        void update(long instance, long mesh, float[] transform, int category, long blend);
        void remove(long instance);
        long blend(InstanceInfoBlendEXT info);
        void freeBlend(long handle);
    }
    private final Backend api;
    private final Thread renderThread = Thread.currentThread();
    private final Map<CaptureOwnerKey, Owner> owners = new HashMap<>();
    private final CaptureOwnerKey ownerProbe = new CaptureOwnerKey();
    private final Map<MeshKey, Mesh> meshes = new HashMap<>();
    private final MeshKey probe = new MeshKey();
    private final ViewModelGeometry viewGeometry = new ViewModelGeometry();
    private final ViewModelUploadBudget viewBudget = new ViewModelUploadBudget();
    private long frame, atlas, material, meshBytes;
    private int count;
    private int diagnosticUploads;
    private boolean closed;

    public ItemScene(RemixAPI api) { this(backend(api)); }
    ItemScene(Backend api) { this.api = api; }

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
        int views = 0;
        // Mark requested assets first, so leaving items free capacity before new admission.
        for (int i = 0; i < limit; i++) {
            CapturedItem item = captured.get(i);
            if (!valid(item)) continue;
            if (ViewModelGeometry.applies(item.categoryFlags)) views++;
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
        int ordinal = viewBudget.beginFrame(views), first = 0;
        for (int i = 0; i < limit; i++) {
            CapturedItem item = captured.get(i);
            if (valid(item) && ViewModelGeometry.applies(item.categoryFlags) && ordinal-- == 0) { first = i; break; }
        }
        for (int i = 0; i < limit; i++) {
            CapturedItem item = captured.get((first + i) % limit);
            if (!valid(item)) continue;
            Mesh mesh = mesh(item);
            Owner owner = owners.get(ownerProbe.set(item.owner, item.categoryFlags));
            if (mesh == null) {
                Part previous = owner == null ? null : owner.parts[item.occurrence];
                if (previous != null && ViewModelGeometry.applies(item.categoryFlags)) {
                    submit(previous, owner, previous.mesh, item); previous.lastSeen = frame; previous.mesh.lastSeen = frame;
                }
                continue;
            }
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
            if (part.retiredBlend != 0) resources++;
        }
        return resources;
    }
    public boolean contains(UUID id) { checkThread(); for (Owner owner : owners.values()) if (id.equals(owner.id)) return true; return false; }
    /** True only while every submitted part of this item uses ordinary opaque/cutout lighting. */
    public boolean ordinaryLighting(UUID id) {
        checkThread();
        boolean found = false;
        for (Owner owner : owners.values()) if (id.equals(owner.id)) for (Part part : owner.parts) {
            if (part == null || part.instance == 0 || part.lastSeen != frame) continue;
            if (part.alphaBlend) return false;
            found = true;
        }
        return found;
    }
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
        boolean viewModel = ViewModelGeometry.applies(item.categoryFlags);
        if (viewModel && !viewBudget.admit(bytes)) return null;
        MeshInfo source = item.geometry.info(item.firstVertex, item.vertexCount, material, HASHES.incrementAndGet(), item.spriteBounds);
        MeshInfo info = source;
        if (viewModel) info = viewGeometry.prepare(info, probe.pose);
        long handle = api.createMesh(info);
        if (viewModel && Boolean.getBoolean("rlcraftremix.viewModelDiagnostics")) diagnose(item, source, info, handle);
        Mesh created = new Mesh(handle, bytes);
        MeshKey key = new MeshKey(); key.set(item);
        meshes.put(key, created);
        meshBytes += bytes;
        return created;
    }
    private void diagnose(CapturedItem item, MeshInfo source, MeshInfo info, long mesh) {
        if (++diagnosticUploads > 900 || diagnosticUploads != 1 && diagnosticUploads % 30 != 0) return;
        float[] pose = probe.pose;
        double determinant = pose[0] * (pose[5] * pose[10] - pose[6] * pose[9])
                - pose[1] * (pose[4] * pose[10] - pose[6] * pose[8]) + pose[2] * (pose[4] * pose[9] - pose[5] * pose[8]);
        float[] minimum = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
        float[] maximum = {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        ByteBuffer vertices = info.surfaces[0].vertexBuffer;
        int alphaMin = 255, alphaMax = 0;
        for (int at = 0; at < info.surfaces[0].vertexCount; at++) {
            int base = at * HardcodedVertex.STRIDE;
            for (int axis = 0; axis < 3; axis++) {
                float value = vertices.getFloat(base + axis * 4);
                minimum[axis] = Math.min(minimum[axis], value); maximum[axis] = Math.max(maximum[axis], value);
            }
            int alpha = vertices.getInt(base + 32) >>> 24;
            alphaMin = Math.min(alphaMin, alpha); alphaMax = Math.max(alphaMax, alpha);
        }
        RemixForgeMod.LOG.info("RLC_VIEW_MODEL upload={} frame={} mesh={} material={} vertices={} category={} blend={} alpha={}/{} vertexAlpha={}..{} flags={} determinant={} local={} baked={} anchor={} bounds={}..{} {}",
                diagnosticUploads, frame, mesh, material, item.vertexCount, item.categoryFlags, item.alphaBlend,
                item.alphaCompare, item.alphaReference, alphaMin, alphaMax, info.surfaces[0].flags, determinant,
                Arrays.toString(item.transform), Arrays.toString(pose), Arrays.toString(FirstPersonCapture.anchorTransform()),
                Arrays.toString(minimum), Arrays.toString(maximum), FirstPersonCapture.diagnoseProjection(source, item.transform));
    }
    private void submit(Part part, Owner owner, Mesh mesh, CapturedItem item) {
        boolean first = part.instance == 0;
        boolean viewModel = ViewModelGeometry.applies(item.categoryFlags);
        float[] transform = viewModel ? FirstPersonCapture.anchorTransform() : item.transform;
        boolean moved = !Arrays.equals(part.transform, transform) || viewModel && part.mesh != mesh;
        boolean alphaBlend = alphaBlend(item);
        boolean changedBlend = first || part.alphaBlend != alphaBlend
                || part.alphaCompare != item.alphaCompare || part.alphaReference != item.alphaReference;
        if (part.retiredBlend != 0) { api.freeBlend(part.retiredBlend); part.retiredBlend = 0; }
        if (!first && !moved && !changedBlend && part.mesh == mesh) return;
        long blend = changedBlend ? api.blend(blend(item)) : part.blend;
        try {
            if (first) { part.instance = api.add(mesh.handle, transform, item.categoryFlags, blend); count++; }
            else { api.update(part.instance, mesh.handle, transform, item.categoryFlags, blend); if (moved) owner.updates++; }
        } catch (RuntimeException | LinkageError ex) {
            if (changedBlend) {
                part.retiredBlend = blend;
                try { api.freeBlend(blend); part.retiredBlend = 0; }
                catch (RuntimeException | LinkageError cleanup) { ex.addSuppressed(cleanup); }
            }
            throw ex;
        }
        if (changedBlend) {
            part.retiredBlend = part.blend;
            part.blend = blend;
            part.alphaBlend = alphaBlend;
            part.alphaCompare = item.alphaCompare; part.alphaReference = item.alphaReference;
        }
        if (part.mesh != mesh) {
            if (part.mesh != null) part.mesh.references--;
            mesh.references++;
            part.mesh = mesh;
        }
        System.arraycopy(transform, 0, part.transform, 0, 12);
        // The native instance already uses its new dependencies even if freeing old state fails.
        if (part.retiredBlend != 0) { api.freeBlend(part.retiredBlend); part.retiredBlend = 0; }
    }
    static InstanceInfoBlendEXT blend(CapturedItem item) {
        InstanceInfoBlendEXT ext = alphaBlend(item)
                ? InstanceInfoBlendEXT.textureTimesVertexColorAlphaBlend(item.alphaReference)
                : InstanceInfoBlendEXT.textureTimesVertexColor();
        ext.alphaTestEnabled = item.alphaCompare == InstanceInfoBlendEXT.VK_COMPARE_OP_ALWAYS ? 0 : 1;
        ext.alphaTestCompareOp = item.alphaCompare;
        ext.alphaTestReferenceValue = (byte) item.alphaReference;
        // The enabled vertex-color array supersedes GL_CURRENT_COLOR and already contains the item tint.
        return ext;
    }
    private static boolean alphaBlend(CapturedItem item) {
        return item.alphaBlend && !SpriteOpacity.unblended(item.coverage, item.alphaCompare, item.alphaReference);
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
        if (part.instance != 0) { api.remove(part.instance); part.instance = 0; count--; }
        if (part.blend != 0) { api.freeBlend(part.blend); part.blend = 0; }
        if (part.retiredBlend != 0) { api.freeBlend(part.retiredBlend); part.retiredBlend = 0; }
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
    private static Backend backend(final RemixAPI remix) {
        return new Backend() {
            public long createMesh(MeshInfo info) { return remix.createMesh(info); }
            public void destroyMesh(long handle) { remix.destroyMesh(handle); }
            public long createMaterial(MaterialInfo info) { return remix.createMaterial(info); }
            public void destroyMaterial(long handle) { remix.destroyMaterial(handle); }
            public long add(long mesh, float[] transform, int category, long blend) { return remix.sceneInstanceAdd(mesh, transform, category, 1, blend); }
            public void update(long instance, long mesh, float[] transform, int category, long blend) { remix.sceneInstanceUpdate(instance, mesh, transform, category, 1, blend); }
            public void remove(long instance) { remix.sceneInstanceRemove(instance); }
            public long blend(InstanceInfoBlendEXT info) { return info.allocNative(0); }
            public void freeBlend(long handle) { RemixBridge.freeExt(handle); }
        };
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
        long instance, blend, retiredBlend, requested, lastSeen;
        int alphaCompare, alphaReference;
        boolean alphaBlend;
    }
    private static final class Mesh {
        final long handle, bytes;
        int references;
        long requested, lastSeen;
        Mesh(long handle, long bytes) { this.handle = handle; this.bytes = bytes; }
    }
    static final class MeshKey {
        Object model, stack;
        long fingerprint;
        int vertices;
        boolean viewModel, spriteBounds;
        final float[] pose = new float[12];
        void set(CapturedItem item) {
            model = item.model; stack = item.stack; fingerprint = item.fingerprint; vertices = item.vertexCount;
            viewModel = ViewModelGeometry.applies(item.categoryFlags);
            spriteBounds = item.spriteBounds;
            if (viewModel) FirstPersonCapture.geometryPose(item.transform, pose);
            else Arrays.fill(pose, 0);
        }
        @Override public int hashCode() {
            int result = 31 * (31 * System.identityHashCode(model) + System.identityHashCode(stack)) + (int) (fingerprint ^ fingerprint >>> 32) + vertices;
            return 31 * (31 * (31 * result + (viewModel ? 1 : 0)) + (spriteBounds ? 1 : 0)) + Arrays.hashCode(pose);
        }
        @Override public boolean equals(Object value) {
            if (!(value instanceof MeshKey)) return false;
            MeshKey other = (MeshKey) value;
            return model == other.model && stack == other.stack && fingerprint == other.fingerprint && vertices == other.vertices
                    && viewModel == other.viewModel && spriteBounds == other.spriteBounds && Arrays.equals(pose, other.pose);
        }
    }
}
