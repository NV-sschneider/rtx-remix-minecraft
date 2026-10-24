/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.lwjgl.util.vector.Matrix4f;

/** Bounded owner snapshots survive Forge's shared-buffer sorting and reuse until scene publication. */
final class FastTileBatch {
    static final int MAX_PARTS = 128;
    private static final Object GEOMETRY_GROUP = new Object();
    private final EntityTriangles triangles = new EntityTriangles();
    private final List<CapturedEntityPart> parts = new ArrayList<CapturedEntityPart>(MAX_PARTS);
    private final List<CapturedEntityPart> view = Collections.unmodifiableList(parts);
    private final CapturedEntityPart[] pool = new CapturedEntityPart[MAX_PARTS];
    private final Object[] rejected = new Object[MAX_PARTS];
    private final double[] x = new double[MAX_PARTS], y = new double[MAX_PARTS], z = new double[MAX_PARTS];
    private final Matrix4f identity = new Matrix4f(), scratch = new Matrix4f();
    private int batchStart = -1, triangleStart, rejectedCount;
    private boolean prepared;
    private long capturedRanges, rejectedRanges;

    FastTileBatch() { for (int i = 0; i < pool.length; i++) pool[i] = new CapturedEntityPart(); }

    void reset() {
        for (CapturedEntityPart part : pool) part.clear();
        for (int i = 0; i < rejectedCount; i++) rejected[i] = null;
        parts.clear(); triangles.reset(); rejectedCount = 0; batchStart = -1; prepared = false;
    }

    void begin() {
        abort();
        batchStart = parts.size(); triangleStart = triangles.count(); prepared = false;
    }

    boolean collecting() { return batchStart >= 0; }

    boolean append(Object owner, ByteBuffer source, int first, int count, int stride,
                   int uvOffset, int colorOffset, double originX, double originY, double originZ) {
        if (!collecting() || prepared || owner == null || blocked(owner)) return false;
        if (count == 0) return true;
        int index = parts.size(), occurrence = 0;
        for (CapturedEntityPart part : parts) if (part.owner == owner) occurrence++;
        int start = triangles.count();
        if (index == MAX_PARTS || !Double.isFinite(originX) || !Double.isFinite(originY) || !Double.isFinite(originZ)
                || !triangles.appendPrimitivesRange(source, first, count, 4, stride,
                        uvOffset, colorOffset, -1, 0xffffffff, identity)) {
            reject(owner); return false;
        }
        CapturedEntityPart part = takeFree();
        part.clear(); part.owner = owner; part.occurrence = occurrence;
        part.triangles = triangles; part.triangleGroup = GEOMETRY_GROUP;
        part.firstVertex = start; part.vertexCount = triangles.count() - start;
        part.geometryFingerprint = triangles.fingerprint(); part.scale = 1; part.argb = 0xffffffff;
        x[index] = originX; y[index] = originY; z[index] = originZ;
        parts.add(part);
        return true;
    }

    void prepare(Object texture, int textureId, int alphaCompare, int alphaReference,
                 Matrix4f inverseView, Matrix4f finalView) {
        if (!collecting()) return;
        if (texture == null || textureId <= 0) { abort(); return; }
        for (int i = batchStart; i < parts.size(); i++) {
            CapturedEntityPart part = parts.get(i);
            part.textureIdentity = texture; part.textureId = textureId;
            part.alphaBlend = true; part.alphaCompare = alphaCompare; part.alphaReference = alphaReference;
            EntityTransform.world(inverseView, finalView, scratch, x[i], y[i], z[i], part.transform);
        }
        prepared = true;
    }

    /** The original OpenGL draw has succeeded; only now may these snapshots be published. */
    void complete() {
        if (!collecting()) return;
        if (!prepared) { abort(); return; }
        capturedRanges += parts.size() - batchStart;
        batchStart = -1; prepared = false;
    }

    void abort() {
        if (!collecting()) return;
        while (parts.size() > batchStart) parts.remove(parts.size() - 1).clear();
        triangles.truncate(triangleStart); batchStart = -1; prepared = false;
    }

    void reject(Object owner) {
        if (owner == null || blocked(owner)) return;
        rejectedRanges++;
        if (rejectedCount < rejected.length) rejected[rejectedCount++] = owner;
        for (int i = parts.size() - 1; i >= 0; i--) if (parts.get(i).owner == owner) {
            parts.remove(i).clear();
            int remaining = parts.size() - i;
            System.arraycopy(x, i + 1, x, i, remaining);
            System.arraycopy(y, i + 1, y, i, remaining);
            System.arraycopy(z, i + 1, z, i, remaining);
            if (batchStart >= 0 && i < batchStart) batchStart--;
        }
    }

    List<CapturedEntityPart> finish() { abort(); return view; }
    int count() { return parts.size(); }
    long capturedRanges() { return capturedRanges; }
    long rejectedRanges() { return rejectedRanges; }

    private boolean blocked(Object owner) {
        for (int i = 0; i < rejectedCount; i++) if (rejected[i] == owner) return true;
        return rejectedCount == rejected.length;
    }
    private CapturedEntityPart takeFree() {
        for (CapturedEntityPart part : pool) if (part.owner == null) return part;
        throw new IllegalStateException("Fast tile part pool exhausted");
    }
}
