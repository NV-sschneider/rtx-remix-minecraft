/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

import com.nvidia.rtxremix.MeshInfoSurfaceTriangles;
import java.nio.ByteBuffer;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.model.ModelBox;
import net.minecraft.client.model.ModelRenderer;
import org.junit.Test;
import static org.junit.Assert.*;

public class EntityMeshTest {
    private static ModelRenderer box(boolean mirror) {
        ModelRenderer part = new ModelRenderer(new ModelBase() {}, 0, 0);
        part.mirror = mirror;
        part.addBox(0, 0, 0, 2, 4, 6);
        return part;
    }

    @Test public void packsScaledCuboidFacesIntoTheNativeAbi() {
        EntityMesh mesh = new EntityMesh();
        assertTrue(mesh.prepare(box(false), 0.5f, 19));
        MeshInfoSurfaceTriangles surface = mesh.info(7).surfaces[0];
        assertEquals(24, surface.vertexCount);
        assertEquals(36, surface.indexCount);
        assertEquals(19, surface.material);
        assertTrue(surface.vertexBuffer.isDirect());
        assertTrue(surface.indexBuffer.isDirect());
        assertEquals(24 * 64 + 36 * 4, mesh.bytes());
        for (int i = 0; i < surface.vertexCount; i++) {
            ByteBuffer vertices = surface.vertexBuffer;
            int offset = i * 64;
            assertTrue(vertices.getFloat(offset) >= 0 && vertices.getFloat(offset) <= 1);
            assertTrue(vertices.getFloat(offset + 4) >= 0 && vertices.getFloat(offset + 4) <= 2);
            assertTrue(vertices.getFloat(offset + 8) >= 0 && vertices.getFloat(offset + 8) <= 3);
            assertEquals(0xFFFFFFFF, vertices.getInt(offset + 32));
            for (int pad = 36; pad < 64; pad += 4) assertEquals(0, vertices.getInt(offset + pad));
        }
        int[] expected = {0, 2, 1, 0, 3, 2};
        for (int i = 0; i < expected.length; i++) assertEquals(expected[i], surface.indexBuffer.getInt(i * 4));
    }

    @Test public void mirroredFacesKeepUnitOutwardNormals() {
        EntityMesh mesh = new EntityMesh();
        assertTrue(mesh.prepare(box(true), 1, 1));
        ByteBuffer vertices = mesh.info(1).surfaces[0].vertexBuffer;
        for (int i = 0; i < 24; i++) {
            int offset = i * 64;
            float nx = vertices.getFloat(offset + 12), ny = vertices.getFloat(offset + 16), nz = vertices.getFloat(offset + 20);
            assertEquals(1, nx * nx + ny * ny + nz * nz, 0.00001);
            float fromCenter = nx * (vertices.getFloat(offset) - 1)
                    + ny * (vertices.getFloat(offset + 4) - 2) + nz * (vertices.getFloat(offset + 8) - 3);
            assertTrue(fromCenter > 0);
        }
    }

    @Test public void skipsOverriddenBoxesAndRejectsOversizedModels() {
        ModelRenderer part = new ModelRenderer(new ModelBase() {}, 0, 0);
        part.cubeList.add(new ModelBox(part, 0, 0, 0, 0, 0, 1, 1, 1, 0) {});
        EntityMesh mesh = new EntityMesh();
        assertFalse(mesh.prepare(part, 1, 1));
        ModelBox ordinary = new ModelBox(part, 0, 0, 0, 0, 0, 1, 1, 1, 0);
        for (int i = 0; i <= EntityMesh.MAX_QUADS / 6; i++) part.cubeList.add(ordinary);
        assertFalse(mesh.prepare(part, 1, 1));
        assertFalse(mesh.prepare(box(false), Float.NaN, 1));
    }

    @Test public void scratchReuseResetsGeometryAndMaterial() {
        EntityMesh mesh = new EntityMesh();
        ModelRenderer part = box(false);
        assertTrue(mesh.prepare(part, 1, 2));
        ByteBuffer first = mesh.info(1).surfaces[0].vertexBuffer;
        assertTrue(mesh.prepare(part, 0.5f, 3));
        MeshInfoSurfaceTriangles surface = mesh.info(2).surfaces[0];
        assertSame(first, surface.vertexBuffer);
        assertEquals(24, surface.vertexCount);
        assertEquals(3, surface.material);
        assertEquals(0, surface.vertexBuffer.position());
        assertEquals(24 * 64, surface.vertexBuffer.remaining());
    }
}
