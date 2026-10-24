/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.terrain;

import com.nvidia.rtxremix.HardcodedVertex;
import java.nio.ByteBuffer;
import org.junit.Test;
import static org.junit.Assert.*;

public class BlockMeshDecoderTest {
    private static final float[] QUAD = {0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0};
    private static final float[] UV = {0.2f, 0.3f, 0.4f, 0.3f, 0.4f, 0.5f, 0.2f, 0.5f};
    private static final float[] METADATA = {0.2f, 0.3f, 0.4f, 0.5f, Float.NaN, 0, 0};

    @Test public void preservesPostprocessedMaterialColorAndSpriteBoundsWithoutReadingLightmap() {
        ByteBuffer input = input(4); input.position(7);
        MeshPacking packed = new MeshPacking(); new BlockMeshDecoder().append(input, 4, METADATA, packed);
        assertEquals(7, input.position()); assertEquals(4, packed.vertexCount());
        ByteBuffer output = packed.vertices();
        for (int v = 0; v < 4; v++) {
            int base = v * MeshPacking.STRIDE;
            assertEquals(QUAD[v * 3], output.getFloat(base), 0);
            assertEquals(QUAD[v * 3 + 1], output.getFloat(base + 4), 0);
            assertEquals(UV[v * 2], output.getFloat(base + 24), 0);
            assertEquals(0xa02468ac, output.getInt(base + 32));
            assertEquals(HardcodedVertex.packHalf2x16(0.2f, 0.3f), output.getInt(base + 36));
            assertEquals(HardcodedVertex.packHalf2x16(0.4f, 0.5f), output.getInt(base + 40));
        }
        assertEquals(1, output.getFloat(20), 0);
    }

    @Test public void keepsAnExplicitBakedNormalAndRollsBackTheWholeMalformedBlock() {
        MeshPacking packed = new MeshPacking(); BlockMeshDecoder decoder = new BlockMeshDecoder();
        float[] normal = METADATA.clone(); normal[4] = 0; normal[5] = 1; normal[6] = 0;
        decoder.append(input(4), 4, normal, packed); assertEquals(1, packed.vertices().getFloat(16), 0);
        ByteBuffer two = input(8); two.putFloat(4 * 28 + 16, Float.NaN);
        float[] metadata = new float[14]; System.arraycopy(METADATA, 0, metadata, 0, 7); System.arraycopy(METADATA, 0, metadata, 7, 7);
        try { decoder.append(two, 8, metadata, packed); fail("Expected malformed UV rejection"); }
        catch (IllegalArgumentException expected) { assertEquals(4, packed.vertexCount()); }
    }

    @Test public void rejectsOversizePartialAndMissingSpriteDataBeforePublication() {
        BlockMeshDecoder decoder = new BlockMeshDecoder(); MeshPacking target = new MeshPacking();
        for (int vertices : new int[] {-4, 3, BlockMeshDecoder.MAX_VERTICES + 4}) {
            try { decoder.append(input(4), vertices, METADATA, target); fail("Expected bound rejection"); }
            catch (IllegalArgumentException expected) { assertEquals(0, target.vertexCount()); }
        }
        float[] missing = METADATA.clone(); missing[0] = Float.NaN;
        try { decoder.append(input(4), 4, missing, target); fail("Expected missing sprite rejection"); }
        catch (IllegalArgumentException expected) { assertEquals(0, target.vertexCount()); }
    }

    @Test public void skippedDegenerateGeometryRevokesReplacementEligibilityUntilNextBlock() {
        BlockMeshDecoder decoder = new BlockMeshDecoder(); MeshPacking target = new MeshPacking();
        ByteBuffer collapsed = input(4);
        for (int v = 0; v < 4; v++) for (int axis = 0; axis < 3; axis++) collapsed.putFloat(v * 28 + axis * 4, 0);
        decoder.append(collapsed, 4, METADATA, target);
        assertEquals(0, target.vertexCount()); assertFalse(decoder.complete());
        decoder.append(input(4), 4, METADATA, target);
        assertEquals(4, target.vertexCount()); assertTrue(decoder.complete());
    }

    private static ByteBuffer input(int count) {
        ByteBuffer data = MeshPacking.direct(count * 28);
        for (int i = 0; i < count; i++) {
            int v = i & 3; data.putFloat(QUAD[v * 3]).putFloat(QUAD[v * 3 + 1]).putFloat(QUAD[v * 3 + 2]);
            data.put((byte) 0x24).put((byte) 0x68).put((byte) 0xac).put((byte) 0xa0);
            data.putFloat(UV[v * 2]).putFloat(UV[v * 2 + 1]).putInt(i * 23456);
        }
        data.flip(); return data;
    }
}
