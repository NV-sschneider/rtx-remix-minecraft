/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import com.nvidia.rtxremix.Float3D;
import com.nvidia.rtxremix.MaterialInfo;
import com.nvidia.rtxremix.MaterialInfoTranslucentEXT;
import com.nvidia.rtxremix.RemixAPI;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.multiplayer.WorldClient;

/** One render-thread water medium, independent of the terrain atlas and surface material. */
public final class CameraMedium implements AutoCloseable {
    interface Backend {
        long createMaterial(MaterialInfo info);
        void select(long material);
        void destroyMaterial(long material);
    }

    private static final AtomicLong HASHES = new AtomicLong(0x524c43414d4d0000L);
    private final Backend api;
    private final Thread renderThread = Thread.currentThread();
    private CameraWater water;
    private long material;
    private long selected;
    private long changes;
    private double x, y, z;
    private boolean underwater;
    private boolean closing;
    private boolean closed;

    public CameraMedium(RemixAPI api) {
        this(new Backend() {
            @Override public long createMaterial(MaterialInfo info) { return api.createMaterial(info); }
            @Override public void select(long material) { api.setCameraMediumMaterial(material); }
            @Override public void destroyMaterial(long material) { api.destroyMaterial(material); }
        });
    }

    CameraMedium(Backend api) { this.api = api; }

    /** Submit the current medium every frame, including zero for air, before Remix submit. */
    public void update(WorldClient world, Float3D actualCameraPosition) {
        checkThread();
        if (closing) throw new IllegalStateException("Camera medium is closing");
        if (water == null) water = new CameraWater();
        boolean inWater = water.sample(world, actualCameraPosition);
        apply(inWater);
        x = actualCameraPosition.x; y = actualCameraPosition.y; z = actualCameraPosition.z;
    }

    void apply(boolean inWater) {
        checkThread();
        if (closing) throw new IllegalStateException("Camera medium is closing");
        if (inWater && material == 0) material = api.createMaterial(description(HASHES.incrementAndGet()));
        long next = inWater ? material : 0;
        api.select(next);
        if (selected != next) changes++;
        selected = next;
        underwater = inWater;
    }

    static MaterialInfo description(long hash) {
        return MaterialInfo.builder().hash(hash).translucentExt(MaterialInfoTranslucentEXT.builder()
                .refractiveIndex(1.333f).transmittanceColor(0.98f, 0.98f, 0.98f)
                .transmittanceMeasurementDistance(1).useDiffuseLayer(false).build()).build();
    }

    public boolean isUnderwater() { return underwater; }
    public long selectedMaterial() { return selected; }
    public int resourceCount() { return material == 0 ? 0 : 1; }
    public long selectionChanges() { return changes; }
    public double cameraX() { return x; }
    public double cameraY() { return y; }
    public double cameraZ() { return z; }

    @Override public void close() {
        checkThread();
        if (closed) return;
        closing = true;
        // A failed clear leaves the material owned and prevents its destruction.
        api.select(0);
        if (selected != 0) changes++;
        selected = 0;
        underwater = false;
        if (material != 0) {
            api.destroyMaterial(material);
            material = 0;
        }
        water = null;
        closed = true;
    }

    private void checkThread() {
        if (Thread.currentThread() != renderThread)
            throw new IllegalStateException("Camera medium requires its render thread");
    }
}
