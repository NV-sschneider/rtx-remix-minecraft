/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.light;

import com.nvidia.rtxremix.CameraInfoParameterizedEXT;
import com.nvidia.rtxremix.InstanceCategoryBit;
import com.nvidia.rtxremix.LightInfo;
import com.nvidia.rtxremix.LightInfoSphereEXT;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.forge.item.HeldItemLights;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.AxisAlignedBB;

/** Bounded render-thread lights whose exact captured item housing was admitted to the native scene. */
public final class HeldLightScene implements AutoCloseable {
    static final int MAX_LIGHTS = 16;
    private static final double RANGE_SQUARED = 48 * 48;
    private static final AtomicLong HASHES = new AtomicLong(0x524c48454c440000L);
    public interface Housing { boolean present(Object owner, int category, int occurrence); }
    interface Backend { long create(LightInfo info); void destroy(long handle); void draw(long handle); }
    interface Collision {
        double distance(HeldItemLights.Sample sample, CameraInfoParameterizedEXT camera, double dx, double dz);
    }
    private final Backend api;
    private final Collision collision;
    private final LongSupplier clock;
    private final Thread renderThread = Thread.currentThread();
    private final HeldItemLights.Sample[] candidates = new HeldItemLights.Sample[HeldItemLights.MAX_SAMPLES];
    private final HeldItemLights.Sample[] selected = new HeldItemLights.Sample[MAX_LIGHTS];
    private final double[] distances = new double[MAX_LIGHTS];
    private final Slot[] slots = new Slot[MAX_LIGHTS];
    private int selectedCount;
    private long lastTime, updates, draws;
    private boolean closed;

    public HeldLightScene(RemixAPI api, WorldClient world) {
        this(new Backend() {
            public long create(LightInfo info) { return api.createLight(info); }
            public void destroy(long handle) { api.destroyLight(handle); }
            public void draw(long handle) { api.drawLightInstance(handle); }
        }, (sample, camera, dx, dz) -> {
            double expansion = sample.radius + HeldLightPlacement.CLEARANCE;
            double x = camera.position.x, y = camera.position.y, z = camera.position.z;
            double endX = x + dx * HeldLightPlacement.DISTANCE, endZ = z + dz * HeldLightPlacement.DISTANCE;
            AxisAlignedBB query = new AxisAlignedBB(Math.min(x, endX) - expansion, y - expansion, Math.min(z, endZ) - expansion,
                    Math.max(x, endX) + expansion, y + expansion, Math.max(z, endZ) + expansion);
            double distance = HeldLightPlacement.DISTANCE;
            for (AxisAlignedBB box : world.getCollisionBoxes((Entity) sample.owner, query)) {
                distance = Math.min(distance, HeldLightPlacement.entry(x, y, z, dx, dz, distance, box, expansion));
            }
            return distance;
        }, System::nanoTime);
    }
    HeldLightScene(Backend api, Collision collision, LongSupplier clock) {
        this.api = api; this.collision = collision; this.clock = clock;
        for (int i = 0; i < slots.length; i++) slots[i] = new Slot();
    }

    /** Call after ItemScene.sync, before the one frame submit. Samples are never retained. */
    public void sync(List<HeldItemLights.Sample> samples, Housing housing, CameraInfoParameterizedEXT camera) {
        checkThread();
        if (closed) throw new IllegalStateException("Held light scene is closed");
        long now = clock.getAsLong();
        float seconds = lastTime == 0 ? 0 : (float) Math.max(0, (now - lastTime) * 1.0e-9);
        lastTime = now;
        try {
            select(samples, housing, camera);
            for (Slot slot : slots) {
                if (slot.owner == null) continue;
                boolean wanted = false;
                for (int i = 0; i < selectedCount; i++) if (matches(slot, selected[i])) { wanted = true; break; }
                if (!wanted) remove(slot);
            }
            for (int i = 0; i < selectedCount; i++) {
                HeldItemLights.Sample sample = selected[i];
                Slot slot = find(sample);
                if (slot == null) {
                    for (Slot empty : slots) if (empty.owner == null) { slot = empty; break; }
                    if (slot == null) throw new IllegalStateException("Held light admission exceeded its cap");
                    slot.owner = sample.owner; slot.id = sample.id; slot.hand = sample.hand;
                    slot.info = new LightInfo(HASHES.incrementAndGet(), 0, 0, 0);
                    slot.info.sphereExt = new LightInfoSphereEXT();
                    slot.previousDistance = Double.NaN;
                }
                update(slot, sample, camera, seconds);
                api.draw(slot.handle); draws++;
            }
        } finally {
            for (int i = 0; i < candidates.length; i++) candidates[i] = null;
            for (int i = 0; i < selected.length; i++) selected[i] = null;
            selectedCount = 0;
        }
    }

    private void select(List<HeldItemLights.Sample> samples, Housing housing, CameraInfoParameterizedEXT camera) {
        int unique = 0;
        for (int i = 0; i < Math.min(samples.size(), candidates.length); i++) {
            HeldItemLights.Sample sample = samples.get(i);
            if (!valid(sample) || !housing.present(sample.owner, sample.category, sample.occurrence)) continue;
            int existing = -1;
            for (int j = 0; j < unique; j++) if (candidates[j].owner == sample.owner && candidates[j].hand == sample.hand) { existing = j; break; }
            if (existing >= 0) {
                if (priority(sample) > priority(candidates[existing])) candidates[existing] = sample;
            } else candidates[unique++] = sample;
        }
        for (int i = 0; i < unique; i++) {
            HeldItemLights.Sample sample = candidates[i];
            double dx = sample.x - camera.position.x, dy = sample.y - camera.position.y, dz = sample.z - camera.position.z;
            double distance = sample.localFirstPerson ? 0 : dx * dx + dy * dy + dz * dz;
            if (!Double.isFinite(distance) || distance > RANGE_SQUARED) continue;
            int index = selectedCount;
            while (index > 0 && distances[index - 1] > distance) index--;
            if (index >= selected.length) continue;
            int last = Math.min(selectedCount, selected.length - 1);
            for (int j = last; j > index; j--) { selected[j] = selected[j - 1]; distances[j] = distances[j - 1]; }
            selected[index] = sample; distances[index] = distance;
            if (selectedCount < selected.length) selectedCount++;
        }
    }

    private void update(Slot slot, HeldItemLights.Sample sample, CameraInfoParameterizedEXT camera, float seconds) {
        float x = sample.x, y = sample.y, z = sample.z;
        if (sample.localFirstPerson) {
            double dx = camera.forward.x, dz = camera.forward.z, length = Math.sqrt(dx * dx + dz * dz);
            if (length < 1.0e-5) { double yaw = Math.toRadians(sample.yaw); dx = -Math.sin(yaw); dz = Math.cos(yaw); }
            else { dx /= length; dz /= length; }
            double target = Math.max(0, Math.min(HeldLightPlacement.DISTANCE, collision.distance(sample, camera, dx, dz)));
            double distance = Double.isNaN(slot.previousDistance) ? target : HeldLightPlacement.advance(slot.previousDistance, target, seconds);
            slot.previousDistance = distance;
            x = (float) (camera.position.x + dx * distance); y = camera.position.y; z = (float) (camera.position.z + dz * distance);
        } else slot.previousDistance = Double.NaN;
        LightInfo info = slot.info;
        LightInfoSphereEXT sphere = info.sphereExt;
        float volumetric = sample.localFirstPerson ? 0 : 1;
        boolean changed = slot.handle == 0 || sphere.position.x != x || sphere.position.y != y || sphere.position.z != z
                || sphere.radius != sample.radius || sphere.volumetricRadianceScale != volumetric
                || info.radiance.x != sample.red || info.radiance.y != sample.green || info.radiance.z != sample.blue;
        if (!changed) return;
        // The C API has no light-update operation. Release before replacement so
        // hash reuse cannot leave a stale owned handle after a runtime cache hit.
        if (slot.handle != 0) { api.destroy(slot.handle); slot.handle = 0; }
        sphere.position.x = x; sphere.position.y = y; sphere.position.z = z;
        sphere.radius = sample.radius; sphere.volumetricRadianceScale = volumetric;
        info.radiance.x = sample.red; info.radiance.y = sample.green; info.radiance.z = sample.blue;
        slot.handle = api.create(info); updates++;
    }

    private static int priority(HeldItemLights.Sample sample) { return sample.category == InstanceCategoryBit.VIEW_MODEL ? 2 : 1; }
    private static boolean valid(HeldItemLights.Sample sample) {
        return sample != null && sample.owner != null && sample.id != null && sample.hand >= 0 && sample.hand <= 1
                && sample.occurrence >= 0 && Float.isFinite(sample.x) && Float.isFinite(sample.y) && Float.isFinite(sample.z)
                && Float.isFinite(sample.yaw) && Float.isFinite(sample.radius) && sample.radius > 0
                && Float.isFinite(sample.red) && Float.isFinite(sample.green) && Float.isFinite(sample.blue)
                && sample.red >= 0 && sample.green >= 0 && sample.blue >= 0 && sample.red + sample.green + sample.blue > 0;
    }
    private static boolean matches(Slot slot, HeldItemLights.Sample sample) { return slot.owner == sample.owner && slot.hand == sample.hand; }
    private Slot find(HeldItemLights.Sample sample) { for (Slot slot : slots) if (matches(slot, sample)) return slot; return null; }
    private void remove(Slot slot) {
        if (slot.handle != 0) { api.destroy(slot.handle); slot.handle = 0; }
        slot.owner = null; slot.id = null; slot.info = null;
    }
    public int count() { checkThread(); int count = 0; for (Slot slot : slots) if (slot.handle != 0) count++; return count; }
    public int localCount() { checkThread(); int count = 0; for (Slot slot : slots) if (slot.handle != 0 && slot.info.sphereExt.volumetricRadianceScale == 0) count++; return count; }
    public boolean contains(UUID owner, int hand) { checkThread(); for (Slot slot : slots) if (slot.handle != 0 && owner.equals(slot.id) && slot.hand == hand) return true; return false; }
    public double localDistance(UUID owner, int hand) {
        checkThread();
        for (Slot slot : slots) if (slot.handle != 0 && owner.equals(slot.id) && slot.hand == hand) return slot.previousDistance;
        return Double.NaN;
    }
    public long updateCount() { return updates; }
    public long drawCount() { return draws; }
    @Override public void close() {
        checkThread(); if (closed) return;
        Throwable failure = null;
        for (Slot slot : slots) try { remove(slot); } catch (RuntimeException | LinkageError error) {
            if (failure == null) failure = error; else failure.addSuppressed(error);
        }
        if (failure != null) throw new IllegalStateException("Cannot release held lights", failure);
        closed = true;
    }
    private void checkThread() { if (Thread.currentThread() != renderThread) throw new IllegalStateException("Held lights require the render thread"); }
    private static final class Slot {
        Object owner;
        UUID id;
        int hand;
        long handle;
        double previousDistance;
        LightInfo info;
    }
}
