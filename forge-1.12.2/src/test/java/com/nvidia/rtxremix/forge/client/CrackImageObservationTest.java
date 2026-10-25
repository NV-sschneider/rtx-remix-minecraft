/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import java.awt.image.BufferedImage;
import org.junit.Test;
import static org.junit.Assert.*;

public class CrackImageObservationTest {
    private final int[] target = {0, 0, 20, 20}, control = {20, 0, 40, 20};

    @Test public void acceptsLocalizedGrowingCracksAndRequiresCancellationToRestoreTheSurface() {
        BufferedImage clear = image(200);
        CrackImageObservation observation = new CrackImageObservation(clear, image(199), target, control);
        assertTrue(observation.stage(cracks(8, 90), false).visible);
        assertTrue(observation.stage(cracks(100, 90), true).visible);
        assertFalse(observation.restored(cracks(100, 90)));
        assertTrue(observation.restored(image(199)));
    }

    @Test public void unchangedSubtleBlackAndGloballyDarkerFramesCannotProveReadableCracks() {
        CrackImageObservation observation = new CrackImageObservation(image(200), image(199), target, control);
        assertFalse(observation.stage(image(200), false).visible);
        assertFalse(observation.stage(cracks(100, 185), false).visible);
        assertFalse(observation.stage(image(0), false).visible);
        assertFalse(observation.stage(image(100), false).visible);
        assertTrue(observation.stage(cracks(100, 90), false).visible);
        assertFalse(observation.stage(cracks(100, 90), true).visible);
    }

    @Test public void baselineNoiseCannotBecomeNewCrackCoverage() {
        CrackImageObservation observation = new CrackImageObservation(image(200), cracks(100, 170), target, control);
        assertFalse(observation.stage(cracks(100, 160), false).visible);
    }

    @Test public void projectsAnInteriorFrontFaceAndRejectsViewportChanges() {
        int[] box = CrackImageObservation.face(1280, 720, 1.5, -1.82, 8.5, .8f, 1.42f);
        assertTrue(box[0] > 640); assertTrue(box[2] > box[0] + 15);
        assertTrue(box[1] > 0); assertTrue(box[3] < 360);
        CrackImageObservation observation = new CrackImageObservation(image(200), image(200), target, control);
        try { observation.stage(new BufferedImage(41, 20, BufferedImage.TYPE_INT_RGB), false); fail(); }
        catch (IllegalStateException expected) { }
    }

    private static BufferedImage cracks(int count, int value) {
        BufferedImage image = image(200);
        for (int i = 0; i < count; i++) image.setRGB(i % 20, i / 20, value * 0x010101);
        return image;
    }
    private static BufferedImage image(int value) {
        BufferedImage image = new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 20; y++) for (int x = 0; x < 40; x++) image.setRGB(x, y, value * 0x010101);
        return image;
    }
}
