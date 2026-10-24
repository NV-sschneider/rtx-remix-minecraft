/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.texture;

import java.awt.image.BufferedImage;
import java.nio.IntBuffer;
import net.minecraft.client.renderer.texture.ITextureObject;

/** Successful render-thread CPU texture uploads; no native calls or GL readback occur here. */
public final class TextureRevisionHooks {
    private static final TextureUpdates[] TARGETS = new TextureUpdates[2];
    private static volatile int count;
    private TextureRevisionHooks() { }
    static synchronized void attach(TextureUpdates value) {
        if (value == null) throw new IllegalArgumentException("Missing texture observer");
        for (int i = 0; i < count; i++) if (TARGETS[i] == value) throw new IllegalStateException("Texture observer already attached");
        if (count == TARGETS.length) throw new IllegalStateException("Texture observer capacity exceeded");
        TARGETS[count] = value;
        count++;
    }
    static synchronized void detach(TextureUpdates value) {
        for (int i = 0; i < count; i++) if (TARGETS[i] == value) {
            for (int next = i + 1; next < count; next++) TARGETS[next - 1] = TARGETS[next];
            TARGETS[count - 1] = null; count--; return;
        }
    }
    public static void begin(int texture, int width, int height) {
        for (int i = 0; i < count; i++) {
            TextureUpdates value = TARGETS[i]; if (value != null && value.ownsThread()) value.begin(texture, 0, 0, width, height);
        }
    }
    public static void beginImage(int texture, Object image, int x, int y) {
        for (int i = 0; i < count; i++) {
            TextureUpdates value = TARGETS[i];
            if (value != null && value.ownsThread()) {
                BufferedImage source = (BufferedImage) image;
                value.begin(texture, x, y, source == null ? 0 : source.getWidth(), source == null ? 0 : source.getHeight());
            }
        }
    }
    public static void beginAtlas(Object atlas) {
        for (int i = 0; i < count; i++) {
            TextureUpdates value = TARGETS[i];
            if (value != null && value.ownsThread()) value.begin(((ITextureObject) atlas).getGlTextureId(), 0, 0, 0, 0);
        }
    }
    public static void end(boolean succeeded) {
        for (int i = 0; i < count; i++) { TextureUpdates value = TARGETS[i]; if (value != null && value.ownsThread()) value.end(succeeded); }
    }
    public static void uploaded(int target, int level, int x, int y, int width, int height, int format, int type, IntBuffer pixels) {
        for (int i = 0; i < count; i++) {
            TextureUpdates value = TARGETS[i];
            if (value != null && value.ownsThread()) value.uploaded(target, level, x, y, width, height, format, type, pixels);
        }
    }
    public static void deleted(int texture) {
        for (int i = 0; i < count; i++) { TextureUpdates value = TARGETS[i]; if (value != null && value.ownsThread()) value.deleted(texture); }
    }
}
