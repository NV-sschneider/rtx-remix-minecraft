/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.core;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import static org.junit.Assert.*;

public class AtlasAnimationTransformerTest {
    private static final String ATLAS = "net/minecraft/client/renderer/texture/TextureMap";
    private static final String GL = "net/minecraft/client/renderer/GlStateManager";
    private static final String HOOKS = "com/nvidia/rtxremix/forge/texture/AtlasUploadHooks";
    private static final String UPLOAD = "(IIIIIIIILjava/nio/IntBuffer;)V";
    private final AtlasAnimationTransformer transformer = new AtlasAnimationTransformer();

    @Test public void bracketsPinnedAtlasTickAndObservesOnlySuccessfulGlUploads() throws Exception {
        byte[] atlas = transform(ATLAS, bytes(ATLAS));
        ClassNode atlasType = read(atlas);
        MethodNode tick = method(atlasType, "updateAnimations", "()V");
        assertEquals(1, calls(tick, HOOKS, "beginAtlas"));
        assertEquals(2, calls(tick, HOOKS, "endAtlas"));
        assertEquals(1, tick.tryCatchBlocks.size());
        assertEquals(1, calls(tick, "net/minecraft/client/renderer/texture/TextureAtlasSprite", "updateAnimation"));
        assertArrayEquals(atlas, transform(ATLAS, atlas));
        verify(atlasType);

        byte[] gl = transform(GL, bytes(GL));
        ClassNode glType = read(gl);
        MethodNode upload = method(glType, "glTexSubImage2D", UPLOAD);
        assertEquals(1, calls(upload, "org/lwjgl/opengl/GL11", "glTexSubImage2D"));
        assertEquals(1, calls(upload, HOOKS, "uploaded"));
        boolean nativeCallSeen = false;
        for (AbstractInsnNode instruction : upload.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (call.owner.equals("org/lwjgl/opengl/GL11")) nativeCallSeen = true;
            if (call.owner.equals(HOOKS)) assertTrue("Observe only after the actual GL call succeeds", nativeCallSeen);
        }
        assertArrayEquals(gl, transform(GL, gl));
        verify(glType);
    }

    @Test public void matchesSrgNamesUsedByTheInstalledForgeClient() throws Exception {
        ClassNode atlas = read(bytes(ATLAS));
        method(atlas, "updateAnimations", "()V").name = "func_94248_c";
        ClassNode transformedAtlas = read(transform(ATLAS, write(atlas)));
        assertEquals(1, calls(method(transformedAtlas, "func_94248_c", "()V"), HOOKS, "beginAtlas"));
        verify(transformedAtlas);
        ClassNode gl = read(bytes(GL));
        method(gl, "glTexSubImage2D", UPLOAD).name = "func_187414_b";
        ClassNode transformedGl = read(transform(GL, write(gl)));
        assertEquals(1, calls(method(transformedGl, "func_187414_b", UPLOAD), HOOKS, "uploaded"));
        verify(transformedGl);
    }

    @Test public void closesAnimationScopeOnNormalReturnAndOriginalException() throws Exception {
        FixtureLoader loader = new FixtureLoader();
        loader.add(ATLAS, transform(ATLAS, atlasFixture()));
        loader.add(HOOKS, hooksFixture());
        Class<?> atlas = loader.loadClass(ATLAS.replace('/', '.'));
        Class<?> hooks = loader.loadClass(HOOKS.replace('/', '.'));
        Object instance = atlas.newInstance();
        atlas.getMethod("updateAnimations").invoke(instance);
        assertEquals(1, hooks.getField("begins").getInt(null));
        assertEquals(1, hooks.getField("ends").getInt(null));
        atlas.getField("fail").setBoolean(null, true);
        try { atlas.getMethod("updateAnimations").invoke(instance); fail("Original exception must propagate"); }
        catch (InvocationTargetException expected) { assertTrue(expected.getCause() instanceof IllegalArgumentException); }
        assertEquals(2, hooks.getField("begins").getInt(null));
        assertEquals(2, hooks.getField("ends").getInt(null));
    }

    @Test public void pinnedDiscreteAndInterpolatedFramesReachObservedUploadPath() throws Exception {
        String sprites = "net/minecraft/client/renderer/texture/";
        ClassNode sprite = read(bytes(sprites + "TextureAtlasSprite"));
        assertTrue(calls(method(sprite, "updateAnimation", "()V"), sprites + "TextureUtil", "uploadTextureMipmap") > 0);
        assertTrue(calls(method(sprite, "updateAnimationInterpolated", "()V"), sprites + "TextureUtil", "uploadTextureMipmap") > 0);
        ClassNode textureUtil = read(bytes(sprites + "TextureUtil"));
        MethodNode subUpload = method(textureUtil, "uploadTextureSub", "(I[IIIIIZZZ)V");
        assertEquals(1, calls(subUpload, GL, "glTexSubImage2D"));
    }

    private byte[] transform(String name, byte[] source) {
        return transformer.transform("obfuscated", name.replace('/', '.'), source);
    }
    private static byte[] bytes(String name) throws Exception {
        try (InputStream input = AtlasAnimationTransformerTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull("Pinned Forge class is required: " + name, input);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }
    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM5);
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
    private static byte[] write(ClassNode node) {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }
    private static MethodNode method(ClassNode node, String name, String descriptor) {
        for (MethodNode method : node.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) return method;
        }
        throw new AssertionError("Missing " + name + descriptor);
    }
    private static int calls(MethodNode method, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (call.owner.equals(owner) && call.name.equals(name)) count++;
        }
        return count;
    }
    private static void verify(ClassNode type) throws Exception {
        for (MethodNode method : type.methods) {
            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0) {
                new Analyzer<>(new BasicVerifier()).analyze(type.name, method);
            }
        }
    }
    private static ClassWriter fixture(String name) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        return writer;
    }
    private static byte[] atlasFixture() {
        ClassWriter writer = fixture(ATLAS);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "fail", "Z", null, null).visitEnd();
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "updateAnimations", "()V", null, null);
        method.visitCode();
        Label normal = new Label();
        method.visitFieldInsn(Opcodes.GETSTATIC, ATLAS, "fail", "Z");
        method.visitJumpInsn(Opcodes.IFEQ, normal);
        method.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalArgumentException");
        method.visitInsn(Opcodes.DUP);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalArgumentException", "<init>", "()V", false);
        method.visitInsn(Opcodes.ATHROW);
        method.visitLabel(normal);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
    private static byte[] hooksFixture() {
        ClassWriter writer = fixture(HOOKS);
        String[] names = {"beginAtlas", "endAtlas"};
        String[] descriptors = {"(Ljava/lang/Object;)V", "()V"};
        String[] counters = {"begins", "ends"};
        for (int i = 0; i < names.length; i++) {
            writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, counters[i], "I", null, null).visitEnd();
            MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, names[i], descriptors[i], null, null);
            method.visitCode();
            method.visitFieldInsn(Opcodes.GETSTATIC, HOOKS, counters[i], "I");
            method.visitInsn(Opcodes.ICONST_1);
            method.visitInsn(Opcodes.IADD);
            method.visitFieldInsn(Opcodes.PUTSTATIC, HOOKS, counters[i], "I");
            method.visitInsn(Opcodes.RETURN);
            method.visitMaxs(0, 0);
            method.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }
    private static final class FixtureLoader extends ClassLoader {
        private final Map<String, byte[]> definitions = new HashMap<>();
        void add(String name, byte[] bytes) { definitions.put(name.replace('/', '.'), bytes); }
        @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!definitions.containsKey(name)) return super.loadClass(name, resolve);
            Class<?> type = findLoadedClass(name);
            if (type == null) {
                byte[] bytes = definitions.get(name);
                type = defineClass(name, bytes, 0, bytes.length);
            }
            if (resolve) resolveClass(type);
            return type;
        }
    }
}
