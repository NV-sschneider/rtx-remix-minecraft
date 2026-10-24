/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

import com.nvidia.rtxremix.HardcodedVertex;
import com.nvidia.rtxremix.MeshInfo;
import com.nvidia.rtxremix.MeshInfoSurfaceTriangles;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;
import static org.junit.Assert.*;

public class ViewModelGeometryTest {
    @Test public void localPosePreservesFineMotionAndAllNonGeometricVertexFields() {
        MeshInfo source = mesh(); ByteBuffer input = source.surfaces[0].vertexBuffer;
        float[] pose = {0, -3, 0, 0.0004f, 2, 0, 0, 0, 0, 0, 0.1f, 0};
        ViewModelGeometry geometry = new ViewModelGeometry();
        MeshInfo result = geometry.prepare(source, pose); ByteBuffer output = result.surfaces[0].vertexBuffer;
        assertTrue(output.isDirect()); assertEquals(73, result.hash); assertEquals(91, result.surfaces[0].material);
        assertSame(source.surfaces[0].indexBuffer, result.surfaces[0].indexBuffer);
        assertEquals(source.surfaces[0].flags, result.surfaces[0].flags);
        assertEquals(-0.2996f, output.getFloat(0), 0.0000001f); assertEquals(0.4f, output.getFloat(4), 0);
        assertEquals(-0.03f, output.getFloat(8), 0.0000001f);
        assertEquals(-2 / Math.sqrt(13), output.getFloat(12), 0.000001);
        assertEquals(3 / Math.sqrt(13), output.getFloat(16), 0.000001);
        for (int vertex = 0; vertex < 3; vertex++) for (int at = 24; at < HardcodedVertex.STRIDE; at += 4) {
            int offset = vertex * HardcodedVertex.STRIDE + at;
            assertEquals(input.getInt(offset), output.getInt(offset));
        }
        assertEquals("source capture buffers remain unchanged", 0.2f, input.getFloat(0), 0);
        assertEquals(0, input.position()); assertEquals(0, output.position());
        float before = output.getFloat(0); pose[3] += 0.000004f;
        assertSame(result, geometry.prepare(source, pose));
        assertTrue(output.getFloat(0) > before);
        assertEquals(15000 + before, 15000 + output.getFloat(0), 0);
    }

    @Test public void mirroredPoseUsesTheInverseTransposeNormalSign() {
        float[] pose = {-1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0};
        ByteBuffer output = new ViewModelGeometry().prepare(mesh(), pose).surfaces[0].vertexBuffer;
        assertEquals(-0.2f, output.getFloat(0), 0);
        assertEquals(-Math.sqrt(0.5), output.getFloat(12), 0.000001);
        assertEquals(Math.sqrt(0.5), output.getFloat(16), 0.000001);
    }

    private static MeshInfo mesh() {
        MeshInfoSurfaceTriangles surface = new MeshInfoSurfaceTriangles();
        surface.vertexBuffer = ByteBuffer.allocateDirect(3 * HardcodedVertex.STRIDE).order(ByteOrder.nativeOrder());
        for (int vertex = 0; vertex < 3; vertex++) {
            HardcodedVertex.create(0.2f, 0.1f, -0.3f, 1, 1, 0, 0.27f, 0.68f, 0x7f123456).writeTo(surface.vertexBuffer);
            for (int offset = 36; offset < HardcodedVertex.STRIDE; offset += 4) surface.vertexBuffer.putInt(vertex * HardcodedVertex.STRIDE + offset, 0x12340000 + offset);
        }
        surface.vertexBuffer.flip();
        surface.indexBuffer = ByteBuffer.allocateDirect(12).order(ByteOrder.nativeOrder());
        surface.indexBuffer.putInt(0).putInt(1).putInt(2).flip();
        surface.vertexCount = surface.indexCount = 3; surface.material = 91; surface.flags = MeshInfoSurfaceTriangles.TEXCOORD_BOUNDS;
        return MeshInfo.builder().hash(73).surfaces(surface).build();
    }
}
