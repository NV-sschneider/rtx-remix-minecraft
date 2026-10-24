/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import static org.junit.Assert.*;

public class MobHudTransformerTest {
    private static final String TARGET = "vazkii/neat/HealthBarRenderer";
    private static final String HOOKS = "com/nvidia/rtxremix/forge/hud/MobHudHooks";
    private static final String GL = "net/minecraft/client/renderer/GlStateManager";
    private static final String EVENT = "net/minecraftforge/client/event/RenderWorldLastEvent";
    private static final String RENDER = "(L" + EVENT + ";)V";

    @Test public void scopesMcpAndSrgRenderersWithoutDuplicatingOrReplacingTheirLogic() throws Exception {
        for (boolean srg : new boolean[] {false, true}) assertPreserved(fixture(srg));
    }

    @Test public void jvmExecutesOriginalEventOnceAndClosesCaptureOnReturnEarlyReturnAndThrow() throws Exception {
        for (boolean srg : new boolean[] {false, true}) for (int mode = 0; mode < 3; mode++) {
            Loader loader = new Loader(); loader.add(TARGET, transform(fixture(srg)));
            loader.add(HOOKS, hooks()); loader.add(EVENT, empty(EVENT));
            Class<?> target = loader.loadClass(TARGET.replace('/', '.'));
            Class<?> hooks = loader.loadClass(HOOKS.replace('/', '.'));
            Class<?> event = loader.loadClass(EVENT.replace('/', '.'));
            target.getField("mode").setInt(null, mode);
            IllegalStateException originalFailure = new IllegalStateException("original renderer");
            target.getField("failure").set(null, originalFailure);
            try {
                target.getMethod("onRenderWorldLast", event).invoke(target.newInstance(), event.newInstance());
                assertNotEquals("The original failure must propagate", 2, mode);
            } catch (InvocationTargetException failure) {
                assertEquals(2, mode); assertSame(originalFailure, failure.getCause());
            }
            assertEquals(1, target.getField("invocations").getInt(null));
            assertEquals(1, target.getField("selections").getInt(null));
            assertEquals(mode == 1 ? 0 : 1, target.getField("draws").getInt(null));
            assertEquals(1, hooks.getField("begins").getInt(null));
            assertEquals(1, hooks.getField("ends").getInt(null));
            assertEquals(0, hooks.getField("depth").getInt(null));
            assertEquals(mode == 2 ? 0 : 1, hooks.getField("successes").getInt(null));
            assertEquals(mode == 2 ? 1 : 0, hooks.getField("failures").getInt(null));
            assertEquals(mode == 1 ? 0 : 1, hooks.getField("blends").getInt(null));
            if (mode != 1) {
                assertEquals(770, hooks.getField("source").getInt(null));
                assertEquals(771, hooks.getField("destination").getInt(null));
            }
        }
    }

    @Test public void unknownEventOrBlendLayoutFailsClosedWithoutReturningAPartialTransform() {
        for (int mutation = 0; mutation < 3; mutation++) {
            ClassNode changed = read(fixture(false));
            for (MethodNode method : changed.methods) {
                if (method.name.equals("onRenderWorldLast")) {
                    if (mutation == 0) method.name = "onUnknownEvent";
                    if (mutation == 1) method.desc = "(Ljava/lang/Object;)V";
                }
                if (mutation == 2) for (AbstractInsnNode node : method.instructions.toArray()) {
                    if (node instanceof MethodInsnNode && GL.equals(((MethodInsnNode) node).owner))
                        ((MethodInsnNode) node).name = "unknownBlend";
                }
            }
            byte[] bytes = write(changed), before = bytes.clone();
            assertThrows(IllegalStateException.class, () -> transform(bytes));
            assertArrayEquals(before, bytes);
            for (MethodNode method : read(bytes).methods) assertEquals(0, hookCount(method, "begin"));
        }
        MobHudTransformer transformer = new MobHudTransformer(); byte[] unrelated = fixture(false);
        assertSame(unrelated, transformer.transform("other.Renderer", "other.Renderer", unrelated));
        assertNull(transformer.transform(TARGET.replace('/', '.'), TARGET.replace('/', '.'), null));
    }

    /** Audits the installed release without adding Neat or its implementation to the test runtime. */
    @Test public void installedNeatRetainsHealthNamesSelectionVisibilityAndOriginalDrawOrder() throws Exception {
        String path = System.getProperty("rtxremix.rlcraftMods"); Assume.assumeNotNull(path);
        try (JarFile jar = new JarFile(new File(path, "Neat 1.4-17.jar"))) {
            assertNotNull(jar.getJarEntry(TARGET + ".class"));
            try (InputStream stream = jar.getInputStream(jar.getJarEntry(TARGET + ".class"))) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
                while ((count = stream.read(buffer)) >= 0) bytes.write(buffer, 0, count);
                assertPreserved(bytes.toByteArray());
            }
        }
    }

    private static void assertPreserved(byte[] input) throws Exception {
        ClassNode original = read(input); byte[] output = transform(input); ClassNode modified = read(output);
        assertEquals(original.methods.size(), modified.methods.size());
        int events = 0, blends = 0;
        for (int index = 0; index < original.methods.size(); index++) {
            MethodNode before = original.methods.get(index), after = modified.methods.get(index);
            assertEquals(before.name, after.name); assertEquals(before.desc, after.desc);
            assertEquals(before.maxLocals, after.maxLocals);
            assertEquals("Original calls and their order in " + before.name, originalCalls(before), originalCalls(after));
            assertEquals("Original configuration and entity fields in " + before.name, fields(before), fields(after));
            int oldBlends = 0;
            for (AbstractInsnNode node : before.instructions.toArray()) if (node instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) node;
                if (GL.equals(call.owner) && call.desc.equals("(II)V")
                        && (call.name.equals("blendFunc") || call.name.equals("func_179112_b"))) oldBlends++;
            }
            assertEquals(oldBlends, hookCount(after, "blendFunc")); blends += oldBlends;
            if (before.name.equals("onRenderWorldLast") && before.desc.equals(RENDER)) {
                events++; assertEquals(1, hookCount(after, "begin"));
                int returns = 0;
                for (AbstractInsnNode node : after.instructions.toArray()) if (node.getOpcode() == Opcodes.RETURN) {
                    returns++; assertTrue(isHook(previous(node), "end"));
                    assertEquals(Opcodes.ICONST_1, previous(previous(node)).getOpcode());
                }
                assertEquals(returns + 1, hookCount(after, "end"));
                assertEquals(before.tryCatchBlocks.size() + 1, after.tryCatchBlocks.size());
                assertNull(after.tryCatchBlocks.get(after.tryCatchBlocks.size() - 1).type);
            } else {
                assertEquals(0, hookCount(after, "begin") + hookCount(after, "end"));
                assertEquals(before.tryCatchBlocks.size(), after.tryCatchBlocks.size());
            }
            if ((after.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0)
                new Analyzer<>(new BasicVerifier()).analyze(modified.name, after);
        }
        assertEquals(1, events); assertTrue(blends > 0); assertSame(output, transform(output));
    }

    private static byte[] fixture(boolean srg) {
        ClassWriter writer = type(TARGET);
        for (String field : new String[] {"mode", "invocations", "selections", "draws"}) integerField(writer, field);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "failure", "Ljava/lang/RuntimeException;", null, null).visitEnd();
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "onRenderWorldLast", RENDER, null, null);
        method.visitCode(); increment(method, TARGET, "invocations");
        method.visitMethodInsn(Opcodes.INVOKESTATIC, TARGET, "select", "()Z", false); Label draw = new Label();
        method.visitJumpInsn(Opcodes.IFNE, draw); method.visitInsn(Opcodes.RETURN); method.visitLabel(draw);
        method.visitVarInsn(Opcodes.ALOAD, 0); method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TARGET, "renderHealthBar", "()V", false);
        method.visitInsn(Opcodes.RETURN); finish(method);
        method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "select", "()Z", null, null);
        method.visitCode(); increment(method, TARGET, "selections"); Label selected = new Label();
        method.visitFieldInsn(Opcodes.GETSTATIC, TARGET, "mode", "I"); method.visitInsn(Opcodes.ICONST_1);
        method.visitJumpInsn(Opcodes.IF_ICMPNE, selected); method.visitInsn(Opcodes.ICONST_0); method.visitInsn(Opcodes.IRETURN);
        method.visitLabel(selected); method.visitInsn(Opcodes.ICONST_1); method.visitInsn(Opcodes.IRETURN); finish(method);
        method = writer.visitMethod(Opcodes.ACC_PUBLIC, "renderHealthBar", "()V", null, null);
        method.visitCode(); increment(method, TARGET, "draws"); method.visitLdcInsn(770); method.visitLdcInsn(771);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, GL, srg ? "func_179112_b" : "blendFunc", "(II)V", false);
        Label done = new Label(); method.visitFieldInsn(Opcodes.GETSTATIC, TARGET, "mode", "I"); method.visitInsn(Opcodes.ICONST_2);
        method.visitJumpInsn(Opcodes.IF_ICMPNE, done);
        method.visitFieldInsn(Opcodes.GETSTATIC, TARGET, "failure", "Ljava/lang/RuntimeException;"); method.visitInsn(Opcodes.ATHROW);
        method.visitLabel(done); method.visitInsn(Opcodes.RETURN); finish(method);
        writer.visitEnd(); return writer.toByteArray();
    }

    private static byte[] hooks() {
        ClassWriter writer = type(HOOKS);
        for (String field : new String[] {"depth", "begins", "ends", "successes", "failures", "blends", "source", "destination"})
            integerField(writer, field);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "begin", "()V", null, null);
        method.visitCode(); increment(method, HOOKS, "begins"); increment(method, HOOKS, "depth");
        method.visitInsn(Opcodes.RETURN); finish(method);
        method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "end", "(Z)V", null, null);
        method.visitCode(); increment(method, HOOKS, "ends");
        method.visitFieldInsn(Opcodes.GETSTATIC, HOOKS, "depth", "I"); method.visitInsn(Opcodes.ICONST_1); method.visitInsn(Opcodes.ISUB);
        method.visitFieldInsn(Opcodes.PUTSTATIC, HOOKS, "depth", "I");
        Label failed = new Label(), done = new Label(); method.visitVarInsn(Opcodes.ILOAD, 0); method.visitJumpInsn(Opcodes.IFEQ, failed);
        increment(method, HOOKS, "successes"); method.visitJumpInsn(Opcodes.GOTO, done);
        method.visitLabel(failed); increment(method, HOOKS, "failures"); method.visitLabel(done); method.visitInsn(Opcodes.RETURN); finish(method);
        method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "blendFunc", "(II)V", null, null);
        method.visitCode(); increment(method, HOOKS, "blends");
        method.visitVarInsn(Opcodes.ILOAD, 0); method.visitFieldInsn(Opcodes.PUTSTATIC, HOOKS, "source", "I");
        method.visitVarInsn(Opcodes.ILOAD, 1); method.visitFieldInsn(Opcodes.PUTSTATIC, HOOKS, "destination", "I");
        method.visitInsn(Opcodes.RETURN); finish(method); writer.visitEnd(); return writer.toByteArray();
    }

    private static ClassWriter type(String name) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode(); constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN); finish(constructor); return writer;
    }
    private static void integerField(ClassWriter writer, String name) {
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, "I", null, null).visitEnd();
    }
    private static void increment(MethodVisitor method, String owner, String field) {
        method.visitFieldInsn(Opcodes.GETSTATIC, owner, field, "I"); method.visitInsn(Opcodes.ICONST_1); method.visitInsn(Opcodes.IADD);
        method.visitFieldInsn(Opcodes.PUTSTATIC, owner, field, "I");
    }
    private static void finish(MethodVisitor method) { method.visitMaxs(0, 0); method.visitEnd(); }
    private static byte[] empty(String name) { ClassWriter writer = type(name); writer.visitEnd(); return writer.toByteArray(); }
    private static byte[] transform(byte[] bytes) {
        return new MobHudTransformer().transform(TARGET.replace('/', '.'), TARGET.replace('/', '.'), bytes);
    }
    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM5); new ClassReader(bytes).accept(node, 0); return node;
    }
    private static byte[] write(ClassNode node) { ClassWriter writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray(); }
    private static boolean isHook(AbstractInsnNode node, String name) {
        return node instanceof MethodInsnNode && HOOKS.equals(((MethodInsnNode) node).owner) && name.equals(((MethodInsnNode) node).name);
    }
    private static int hookCount(MethodNode method, String name) {
        int count = 0; for (AbstractInsnNode node : method.instructions.toArray()) if (isHook(node, name)) count++; return count;
    }
    private static AbstractInsnNode previous(AbstractInsnNode node) {
        do { node = node.getPrevious(); } while (node != null && node.getOpcode() < 0); return node;
    }
    private static List<String> originalCalls(MethodNode method) {
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode node : method.instructions.toArray()) if (node instanceof MethodInsnNode) {
            MethodInsnNode call = (MethodInsnNode) node;
            if (HOOKS.equals(call.owner) && !call.name.equals("blendFunc")) continue;
            if (HOOKS.equals(call.owner) || GL.equals(call.owner)
                    && (call.name.equals("blendFunc") || call.name.equals("func_179112_b")) && call.desc.equals("(II)V"))
                calls.add(Opcodes.INVOKESTATIC + GL + "blendFunc(II)V");
            else calls.add(call.getOpcode() + call.owner + call.name + call.desc);
        }
        return calls;
    }
    private static List<String> fields(MethodNode method) {
        List<String> fields = new ArrayList<>();
        for (AbstractInsnNode node : method.instructions.toArray()) if (node instanceof FieldInsnNode) {
            FieldInsnNode field = (FieldInsnNode) node; fields.add(field.getOpcode() + field.owner + field.name + field.desc);
        }
        return fields;
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
