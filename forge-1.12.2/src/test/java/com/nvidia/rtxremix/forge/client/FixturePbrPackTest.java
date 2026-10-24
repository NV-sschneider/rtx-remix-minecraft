/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import net.minecraft.util.ResourceLocation;
import org.junit.Test;
import static org.junit.Assert.*;

public class FixturePbrPackTest {
    @Test public void animatedSidecarsShareThreeFramesAndOnlyTheAlbedoOwnsTiming() throws Exception {
        FixturePbrPack pack = new FixturePbrPack(1);
        for (String suffix : new String[] {"", "_n", "_s"}) {
            BufferedImage image = image(pack, "diamond_block" + suffix);
            assertEquals(16, image.getWidth()); assertEquals(48, image.getHeight());
        }
        ResourceLocation meta = resource("diamond_block.png.mcmeta");
        try (InputStream source = pack.getInputStream(meta)) {
            JsonObject animation = new JsonParser().parse(new InputStreamReader(source, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("animation");
            assertTrue(animation.get("interpolate").getAsBoolean());
            JsonArray frames = animation.getAsJsonArray("frames");
            assertEquals(2, frames.get(0).getAsJsonObject().get("index").getAsInt());
            assertEquals(0, frames.get(1).getAsJsonObject().get("index").getAsInt());
            assertEquals(1, frames.get(2).getAsJsonObject().get("index").getAsInt());
            assertEquals(8, frames.get(0).getAsJsonObject().get("time").getAsInt());
        }
        assertFalse(pack.resourceExists(resource("diamond_block_n.png.mcmeta")));
        assertFalse(pack.resourceExists(resource("diamond_block_s.png.mcmeta")));
    }

    @Test public void changingThePackChangesMapsWhileKeepingTheAlbedoComparisonIdentical() throws Exception {
        FixturePbrPack first = new FixturePbrPack(1), second = new FixturePbrPack(2);
        for (String sprite : FixturePbrPack.SPRITES) {
            BufferedImage a = image(first, sprite), b = image(second, sprite);
            for (int y = 0; y < a.getHeight(); y++) for (int x = 0; x < a.getWidth(); x++) assertEquals(a.getRGB(x, y), b.getRGB(x, y));
        }
        assertNotEquals(image(first, "brick_n").getRGB(3, 3), image(second, "brick_n").getRGB(3, 3));
        assertNotEquals(image(first, "iron_block_s").getRGB(3, 3), image(second, "iron_block_s").getRGB(3, 3));
        assertEquals(255, (image(first, "gold_block_s").getRGB(3, 3) >>> 8) & 255);
        assertEquals(0, (image(second, "gold_block_s").getRGB(3, 3) >>> 8) & 255);
        assertEquals(254, image(first, "redstone_block_s").getRGB(3, 3) >>> 24);
        assertEquals(0, image(second, "redstone_block_s").getRGB(3, 3) >>> 24);
    }

    @Test public void missingResourcesDoNotMaskLowerPriorityPacks() throws Exception {
        FixturePbrPack pack = new FixturePbrPack(1);
        ResourceLocation absent = resource("stone.png");
        assertFalse(pack.resourceExists(absent));
        try { pack.getInputStream(absent); fail("Missing fixture resource must delegate to other packs"); }
        catch (FileNotFoundException expected) { assertEquals(absent.toString(), expected.getMessage()); }
    }

    private static BufferedImage image(FixturePbrPack pack, String name) throws Exception {
        try (InputStream source = pack.getInputStream(resource(name + ".png"))) { return ImageIO.read(source); }
    }
    private static ResourceLocation resource(String name) { return new ResourceLocation("minecraft", "textures/blocks/" + name); }
}
