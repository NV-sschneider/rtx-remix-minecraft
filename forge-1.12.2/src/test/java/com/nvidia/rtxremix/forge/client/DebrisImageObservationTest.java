/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.util.math.BlockPos;
import org.junit.Test;
import static org.junit.Assert.*;

public class DebrisImageObservationTest {
    private final int[] target = {8, 8, 48, 48}, control = {56, 8, 72, 48};

    @Test public void blackSilhouettesShadowsAndUnchangedImagesCannotProveLitDebris() {
        DebrisImageObservation probe = probe(false);
        assertFalse(probe.sample(image(0x303030), 1, 1).visible);
        assertFalse(probe.sample(particles(0, 0x303030), 2, 2).visible);
        assertFalse(probe.sample(particles(0x151515, 0x303030), 3, 3).visible);
        assertFalse(probe.passed());
    }

    @Test public void requiresThreeDistinctRenderedAnimatedLitStoneSamples() {
        DebrisImageObservation probe = probe(false); BufferedImage lit = particles(0x958675, 0x303030);
        assertTrue(probe.sample(lit, 1, 11).visible); assertFalse(probe.passed());
        probe.sample(lit, 1, 12); probe.sample(lit, 2, 11); assertFalse(probe.passed());
        probe.sample(lit, 3, 13); assertFalse(probe.passed());
        probe.sample(lit, 4, 14); assertTrue(probe.passed());
    }

    @Test public void plantMustRetainItsGreenColorAndGlobalLightingChangesDoNotPass() {
        DebrisImageObservation probe = probe(true);
        assertFalse(probe.sample(particles(0x959595, 0x303030), 1, 1).visible);
        assertTrue(probe.sample(particles(0x52953c, 0x303030), 2, 2).visible);
        assertFalse(probe.sample(particles(0x52953c, 0x707070), 3, 3).visible);
    }

    @Test public void observedIdleNoiseCannotBecomeParticleEvidence() {
        DebrisImageObservation probe = new DebrisImageObservation(image(0x303030), particles(0x606060, 0x303030), target, control, false);
        assertFalse(probe.sample(particles(0x808080, 0x303030), 1, 1).visible);
    }

    @Test public void shadedStoneUsesItsActuallyLitReferenceAndRetainsSkylightColor() {
        BufferedImage empty = reference(image(0x101010), 0x405070);
        DebrisImageObservation probe = new DebrisImageObservation(empty, empty, target, control, false, true);
        assertFalse(probe.sample(reference(particles(0, 0x101010), 0x405070), 1, 1).visible);
        assertFalse(probe.sample(reference(particles(0x101520, 0x101010), 0x405070), 2, 2).visible);
        DebrisImageObservation.Result lit = probe.sample(reference(particles(0x304060, 0x101010), 0x405070), 3, 3);
        assertTrue(lit.visible); assertEquals(lit.referenceLight * .45, lit.minimumLight, .0001);
        assertFalse(probe.sample(reference(particles(0x803020, 0x101010), 0x405070), 4, 4).visible);
        assertFalse(probe.sample(reference(particles(0x304060, 0x101010), 0x8090b0), 5, 5).visible);
        BufferedImage dark = image(0x080808);
        assertThrows(IllegalStateException.class, () -> new DebrisImageObservation(dark, dark, target, control, false, true));
    }

    @Test public void shadedPlantStillRequiresRealGreenAlbedoAboveTheRelativeLightFloor() {
        BufferedImage empty = reference(image(0x101010), 0x405070);
        DebrisImageObservation probe = new DebrisImageObservation(empty, empty, target, control, true, true);
        assertFalse(probe.sample(reference(particles(0x304060, 0x101010), 0x405070), 1, 1).visible);
        assertFalse(probe.sample(reference(particles(0x081808, 0x101010), 0x405070), 2, 2).visible);
        assertTrue(probe.sample(reference(particles(0x304c28, 0x101010), 0x405070), 3, 3).visible);
    }

    @Test public void shelterIsFiniteUniqueAndLeavesTheCameraAndFrontOpeningClear() {
        FixtureBlockEffects.Layout layout = new FixtureBlockEffects.Layout(100, 3, 200);
        Set<BlockPos> blocks = new HashSet<>();
        for (int i = 0; i < 153; i++) {
            BlockPos pos = layout.shade(i); assertTrue(blocks.add(pos));
            assertTrue(pos.getX() >= 96 && pos.getX() <= 104);
            assertTrue(pos.getY() >= 4 && pos.getY() <= 8);
            assertTrue(pos.getZ() >= 206 && pos.getZ() <= 214);
            assertTrue(pos.getY() == 8 || pos.getX() == 96 || pos.getX() == 104);
        }
        assertFalse(blocks.contains(new BlockPos(100, 5, 211)));
        assertFalse(blocks.contains(new BlockPos(100, 5, 214)));
        for (int i = 0; i < 28; i++) assertFalse(blocks.contains(layout.backdrop(i)));
        assertEquals(layout.shadeReference(), layout.backdrop(12));
        assertThrows(IndexOutOfBoundsException.class, () -> layout.shade(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> layout.shade(153));
    }

    @Test public void fixtureVolumesRemainSeparatedAndViewportChangesFail() {
        int[] volume = DebrisImageObservation.box(1280, 720, -.85, .85, -1.82, .28, 1.9, 2.5, .8f, 1.42f);
        int[] reference = DebrisImageObservation.box(1280, 720, 1.7, 2.2, -1.02, -.12, 2.49, 2.49, .8f, 1.42f);
        assertTrue(volume[2] < reference[0]); assertTrue(volume[2] - volume[0] > 100);
        assertTrue(reference[2] - reference[0] >= 8);
        assertThrows(IllegalStateException.class, () -> probe(false).sample(new BufferedImage(81, 60, BufferedImage.TYPE_INT_RGB), 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new DebrisImageObservation(image(0), image(0), target, target, false));
    }

    private DebrisImageObservation probe(boolean plant) {
        return new DebrisImageObservation(image(0x303030), image(0x313131), target, control, plant);
    }
    private static BufferedImage particles(int color, int background) {
        BufferedImage image = image(background);
        for (int y = 20; y < 26; y++) for (int x = 20; x < 26; x++) image.setRGB(x, y, color);
        return image;
    }
    private BufferedImage reference(BufferedImage image, int color) {
        for (int y = control[1]; y < control[3]; y++) for (int x = control[0]; x < control[2]; x++) image.setRGB(x, y, color);
        return image;
    }
    private static BufferedImage image(int color) {
        BufferedImage image = new BufferedImage(80, 60, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 60; y++) for (int x = 0; x < 80; x++) image.setRGB(x, y, color);
        return image;
    }
}
