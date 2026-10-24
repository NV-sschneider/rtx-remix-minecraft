/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.cloud;

import com.nvidia.rtxremix.HardcodedVertex;
import java.nio.ByteBuffer;
import org.junit.Test;
import org.lwjgl.util.vector.Matrix4f;
import static org.junit.Assert.*;

public class CloudGeometryTest {
    @Test public void capturesFancyCloudScaleRgbaTextureMotionAndWorldOrigin() {
        CapturedCloudBatch batch = batch();
        Matrix4f model = new Matrix4f(); model.m00 = model.m22 = 12;
        Matrix4f texture = new Matrix4f(); texture.m30 = 0.25f; texture.m31 = -0.5f;
        int accepted = new CloudGeometry().append(quad(), 4, 7, 24, 12, 20, 0, model, texture, 100, batch);
        assertEquals(6, accepted);
        assertEquals(10, batch.transform[3], 0); assertEquals(148, batch.transform[7], 0); assertEquals(30, batch.transform[11], 0);
        assertEquals(12, batch.vertices.getFloat(HardcodedVertex.STRIDE), 0);
        assertEquals(0.25f, batch.vertices.getFloat(24), 0); assertEquals(-0.5f, batch.vertices.getFloat(28), 0);
        assertEquals(0xcc204060, batch.vertices.getInt(32));
        assertEquals(0, batch.vertices.getFloat(12), 0); assertEquals(-1, batch.vertices.getFloat(16), 0);
    }

    @Test public void movingOnlyTheCloudAnchorKeepsTheMeshHash() {
        CapturedCloudBatch first = batch(), second = batch();
        Matrix4f shifted = new Matrix4f(); shifted.m30 = 16; shifted.m31 = -32;
        decode(quad(), 4, 7, new Matrix4f(), 100, first);
        decode(quad(), 4, 7, shifted, 100, second);
        assertEquals(first.hash, second.hash);
        assertEquals(first.transform[3] + 16, second.transform[3], 0);
        assertEquals(first.transform[7] - 32, second.transform[7], 0);
    }

    @Test public void supportsUntexturedSkyTriangleFansWithActualCurrentColor() {
        ByteBuffer source = CapturedCloudBatch.direct(4 * 12);
        source.putFloat(0).putFloat(0).putFloat(0).putFloat(1).putFloat(0).putFloat(0)
                .putFloat(1).putFloat(1).putFloat(0).putFloat(0).putFloat(1).putFloat(0).flip();
        CapturedCloudBatch batch = batch();
        assertEquals(6, new CloudGeometry().append(source, 4, 6, 12, -1, -1, 0x80557799,
                new Matrix4f(), new Matrix4f(), 100, batch));
        assertEquals(0x80557799, batch.vertices.getInt(32));
        assertEquals(1, batch.vertices.getFloat(20), 0);
    }

    @Test public void stripTrianglesKeepConsistentWinding() {
        ByteBuffer source = CapturedCloudBatch.direct(4 * 12);
        source.putFloat(0).putFloat(0).putFloat(0).putFloat(1).putFloat(0).putFloat(0)
                .putFloat(0).putFloat(1).putFloat(0).putFloat(1).putFloat(1).putFloat(0).flip();
        CapturedCloudBatch batch = batch();
        new CloudGeometry().append(source, 4, 5, 12, -1, -1, -1, new Matrix4f(), new Matrix4f(), 100, batch);
        assertEquals(6, batch.count);
        assertEquals(1, batch.vertices.getFloat(20), 0);
        assertEquals(1, batch.vertices.getFloat(3 * HardcodedVertex.STRIDE + 20), 0);
    }

    @Test public void malformedAndNonfiniteVerticesDoNotConsumeValidGeometry() {
        CapturedCloudBatch batch = batch();
        decode(quad(), 4, 7, new Matrix4f(), 3, batch); assertEquals(3, batch.count);
        long hash = batch.hash;
        ByteBuffer invalid = quad(); invalid.putFloat(0, Float.NaN);
        assertEquals(0, decode(invalid, 4, 7, new Matrix4f(), 100, batch));
        assertEquals(hash, batch.hash); assertEquals(3, batch.count);
        try { decode(quad(), 400, 7, new Matrix4f(), 100, batch); fail(); }
        catch (IllegalArgumentException expected) { }
        assertEquals(hash, batch.hash);
        assertEquals(-1, CloudGeometry.triangles(1, 4)); assertEquals(-1, CloudGeometry.triangles(7, 5));
    }

    static CapturedCloudBatch batch() {
        CapturedCloudBatch result = new CapturedCloudBatch();
        result.initialize(null, 0, false, CloudBlend.key(false, 1, 0, 32774, 1, 0, 32774, false, 519, 0, false), 10, 20, 30);
        return result;
    }
    static int decode(ByteBuffer source, int vertices, int mode, Matrix4f transform, int allowed, CapturedCloudBatch batch) {
        return new CloudGeometry().append(source, vertices, mode, 24, 12, 20, 0, transform, new Matrix4f(), allowed, batch);
    }
    static ByteBuffer quad() {
        ByteBuffer result = CapturedCloudBatch.direct(4 * 24);
        vertex(result, 0, 128, 0, 0, 0); vertex(result, 1, 128, 0, 1, 0);
        vertex(result, 1, 128, 1, 1, 1); vertex(result, 0, 128, 1, 0, 1);
        result.flip(); return result;
    }
    private static void vertex(ByteBuffer buffer, float x, float y, float z, float u, float v) {
        buffer.putFloat(x).putFloat(y).putFloat(z).putFloat(u).putFloat(v)
                .put((byte) 0x20).put((byte) 0x40).put((byte) 0x60).put((byte) 0xcc);
    }
}
