/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.light;

import com.nvidia.rtxremix.Float3D;
import com.nvidia.rtxremix.LightInfo;
import com.nvidia.rtxremix.LightInfoSphereEXT;
import com.nvidia.rtxremix.RemixAPI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;

/** Render-thread local lighting from the same revision of terrain that was published. */
public final class BlockLightScene implements AutoCloseable {
    private static final int MAX_LIGHTS = 128;
    private static final int RANGE = 48;
    private static final int CHANGES_PER_FRAME = 8;
    private static final long SELECT_INTERVAL = 200000000L;
    private static final long HASH = 0x524c424c00000000L;
    private final RemixAPI api;
    private final WorldClient world;
    private final Map<Long, long[]> sections = new HashMap<Long, long[]>();
    private final Map<Long, NativeLight> lights = new HashMap<Long, NativeLight>();
    private final List<Long> selected = new ArrayList<Long>();
    private final Set<Long> wanted = new HashSet<Long>();
    private final Set<Long> changed = new HashSet<Long>();
    private long nextSelection;
    private long revisions;
    private int candidates;
    private boolean closed;

    public BlockLightScene(RemixAPI api, WorldClient world) { this.api = api; this.world = world; }

    /** Capture only potential local lights. Lava is lit by its emissive surface material. */
    public void sample(SectionLights target, IBlockState state, BlockPos pos) {
        if (state.getMaterial() == Material.LAVA) return;
        int level = state.getLightValue(world, pos);
        if (level > 0) target.add(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15,
                pos.toLong(), Math.min(15, level));
    }

    public void publish(int x, int y, int z, SectionLights captured) {
        checkThread();
        long key = sectionKey(x, y, z);
        long[] old = sections.remove(key);
        if (old != null) candidates -= old.length;
        long[] positions = captured.snapshot();
        if (positions.length != 0) { sections.put(key, positions); candidates += positions.length; }
        revisions++;
    }

    public void removeColumn(int x, int z) {
        checkThread();
        for (int y = 0; y < 16; y++) {
            long[] old = sections.remove(sectionKey(x, y, z));
            if (old != null) candidates -= old.length;
        }
    }

    public void update(Float3D camera) {
        checkThread();
        if (closed) throw new IllegalStateException("Block light scene is closed");
        long now = System.nanoTime();
        if (now - nextSelection >= 0) {
            select(camera);
            nextSelection = now + SELECT_INTERVAL;
        }
        int changes = 0;
        Iterator<Map.Entry<Long, NativeLight>> iterator = lights.entrySet().iterator();
        while (iterator.hasNext() && changes < CHANGES_PER_FRAME) {
            Map.Entry<Long, NativeLight> entry = iterator.next();
            if (wanted.contains(entry.getKey()) && !changed.contains(entry.getKey())) continue;
            api.destroyLight(entry.getValue().handle);
            changed.remove(entry.getKey());
            iterator.remove();
            changes++;
        }
        for (Long position : selected) {
            if (changes == CHANGES_PER_FRAME || lights.size() >= MAX_LIGHTS) break;
            if (lights.containsKey(position)) continue;
            BlockPos pos = BlockPos.fromLong(position);
            if (world.getChunkProvider().getLoadedChunk(pos.getX() >> 4, pos.getZ() >> 4) == null) continue;
            IBlockState state = world.getBlockState(pos);
            LightInfo info = describe(state, pos);
            if (info == null) continue;
            lights.put(position, new NativeLight(api.createLight(info), state, emission(state, pos)));
            changes++;
        }
        for (NativeLight light : lights.values()) api.drawLightInstance(light.handle);
    }

    private void select(final Float3D camera) {
        final Comparator<Candidate> farthestFirst = new Comparator<Candidate>() {
            @Override public int compare(Candidate a, Candidate b) {
                int order = Double.compare(b.distance, a.distance);
                return order != 0 ? order : Long.compare(b.position, a.position);
            }
        };
        PriorityQueue<Candidate> nearest = new PriorityQueue<Candidate>(MAX_LIGHTS, farthestFirst);
        for (Map.Entry<Long, long[]> section : sections.entrySet()) {
            BlockPos sectionPos = BlockPos.fromLong(section.getKey());
            if (Math.abs(sectionPos.getX() * 16 + 8 - camera.x) > RANGE + 16
                    || Math.abs(sectionPos.getY() * 16 + 8 - camera.y) > RANGE + 16
                    || Math.abs(sectionPos.getZ() * 16 + 8 - camera.z) > RANGE + 16) continue;
            for (long position : section.getValue()) {
                BlockPos pos = BlockPos.fromLong(position);
                double dx = pos.getX() + 0.5 - camera.x, dy = pos.getY() + 0.5 - camera.y;
                double dz = pos.getZ() + 0.5 - camera.z;
                double distance = dx * dx + dy * dy + dz * dz;
                if (distance > RANGE * RANGE) continue;
                if (nearest.size() < MAX_LIGHTS) nearest.add(new Candidate(position, distance));
                else if (distance < nearest.peek().distance) {
                    nearest.poll();
                    nearest.add(new Candidate(position, distance));
                }
            }
        }
        selected.clear();
        while (!nearest.isEmpty()) selected.add(nearest.poll().position);
        Collections.reverse(selected);
        wanted.clear();
        wanted.addAll(selected);
        changed.clear();
        // Remove a changed/unlit block promptly even when it kept the same position.
        for (Map.Entry<Long, NativeLight> entry : lights.entrySet()) {
            BlockPos pos = BlockPos.fromLong(entry.getKey());
            if (world.getChunkProvider().getLoadedChunk(pos.getX() >> 4, pos.getZ() >> 4) == null) {
                wanted.remove(entry.getKey());
            } else if (world.getBlockState(pos) != entry.getValue().state
                    || emission(entry.getValue().state, pos) != entry.getValue().level) changed.add(entry.getKey());
        }
        selected.retainAll(wanted);
    }

    private LightInfo describe(IBlockState state, BlockPos pos) {
        int level = emission(state, pos);
        if (level <= 0 || state.getMaterial() == Material.LAVA) return null;
        ResourceLocation registry = state.getBlock().getRegistryName();
        String name = registry == null ? "" : registry.getPath();
        float red = 1, green = 0.65f, blue = 0.28f;
        if (name.contains("redstone_torch") || name.contains("redstone_wire")) { green = 0.08f; blue = 0.02f; }
        else if (name.contains("sea_lantern") || name.contains("end_rod")) { red = 0.65f; green = 0.85f; blue = 1; }
        else if (name.contains("fire")) { green = 0.35f; blue = 0.06f; }
        boolean solid = state.isOpaqueCube();
        float radius = solid ? 0.65f : 0.12f;
        float intensity = (solid ? 7 : 90) * (level / 15.0f) * (level / 15.0f);
        LightInfo info = new LightInfo(HASH ^ pos.toLong(), red * intensity, green * intensity, blue * intensity);
        info.sphereExt = LightInfoSphereEXT.builder().position(pos.getX() + 0.5f,
                pos.getY() + (solid ? 0.5f : 0.72f), pos.getZ() + 0.5f).radius(radius).build();
        return info;
    }

    public int resourceCount() { return lights.size(); }
    public int candidateCount() { return candidates; }
    public long revisionCount() { return revisions; }
    public boolean contains(BlockPos pos) { return lights.containsKey(pos.toLong()); }
    public void clear() {
        checkThread();
        RuntimeException failure = null;
        Iterator<NativeLight> iterator = lights.values().iterator();
        while (iterator.hasNext()) {
            try { api.destroyLight(iterator.next().handle); iterator.remove(); }
            catch (RuntimeException error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        }
        sections.clear(); selected.clear(); wanted.clear(); changed.clear(); candidates = 0; nextSelection = 0;
        if (failure != null) throw failure;
    }
    @Override public void close() { if (!closed) { clear(); closed = true; } }
    private static long sectionKey(int x, int y, int z) { return new BlockPos(x, y, z).toLong(); }
    private int emission(IBlockState state, BlockPos pos) {
        return Math.max(0, Math.min(15, state.getLightValue(world, pos)));
    }
    private static void checkThread() {
        if (!Minecraft.getMinecraft().isCallingFromMinecraftThread())
            throw new IllegalStateException("Block lights require the Minecraft render thread");
    }
    private static final class Candidate {
        final long position; final double distance;
        Candidate(long position, double distance) { this.position = position; this.distance = distance; }
    }
    private static final class NativeLight {
        final long handle; final IBlockState state; final int level;
        NativeLight(long handle, IBlockState state, int level) { this.handle = handle; this.state = state; this.level = level; }
    }
}
