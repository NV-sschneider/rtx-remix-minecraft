/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.texture;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** Bounds PNG dimensions before decoding; frames follow the albedo's vertical strip. */
final class MaterialImage {
    static final long MAX_BYTES = 16L * 1024 * 1024;
    final int width, height, frames;
    final int[] pixels;

    MaterialImage(int width, int height, int frames, int[] pixels) {
        this.width = width; this.height = height; this.frames = frames; this.pixels = pixels;
    }

    static MaterialImage read(InputStream source, int width, int height, int baseFrames) throws IOException {
        try (MemoryCacheImageInputStream input = new MemoryCacheImageInputStream(source)) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Material image is not a supported PNG");
            ImageReader reader = readers.next();
            try {
                if (!"png".equalsIgnoreCase(reader.getFormatName())) throw new IOException("Material image must be PNG");
                reader.setInput(input, true, true);
                int imageWidth = reader.getWidth(0), imageHeight = reader.getHeight(0);
                if (width <= 0 || height <= 0 || imageWidth != width || imageHeight <= 0
                        || imageHeight % height != 0 || (long) imageWidth * imageHeight * 4 > MAX_BYTES) {
                    throw new IOException("Material image dimensions must match the stitched sprite and fit 16 MiB");
                }
                int frames = imageHeight / height;
                if (frames != 1 && frames != Math.max(1, baseFrames)) {
                    throw new IOException("Material strip must contain one frame or every albedo frame");
                }
                BufferedImage decoded = reader.read(0);
                return new MaterialImage(width, height, frames,
                        decoded.getRGB(0, 0, imageWidth, imageHeight, null, 0, imageWidth));
            } finally { reader.dispose(); }
        }
    }

    int pixel(int frame, int x, int y) {
        return pixels[((frames == 1 ? 0 : frame) * height + y) * width + x];
    }
    long bytes() { return (long) pixels.length * 4; }
}
