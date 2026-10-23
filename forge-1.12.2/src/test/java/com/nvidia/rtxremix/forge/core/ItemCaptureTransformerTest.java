/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.core;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
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

public class ItemCaptureTransformerTest {
    private static final String MANAGER = "net/minecraft/client/renderer/entity/RenderManager";
    private static final String ITEM = "net/minecraft/client/renderer/RenderItem";
    private static final String ENTITY = "net/minecraft/entity/Entity";
    private static final String MODEL = "net/minecraft/client/renderer/block/model/IBakedModel";
    private static final String STACK = "net/minecraft/item/ItemStack";
    private static final String HOOKS = "com/nvidia/rtxremix/forge/item/ItemCaptureHooks";
    private static final String ENTITY_DESC = "(L" + ENTITY + ";DDDFFZ)V";
    private static final String MODEL_DESC = "(L" + MODEL + ";L" + STACK + ";)V";
    private final ItemCaptureTransformer transformer = new ItemCaptureTransformer();

    @Test public void pinnedDrawsPreserveForgeTintAndGlintIsolationWithoutEvaluatingModelsAgain() throws Exception {
        for (boolean srg : new boolean[] {false, true}) {
            ClassNode original = read(classBytes(ITEM));
            if (srg) {
                for (MethodNode method : original.methods) {
                    if (method.name.equals("renderModel") && method.desc.equals(MODEL_DESC)) method.name = "func_191961_a";
                    if (method.name.equals("renderQuads")) method.name = "func_191970_a";
                }
            }
            ClassWriter source = new ClassWriter(0); original.accept(source);
            byte[] output = transform(ITEM, source.toByteArray());
            ClassNode modified = read(output);
            int modelScopes = 0, observations = 0;
            for (int i = 0; i < original.methods.size(); i++) {
                MethodNode before = original.methods.get(i), after = modified.methods.get(i);
                int scopes = count(after, "beginModel");
                if (MODEL_DESC.equals(after.desc)) {
                    assertEquals(1, scopes);
                    assertEquals(2, count(after, "endModel"));
                    assertEquals(before.tryCatchBlocks.size() + 1, after.tryCatchBlocks.size());
                } else assertEquals("Glint and GUI overloads must not open model capture", 0, scopes);
                modelScopes += scopes;
                for (AbstractInsnNode instruction : after.instructions.toArray()) {
                    if (!isHook(instruction, "beforeQuad")) continue;
                    observations++;
                    assertEquals(Opcodes.ALOAD, instruction.getPrevious().getOpcode());
                    assertEquals(Opcodes.DUP2, instruction.getPrevious().getPrevious().getOpcode());
                    MethodInsnNode draw = (MethodInsnNode) instruction.getNext();
                    assertEquals("net/minecraftforge/client/model/pipeline/LightUtil", draw.owner);
                    assertEquals("renderQuadColor", draw.name);
                    assertTrue(isHook(draw.getNext().getNext(), "afterQuad"));
                    assertEquals(1, count(after, "afterQuad"));
                }
                assertEquals(before.maxLocals, after.maxLocals);
                assertEquals(calls(before, "getQuads"), calls(after, "getQuads"));
                assertEquals(calls(before, "renderQuadColor"), calls(after, "renderQuadColor"));
            }
            assertEquals(1, modelScopes);
            assertEquals(1, observations);
            verify(modified);
            assertSame(output, transform(ITEM, output));
        }
    }

    @Test public void pinnedRenderManagerScopesOnlyItsEntityDispatch() throws Exception {
        byte[] original = classBytes(MANAGER);
        ClassNode modified = read(transform(MANAGER, original));
        int scopes = 0;
        for (MethodNode method : modified.methods) {
            if (method.desc.equals(ENTITY_DESC) && method.name.equals("renderEntity")) {
                assertEquals(1, count(method, "beginEntity"));
                assertEquals(2, count(method, "endEntity"));
            } else assertEquals(0, count(method, "beginEntity"));
            scopes += count(method, "beginEntity");
        }
        assertEquals(1, scopes);
        verify(modified);
    }

    @Test public void bothScopesCloseOnEarlyReturnAndExceptionInMappedAndProductionNames() throws Exception {
        for (boolean model : new boolean[] {false, true}) for (boolean srg : new boolean[] {false, true}) {
            String type = model ? ITEM : MANAGER;
            String descriptor = model ? MODEL_DESC : ENTITY_DESC;
            String name = model ? (srg ? "func_191961_a" : "renderModel") : (srg ? "func_188391_a" : "renderEntity");
            FixtureLoader loader = new FixtureLoader();
            loader.add(ENTITY, empty(ENTITY)); loader.add(MODEL, empty(MODEL)); loader.add(STACK, empty(STACK));
            loader.add(HOOKS, hooks());
            loader.add(type, transform(type, scopeFixture(type, name, descriptor)));
            Class<?> target = loader.loadClass(type.replace('/', '.'));
            Class<?> hooks = loader.loadClass(HOOKS.replace('/', '.'));
            Method draw = model ? target.getMethod(name, loader.loadClass(MODEL.replace('/', '.')), loader.loadClass(STACK.replace('/', '.')))
                    : target.getMethod(name, loader.loadClass(ENTITY.replace('/', '.')), double.class, double.class, double.class,
                    float.class, float.class, boolean.class);
            Object instance = target.newInstance();
            for (int mode = 0; mode < 3; mode++) {
                target.getField("mode").setInt(null, mode);
                try {
                    if (model) draw.invoke(instance, new Object[] {null, null});
                    else draw.invoke(instance, null, 0D, 0D, 0D, 0F, 0F, false);
                    assertTrue(mode < 2);
                } catch (InvocationTargetException failure) {
                    assertEquals(2, mode);
                    assertTrue(failure.getCause() instanceof IllegalStateException);
                }
                assertEquals(mode + 1, hooks.getField("begins").getInt(null));
                assertEquals(mode + 1, hooks.getField("ends").getInt(null));
            }
        }
    }

    private static byte[] scopeFixture(String type, String name, String descriptor) {
        ClassWriter writer = fixture(type);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "mode", "I", null, null).visitEnd();
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, name, descriptor, null, null);
        method.visitCode();
        Label next = new Label();
        method.visitFieldInsn(Opcodes.GETSTATIC, type, "mode", "I"); method.visitInsn(Opcodes.ICONST_1);
        method.visitJumpInsn(Opcodes.IF_ICMPNE, next); method.visitInsn(Opcodes.RETURN); method.visitLabel(next);
        Label done = new Label();
        method.visitFieldInsn(Opcodes.GETSTATIC, type, "mode", "I"); method.visitInsn(Opcodes.ICONST_2);
        method.visitJumpInsn(Opcodes.IF_ICMPNE, done);
        method.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException"); method.visitInsn(Opcodes.DUP);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "()V", false);
        method.visitInsn(Opcodes.ATHROW); method.visitLabel(done); method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0); method.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }
    private static byte[] hooks() {
        ClassWriter writer = fixture(HOOKS);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "begins", "I", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "ends", "I", null, null).visitEnd();
        for (String name : new String[] {"beginEntity", "endEntity", "beginModel", "endModel"}) {
            boolean begin = name.startsWith("begin");
            String desc = !begin ? "()V" : name.equals("beginEntity") ? "(Ljava/lang/Object;)V" : "(Ljava/lang/Object;Ljava/lang/Object;)V";
            MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, desc, null, null);
            method.visitCode(); method.visitFieldInsn(Opcodes.GETSTATIC, HOOKS, begin ? "begins" : "ends", "I");
            method.visitInsn(Opcodes.ICONST_1); method.visitInsn(Opcodes.IADD);
            method.visitFieldInsn(Opcodes.PUTSTATIC, HOOKS, begin ? "begins" : "ends", "I");
            method.visitInsn(Opcodes.RETURN); method.visitMaxs(0, 0); method.visitEnd();
        }
        writer.visitEnd(); return writer.toByteArray();
    }
    private static ClassWriter fixture(String type) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, type, null, "java/lang/Object", null);
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode(); init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN); init.visitMaxs(0, 0); init.visitEnd(); return writer;
    }
    private static byte[] empty(String type) { ClassWriter writer = fixture(type); writer.visitEnd(); return writer.toByteArray(); }
    private byte[] transform(String name, byte[] bytes) { return transformer.transform(name.replace('/', '.'), name.replace('/', '.'), bytes); }
    private static ClassNode read(byte[] bytes) { ClassNode node = new ClassNode(Opcodes.ASM5); new ClassReader(bytes).accept(node, 0); return node; }
    private static void verify(ClassNode type) throws Exception {
        for (MethodNode method : type.methods) if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0) {
            new Analyzer<>(new BasicVerifier()).analyze(type.name, method);
        }
    }
    private static int count(MethodNode method, String name) {
        int count = 0; for (AbstractInsnNode instruction : method.instructions.toArray()) if (isHook(instruction, name)) count++; return count;
    }
    private static boolean isHook(AbstractInsnNode instruction, String name) {
        return instruction instanceof MethodInsnNode && ((MethodInsnNode) instruction).owner.equals(HOOKS)
                && ((MethodInsnNode) instruction).name.equals(name);
    }
    private static int calls(MethodNode method, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode && ((MethodInsnNode) instruction).name.equals(name)) count++;
        }
        return count;
    }
    private static byte[] classBytes(String name) throws Exception {
        try (InputStream stream = ItemCaptureTransformerTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(name, stream);
            ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int size;
            while ((size = stream.read(buffer)) >= 0) output.write(buffer, 0, size);
            return output.toByteArray();
        }
    }
    private static final class FixtureLoader extends ClassLoader {
        private final Map<String, byte[]> classes = new HashMap<>();
        void add(String name, byte[] bytes) { classes.put(name.replace('/', '.'), bytes); }
        @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!classes.containsKey(name)) return super.loadClass(name, resolve);
            Class<?> type = findLoadedClass(name);
            if (type == null) { byte[] bytes = classes.get(name); type = defineClass(name, bytes, 0, bytes.length); }
            if (resolve) resolveClass(type); return type;
        }
    }
}
