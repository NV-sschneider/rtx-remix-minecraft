/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.effect;

import com.nvidia.rtxremix.HardcodedVertex;
import java.nio.ByteBuffer;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import org.junit.Test;
import org.lwjgl.util.vector.Matrix4f;
import static org.junit.Assert.*;

public class FinishedEffectBufferTest {
    @Test public void completedUnpaddedCubeKeepsAllSixFacesAndOriginalBufferState() {
        BufferBuilder source = cube();
        ByteBuffer bytes = source.getByteBuffer();
        assertEquals(31, source.getVertexFormat().getSize());
        assertEquals(24, source.getVertexCount());
        assertEquals(24 * 28, bytes.limit());
        byte[] original = payload(bytes, 24 * 31);
        CapturedEffectBatch target = new CapturedEffectBatch();
        assertEquals(6, decode(source, 6, target));
        assertEquals(6, target.quads);
        assertEquals(24 * HardcodedVertex.STRIDE, target.vertices.position());
        // The final vertex lies beyond finishDrawing's shortened limit.
        int last = 23 * HardcodedVertex.STRIDE;
        assertEquals(1, target.vertices.getFloat(last), 0);
        assertEquals(0, target.vertices.getFloat(last + 4), 0);
        assertEquals(0, target.vertices.getFloat(last + 8), 0);
        assertEquals(0x78123456, target.vertices.getInt(last + 32));
        assertEquals(0, bytes.position());
        assertEquals(24 * 28, bytes.limit());
        assertArrayEquals(original, payload(bytes, 24 * 31));
    }

    @Test public void rawDecoderStillRejectsTheShortLimitAndAdapterRestoresAfterFailure() {
        BufferBuilder source = cube();
        CapturedEffectBatch target = new CapturedEffectBatch();
        assertThrows(IllegalArgumentException.class, () -> new EffectVertexDecoder().append(source.getByteBuffer(),
                24, 31, 12, 20, -1, new Matrix4f(), 6, target));
        target.triangles = true;
        assertThrows(IllegalArgumentException.class, () -> decode(source, 6, target));
        assertEquals(0, target.quads);
        assertEquals(0, source.getByteBuffer().position());
        assertEquals(24 * 28, source.getByteBuffer().limit());
    }

    @Test public void normalizationDoesNotBypassThePrimitiveBudget() {
        BufferBuilder source = cube();
        CapturedEffectBatch target = new CapturedEffectBatch();
        assertEquals(2, decode(source, 2, target));
        assertEquals(2, target.quads);
        assertEquals(24 * 28, source.getByteBuffer().limit());
    }

    @Test public void arbitraryTruncationEmptyExtentAndNonzeroCursorStayRejected() {
        BufferBuilder source = cube();
        ByteBuffer bytes = source.getByteBuffer();
        for (int limit : new int[] {0, 24 * 28 - 1, 24 * 28 + 1, 24 * 31 - 1}) {
            bytes.limit(limit);
            assertThrows(IllegalArgumentException.class, () -> decode(source, 6, new CapturedEffectBatch()));
            assertEquals(limit, bytes.limit());
            assertEquals(0, bytes.position());
        }
        bytes.limit(24 * 28).position(1);
        assertThrows(IllegalArgumentException.class, () -> decode(source, 6, new CapturedEffectBatch()));
        assertEquals(1, bytes.position());
        assertEquals(24 * 28, bytes.limit());
    }

    @Test public void exactRoundedLimitStillRequiresDirectNativeStorageAndFullCapacity() {
        BufferBuilder completed = cube();
        for (ByteBuffer bytes : new ByteBuffer[] {
                CapturedEffectBatch.direct(24 * 28),
                ByteBuffer.allocate(24 * 31).order(java.nio.ByteOrder.nativeOrder()),
                CapturedEffectBatch.direct(24 * 31).order(java.nio.ByteOrder.nativeOrder()
                        == java.nio.ByteOrder.LITTLE_ENDIAN ? java.nio.ByteOrder.BIG_ENDIAN : java.nio.ByteOrder.LITTLE_ENDIAN)}) {
            bytes.limit(24 * 28);
            BufferBuilder malformed = new BufferBuilder(1) {
                @Override public ByteBuffer getByteBuffer() { return bytes; }
                @Override public int getVertexCount() { return 24; }
                @Override public int getDrawMode() { return 7; }
                @Override public VertexFormat getVertexFormat() { return completed.getVertexFormat(); }
            };
            assertThrows(IllegalArgumentException.class, () -> decode(malformed, 6, new CapturedEffectBatch()));
            assertEquals(24 * 28, bytes.limit());
        }
    }

    @Test public void otherUnpaddedFormatsDoNotGainCapacityBasedReads() {
        BufferBuilder source = new BufferBuilder(128);
        source.begin(7, new VertexFormat().addElement(DefaultVertexFormats.POSITION_3F)
                .addElement(DefaultVertexFormats.TEX_2F).addElement(DefaultVertexFormats.NORMAL_3B));
        for (int i = 0; i < 4; i++) source.pos(i & 1, i >> 1, 0).tex(0, 0).normal(0, 0, 1).endVertex();
        source.finishDrawing();
        assertEquals(23, source.getVertexFormat().getSize());
        assertEquals(80, source.getByteBuffer().limit());
        assertThrows(IllegalArgumentException.class, () -> decode(source, 1, new CapturedEffectBatch()));
        assertEquals(80, source.getByteBuffer().limit());
    }

    private static int decode(BufferBuilder source, int allowed, CapturedEffectBatch target) {
        return FinishedEffectBuffer.append(new EffectVertexDecoder(), source, -1, new Matrix4f(), allowed, target);
    }

    private static BufferBuilder cube() {
        BufferBuilder source = new BufferBuilder(256);
        source.begin(7, new VertexFormat().addElement(DefaultVertexFormats.POSITION_3F)
                .addElement(DefaultVertexFormats.TEX_2F).addElement(DefaultVertexFormats.COLOR_4UB)
                .addElement(DefaultVertexFormats.TEX_2S).addElement(DefaultVertexFormats.NORMAL_3B));
        float[][] faces = {
                {0,0,0, 1,0,0, 1,1,0, 0,1,0}, {1,0,1, 0,0,1, 0,1,1, 1,1,1},
                {0,0,1, 0,0,0, 0,1,0, 0,1,1}, {1,0,0, 1,0,1, 1,1,1, 1,1,0},
                {0,1,0, 1,1,0, 1,1,1, 0,1,1}, {0,0,0, 0,0,1, 1,0,1, 1,0,0}};
        for (float[] face : faces) for (int vertex = 0; vertex < 4; vertex++) {
            source.pos(face[vertex * 3], face[vertex * 3 + 1], face[vertex * 3 + 2])
                    .tex(vertex & 1, vertex >> 1).color(0x12, 0x34, 0x56, 0x78)
                    .lightmap(240, 240).normal(0, 0, 1).endVertex();
        }
        source.finishDrawing();
        return source;
    }

    private static byte[] payload(ByteBuffer source, int extent) {
        byte[] result = new byte[extent];
        ByteBuffer view = source.duplicate();
        view.clear().limit(extent);
        view.get(result);
        return result;
    }
}
