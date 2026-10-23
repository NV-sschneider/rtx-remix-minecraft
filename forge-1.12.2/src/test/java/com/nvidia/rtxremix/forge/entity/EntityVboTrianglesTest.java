/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

import com.nvidia.rtxremix.MeshInfoSurfaceTriangles;
import java.nio.ByteBuffer;
import org.junit.Test;
import org.lwjgl.util.vector.Matrix4f;
import static org.junit.Assert.*;

public class EntityVboTrianglesTest {
    @Test public void capturesActualColorAlphaTextureMatrixAndPackedNormal() {
        EntityTriangles triangles = new EntityTriangles();
        ByteBuffer source = source(); source.position(5).mark().position(9);
        Matrix4f texture = new Matrix4f(); texture.m30 = 0.04f; texture.m31 = -0.02f;
        assertTrue(triangles.appendVbo(source, 3, 0x80402010, texture));
        assertEquals(9, source.position()); source.reset(); assertEquals(5, source.position());
        MeshInfoSurfaceTriangles surface = triangles.info(0, 3, 42, 1).surfaces[0];
        assertEquals(0.29f, surface.vertexBuffer.getFloat(24), 0.000001f);
        assertEquals(0.73f, surface.vertexBuffer.getFloat(28), 0.000001f);
        assertEquals(0x80402010, surface.vertexBuffer.getInt(32));
        assertEquals(1, surface.vertexBuffer.getFloat(20), 0);
        assertEquals(1, surface.vertexBuffer.getFloat(64), 0);
    }
    @Test public void colorAndUvChangesReachExistingMeshFingerprint() {
        EntityTriangles triangles = new EntityTriangles(); Matrix4f texture = new Matrix4f();
        assertTrue(triangles.appendVbo(source(), 3, 0xffffffff, texture)); long before = triangles.fingerprint();
        texture.m30 = 0.01f;
        assertTrue(triangles.appendVbo(source(), 3, 0xffffffff, texture)); long uv = triangles.fingerprint();
        assertNotEquals(before, uv);
        assertTrue(triangles.appendVbo(source(), 3, 0x40ffffff, texture)); assertNotEquals(uv, triangles.fingerprint());
    }
    @Test public void unsupportedProjectiveTextureOrBadVertexRejectsAtomically() {
        EntityTriangles triangles = new EntityTriangles(); Matrix4f texture = new Matrix4f();
        assertTrue(triangles.appendVbo(source(), 3, -1, texture));
        texture.m03 = 0.5f;
        assertFalse(triangles.appendVbo(source(), 3, -1, texture)); assertEquals(3, triangles.count());
        texture.m03 = 0;
        ByteBuffer source = source(); source.putFloat(24, Float.NaN);
        assertFalse(triangles.appendVbo(source, 3, -1, texture)); assertEquals(3, triangles.count());
    }
    private static ByteBuffer source() {
        ByteBuffer result = EntityMesh.direct(72);
        for (int i = 0; i < 3; i++) result.putFloat(i == 1 ? 1 : 0).putFloat(i == 2 ? 1 : 0).putFloat(0)
                .putFloat(0.25f).putFloat(0.75f).put((byte) 0).put((byte) 0).put((byte) 127).put((byte) 0);
        result.flip(); return result;
    }
}
