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

public class FallingTreeBufferTest {
    @Test public void blockQuadsDeriveNormalsWithoutInterpretingLightmapAsNormalOrColor() {
        // Dynamic Trees submits DefaultVertexFormats.BLOCK: XYZ, RGBA, UV0, UV1.
        ByteBuffer source = EntityMesh.direct(4 * 28);
        float[] xz = {0, 0, 0, 1, 1, 1, 1, 0};
        for (int corner = 0; corner < 4; corner++) {
            source.putFloat(xz[corner * 2]).putFloat(2).putFloat(xz[corner * 2 + 1]);
            source.put((byte) 80).put((byte) 160).put((byte) 40).put((byte) 255);
            source.putFloat(0.25f + xz[corner * 2] / 8).putFloat(0.5f + xz[corner * 2 + 1] / 8);
            source.putShort((short) 240).putShort((short) 112);
        }
        source.flip(); source.position(3).mark().position(9);
        EntityTriangles triangles = new EntityTriangles();
        assertTrue(triangles.appendPrimitives(source, 4, 4, 28, 16, 12, -1, -1, new Matrix4f()));
        MeshInfoSurfaceTriangles mesh = triangles.info(0, 6, 7, 9).surfaces[0];
        for (int vertex = 0; vertex < 6; vertex++) {
            int offset = vertex * 64;
            assertEquals(0, mesh.vertexBuffer.getFloat(offset + 12), 0);
            assertEquals(1, mesh.vertexBuffer.getFloat(offset + 16), 0);
            assertEquals(0, mesh.vertexBuffer.getFloat(offset + 20), 0);
            assertEquals(0xff50a028, mesh.vertexBuffer.getInt(offset + 32));
        }
        assertEquals(0.25f, mesh.vertexBuffer.getFloat(24), 0);
        assertEquals(0.5f, mesh.vertexBuffer.getFloat(28), 0);
        assertEquals(0, mesh.indexBuffer.getInt(0)); assertEquals(2, mesh.indexBuffer.getInt(4));
        assertEquals(1, mesh.indexBuffer.getInt(8));
        assertEquals(9, source.position()); source.reset(); assertEquals(3, source.position());
        assertEquals(240, source.getShort(24)); assertEquals(112, source.getShort(26));
    }

    @Test public void animatedWholeTreePoseUsesCurrentMatrixAndKeepsItsWorldPivot() {
        Matrix4f view = new Matrix4f(); view.translate(new org.lwjgl.util.vector.Vector3f(-3, -4, -5));
        Matrix4f inverse = Matrix4f.invert(view, null);
        Matrix4f pose = new Matrix4f(); pose.translate(new org.lwjgl.util.vector.Vector3f(7, 8, 9));
        pose.rotate((float) Math.PI / 3, new org.lwjgl.util.vector.Vector3f(0, 0, 1));
        Matrix4f live = Matrix4f.mul(view, pose, null);
        float[] captured = new float[12];
        EntityTransform.world(inverse, live, new Matrix4f(), 1024, 64, -2048, captured);
        assertEquals(1031, captured[3], 0); assertEquals(72, captured[7], 0); assertEquals(-2039, captured[11], 0);
        assertEquals(0.5f, captured[0], 0.00001f);
        assertEquals(-(float) Math.sqrt(3) / 2, captured[1], 0.00001f);
        assertEquals((float) Math.sqrt(3) / 2, captured[4], 0.00001f);
    }
}
