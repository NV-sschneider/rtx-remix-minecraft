/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.sky;

import com.nvidia.rtxremix.HardcodedVertex;
import com.nvidia.rtxremix.MeshInfoSurfaceTriangles;
import java.nio.ByteBuffer;
import org.junit.Test;
import static org.junit.Assert.*;

public class SkyGeometryTest {
    @Test public void quadHasNativeLayoutAndInwardWinding() {
        MeshInfoSurfaceTriangles face = SkyGeometry.quad(17, 23, .25f, .5f, .5f, 1).surfaces[0];
        assertEquals(23, face.material);
        assertTrue(face.vertexBuffer.isDirect());
        assertTrue(face.indexBuffer.isDirect());
        assertEquals(4 * HardcodedVertex.STRIDE, face.vertexBuffer.remaining());
        assertEquals(6 * Integer.BYTES, face.indexBuffer.remaining());
        assertEquals(.25f, component(face.vertexBuffer, 0, 6), 0);
        assertEquals(1, component(face.vertexBuffer, 0, 7), 0);
        assertEquals(.5f, component(face.vertexBuffer, 2, 6), 0);
        assertEquals(.5f, component(face.vertexBuffer, 2, 7), 0);
        assertEquals(0, face.indexBuffer.getInt(0));
        assertEquals(2, face.indexBuffer.getInt(4));
        assertEquals(1, face.indexBuffer.getInt(8));
        assertEquals(-1, component(face.vertexBuffer, 0, 5), 0);
    }

    @Test public void celestialBasisKeepsVanillaTextureOrientationAtZenith() {
        SkyState state = new SkyState();
        float[] transform = new float[12];
        state.orient();
        SkyGeometry.transform(transform, state, 5, 7, 11, false);
        assertArrayEquals(new float[] {0, 30, 0, 5, 0, 0, 1, 107, 30, 0, 0, 11}, transform, .00001f);
        // Increasing texture U moves +Z, while the top of the texture faces +X.
        state.angle = .5f;
        state.orient();
        SkyGeometry.transform(transform, state, 5, 7, 11, true);
        assertArrayEquals(new float[] {0, -20, 0, 5, 0, 0, 1, 107, -20, 0, 0, 11}, transform, .00001f);
    }

    @Test public void celestialBasisRemainsFiniteOrthogonalThroughBothPoles() {
        SkyState state = new SkyState();
        float[] transform = new float[12];
        for (float angle : new float[] {0, .000001f, .25f, .499999f, .5f, .500001f, .75f, .999999f}) {
            state.angle = angle;
            state.orient();
            for (boolean moon : new boolean[] {false, true}) {
                SkyGeometry.transform(transform, state, 123, 80, -500, moon);
                for (float value : transform) assertTrue(Float.isFinite(value));
                float half = moon ? 20 : 30;
                assertEquals(half * half, columnDot(transform, 0, 0), .001f);
                assertEquals(half * half, columnDot(transform, 1, 1), .001f);
                assertEquals(0, columnDot(transform, 0, 1), .00001f);
                assertEquals(0, columnDot(transform, 1, 2), .00001f);
            }
        }
    }

    @Test public void starsHaveDeterministicUniformAngularSizeAndInwardNormals() {
        MeshInfoSurfaceTriangles stars = SkyGeometry.stars(10, 20).surfaces[0];
        MeshInfoSurfaceTriangles repeated = SkyGeometry.stars(10, 20).surfaces[0];
        assertEquals(stars.vertexBuffer, repeated.vertexBuffer);
        assertEquals(SkyGeometry.STAR_COUNT * 4, stars.vertexCount);
        assertEquals(SkyGeometry.STAR_COUNT * 6, stars.indexCount);
        for (int star = 0; star < SkyGeometry.STAR_COUNT; star++) {
            int base = star * 4;
            double centerSquared = 0, sideSquared = 0, inwardDot = 0;
            for (int axis = 0; axis < 3; axis++) {
                float a = component(stars.vertexBuffer, base, axis);
                float b = component(stars.vertexBuffer, base + 1, axis);
                float opposite = component(stars.vertexBuffer, base + 2, axis);
                double center = (a + opposite) * .5;
                centerSquared += center * center;
                sideSquared += (a - b) * (a - b);
                inwardDot += center * component(stars.vertexBuffer, base, axis + 3);
            }
            double radius = Math.sqrt(centerSquared);
            assertEquals(SkyGeometry.STAR_DISTANCE, radius, .00003);
            double halfAngle = Math.atan(Math.sqrt(sideSquared) / (2 * radius));
            assertTrue(halfAngle >= SkyGeometry.MIN_STAR_HALF_ANGLE - .000001);
            assertTrue(halfAngle <= SkyGeometry.MAX_STAR_HALF_ANGLE + .000001);
            assertEquals(-SkyGeometry.STAR_DISTANCE, inwardDot, .00003);
        }
    }

    @Test public void starOrbitUsesSameRotationAsSunDirection() {
        SkyState state = new SkyState();
        state.angle = .25f;
        state.orient();
        float[] transform = new float[12];
        SkyGeometry.starTransform(transform, state, 4, 8, 16);
        assertEquals(state.sunX, transform[1], 0);
        assertEquals(state.sunY, transform[5], 0);
        assertEquals(4, transform[3], 0);
        assertEquals(8, transform[7], 0);
        assertEquals(16, transform[11], 0);
        assertEquals(1, columnDot(transform, 0, 0), .00001f);
        assertEquals(1, columnDot(transform, 1, 1), .00001f);
    }

    private static float columnDot(float[] matrix, int a, int b) {
        return matrix[a] * matrix[b] + matrix[4 + a] * matrix[4 + b] + matrix[8 + a] * matrix[8 + b];
    }

    private static float component(ByteBuffer vertices, int vertex, int component) {
        return vertices.getFloat(vertex * HardcodedVertex.STRIDE + component * Float.BYTES);
    }
}
