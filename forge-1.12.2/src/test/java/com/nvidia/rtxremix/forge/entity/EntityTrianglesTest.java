/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

import com.nvidia.rtxremix.InstanceInfoBlendEXT;
import com.nvidia.rtxremix.MaterialInfo;
import com.nvidia.rtxremix.MeshInfoSurfaceTriangles;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.util.vector.Matrix4f;
import static org.junit.Assert.*;

public class EntityTrianglesTest {
    @Test public void projectileQuadsPreservePoseUvsAndCurrentColorWithoutVertexNormals() {
        ByteBuffer source = EntityMesh.direct(4 * 20);
        float[] xy = {0, 0, 1, 0, 1, 1, 0, 1};
        for (int i = 0; i < 4; i++) source.putFloat(xy[i * 2]).putFloat(xy[i * 2 + 1]).putFloat(0)
                .putFloat(xy[i * 2]).putFloat(xy[i * 2 + 1]);
        source.flip(); source.position(3).mark().position(7);
        Matrix4f texture = new Matrix4f(); texture.m30 = 0.25f; texture.m31 = -0.5f;
        EntityTriangles triangles = new EntityTriangles();
        assertTrue(triangles.appendPrimitives(source, 4, 4, 20, 12, -1, -1, 0xff804020, texture));
        assertEquals(7, source.position()); source.reset(); assertEquals(3, source.position());
        MeshInfoSurfaceTriangles surface = triangles.info(0, 6, 7, 9).surfaces[0];
        assertEquals(6, surface.vertexCount);
        ByteBuffer vertices = surface.vertexBuffer;
        assertEquals(1, vertices.getFloat(20), 0);
        assertEquals(0.25f, vertices.getFloat(24), 0); assertEquals(-0.5f, vertices.getFloat(28), 0);
        assertEquals(0xff804020, vertices.getInt(32));
        assertEquals(0, vertices.getFloat(3 * 64), 0);
        assertEquals(1, vertices.getFloat(4 * 64), 0);
        assertEquals(0, vertices.getFloat(5 * 64), 0);
        assertEquals(1, vertices.getFloat(5 * 64 + 4), 0);
    }

    @Test public void malformedProjectileQuadsRejectAtomicallyAndCannotOverflowTheArena() {
        ByteBuffer source = EntityMesh.direct(4 * 20);
        for (int i = 0; i < 4; i++) source.putFloat(i == 1 || i == 2 ? 1 : 0)
                .putFloat(i >= 2 ? 1 : 0).putFloat(0).putFloat(0).putFloat(0);
        source.flip();
        EntityTriangles triangles = new EntityTriangles(); Matrix4f texture = new Matrix4f();
        assertTrue(triangles.appendPrimitives(source, 4, 4, 20, 12, -1, -1, -1, texture));
        source.putFloat(40, Float.NaN);
        assertFalse(triangles.appendPrimitives(source, 4, 4, 20, 12, -1, -1, -1, texture));
        assertEquals(6, triangles.count());
        assertFalse(triangles.appendPrimitives(source, Integer.MAX_VALUE, 4, 20, 12, -1, -1, -1, texture));
        assertFalse(triangles.appendPrimitives(source, 4, 2, 20, 12, -1, -1, -1, texture));
    }
    @Test public void preservesCompletedTriangleColorUvNormalAndProducerState() {
        ByteBuffer source = source(3);
        source.position(5).mark().position(9);
        EntityTriangles triangles = new EntityTriangles();
        assertTrue(append(triangles, source, 3));
        assertEquals(9, source.position());
        assertEquals(84, source.limit());
        source.reset();
        assertEquals(5, source.position());
        MeshInfoSurfaceTriangles surface = triangles.info(0, 3, 12, 77).surfaces[0];
        assertEquals(3, surface.vertexCount);
        assertEquals(3, surface.indexCount);
        assertEquals(12, surface.material);
        assertTrue(surface.vertexBuffer.isDirect());
        assertTrue(surface.indexBuffer.isDirect());
        assertEquals(1, surface.vertexBuffer.getFloat(64), 0);
        assertEquals(0.25f, surface.vertexBuffer.getFloat(24), 0);
        assertEquals(0.75f, surface.vertexBuffer.getFloat(28), 0);
        assertEquals(1, surface.vertexBuffer.getFloat(20), 0);
        assertEquals(0x280a141e, surface.vertexBuffer.getInt(32));
        assertEquals(0, surface.indexBuffer.getInt(0));
        assertEquals(2, surface.indexBuffer.getInt(4));
        assertEquals(1, surface.indexBuffer.getInt(8));
        assertEquals(204, EntityTriangles.bytes(3));
    }

    @Test public void geometryFingerprintDetectsAnimatedUvAndColorButNotArenaPosition() {
        ByteBuffer source = source(3);
        EntityTriangles triangles = new EntityTriangles();
        assertTrue(append(triangles, source, 3));
        long original = triangles.fingerprint();
        assertTrue(append(triangles, source, 3));
        assertEquals(original, triangles.fingerprint());
        source.putFloat(12, 1.25f);
        assertTrue(append(triangles, source, 3));
        long animated = triangles.fingerprint();
        assertNotEquals(original, animated);
        source.put(20, (byte) 99);
        assertTrue(append(triangles, source, 3));
        assertNotEquals(animated, triangles.fingerprint());
        CapturedEntityPart captured = new CapturedEntityPart();
        captured.triangles = triangles; captured.triangleGroup = new Object(); captured.vertexCount = 3;
        captured.geometryFingerprint = original;
        EntityScene.MeshKey before = new EntityScene.MeshKey(), after = new EntityScene.MeshKey();
        before.set(captured, null);
        captured.geometryFingerprint = animated;
        after.set(captured, null);
        assertNotEquals(before, after);
    }

    @Test public void malformedOrOversizedGroupsRejectAtomically() {
        EntityTriangles triangles = new EntityTriangles();
        ByteBuffer source = source(3);
        assertTrue(append(triangles, source, 3));
        source.putFloat(28, Float.NaN);
        assertFalse(append(triangles, source, 3));
        assertEquals(3, triangles.count());
        assertFalse(append(triangles, source, 2));
        assertFalse(append(triangles, source, EntityTriangles.MAX_GROUP_VERTICES + 3));
        source.putFloat(28, 1);
        source.put(26, (byte) 0);
        assertFalse(append(triangles, source, 3));
        assertEquals(3, triangles.count());
    }

    @Test public void frameArenaIsBoundedAndReusable() {
        EntityTriangles triangles = new EntityTriangles();
        ByteBuffer source = source(EntityTriangles.MAX_GROUP_VERTICES);
        for (int i = 0; i < 4; i++) assertTrue(append(triangles, source, EntityTriangles.MAX_GROUP_VERTICES));
        assertEquals(EntityTriangles.MAX_FRAME_VERTICES, triangles.count());
        assertFalse(append(triangles, source, 3));
        triangles.truncate(EntityTriangles.MAX_GROUP_VERTICES);
        assertTrue(append(triangles, source, EntityTriangles.MAX_GROUP_VERTICES));
        assertEquals(EntityTriangles.MAX_GROUP_VERTICES * 2, triangles.count());
        triangles.reset();
        assertTrue(append(triangles, source, 3));
        assertEquals(3, triangles.count());
    }

    @Test public void unrelatedBoxPartsDoNotDelayTriangleAdmission() {
        List<CapturedEntityPart> parts = new ArrayList<>();
        for (int i = 0; i < 500; i++) parts.add(new CapturedEntityPart());
        EntityTriangles triangles = new EntityTriangles();
        for (int i = 0; i < 16; i++) {
            CapturedEntityPart part = new CapturedEntityPart();
            part.triangles = triangles;
            parts.add(part);
        }
        TriangleUploadBudget budget = new TriangleUploadBudget();
        for (int frame = 0; frame < 16; frame++) assertEquals(500 + frame, budget.beginFrame(parts, parts.size()));
    }

    @Test public void uploadAdmissionBoundsBytesAndCallsWithoutStarvingLargeGroups() {
        TriangleUploadBudget budget = new TriangleUploadBudget();
        budget.beginFrame(12);
        for (int i = 0; i < TriangleUploadBudget.MAX_UPLOADS; i++) assertTrue(budget.admit(204));
        assertFalse(budget.admit(204));
        boolean[] seen = new boolean[32];
        for (int frame = 0; frame < seen.length; frame++) {
            int first = budget.beginFrame(seen.length);
            assertTrue(budget.admit(EntityTriangles.bytes(EntityTriangles.MAX_GROUP_VERTICES)));
            assertFalse(budget.admit(EntityTriangles.bytes(EntityTriangles.MAX_GROUP_VERTICES)));
            seen[first] = true;
        }
        for (boolean admitted : seen) assertTrue(admitted);
        assertEquals(0, budget.beginFrame(0));
        assertFalse(budget.admit(TriangleUploadBudget.MAX_BYTES + 1));
        assertFalse(budget.admit(-1));
    }

    @Test public void triangleDrawsUseVertexRgbaAndPreserveRepeatingUvSemantics() {
        InstanceInfoBlendEXT blend = EntityScene.blendState(0xff123456, true, 4, 12, true);
        assertEquals(InstanceInfoBlendEXT.RT_TEXTURE_ARG_VERTEX_COLOR, blend.textureColorArg2Source);
        assertEquals(InstanceInfoBlendEXT.RT_TEXTURE_ARG_VERTEX_COLOR, blend.textureAlphaArg2Source);
        assertEquals(MaterialInfo.WRAP_MODE_REPEAT, EntityTextures.wrapMode(GL11.GL_REPEAT));
        assertEquals(MaterialInfo.WRAP_MODE_MIRRORED_REPEAT, EntityTextures.wrapMode(GL14.GL_MIRRORED_REPEAT));
        assertEquals(MaterialInfo.WRAP_MODE_CLAMP, EntityTextures.wrapMode(GL11.GL_CLAMP));
    }

    private static boolean append(EntityTriangles target, ByteBuffer source, int count) {
        return target.append(source, count, 28, 12, 20, 24);
    }

    private static ByteBuffer source(int count) {
        ByteBuffer source = EntityMesh.direct(count * 28);
        for (int i = 0; i < count; i++) {
            source.putFloat(i % 3 == 1 ? 1 : 0).putFloat(i % 3 == 2 ? 1 : 0).putFloat(0);
            source.putFloat(0.25f).putFloat(0.75f);
            source.put((byte) 10).put((byte) 20).put((byte) 30).put((byte) 40);
            source.put((byte) 0).put((byte) 0).put((byte) 127).put((byte) 0);
        }
        source.flip();
        return source;
    }
}
