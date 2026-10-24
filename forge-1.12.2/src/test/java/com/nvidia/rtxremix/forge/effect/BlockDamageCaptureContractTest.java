/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.effect;

import com.nvidia.rtxremix.HardcodedVertex;
import java.nio.ByteBuffer;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.junit.Test;
import org.lwjgl.util.vector.Matrix4f;
import static org.junit.Assert.*;

public class BlockDamageCaptureContractTest {
    @Test public void pinnedNoColorWritesOpaqueWhiteVerticesAndDoesNotUseCurrentGlColor() {
        BufferBuilder source = new BufferBuilder(256);
        source.begin(7, DefaultVertexFormats.BLOCK);
        source.noColor();
        int[] data = new int[4 * 7];
        for (int v = 0; v < 4; v++) {
            int base = v * 7;
            data[base] = Float.floatToIntBits(v == 1 || v == 2 ? 1 : 0);
            data[base + 1] = Float.floatToIntBits(v >= 2 ? 1 : 0);
            data[base + 3] = 0x10203040;
            data[base + 4] = Float.floatToIntBits(v == 1 || v == 2 ? 0.5f : 0.25f);
            data[base + 5] = Float.floatToIntBits(v >= 2 ? 0.75f : 0.5f);
            data[base + 6] = 0;
        }
        source.addVertexData(data);
        for (int v = 1; v <= 4; v++) source.putColorMultiplier(0.2f, 0.3f, 0.4f, v);
        source.finishDrawing();
        CapturedEffectBatch batch = new CapturedEffectBatch();
        batch.initialize(new Object(), 1, false, true, 0, 10, 20, 30);
        assertEquals(1, new EffectVertexDecoder().append(source.getByteBuffer(), 4, 28, 16, 12,
                0x80ffffff, new Matrix4f(), 1, batch));
        ByteBuffer output = batch.vertices;
        for (int v = 0; v < 4; v++) assertEquals(0xffffffff, output.getInt(v * HardcodedVertex.STRIDE + 32));
        assertEquals(10, batch.transform[3], 0); assertEquals(20, batch.transform[7], 0); assertEquals(30, batch.transform[11], 0);
    }
}
