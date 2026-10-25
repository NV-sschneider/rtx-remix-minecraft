/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.item;

import com.nvidia.rtxremix.HardcodedVertex;
import com.nvidia.rtxremix.InstanceInfoBlendEXT;
import com.nvidia.rtxremix.MeshInfoSurfaceTriangles;
import java.nio.ByteBuffer;
import org.junit.Test;
import org.lwjgl.util.vector.Matrix4f;
import org.lwjgl.util.vector.Vector3f;
import static org.junit.Assert.*;

public class ItemGeometryTest {
    private static final int STRIDE = 28;
    private static ByteBuffer quad() {
        ByteBuffer buffer = ItemGeometry.direct(8 * STRIDE);
        // The first quad belongs to an earlier Forge batch entry and must not be captured again.
        for (int i = 0; i < 4 * STRIDE; i++) buffer.put((byte) 0x7f);
        for (int v = 0; v < 4; v++) {
            buffer.putFloat(v == 1 || v == 2 ? 1 : 0).putFloat(v >= 2 ? 1 : 0).putFloat(0.25f);
            buffer.put((byte) 0x24).put((byte) 0x68).put((byte) 0xac).put((byte) 0x80);
            buffer.putFloat(v == 1 || v == 2 ? 0.7f : 0.5f).putFloat(v >= 2 ? 0.7f : 0.5f);
            buffer.put((byte) 0).put((byte) 0).put((byte) 127).put((byte) 0);
        }
        buffer.flip();
        return buffer;
    }
    private static boolean append(ItemGeometry geometry, ByteBuffer input) {
        return geometry.append(input, 4, STRIDE, 16, 12, 24, 0.5f, 0.5f, 0.75f, 0.75f);
    }

    @Test public void capturesOnlyTheCompletedQuadWithFinalTintAlphaNormalsAndSpriteBounds() {
        ItemGeometry geometry = new ItemGeometry();
        ByteBuffer input = quad();
        input.position(19);
        assertTrue(append(geometry, input));
        MeshInfoSurfaceTriangles surface = geometry.info(0, 4, 11, 13).surfaces[0];
        assertEquals(4, surface.vertexCount);
        assertEquals(6, surface.indexCount);
        assertEquals(11, surface.material);
        assertEquals(MeshInfoSurfaceTriangles.TEXCOORD_BOUNDS, surface.flags);
        assertTrue(surface.vertexBuffer.isDirect());
        assertTrue(surface.indexBuffer.isDirect());
        for (int v = 0; v < 4; v++) {
            int base = v * HardcodedVertex.STRIDE;
            assertEquals(v == 1 || v == 2 ? 1 : 0, surface.vertexBuffer.getFloat(base), 0);
            assertEquals(0.25f, surface.vertexBuffer.getFloat(base + 8), 0);
            assertEquals(1, surface.vertexBuffer.getFloat(base + 20), 0);
            assertEquals(0x802468ac, surface.vertexBuffer.getInt(base + 32));
            assertEquals(HardcodedVertex.packHalf2x16(0.5f, 0.5f), surface.vertexBuffer.getInt(base + 36));
            assertEquals(HardcodedVertex.packHalf2x16(0.75f, 0.75f), surface.vertexBuffer.getInt(base + 40));
        }
        int[] winding = {0, 2, 1, 0, 3, 2};
        for (int i = 0; i < winding.length; i++) assertEquals(winding[i], surface.indexBuffer.getInt(i * 4));
        assertEquals(19, input.position());
    }

    @Test public void changedTintInvalidatesAMeshWhileRejectedGeometryPreservesTheEarlierSnapshot() {
        ItemGeometry geometry = new ItemGeometry();
        ByteBuffer input = quad();
        assertTrue(append(geometry, input));
        long original = geometry.fingerprint(0, 4);
        input.put(4 * STRIDE + 12, (byte) 0x55);
        assertTrue(append(geometry, input));
        assertNotEquals(original, geometry.fingerprint(4, 4));
        assertEquals(original, geometry.fingerprint(0, 4));
        input.putFloat(4 * STRIDE, Float.NaN);
        assertFalse(append(geometry, input));
        assertEquals(8, geometry.count());
        assertEquals(original, geometry.fingerprint(0, 4));
        assertFalse(geometry.append(input, Integer.MAX_VALUE, STRIDE, 16, 12, 24, 0, 0, 1, 1));
    }

    @Test public void frameCapacityRejectsAdditionalGeometryWithoutOverwritingEarlierItems() {
        ItemGeometry geometry = new ItemGeometry();
        ByteBuffer input = quad();
        for (int i = 0; i < ItemGeometry.MAX_VERTICES; i += 4) assertTrue(append(geometry, input));
        long original = geometry.fingerprint(0, 4);
        assertFalse(append(geometry, input));
        assertEquals(ItemGeometry.MAX_VERTICES, geometry.count());
        assertEquals(original, geometry.fingerprint(0, 4));
        geometry.truncate(0);
        assertTrue(append(geometry, input));
        assertEquals(original, geometry.fingerprint(0, 4));
    }

    @Test public void itemPhysicGroundRotationSurvivesCameraMovementAndModelCentering() {
        Matrix4f item = new Matrix4f();
        item.translate(new Vector3f(3, 4, 5));
        item.rotate((float) Math.PI / 2, new Vector3f(1, 0, 0));
        item.scale(new Vector3f(0.5f, 0.5f, 0.5f));
        item.translate(new Vector3f(-0.5f, -0.5f, -0.5f));
        for (float angle : new float[] {0.3f, 1.7f}) {
            Matrix4f view = new Matrix4f();
            view.rotate(angle, new Vector3f(0, 1, 0));
            float[] transform = new float[12];
            ItemTransform.world(Matrix4f.invert(view, null), Matrix4f.mul(view, item, null), new Matrix4f(), 100, 200, 300, transform);
            assertArrayEquals(new float[] {0.5f, 0, 0, 102.75f, 0, 0, -0.5f, 204.25f, 0, 0.5f, 0, 304.75f}, transform, 0.0001f);
        }
    }

    @Test public void transparentItemsKeepVertexTintAndTheirObservedAlphaTest() {
        CapturedItem item = new CapturedItem();
        item.alphaBlend = true;
        item.alphaCompare = 4;
        item.alphaReference = 26;
        InstanceInfoBlendEXT blend = ItemScene.blend(item);
        assertEquals(1, blend.alphaTestEnabled);
        assertEquals(4, blend.alphaTestCompareOp);
        assertEquals(26, blend.alphaTestReferenceValue & 255);
        assertEquals(InstanceInfoBlendEXT.textureTimesVertexColor().textureColorArg2Source, blend.textureColorArg2Source);
    }

    @Test public void emittedUvAndVertexAlphaMustBothSupportTheSpriteProof() {
        ItemGeometry geometry = new ItemGeometry(); ByteBuffer input = quad();
        assertTrue(append(geometry, input));
        assertTrue(geometry.lastQuadBounded()); assertFalse(geometry.lastQuadOpaque());
        for (int v = 0; v < 4; v++) input.put((4 + v) * STRIDE + 15, (byte) 255);
        assertTrue(append(geometry, input));
        assertTrue(geometry.lastQuadBounded()); assertTrue(geometry.lastQuadOpaque());
        // A custom item may sample outside the nominal sprite. Keep its geometry and ordinary
        // atlas UVs, but do not clamp the surface or infer alpha from the wrong sprite.
        input.putFloat(4 * STRIDE + 16, 0.49f);
        assertTrue(append(geometry, input)); assertFalse(geometry.lastQuadBounded());
        MeshInfoSurfaceTriangles surface = geometry.info(8, 4, 11, 13, false).surfaces[0];
        assertEquals(0, surface.flags); assertEquals(0.49f, surface.vertexBuffer.getFloat(24), 0);
        assertEquals(0xff2468ac, surface.vertexBuffer.getInt(32));
        // Even a binary 0/255 vertex gradient interpolates fractional opacity across the quad.
        input.putFloat(4 * STRIDE + 16, 0.5f); input.put(4 * STRIDE + 15, (byte) 0);
        assertTrue(append(geometry, input)); assertFalse(geometry.lastQuadOpaque());
    }
}
