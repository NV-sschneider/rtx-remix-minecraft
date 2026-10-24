/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.terrain;

import java.util.Arrays;
import java.util.Collections;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.util.EnumFacing;
import org.junit.Test;
import static org.junit.Assert.*;

public class BakedBlockCaptureTest {
    @Test public void validUnlitQuadsQualifyButMalformedNeighborsRevokeTheWholeBlock() {
        BakedBlockCapture capture = new BakedBlockCapture(null); MeshPacking mesh = new MeshPacking();
        capture.beginBlock(); capture.emitQuads(mesh, Collections.singletonList(quad(0)), null, null, null, 0, 0, 0);
        assertTrue(capture.complete()); assertEquals(4, mesh.vertexCount());
        capture.beginBlock(); capture.emitQuads(mesh, Arrays.asList(quad(1), quad(0)), null, null, null, 0, 0, 0);
        assertFalse(capture.complete()); assertEquals(8, mesh.vertexCount());
        // A later block does not inherit an earlier omission.
        capture.beginBlock(); capture.emitQuads(mesh, Collections.singletonList(quad(0)), null, null, null, 0, 0, 0);
        assertTrue(capture.complete());
    }

    @Test public void missingSpritesTruncatedBuffersAndNonfiniteGeometryNeverAuthorizeSuppression() {
        for (int invalid = 1; invalid <= 3; invalid++) {
            BakedBlockCapture capture = new BakedBlockCapture(null); MeshPacking mesh = new MeshPacking();
            capture.beginBlock(); capture.emitQuads(mesh, Collections.singletonList(quad(invalid)), null, null, null, 0, 0, 0);
            assertFalse(capture.complete());
            assertEquals(invalid == 3 ? 4 : 0, mesh.vertexCount());
        }
        assertTrue(BakedBlockCapture.supportedFormat(DefaultVertexFormats.ITEM));
        assertTrue(BakedBlockCapture.supportedFormat(DefaultVertexFormats.BLOCK));
        assertFalse(BakedBlockCapture.supportedFormat(DefaultVertexFormats.POSITION));
        assertFalse(BakedBlockCapture.supportedFormat(null));
    }
    private static BakedQuad quad(int invalid) {
        VertexFormat format = DefaultVertexFormats.ITEM;
        int stride = format.getIntegerSize(), uv = format.getUvOffsetById(0) / 4;
        int[] data = new int[stride * 4]; float[] xy = {0, 0, 1, 0, 1, 1, 0, 1};
        for (int i = 0; i < 4; i++) {
            data[i * stride] = Float.floatToIntBits(xy[i * 2]);
            data[i * stride + 1] = Float.floatToIntBits(xy[i * 2 + 1]);
            data[i * stride + format.getColorOffset() / 4] = -1;
            data[i * stride + uv] = Float.floatToIntBits(xy[i * 2]);
            data[i * stride + uv + 1] = Float.floatToIntBits(xy[i * 2 + 1]);
        }
        if (invalid == 1) data[0] = Float.floatToIntBits(Float.NaN);
        if (invalid == 2) data = Arrays.copyOf(data, data.length - 1);
        TextureAtlasSprite sprite = new TextureAtlasSprite("fixture") { };
        sprite.setIconWidth(16); sprite.setIconHeight(16); sprite.initSprite(16, 16, 0, 0, false);
        return new BakedQuad(data, -1, EnumFacing.NORTH, invalid == 3 ? null : sprite, true, format);
    }
}
