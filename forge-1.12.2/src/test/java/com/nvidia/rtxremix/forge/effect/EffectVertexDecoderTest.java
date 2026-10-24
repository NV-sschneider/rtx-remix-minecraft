/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.effect;

import com.nvidia.rtxremix.HardcodedVertex;
import java.nio.ByteBuffer;
import org.junit.Test;
import org.lwjgl.util.vector.Matrix4f;
import static org.junit.Assert.*;

public class EffectVertexDecoderTest {
    @Test public void keepsRgbaAndUvWithoutLightmapOrSourceBufferMutation() {
        ByteBuffer source = quad(1);
        source.position(5);
        CapturedEffectBatch batch = new CapturedEffectBatch();
        assertEquals(1, decode(source, 4, new Matrix4f(), 1, batch));
        assertEquals(5, source.position());
        assertEquals(112, source.limit());
        assertEquals(0x78123456, batch.vertices.getInt(32));
        assertEquals(0.25f, batch.vertices.getFloat(24), 0);
        assertEquals(0.5f, batch.vertices.getFloat(28), 0);
        assertEquals(0x78123456, batch.vertices.getInt(32 + HardcodedVertex.STRIDE));
        assertEquals(1, batch.vertices.getFloat(20), 0.000001f);
    }

    @Test public void appliesPassRelativeMatrixAndRetainsSeparateWorldOrigin() {
        Matrix4f view = new Matrix4f();
        view.m30 = 8; view.m31 = -3;
        Matrix4f drawn = new Matrix4f();
        drawn.m30 = 10; drawn.m31 = 1;
        Matrix4f inverse = Matrix4f.invert(view, new Matrix4f());
        Matrix4f relative = Matrix4f.mul(inverse, drawn, new Matrix4f());
        CapturedEffectBatch batch = new CapturedEffectBatch();
        batch.initialize(new Object(), 1, false, false, 0, 10000.25, 63.5, -9000.75);
        decode(quad(1), 4, relative, 1, batch);
        assertEquals(2, batch.vertices.getFloat(0), 0);
        assertEquals(4, batch.vertices.getFloat(4), 0);
        assertEquals(10000.25f, batch.transform[3], 0);
        assertEquals(63.5f, batch.transform[7], 0);
        assertEquals(-9000.75f, batch.transform[11], 0);
    }

    @Test public void capsWholeQuadsAndSkipsInvalidPrimitivesWithoutLosingValidNeighbors() {
        CapturedEffectBatch batch = new CapturedEffectBatch();
        assertEquals(2, decode(quad(4), 16, new Matrix4f(), 2, batch));
        long oldHash = batch.contentHash;
        ByteBuffer bad = quad(2);
        bad.putFloat(4 * 28 + 12, Float.NaN);
        assertEquals(1, decode(bad, 8, new Matrix4f(), 8, batch));
        assertEquals(3, batch.quads);
        assertNotEquals(oldHash, batch.contentHash);
        assertEquals(3 * 4 * HardcodedVertex.STRIDE, batch.vertices.position());
        assertEquals(0, decode(quad(1), 4, new Matrix4f(), 0, batch));
    }

    @Test public void vanillaRainCenterNaNsDoNotDiscardTheOtherFourHundredFortyColumns() {
        ByteBuffer rain = quad(441);
        float center = 0f / (float) Math.sqrt(0f);
        assertTrue(Float.isNaN(center));
        for (int v = 0; v < 4; v++) rain.putFloat((220 * 4 + v) * 28, center);
        CapturedEffectBatch batch = new CapturedEffectBatch();
        assertEquals(440, decode(rain, 441 * 4, new Matrix4f(), 441, batch));
        assertEquals(440 * 4 * HardcodedVertex.STRIDE, batch.vertices.position());
        assertEquals(0, rain.position());
    }

    @Test public void absentVertexColorUsesCapturedCurrentColorAndWeatherUvsCanRepeat() {
        ByteBuffer source = quad(1);
        source.putFloat(12, 3.5f); source.putFloat(16, -2);
        CapturedEffectBatch batch = new CapturedEffectBatch();
        new EffectVertexDecoder().append(source, 4, 28, 12, -1, 0x11223344, new Matrix4f(), 1, batch);
        assertEquals(0x11223344, batch.vertices.getInt(32));
        assertEquals(3.5f, batch.vertices.getFloat(24), 0);
        assertEquals(-2, batch.vertices.getFloat(28), 0);
    }

    @Test public void rejectsInvalidCountsAndBoundsBeforeAppending() {
        CapturedEffectBatch batch = new CapturedEffectBatch();
        for (int count : new int[] {-4, 3, 8}) {
            try { decode(quad(1), count, new Matrix4f(), 100, batch); fail(); }
            catch (IllegalArgumentException expected) { }
            assertEquals(0, batch.quads);
        }
    }

    @Test public void diagnosticHashChangesWithGeometryButNotFrameOrBufferIdentity() {
        CapturedEffectBatch first = new CapturedEffectBatch(), second = new CapturedEffectBatch();
        decode(quad(1), 4, new Matrix4f(), 1, first);
        decode(quad(1), 4, new Matrix4f(), 1, second);
        assertEquals(first.contentHash, second.contentHash);
        second.clear();
        Matrix4f moved = new Matrix4f(); moved.m30 = 1;
        decode(quad(1), 4, moved, 1, second);
        assertNotEquals(first.contentHash, second.contentHash);
    }

    @Test public void triangleFanAndStripBecomeIndependentTrianglesWithConsistentWinding() {
        EffectVertexDecoder decoder = new EffectVertexDecoder();
        CapturedEffectBatch fan = new CapturedEffectBatch(); fan.triangles = true;
        assertEquals(2, decoder.append(quad(1), 4, 6, 28, 12, 20, -1, new Matrix4f(), 8, fan));
        assertEquals(6 * HardcodedVertex.STRIDE, fan.vertices.position());
        assertEquals(1, fan.vertices.getFloat(20), 0);
        assertEquals(1, fan.vertices.getFloat(3 * HardcodedVertex.STRIDE + 20), 0);
        ByteBuffer source = quad(1);
        // A strip visits bottom-left, bottom-right, top-left, top-right.
        source.putFloat(2 * 28, 0); source.putFloat(3 * 28, 1);
        CapturedEffectBatch strip = new CapturedEffectBatch(); strip.triangles = true;
        assertEquals(2, decoder.append(source, 4, 5, 28, 12, 20, -1, new Matrix4f(), 8, strip));
        assertEquals(1, strip.vertices.getFloat(20), 0);
        assertEquals(1, strip.vertices.getFloat(3 * HardcodedVertex.STRIDE + 20), 0);
        assertEquals(0x78123456, strip.vertices.getInt(3 * HardcodedVertex.STRIDE + 32));
        CapturedEffectBatch one = new CapturedEffectBatch(); one.triangles = true;
        assertEquals(1, decoder.append(source, 3, 4, 28, 12, -1, 0x42123456, new Matrix4f(), 1, one));
        assertEquals(0x42123456, one.vertices.getInt(32));
    }

    @Test public void triangleCountsTopologyAndBudgetAreValidatedBeforeAnyAppend() {
        EffectVertexDecoder decoder = new EffectVertexDecoder();
        CapturedEffectBatch target = new CapturedEffectBatch(); target.triangles = true;
        assertEquals(1, decoder.append(quad(1), 4, 6, 28, 12, 20, -1, new Matrix4f(), 1, target));
        long hash = target.contentHash;
        assertThrows(IllegalArgumentException.class, () -> decoder.append(quad(1), 4, 4, 28, 12, 20, -1, new Matrix4f(), 4, target));
        assertThrows(IllegalArgumentException.class, () -> decoder.append(quad(1), 4, 7, 28, 12, 20, -1, new Matrix4f(), 4, target));
        assertThrows(IllegalArgumentException.class, () -> decoder.append(quad(1), 4, 1, 28, 12, 20, -1, new Matrix4f(), 4, target));
        assertEquals(hash, target.contentHash); assertEquals(1, target.quads);
        assertEquals(3 * HardcodedVertex.STRIDE, target.vertices.position());
    }

    static ByteBuffer quad(int count) {
        ByteBuffer result = CapturedEffectBatch.direct(count * 4 * 28);
        float[] xy = {0, 0, 1, 0, 1, 1, 0, 1};
        for (int q = 0; q < count; q++) for (int v = 0; v < 4; v++) {
            result.putFloat(xy[v * 2]).putFloat(xy[v * 2 + 1]).putFloat(0);
            result.putFloat(0.25f + xy[v * 2] * 0.1f).putFloat(0.5f + xy[v * 2 + 1] * 0.1f);
            result.put((byte) 0x12).put((byte) 0x34).put((byte) 0x56).put((byte) 0x78);
            result.putInt(0x00f000f0);
        }
        result.flip();
        return result;
    }
    static int decode(ByteBuffer source, int count, Matrix4f matrix, int allowed, CapturedEffectBatch batch) {
        return new EffectVertexDecoder().append(source, count, 28, 12, 20, -1, matrix, allowed, batch);
    }
}
