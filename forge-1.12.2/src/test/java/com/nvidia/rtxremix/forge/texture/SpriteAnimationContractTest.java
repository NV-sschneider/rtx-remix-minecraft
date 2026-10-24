/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.texture;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.jar.JarFile;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.data.AnimationFrame;
import net.minecraft.client.resources.data.AnimationMetadataSection;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import static org.junit.Assert.*;

public class SpriteAnimationContractTest {
    @Test public void ordinaryAnimationIsAcceptedButUnknownAnimationAndRotationAreDistinct() throws Exception {
        TextureAtlasSprite vanilla = Sprite.vanilla();
        animated(vanilla);
        assertNull(SpriteMaterial.unsupportedReason(vanilla));
        Sprite custom = new Sprite();
        assertNull(SpriteMaterial.unsupportedReason(custom));
        animated(custom);
        assertTrue(SpriteMaterial.unsupportedReason(custom).contains("unsupported animated sprite class"));
        vanilla.initSprite(16, 16, 0, 0, true);
        assertEquals("rotated atlas sprite", SpriteMaterial.unsupportedReason(vanilla));
    }

    @Test(expected = IOException.class) public void optionalClassAuthenticationIsBounded() throws Exception {
        SpriteAnimationContract.fingerprint(new ByteArrayInputStream(new byte[128 * 1024 + 1]));
    }

    @Test public void installedFoamFixKeepsVanillaClockAndItsCpuUploadAndStorageContracts() throws Exception {
        String directory = System.getProperty("rtxremix.rlcraftMods"); Assume.assumeNotNull(directory);
        String name = SpriteAnimationContract.FOAMFIX.replace('.', '/');
        try (JarFile jar = new JarFile(new File(directory, "foamfix-0.10.15-1.12.2.jar"))) {
            assertNotNull(jar.getJarEntry(name + ".class"));
            try (InputStream stream = jar.getInputStream(jar.getJarEntry(name + ".class"))) {
                assertEquals(SpriteAnimationContract.FOAMFIX_SHA256, SpriteAnimationContract.fingerprint(stream));
            }
            ClassNode type = new ClassNode(Opcodes.ASM5);
            try (InputStream stream = jar.getInputStream(jar.getJarEntry(name + ".class"))) {
                new ClassReader(stream).accept(type, ClassReader.SKIP_DEBUG);
            }
            assertEquals("net/minecraft/client/renderer/texture/TextureAtlasSprite", type.superName);
            assertTrue(type.interfaces.contains("pl/asie/foamfix/api/IFoamFixSprite"));
            for (FieldNode field : type.fields) {
                assertFalse("Optional sprite must use inherited animation fields", Arrays.asList(
                        "field_110982_k", "field_110973_g", "field_110983_h").contains(field.name));
            }
            MethodNode tick = method(type, "func_94219_l", "()V");
            assertTrue(field(tick, "field_110982_k", Opcodes.GETFIELD));
            assertTrue(field(tick, "field_110973_g", Opcodes.PUTFIELD));
            assertTrue(field(tick, "field_110983_h", Opcodes.PUTFIELD));
            assertTrue(call(tick, name, "uploadTextureMaxMips"));
            assertTrue(call(tick, "org/lwjgl/opengl/ARBCopyImage", "glCopyImageSubData"));
            assertTrue(call(method(type, "func_180599_n", "()V"), name, "uploadTextureMaxMips"));
            assertTrue(call(method(type, "uploadTextureMaxMips", "(I[[IIIIIZZZ)V"),
                    "net/minecraft/client/renderer/texture/TextureUtil", "func_147947_a"));
            MethodNode storage = method(type, "isStoredOnGPU", "()Z");
            assertTrue((storage.access & Opcodes.ACC_PUBLIC) != 0);
            assertTrue(field(storage, "textureId", Opcodes.GETFIELD));
            assertTrue(call(storage, name, "func_130098_m"));
        }
    }

    private static void animated(TextureAtlasSprite sprite) throws Exception {
        sprite.setFramesTextureData(Arrays.asList(new int[][] {{-1}}, new int[][] {{0xff000000}}));
        ReflectionHelper.findField(TextureAtlasSprite.class, new String[] {"animationMetadata", "field_110982_k"})
                .set(sprite, new AnimationMetadataSection(Arrays.asList(new AnimationFrame(1, 4), new AnimationFrame(0, 2)), 1, 1, 4, true));
    }
    private static MethodNode method(ClassNode type, String name, String descriptor) {
        for (MethodNode method : type.methods) if (name.equals(method.name) && descriptor.equals(method.desc)) return method;
        throw new AssertionError("Missing optional sprite contract " + name + descriptor);
    }
    private static boolean field(MethodNode method, String name, int opcode) {
        for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction instanceof FieldInsnNode
                && instruction.getOpcode() == opcode && name.equals(((FieldInsnNode) instruction).name)) return true;
        return false;
    }
    private static boolean call(MethodNode method, String owner, String name) {
        for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction instanceof MethodInsnNode
                && owner.equals(((MethodInsnNode) instruction).owner) && name.equals(((MethodInsnNode) instruction).name)) return true;
        return false;
    }
    private static final class Sprite extends TextureAtlasSprite {
        Sprite() { super("test:custom"); setIconWidth(1); setIconHeight(1); }
        static TextureAtlasSprite vanilla() { return makeAtlasSprite(new ResourceLocation("test:vanilla")); }
    }
}
