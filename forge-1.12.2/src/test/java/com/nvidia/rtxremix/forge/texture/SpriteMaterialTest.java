/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.texture;

import java.util.Arrays;
import java.util.Collections;
import net.minecraft.client.resources.data.AnimationFrame;
import net.minecraft.client.resources.data.AnimationMetadataSection;
import org.junit.Test;
import static org.junit.Assert.*;

public class SpriteMaterialTest {
    @Test public void sequenceOrderAndPerFrameDurationsDriveInterpolation() {
        AnimationMetadataSection metadata = new AnimationMetadataSection(Arrays.asList(
                new AnimationFrame(2, 8), new AnimationFrame(0, 4), new AnimationFrame(1, 2)), 1, 1, 1, true);
        SpriteMaterial.Sampling sample = new SpriteMaterial.Sampling();
        SpriteMaterial.sample(metadata, 3, 0, 2, sample);
        assertEquals(2, sample.first); assertEquals(0, sample.second); assertEquals(0.25f, sample.mix, 0);
        SpriteMaterial.sample(metadata, 3, 1, 2, sample);
        assertEquals(0, sample.first); assertEquals(1, sample.second); assertEquals(0.5f, sample.mix, 0);
        SpriteMaterial.sample(metadata, 3, 2, 1, sample);
        assertEquals(1, sample.first); assertEquals(2, sample.second); assertEquals(0.5f, sample.mix, 0);
    }

    @Test public void repeatedFramesAndDisabledInterpolationKeepTheCurrentFrame() {
        SpriteMaterial.Sampling sample = new SpriteMaterial.Sampling();
        AnimationMetadataSection repeated = new AnimationMetadataSection(Arrays.asList(
                new AnimationFrame(1), new AnimationFrame(1)), 1, 1, 8, true);
        SpriteMaterial.sample(repeated, 2, 0, 5, sample);
        assertEquals(1, sample.first); assertEquals(1, sample.second); assertEquals(0, sample.mix, 0);
        AnimationMetadataSection sequential = new AnimationMetadataSection(Collections.<AnimationFrame>emptyList(), 1, 1, 8, false);
        SpriteMaterial.sample(sequential, 4, 3, 5, sample);
        assertEquals(3, sample.first); assertEquals(0, sample.second); assertEquals(0, sample.mix, 0);
    }

    @Test(expected = IllegalStateException.class) public void missingFrameReferencesFailExplicitly() {
        AnimationMetadataSection metadata = new AnimationMetadataSection(Arrays.asList(new AnimationFrame(5)), 1, 1, 1, true);
        SpriteMaterial.sample(metadata, 2, 0, 0, new SpriteMaterial.Sampling());
    }
}
