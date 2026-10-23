/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FixtureImageReadinessTest {
    @Test
    public void rejectsBlackWorldEvenWithVisibleHudCrosshairAndHand() {
        BufferedImage image = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(630, 350, 20, 20);
            graphics.fillRect(400, 660, 480, 60);
            graphics.fillRect(10, 540, 400, 100);
            graphics.setColor(Color.ORANGE);
            graphics.fillRect(900, 450, 200, 270);
        } finally {
            graphics.dispose();
        }
        assertFalse(FixtureImageReadiness.evaluate(image).isReady());
    }

    @Test
    public void acceptsLitFloorAndColoredFixtureGeometry() {
        BufferedImage image = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.GRAY);
            graphics.fillRect(200, 300, 360, 160);
            graphics.setColor(Color.RED);
            graphics.fillRect(240, 260, 70, 120);
            graphics.setColor(Color.BLUE);
            graphics.fillRect(340, 260, 70, 120);
        } finally {
            graphics.dispose();
        }
        assertTrue(FixtureImageReadiness.evaluate(image).isReady());
    }

    @Test
    public void rejectsNearBlackNoiseAndBrightUncoloredOutput() {
        BufferedImage image = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(20, 0, 0));
            graphics.fillRect(0, 0, 200, 100);
            assertFalse(FixtureImageReadiness.evaluate(image).isReady());
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 200, 100);
            assertFalse(FixtureImageReadiness.evaluate(image).isReady());
            // A few bright colored pixels cannot substitute for the fixture area.
            image.setRGB(30, 35, Color.RED.getRGB());
            assertFalse(FixtureImageReadiness.evaluate(image).isReady());
        } finally {
            graphics.dispose();
        }
    }

    @Test
    public void rejectsImagesTooSmallToSample() {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, Color.RED.getRGB());
        assertFalse(FixtureImageReadiness.evaluate(image).isReady());
    }
}
