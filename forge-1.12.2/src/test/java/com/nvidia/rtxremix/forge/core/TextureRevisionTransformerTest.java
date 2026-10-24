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

public class TextureRevisionTransformerTest {
    private static final String UTIL = "net/minecraft/client/renderer/texture/TextureUtil";
    private static final String STATE = "net/minecraft/client/renderer/GlStateManager";
    private static final String ATLAS = "net/minecraft/client/renderer/texture/TextureMap";
    private static final String HOOKS = "com/nvidia/rtxremix/forge/texture/TextureRevisionHooks";
    private static final String IMAGE = "(ILjava/awt/image/BufferedImage;IIZZ)I";
    private static final String UPLOAD = "(IIIIIIIILjava/nio/IntBuffer;)V";
    private final TextureRevisionTransformer transformer = new TextureRevisionTransformer();

    @Test public void pinnedDynamicMapsAndDownloadedSkinsReachObservedSuccessfulCpuUploads() throws Exception {
        ClassNode dynamic = read(bytes("net/minecraft/client/renderer/texture/DynamicTexture"));
        assertEquals(1, calls(method(dynamic, "updateDynamicTexture", "()V"), UTIL, "uploadTexture"));
        ClassNode skin = read(bytes("net/minecraft/client/renderer/ThreadDownloadImageData"));
        assertEquals(1, calls(method(skin, "checkTextureUploaded", "()V"), UTIL, "uploadTextureImage"));
        ClassNode util = read(transform(UTIL, bytes(UTIL))); verify(util);
        assertEquals(1, calls(method(util, "uploadTexture", "(I[III)V"), HOOKS, "begin"));
        assertEquals(2, calls(method(util, "uploadTexture", "(I[III)V"), HOOKS, "end"));
        assertEquals(1, calls(method(util, "uploadTextureImageSub", IMAGE), HOOKS, "beginImage"));
        assertEquals(2, calls(method(util, "uploadTextureImageSub", IMAGE), HOOKS, "end"));
        MethodNode deletion = method(util, "deleteTexture", "(I)V");
        assertEquals(1, calls(deletion, HOOKS, "deleted"));
        boolean deleted = false;
        for (AbstractInsnNode instruction : deletion.instructions.toArray()) if (instruction instanceof MethodInsnNode) {
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (call.owner.equals(STATE) && call.name.equals("deleteTexture")) deleted = true;
            if (call.owner.equals(HOOKS)) assertTrue("Observe after the real deletion", deleted);
        }
        for (String name : new String[] {UTIL, STATE, ATLAS}) {
            byte[] patched = transform(name, bytes(name)); verify(read(patched)); assertArrayEquals(patched, transform(name, patched));
        }
    }

    @Test public void composesWithAtlasObserverAndMatchesOnlyPinnedSrgMethods() throws Exception {
        for (String name : new String[] {STATE, ATLAS}) {
            byte[] atlas = new AtlasAnimationTransformer().transform("obfuscated", name.replace('/', '.'), bytes(name));
            ClassNode result = read(transform(name, atlas)); verify(result);
            MethodNode method = name.equals(STATE) ? method(result, "glTexSubImage2D", UPLOAD) : method(result, "updateAnimations", "()V");
            assertEquals(1, calls(method, HOOKS, name.equals(STATE) ? "uploaded" : "beginAtlas"));
            assertEquals(1, calls(method, "com/nvidia/rtxremix/forge/texture/AtlasUploadHooks", name.equals(STATE) ? "uploaded" : "beginAtlas"));
        }
        String[][] names = {{UTIL, "uploadTexture", "func_110988_a", "(I[III)V", "begin"},
                {UTIL, "uploadTextureImageSub", "func_110995_a", IMAGE, "beginImage"},
                {UTIL, "deleteTexture", "func_147942_a", "(I)V", "deleted"},
                {STATE, "glTexSubImage2D", "func_187414_b", UPLOAD, "uploaded"},
                {ATLAS, "updateAnimations", "func_94248_c", "()V", "beginAtlas"}};
        for (String[] target : names) {
            ClassNode original = read(bytes(target[0])); method(original, target[1], target[3]).name = target[2];
            ClassWriter writer = new ClassWriter(0); original.accept(writer); ClassNode changed = read(transform(target[0], writer.toByteArray()));
            verify(changed); assertEquals(1, calls(method(changed, target[2], target[3]), HOOKS, target[4]));
        }
    }

    @Test public void realJvmPreservesTextureIdReturnAndDiscardsThrowingUploadScopes() throws Exception {
        FixtureLoader loader = new FixtureLoader(); loader.add(UTIL, transform(UTIL, uploadFixture())); loader.add(HOOKS, hooksFixture());
        Class<?> util = loader.loadClass(UTIL.replace('/', '.')), hooks = loader.loadClass(HOOKS.replace('/', '.'));
        assertEquals(42, util.getMethod("uploadTextureImageSub", int.class, java.awt.image.BufferedImage.class,
                int.class, int.class, boolean.class, boolean.class).invoke(null, 42, null, 0, 0, false, false));
        assertEquals(1, hooks.getField("success").getInt(null));
        try {
            util.getMethod("uploadTextureImageSub", int.class, java.awt.image.BufferedImage.class,
                    int.class, int.class, boolean.class, boolean.class).invoke(null, -1, null, 0, 0, false, false); fail();
        } catch (InvocationTargetException expected) { assertTrue(expected.getCause() instanceof IllegalArgumentException); }
        assertEquals(1, hooks.getField("failure").getInt(null));
    }

    private byte[] transform(String name, byte[] bytes) { return transformer.transform("obfuscated", name.replace('/', '.'), bytes); }
    private static ClassNode read(byte[] bytes) { ClassNode node = new ClassNode(Opcodes.ASM5); new ClassReader(bytes).accept(node, 0); return node; }
    private static byte[] bytes(String name) throws Exception {
        try (InputStream input = TextureRevisionTransformerTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(name, input); ByteArrayOutputStream result = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) >= 0) result.write(buffer, 0, count); return result.toByteArray();
        }
    }
    private static MethodNode method(ClassNode node, String name, String descriptor) {
        for (MethodNode method : node.methods) if (name.equals(method.name) && descriptor.equals(method.desc)) return method;
        throw new AssertionError(name + descriptor);
    }
    private static int calls(MethodNode method, String owner, String name) {
        int count = 0; for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction instanceof MethodInsnNode) {
            MethodInsnNode call = (MethodInsnNode) instruction; if (owner.equals(call.owner) && name.equals(call.name)) count++;
        } return count;
    }
    private static void verify(ClassNode node) throws Exception {
        for (MethodNode method : node.methods) if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0)
            new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
    }
    private static ClassWriter fixture(String name) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null); return writer;
    }
    private static byte[] uploadFixture() {
        ClassWriter writer = fixture(UTIL); MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "uploadTextureImageSub", IMAGE, null, null);
        method.visitCode(); method.visitVarInsn(Opcodes.ILOAD, 0); Label valid = new Label(); method.visitJumpInsn(Opcodes.IFGE, valid);
        method.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalArgumentException"); method.visitInsn(Opcodes.DUP);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalArgumentException", "<init>", "()V", false); method.visitInsn(Opcodes.ATHROW);
        method.visitLabel(valid); method.visitVarInsn(Opcodes.ILOAD, 0); method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(0, 0); method.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }
    private static byte[] hooksFixture() {
        ClassWriter writer = fixture(HOOKS);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "success", "I", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "failure", "I", null, null).visitEnd();
        MethodVisitor begin = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "beginImage", "(ILjava/lang/Object;II)V", null, null);
        begin.visitCode(); begin.visitInsn(Opcodes.RETURN); begin.visitMaxs(0, 0); begin.visitEnd();
        MethodVisitor end = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "end", "(Z)V", null, null); end.visitCode();
        Label failure = new Label(), done = new Label(); end.visitVarInsn(Opcodes.ILOAD, 0); end.visitJumpInsn(Opcodes.IFEQ, failure);
        increment(end, "success"); end.visitJumpInsn(Opcodes.GOTO, done); end.visitLabel(failure); increment(end, "failure");
        end.visitLabel(done); end.visitInsn(Opcodes.RETURN); end.visitMaxs(0, 0); end.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }
    private static void increment(MethodVisitor method, String field) {
        method.visitFieldInsn(Opcodes.GETSTATIC, HOOKS, field, "I"); method.visitInsn(Opcodes.ICONST_1); method.visitInsn(Opcodes.IADD);
        method.visitFieldInsn(Opcodes.PUTSTATIC, HOOKS, field, "I");
    }
    private static final class FixtureLoader extends ClassLoader {
        final Map<String, byte[]> definitions = new HashMap<String, byte[]>();
        void add(String name, byte[] bytes) { definitions.put(name.replace('/', '.'), bytes); }
        @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!definitions.containsKey(name)) return super.loadClass(name, resolve);
            Class<?> result = findLoadedClass(name); if (result == null) { byte[] value = definitions.get(name); result = defineClass(name, value, 0, value.length); }
            if (resolve) resolveClass(result); return result;
        }
    }
}
