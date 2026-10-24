/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import net.minecraft.client.resources.IResourcePack;
import net.minecraft.client.resources.data.IMetadataSection;
import net.minecraft.client.resources.data.MetadataSerializer;
import net.minecraft.client.resources.data.PackMetadataSection;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentString;

/** Original procedural test images kept entirely in memory; never changes user pack files. */
final class FixturePbrPack implements IResourcePack {
    static final ResourceLocation REVISION = new ResourceLocation("minecraft", "rlcraftremix/fixture_pbr_revision.txt");
    static final String ANIMATED_SPRITE = "minecraft:blocks/diamond_block";
    static final String[] SPRITES = {"brick", "iron_block", "gold_block", "redstone_block", "diamond_block"};
    private final Map<ResourceLocation, byte[]> resources = new LinkedHashMap<>();
    private final int revision;

    FixturePbrPack(int revision) throws IOException {
        if (revision != 1 && revision != 2) throw new IllegalArgumentException("Fixture pack revision must be 1 or 2");
        this.revision = revision;
        resources.put(REVISION, Integer.toString(revision).getBytes(StandardCharsets.UTF_8));
        for (int panel = 0; panel < SPRITES.length; panel++) {
            String name = "textures/blocks/" + SPRITES[panel];
            image(name + ".png", panel, 0);
            image(name + "_n.png", panel, 1);
            image(name + "_s.png", panel, 2);
        }
        resources.put(new ResourceLocation("minecraft", "textures/blocks/diamond_block.png.mcmeta"),
                ("{\"animation\":{\"interpolate\":true,\"frametime\":4,\"frames\":["
                        + "{\"index\":2,\"time\":8},{\"index\":0,\"time\":4},{\"index\":1,\"time\":6}]}}")
                        .getBytes(StandardCharsets.UTF_8));
    }

    int revision() { return revision; }
    private void image(String name, int panel, int channel) throws IOException {
        int frames = panel == 4 ? 3 : 1;
        BufferedImage image = new BufferedImage(16, 16 * frames, BufferedImage.TYPE_INT_ARGB);
        for (int frame = 0; frame < frames; frame++) for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
            image.setRGB(x, y + frame * 16, pixel(panel, channel, frame, x, y));
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", bytes)) throw new IOException("PNG writer unavailable");
        resources.put(new ResourceLocation("minecraft", name), bytes.toByteArray());
    }

    private int pixel(int panel, int channel, int frame, int x, int y) {
        boolean border = x == 0 || y == 0 || x == 15 || y == 15;
        if (channel == 0) {
            int[] colors = {0xa0a0a0, 0x949caf, 0xe7bd4b, 0x50d8ef};
            int[] animated = {0x38c8b5, 0xdd5dbc, 0xe1bd43};
            int rgb = panel == 4 ? animated[frame] : colors[panel];
            return 0xff000000 | (border ? 0x202830 : rgb);
        }
        if (channel == 1) {
            int amplitude = panel == 0 ? 68 : panel == 4 ? 24 + frame * 16 : 0;
            int direction = revision == 1 ? 1 : -1;
            int red = 128 + direction * (x < 8 ? amplitude : -amplitude);
            int green = 128 + direction * (y < 8 ? amplitude : -amplitude);
            return 0xff0000ff | red << 16 | green << 8;
        }
        int smoothness = panel == 1 ? ((x < 8) == (revision == 1) ? 245 : 20)
                : panel == 2 ? 240 : panel == 4 ? frame * 110 : 60;
        int metal = panel == 2 ? (revision == 1 ? 255 : 0) : panel == 4 && frame == 1 ? 255 : 0;
        int emission = panel == 3 && !border && (x + y) % 5 != 0 ? (revision == 1 ? 254 : 0)
                : panel == 4 && frame == 2 ? (revision == 1 ? 140 : 40) : 255;
        return emission << 24 | smoothness << 16 | metal << 8;
    }

    @Override public InputStream getInputStream(ResourceLocation location) throws IOException {
        byte[] data = resources.get(location);
        if (data == null) throw new FileNotFoundException(location.toString());
        return new ByteArrayInputStream(data);
    }
    @Override public boolean resourceExists(ResourceLocation location) { return resources.containsKey(location); }
    @Override public Set<String> getResourceDomains() { return Collections.singleton("minecraft"); }
    @Override @SuppressWarnings("unchecked") public <T extends IMetadataSection> T getPackMetadata(MetadataSerializer serializer, String section) {
        return "pack".equals(section) ? (T) new PackMetadataSection(new TextComponentString("Owned Remix material test images"), 3) : null;
    }
    @Override public BufferedImage getPackImage() {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xff60b8d0); return image;
    }
    @Override public String getPackName() { return "RTX Remix PBR fixture " + revision; }
}
