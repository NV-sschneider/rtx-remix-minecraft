/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FixtureSkyImageTest {
    @Test
    public void rejectsBlackSkyWithBrightHudAndAVisibleSun() {
        BufferedImage image = solid(Color.BLACK);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 160, 400, 40);
            graphics.fillRect(0, 0, 60, 80);
            graphics.fillRect(185, 35, 30, 30);
        } finally {
            graphics.dispose();
        }
        assertFalse(FixtureSkyImage.evaluate(image).isVisible());
    }

    @Test
    public void acceptsBroadSkyAndDistinguishesWeatherWithoutRequiringDarkerExposure() {
        FixtureSkyImage.Result day = FixtureSkyImage.evaluate(solid(new Color(80, 130, 210)));
        FixtureSkyImage.Result rain = FixtureSkyImage.evaluate(solid(new Color(140, 140, 140)));
        assertTrue(day.isVisible());
        assertTrue(rain.isVisible());
        assertTrue(day.difference(rain) > 8.0);
    }

    @Test
    public void rejectsNoiseAsEvidenceOfAChangedSky() {
        FixtureSkyImage.Result day = FixtureSkyImage.evaluate(solid(new Color(80, 130, 210)));
        FixtureSkyImage.Result noise = FixtureSkyImage.evaluate(solid(new Color(81, 129, 211)));
        assertEquals(0.0, day.difference(day), 0.0);
        assertTrue(day.difference(noise) < 8.0);
        assertTrue(day.difference(FixtureSkyImage.evaluate(solid(new Color(5, 7, 20)))) > 8.0);
    }

    @Test
    public void rejectsImagesWithNoProbePixels() {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, Color.WHITE.getRGB());
        assertFalse(FixtureSkyImage.evaluate(image).isVisible());
    }

    private static BufferedImage solid(Color color) {
        BufferedImage image = new BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(color);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        } finally {
            graphics.dispose();
        }
        return image;
    }
}
