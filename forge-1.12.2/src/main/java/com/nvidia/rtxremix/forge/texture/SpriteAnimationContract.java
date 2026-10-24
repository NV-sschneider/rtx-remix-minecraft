/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.texture;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/** Recognizes optional sprites whose CPU uploads and vanilla animation fields share one clock. */
final class SpriteAnimationContract {
    static final String FOAMFIX = "pl.asie.foamfix.client.FastTextureAtlasSprite";
    // The 0.10.15 release's annotation reports @VERSION@; authenticate its original class resource.
    static final String FOAMFIX_SHA256 = "6ef144e981602abdd7a91ea903d44d9f7574f97e8c2bccf3d66fc37a1c5d4436";
    private static final ClassValue<Contract> CONTRACTS = new ClassValue<Contract>() {
        @Override protected Contract computeValue(Class<?> type) {
            if (!FOAMFIX.equals(type.getName()) || type.getSuperclass() != TextureAtlasSprite.class)
                return new Contract(null, "unsupported animated sprite class " + type.getName());
            try (InputStream source = type.getResourceAsStream("/" + FOAMFIX.replace('.', '/') + ".class")) {
                if (source == null || !FOAMFIX_SHA256.equals(fingerprint(source)))
                    return new Contract(null, "unsupported FoamFix sprite bytecode; expected the 1.12.2 0.10.15 release");
                MethodHandle stored = MethodHandles.publicLookup().findVirtual(type, "isStoredOnGPU", MethodType.methodType(boolean.class))
                        .asType(MethodType.methodType(boolean.class, Object.class));
                return new Contract(stored, null);
            } catch (IOException | ReflectiveOperationException | LinkageError failure) {
                return new Contract(null, "FoamFix sprite storage contract unavailable");
            }
        }
    };

    private SpriteAnimationContract() { }

    static String unsupported(TextureAtlasSprite sprite) {
        if (sprite.getClass() == TextureAtlasSprite.class) return null;
        Contract contract = CONTRACTS.get(sprite.getClass());
        if (contract.reason != null) return contract.reason;
        try {
            return (boolean) contract.stored.invokeExact((Object) sprite)
                    ? "FoamFix GPU-copy animation is outside the CPU upload observer" : null;
        } catch (Throwable failure) {
            throw new IllegalStateException("Cannot inspect FoamFix sprite storage", failure);
        }
    }

    static String fingerprint(InputStream source) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[4096]; int count, total = 0;
            while ((count = source.read(buffer)) != -1) {
                total += count;
                if (total > 128 * 1024) throw new IOException("Optional sprite class exceeds its contract bound");
                digest.update(buffer, 0, count);
            }
            char[] hex = new char[64], digits = "0123456789abcdef".toCharArray();
            byte[] hash = digest.digest();
            for (int i = 0; i < hash.length; i++) {
                hex[i * 2] = digits[(hash[i] >>> 4) & 15]; hex[i * 2 + 1] = digits[hash[i] & 15];
            }
            return new String(hex);
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static final class Contract {
        final MethodHandle stored;
        final String reason;
        Contract(MethodHandle stored, String reason) { this.stored = stored; this.reason = reason; }
    }
}
