/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.effect;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import org.lwjgl.util.vector.Matrix4f;

/** Adapts the completed BufferBuilder contract at WorldVertexBufferUploader's entry. */
final class FinishedEffectBuffer {
    // Fancy Block Particles writes this unpadded format through BufferBuilder's
    // ordinary per-vertex methods. No third-party implementation is linked here.
    private static final VertexFormat UNPADDED_PARTICLE = new VertexFormat()
            .addElement(DefaultVertexFormats.POSITION_3F).addElement(DefaultVertexFormats.TEX_2F)
            .addElement(DefaultVertexFormats.COLOR_4UB).addElement(DefaultVertexFormats.TEX_2S)
            .addElement(DefaultVertexFormats.NORMAL_3B);

    private FinishedEffectBuffer() {}

    static int append(EffectVertexDecoder decoder, BufferBuilder buffer, int fallbackColor,
                      Matrix4f transform, int allowed, CapturedEffectBatch target) {
        ByteBuffer source = buffer.getByteBuffer();
        VertexFormat format = buffer.getVertexFormat();
        int vertices = buffer.getVertexCount(), stride = format.getSize();
        int position = source.position(), limit = source.limit();
        long extent = (long) vertices * stride;
        boolean expanded = extent > limit && buffer.getDrawMode() == 7 && vertices > 0 && vertices % 4 == 0
                && stride == 31 && UNPADDED_PARTICLE.equals(format)
                && source.isDirect() && source.order() == ByteOrder.nativeOrder() && position == 0
                && limit == (long) vertices * 28 && extent <= source.capacity();
        // Pinned finishDrawing uses vertexCount * getIntegerSize() * 4, rounding
        // each 31-byte vertex down to 28. The uploader nevertheless passes the
        // full 31-byte stride and vertex count to glDrawArrays. Only normalize
        // that exact completed-buffer shape; arbitrary truncated inputs stay invalid.
        if (expanded) source.limit((int) extent);
        try {
            return decoder.append(source, vertices, buffer.getDrawMode(), stride, format.getUvOffsetById(0),
                    format.hasColor() ? format.getColorOffset() : -1, fallbackColor, transform, allowed, target);
        } finally {
            if (expanded) { source.limit(limit); source.position(position); }
        }
    }
}
