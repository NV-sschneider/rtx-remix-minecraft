/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.core;

import com.nvidia.rtxremix.forge.frame.WorldFallbackHooks;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import static org.junit.Assert.*;

public class WorldFallbackTransformerTest {
    private static final String TARGET = "net/minecraft/client/renderer/EntityRenderer";
    private static final String HOOKS = "com/nvidia/rtxremix/forge/frame/WorldFallbackHooks";
    private final WorldFallbackTransformer transformer = new WorldFallbackTransformer();

    @Test public void hookRunsAfterWorldAndHandCompletionBeforePostProcessingHudAndScreens() throws Exception {
        byte[] original = bytes();
        byte[] weather = new EffectCaptureTransformer().transform("obfuscated", TARGET.replace('/', '.'), original);
        byte[] hands = new FirstPersonCaptureTransformer().transform("obfuscated", TARGET.replace('/', '.'), weather);
        byte[] patched = transform(hands); ClassNode type = read(patched); verify(type);
        assertArrayEquals(patched, transform(patched));
        MethodNode update = method(type, "updateCameraAndRender"); boolean rendered = false, hooked = false;
        int count = 0;
        for (AbstractInsnNode instruction : update.instructions.toArray()) if (instruction instanceof MethodInsnNode) {
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (call.name.equals("renderWorld")) { rendered = true; assertFalse(hooked); }
            if (call.owner.equals(HOOKS)) { assertTrue(rendered); assertFalse(hooked); hooked = true; count++; }
            if (call.name.equals("renderGameOverlay") || call.name.equals("drawScreen")) assertTrue(hooked);
            if (call.owner.equals("net/minecraft/client/shader/ShaderGroup") && call.name.equals("render")) assertTrue(hooked);
        }
        assertEquals(1, count);
    }

    @Test public void pinnedSrgNamesComposeAndMissingWorldCallIsRejected() throws Exception {
        ClassNode type = read(bytes()); MethodNode method = method(type, "updateCameraAndRender"); method.name = "func_181560_a";
        for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction instanceof MethodInsnNode) {
            MethodInsnNode call = (MethodInsnNode) instruction; if (call.name.equals("renderWorld")) call.name = "func_78471_a";
        }
        byte[] patched = transform(write(type)); verify(read(patched));
        ClassNode missing = read(bytes()); MethodNode update = method(missing, "updateCameraAndRender");
        for (AbstractInsnNode instruction : update.instructions.toArray()) if (instruction instanceof MethodInsnNode && ((MethodInsnNode) instruction).name.equals("renderWorld")) ((MethodInsnNode) instruction).name = "unknownWorldRenderer";
        assertThrows(IllegalStateException.class, () -> transform(write(missing)));
    }

    @Test public void actualJvmCallbackRunsEvenWhenCaptureResetDuringTheWorldPass() throws Exception {
        byte[] value = transform(fixture());
        Class<?> type = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() { return defineClass(TARGET.replace('/', '.'), value, 0, value.length); }
        }.define();
        int[] calls = {0};
        WorldFallbackHooks.setBeforeHudCallback(() -> {
            try { assertFalse(type.getField("captureArmed").getBoolean(null)); assertFalse(type.getField("hudDrawn").getBoolean(null)); calls[0]++; }
            catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        });
        try {
            type.getField("captureArmed").setBoolean(null, true);
            type.getMethod("updateCameraAndRender", float.class, long.class).invoke(type.newInstance(), 0f, 0L);
            assertEquals(1, calls[0]); assertTrue(type.getField("hudDrawn").getBoolean(null));
        } finally { WorldFallbackHooks.setBeforeHudCallback(null); }
    }

    private byte[] transform(byte[] bytes) { return transformer.transform("obfuscated", TARGET.replace('/', '.'), bytes); }
    private static ClassNode read(byte[] bytes) { ClassNode node = new ClassNode(Opcodes.ASM5); new ClassReader(bytes).accept(node, 0); return node; }
    private static byte[] write(ClassNode node) { ClassWriter writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray(); }
    private static MethodNode method(ClassNode type, String name) { for (MethodNode method : type.methods) if (name.equals(method.name) && "(FJ)V".equals(method.desc)) return method; throw new AssertionError(name); }
    private static byte[] bytes() throws Exception {
        try (InputStream input = WorldFallbackTransformerTest.class.getClassLoader().getResourceAsStream(TARGET + ".class")) {
            assertNotNull(input); ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int length;
            while ((length = input.read(buffer)) >= 0) bytes.write(buffer, 0, length); return bytes.toByteArray();
        }
    }
    private static void verify(ClassNode type) throws Exception {
        for (MethodNode method : type.methods) if ((method.access & (Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT)) == 0) new Analyzer<>(new BasicVerifier()).analyze(type.name, method);
    }
    private static byte[] fixture() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, TARGET, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "captureArmed", "Z", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "hudDrawn", "Z", null, null).visitEnd();
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode(); constructor.visitVarInsn(Opcodes.ALOAD, 0); constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false); constructor.visitInsn(Opcodes.RETURN); constructor.visitMaxs(0, 0); constructor.visitEnd();
        MethodVisitor world = writer.visitMethod(Opcodes.ACC_PUBLIC, "renderWorld", "(FJ)V", null, null);
        world.visitCode(); world.visitInsn(Opcodes.ICONST_0); world.visitFieldInsn(Opcodes.PUTSTATIC, TARGET, "captureArmed", "Z"); world.visitInsn(Opcodes.RETURN); world.visitMaxs(0, 0); world.visitEnd();
        MethodVisitor update = writer.visitMethod(Opcodes.ACC_PUBLIC, "updateCameraAndRender", "(FJ)V", null, null);
        update.visitCode(); update.visitVarInsn(Opcodes.ALOAD, 0); update.visitVarInsn(Opcodes.FLOAD, 1); update.visitVarInsn(Opcodes.LLOAD, 2);
        update.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TARGET, "renderWorld", "(FJ)V", false);
        update.visitInsn(Opcodes.ICONST_1); update.visitFieldInsn(Opcodes.PUTSTATIC, TARGET, "hudDrawn", "Z"); update.visitInsn(Opcodes.RETURN); update.visitMaxs(0, 0); update.visitEnd();
        writer.visitEnd(); return writer.toByteArray();
    }
}
