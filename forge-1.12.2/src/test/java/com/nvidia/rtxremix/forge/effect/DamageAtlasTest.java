/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.effect;

import com.nvidia.rtxremix.HardcodedVertex;
import java.nio.ByteBuffer;
import org.junit.Test;
import static org.junit.Assert.*;

public class DamageAtlasTest {
    @Test public void coveragePreservesDisplaySpaceDarkeningAfterTheRuntimeGammaTransfer() {
        assertEquals(255, DamageAtlas.coverage(0xff000000));
        assertEquals(205, DamageAtlas.coverage(0xff3d3d3d));
        assertEquals(0, DamageAtlas.coverage(0xff9b9b9b));
        assertEquals(0, DamageAtlas.coverage(0xff808080));
        assertEquals(0, DamageAtlas.coverage(0x01ffffff));
        assertEquals(0, DamageAtlas.coverage(0x19000000));
        assertEquals(255, DamageAtlas.coverage(0x1a000000));
        assertEquals(255, DamageAtlas.coverage(0x1b000000));
        assertEquals(255, DamageAtlas.coverage(0x80000000));
        for (int gray = 0; gray <= 255; gray++) {
            double transmission = Math.min(1, 2 * gray / 255.0);
            double retainedBase = 1 - DamageAtlas.coverage(0xff000000 | gray * 0x010101) / 255.0;
            assertEquals(Math.pow(transmission, 2.2), retainedBase, .5 / 255 + 1e-12);
            // Check the material-space multiplication, not just the lookup formula.
            for (double displayBase : new double[] {.1, .4, 1}) {
                double expectedLinear = Math.pow(displayBase * transmission, 2.2);
                double actualLinear = Math.pow(displayBase, 2.2) * retainedBase;
                assertEquals(expectedLinear, actualLinear, .5 / 255 + 1e-12);
            }
        }
    }

    @Test public void sourceAlphaIsOnlyTheCapturedRejectGateNotASecondBlendWeight() {
        for (int gray : new int[] {0, 61, 100, 127, 128, 155, 255}) for (int alpha = 0; alpha <= 255; alpha++) {
            int rgb = gray * 0x010101;
            assertEquals(alpha <= 25 ? 0 : DamageAtlas.coverage(0xff000000 | rgb),
                    DamageAtlas.coverage(alpha << 24 | rgb));
        }
    }

    @Test public void compactAtlasPadsBlackCoverageAndRemapsAllFourVerticesWithoutMutatingCapture() {
        DamageAtlas.Source[] sources = new DamageAtlas.Source[10];
        sources[3] = new DamageAtlas.Source(.2f, .3f, .4f, .5f, 2, 2,
                new int[] {0xff000000, 0xff3d3d3d, 0xff9b9b9b, 0x01ffffff});
        DamageAtlas atlas = new DamageAtlas(sources);
        assertEquals(40, atlas.width); assertEquals(4, atlas.height);
        int pixel = (atlas.width + 13) * 4;
        assertEquals(0, atlas.pixels.get(pixel)); assertEquals(255, atlas.pixels.get(pixel + 3) & 255);
        assertEquals(205, atlas.pixels.get(pixel + 7) & 255);
        assertEquals(255, atlas.pixels.get(12 * 4 + 3) & 255); // repeated corner padding
        CapturedEffectBatch batch = damage(.2f, .3f, .4f, .5f);
        byte[] original = new byte[batch.vertices.limit()];
        for (int i = 0; i < original.length; i++) original[i] = batch.vertices.get(i);
        int position = batch.vertices.position(), limit = batch.vertices.limit();
        ByteBuffer mapped = atlas.remap(batch);
        assertNotNull(mapped); assertTrue(mapped.isDirect());
        assertEquals(13f / 40, mapped.getFloat(24), .000001f);
        assertEquals(1f / 4, mapped.getFloat(28), .000001f);
        assertEquals(15f / 40, mapped.getFloat(HardcodedVertex.STRIDE + 24), .000001f);
        assertEquals(HardcodedVertex.packHalf2x16(13f / 40, 1f / 4), mapped.getInt(36));
        assertEquals(HardcodedVertex.packHalf2x16(15f / 40, 3f / 4), mapped.getInt(40));
        assertSame(mapped, atlas.remap(batch));
        assertEquals(position, batch.vertices.position()); assertEquals(limit, batch.vertices.limit());
        for (int i = 0; i < original.length; i++) assertEquals(original[i], batch.vertices.get(i));
        assertEquals((long) atlas.pixels.capacity() + 4 * HardcodedVertex.STRIDE, atlas.retainedBytes());
    }

    @Test public void missingMalformedColoredAndOverBudgetSpritesCannotBecomeCrackMaterials() {
        DamageAtlas.Source[] sources = new DamageAtlas.Source[10];
        sources[0] = new DamageAtlas.Source(0, 0, 1, 1, 1, 1, new int[] {0xffff0000});
        sources[1] = new DamageAtlas.Source(0, 0, 1, 1, 1, 2, new int[] {-1});
        sources[2] = new DamageAtlas.Source(0, 0, 1, 1, DamageAtlas.MAX_SIDE + 1, 1, null);
        sources[3] = new DamageAtlas.Source(0, 0, Float.NaN, 1, 1, 1, new int[] {-1});
        DamageAtlas atlas = new DamageAtlas(sources);
        assertEquals(30 * 3 * 4, atlas.pixels.capacity());
        assertNull(atlas.remap(damage(0, 0, 1, 1)));
    }

    @Test public void rejectsMixedSpritesTintedAndNonDamageGeometryInsteadOfChangingOrdinaryParticles() {
        DamageAtlas atlas = full();
        CapturedEffectBatch batch = damage(0, 0, 1, 1);
        batch.damage = false; assertNull(atlas.remap(batch)); batch.damage = true;
        batch.blockAtlas = false; assertNull(atlas.remap(batch)); batch.blockAtlas = true;
        batch.triangles = true; assertNull(atlas.remap(batch)); batch.triangles = false;
        batch.vertices.putInt(32, 0x7fffffff); assertNull(atlas.remap(batch)); batch.vertices.putInt(32, -1);
        batch.vertices.putFloat(24, -.01f); assertNull(atlas.remap(batch)); batch.vertices.putFloat(24, 0);
        batch.vertices.putFloat(28, Float.NaN); assertNull(atlas.remap(batch)); batch.vertices.putFloat(28, 0);
        assertNotNull(atlas.remap(batch));
    }

    static DamageAtlas full() {
        DamageAtlas.Source[] sources = new DamageAtlas.Source[10];
        sources[0] = new DamageAtlas.Source(0, 0, 1, 1, 1, 1, new int[] {0xff3d3d3d});
        return new DamageAtlas(sources);
    }

    static CapturedEffectBatch damage(float minU, float minV, float maxU, float maxV) {
        CapturedEffectBatch batch = new CapturedEffectBatch();
        batch.initialize(new Object(), 1, false, true,
                EffectBlend.key(true, true, 774, 768, 32774, 1, 0, 32774, true, 516, 26), 10, 20, 30);
        batch.damage = true; batch.quads = 1;
        for (int i = 0; i < 4; i++) {
            HardcodedVertex.create(i % 2, i / 2, 0, 0, 0, 1,
                    i % 2 == 0 ? minU : maxU, i / 2 == 0 ? minV : maxV, -1).writeTo(batch.vertices);
        }
        batch.vertices.flip();
        return batch;
    }
}
