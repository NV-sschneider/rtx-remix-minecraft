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

public class FirstPersonCaptureTransformerTest {
    private static final String TARGET = "net/minecraft/client/renderer/EntityRenderer";
    private static final String HOOKS = "com/nvidia/rtxremix/forge/entity/FirstPersonCapture";
    private static final String FORGE = "net/minecraftforge/client/ForgeHooksClient";
    private static final String GL = "net/minecraft/client/renderer/GlStateManager";
    private static final String ITEM = "net/minecraft/client/renderer/ItemRenderer";
    private static final String GLOBAL = "net/minecraft/client/renderer/RenderGlobal";
    private static final String EVENT = "(L" + GLOBAL + ";FI)Z";

    @Test public void pinnedTimingIncludesForgeReplacementAndPreservesAllOriginalDrawCalls() throws Exception {
        for (boolean srg : new boolean[] {false, true}) {
            ClassNode original = read(classBytes(TARGET));
            if (srg) for (MethodNode method : original.methods) {
                if (method.name.equals("renderHand")) method.name = "func_78476_b";
                if (method.name.equals("renderWorldPass")) method.name = "func_175068_a";
                for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) instruction;
                    if (GL.equals(call.owner) && call.name.equals("popMatrix")) call.name = "func_179121_F";
                    if (ITEM.equals(call.owner) && call.name.equals("renderOverlays")) call.name = "func_78447_b";
                }
            }
            ClassWriter source = new ClassWriter(0); original.accept(source);
            byte[] output = transform(source.toByteArray());
            ClassNode modified = read(output);
            int handMethods = 0, worldMethods = 0;
            for (int i = 0; i < original.methods.size(); i++) {
                MethodNode before = original.methods.get(i), after = modified.methods.get(i);
                assertEquals(originalCalls(before), originalCalls(after));
                assertEquals(before.maxLocals, after.maxLocals);
                if (after.name.equals(srg ? "func_78476_b" : "renderHand")) {
                    handMethods++;
                    assertEquals(1, count(after, "beginHand"));
                    assertEquals(1, count(after, "submitFrame"));
                    assertEquals(before.tryCatchBlocks.size() + 1, after.tryCatchBlocks.size());
                    for (AbstractInsnNode instruction : after.instructions.toArray()) if (instruction instanceof MethodInsnNode) {
                        MethodInsnNode call = (MethodInsnNode) instruction;
                        if (FORGE.equals(call.owner) && call.name.equals("renderFirstPersonHand")) assertTrue(hook(call.getPrevious(), "beginHand"));
                        if (GL.equals(call.owner) && call.name.equals(srg ? "func_179121_F" : "popMatrix")) assertTrue(hook(call.getPrevious(), "endHand"));
                        if (ITEM.equals(call.owner) && call.name.equals(srg ? "func_78447_b" : "renderOverlays")) assertTrue(hook(call.getPrevious(), "submitFrame"));
                    }
                } else if (after.name.equals(srg ? "func_175068_a" : "renderWorldPass")) {
                    worldMethods++;
                    for (AbstractInsnNode instruction : after.instructions.toArray()) if (instruction.getOpcode() == Opcodes.RETURN) {
                        assertTrue(hook(instruction.getPrevious(), "submitFrame"));
                    }
                } else assertEquals(0, count(after, "beginHand") + count(after, "submitFrame"));
                if ((after.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0) new Analyzer<>(new BasicVerifier()).analyze(modified.name, after);
            }
            assertEquals(1, handMethods); assertEquals(1, worldMethods);
            assertSame(output, transform(output));
        }
    }

    @Test public void jvmVerifiesFinallyForAcceptedCancelledSkippedAndThrowingHands() throws Exception {
        for (int mode = 0; mode < 4; mode++) {
            Loader loader = new Loader();
            loader.add(TARGET, transform(fixture()));
            loader.add(HOOKS, hookFixture()); loader.add(FORGE, forgeFixture());
            loader.add(GL, noOp(GL, "popMatrix", "()V"));
            loader.add(ITEM, noOp(ITEM, "renderOverlays", "(F)V"));
            loader.add(GLOBAL, noOp(GLOBAL, "noop", "()V"));
            Class<?> target = loader.loadClass(TARGET.replace('/', '.'));
            Class<?> hooks = loader.loadClass(HOOKS.replace('/', '.'));
            Class<?> forge = loader.loadClass(FORGE.replace('/', '.'));
            forge.getField("mode").setInt(null, mode);
            target.getField("skip").setBoolean(null, mode == 3);
            try {
                target.getMethod("renderWorldPass", int.class, float.class, long.class).invoke(target.newInstance(), 0, 0.5f, 0L);
                assertNotEquals(2, mode);
            } catch (InvocationTargetException failure) {
                assertEquals(2, mode); assertTrue(failure.getCause() instanceof IllegalStateException);
            }
            assertEquals(0, hooks.getField("depth").getInt(null));
            assertEquals(mode == 3 ? 0 : 1, hooks.getField("begins").getInt(null));
            assertEquals(mode == 3 ? 0 : 1, hooks.getField("ends").getInt(null));
            assertEquals(mode == 2 ? 0 : 1, hooks.getField("submits").getInt(null));
            assertEquals(mode == 0 ? 1 : 0, target.getField("draws").getInt(null));
        }
    }

    private static byte[] fixture() {
        ClassWriter writer = type(TARGET);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "skip", "Z", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "draws", "I", null, null).visitEnd();
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "renderHand", "(FI)V", null, null);
        method.visitCode(); Label start = new Label(), done = new Label();
        method.visitFieldInsn(Opcodes.GETSTATIC, TARGET, "skip", "Z"); method.visitJumpInsn(Opcodes.IFEQ, start);
        method.visitInsn(Opcodes.RETURN); method.visitLabel(start);
        method.visitInsn(Opcodes.ACONST_NULL); method.visitVarInsn(Opcodes.FLOAD, 1); method.visitVarInsn(Opcodes.ILOAD, 2);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, FORGE, "renderFirstPersonHand", EVENT, false);
        method.visitJumpInsn(Opcodes.IFNE, done); increment(method, TARGET, "draws"); method.visitLabel(done);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, GL, "popMatrix", "()V", false);
        method.visitVarInsn(Opcodes.FLOAD, 1); method.visitMethodInsn(Opcodes.INVOKESTATIC, ITEM, "renderOverlays", "(F)V", false);
        method.visitInsn(Opcodes.RETURN); finish(method);
        method = writer.visitMethod(Opcodes.ACC_PUBLIC, "renderWorldPass", "(IFJ)V", null, null);
        method.visitCode(); method.visitVarInsn(Opcodes.ALOAD, 0); method.visitVarInsn(Opcodes.FLOAD, 2); method.visitVarInsn(Opcodes.ILOAD, 1);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TARGET, "renderHand", "(FI)V", false); method.visitInsn(Opcodes.RETURN); finish(method);
        writer.visitEnd(); return writer.toByteArray();
    }
    private static byte[] hookFixture() {
        ClassWriter writer = type(HOOKS);
        for (String field : new String[] {"depth", "begins", "ends", "submits"}) writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, field, "I", null, null).visitEnd();
        for (String name : new String[] {"beginHand", "endHand", "submitFrame"}) {
            MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, "()V", null, null); method.visitCode();
            Label done = new Label();
            if (name.equals("beginHand")) { increment(method, HOOKS, "begins"); increment(method, HOOKS, "depth"); }
            else if (name.equals("endHand")) {
                method.visitFieldInsn(Opcodes.GETSTATIC, HOOKS, "depth", "I"); method.visitJumpInsn(Opcodes.IFEQ, done);
                method.visitInsn(Opcodes.ICONST_0); method.visitFieldInsn(Opcodes.PUTSTATIC, HOOKS, "depth", "I"); increment(method, HOOKS, "ends");
            } else {
                method.visitFieldInsn(Opcodes.GETSTATIC, HOOKS, "depth", "I"); Label clean = new Label(); method.visitJumpInsn(Opcodes.IFEQ, clean);
                throwFailure(method); method.visitLabel(clean);
                method.visitFieldInsn(Opcodes.GETSTATIC, HOOKS, "submits", "I"); method.visitJumpInsn(Opcodes.IFNE, done); increment(method, HOOKS, "submits");
            }
            method.visitLabel(done); method.visitInsn(Opcodes.RETURN); finish(method);
        }
        writer.visitEnd(); return writer.toByteArray();
    }
    private static byte[] forgeFixture() {
        ClassWriter writer = type(FORGE);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "mode", "I", null, null).visitEnd();
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "renderFirstPersonHand", EVENT, null, null);
        method.visitCode(); Label okay = new Label();
        method.visitFieldInsn(Opcodes.GETSTATIC, FORGE, "mode", "I"); method.visitInsn(Opcodes.ICONST_2); method.visitJumpInsn(Opcodes.IF_ICMPNE, okay);
        throwFailure(method); method.visitLabel(okay); method.visitFieldInsn(Opcodes.GETSTATIC, FORGE, "mode", "I"); method.visitInsn(Opcodes.IRETURN); finish(method);
        writer.visitEnd(); return writer.toByteArray();
    }
    private static void throwFailure(MethodVisitor method) {
        method.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException"); method.visitInsn(Opcodes.DUP);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "()V", false); method.visitInsn(Opcodes.ATHROW);
    }
    private static byte[] noOp(String owner, String name, String descriptor) {
        ClassWriter writer = type(owner); MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, descriptor, null, null);
        method.visitCode(); method.visitInsn(Opcodes.RETURN); finish(method); writer.visitEnd(); return writer.toByteArray();
    }
    private static void increment(MethodVisitor method, String owner, String field) {
        method.visitFieldInsn(Opcodes.GETSTATIC, owner, field, "I"); method.visitInsn(Opcodes.ICONST_1); method.visitInsn(Opcodes.IADD); method.visitFieldInsn(Opcodes.PUTSTATIC, owner, field, "I");
    }
    private static ClassWriter type(String name) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null); method.visitCode(); method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false); method.visitInsn(Opcodes.RETURN); finish(method); return writer;
    }
    private static void finish(MethodVisitor method) { method.visitMaxs(0, 0); method.visitEnd(); }
    private static byte[] transform(byte[] bytes) { return new FirstPersonCaptureTransformer().transform(TARGET.replace('/', '.'), TARGET.replace('/', '.'), bytes); }
    private static ClassNode read(byte[] bytes) { ClassNode type = new ClassNode(Opcodes.ASM5); new ClassReader(bytes).accept(type, 0); return type; }
    private static int count(MethodNode method, String name) { int count = 0; for (AbstractInsnNode instruction : method.instructions.toArray()) if (hook(instruction, name)) count++; return count; }
    private static boolean hook(AbstractInsnNode instruction, String name) {
        return instruction instanceof MethodInsnNode && HOOKS.equals(((MethodInsnNode) instruction).owner) && name.equals(((MethodInsnNode) instruction).name);
    }
    private static Map<String, Integer> originalCalls(MethodNode method) {
        Map<String, Integer> result = new HashMap<>();
        for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction instanceof MethodInsnNode) {
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (HOOKS.equals(call.owner)) continue;
            String key = call.getOpcode() + call.owner + call.name + call.desc;
            result.put(key, result.containsKey(key) ? result.get(key) + 1 : 1);
        }
        return result;
    }
    private static byte[] classBytes(String name) throws Exception {
        try (InputStream stream = FirstPersonCaptureTransformerTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(stream); ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
            while ((count = stream.read(buffer)) >= 0) out.write(buffer, 0, count); return out.toByteArray();
        }
    }
    private static final class Loader extends ClassLoader {
        private final Map<String, byte[]> classes = new HashMap<>();
        void add(String name, byte[] bytes) { classes.put(name.replace('/', '.'), bytes); }
        @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            byte[] bytes = classes.get(name); if (bytes == null) return super.loadClass(name, resolve);
            Class<?> type = findLoadedClass(name); if (type == null) type = defineClass(name, bytes, 0, bytes.length);
            if (resolve) resolveClass(type); return type;
        }
    }
}
