/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import static org.junit.Assert.*;

public class EntityCaptureTransformerTest {
    private static final String LIVING = "net/minecraft/client/renderer/entity/RenderLivingBase";
    private static final String MODEL = "net/minecraft/client/model/ModelRenderer";
    private static final String ADVANCED = "net/ilexiconn/llibrary/client/model/tools/AdvancedModelRenderer";
    private static final String TEXTURES = "net/minecraft/client/renderer/texture/TextureManager";
    private static final String ENTITY = "net/minecraft/entity/EntityLivingBase";
    private static final String GL_STATE = "net/minecraft/client/renderer/GlStateManager";
    private static final String HOOKS = "com/nvidia/rtxremix/forge/entity/EntityCaptureHooks";
    private static final String LIVING_DESCRIPTOR = "(L" + ENTITY + ";DDDFF)V";
    private final EntityCaptureTransformer transformer = new EntityCaptureTransformer();

    @Test
    public void bracketsPinnedLivingBodyAfterCancellationAndPreservesExistingFrames() throws Exception {
        byte[] original = classBytes(LIVING);
        ClassNode before = read(original);
        ClassNode after = read(transform(LIVING, original));
        MethodNode oldMethod = method(before, "doRender", LIVING_DESCRIPTOR);
        MethodNode newMethod = method(after, "doRender", LIVING_DESCRIPTOR);
        assertEquals(before.methods.size(), after.methods.size());
        assertEquals(oldMethod.maxLocals, newMethod.maxLocals);
        assertEquals(frameCount(oldMethod) + 2, frameCount(newMethod));
        assertEquals(oldMethod.tryCatchBlocks.size() + 1, newMethod.tryCatchBlocks.size());
        assertEquals(1, hookCount(newMethod, "beginLiving"));
        assertEquals(2, hookCount(newMethod, "endLiving"));

        int beginIndex = -1;
        int cancellationReturns = 0;
        int postEventIndex = -1;
        for (AbstractInsnNode instruction : newMethod.instructions.toArray()) {
            if (isHook(instruction, "beginLiving")) {
                beginIndex = newMethod.instructions.indexOf(instruction);
                assertEquals("(Ljava/lang/Object;)V", ((MethodInsnNode) instruction).desc);
                MethodInsnNode nextCall = nextCall(instruction);
                assertEquals(GL_STATE, nextCall.owner);
                assertEquals("pushMatrix", nextCall.name);
            } else if (beginIndex < 0 && instruction.getOpcode() == Opcodes.RETURN) {
                cancellationReturns++;
            }
            if (instruction instanceof MethodInsnNode && ((MethodInsnNode) instruction).owner
                    .equals("net/minecraftforge/client/event/RenderLivingEvent$Post")) {
                postEventIndex = newMethod.instructions.indexOf(instruction);
            }
        }
        assertEquals("Canceled Forge Pre must return without opening a scope", 1, cancellationReturns);
        assertTrue(beginIndex >= 0);
        TryCatchBlockNode cleanup = newMethod.tryCatchBlocks.get(newMethod.tryCatchBlocks.size() - 1);
        assertNull(cleanup.type);
        assertTrue(newMethod.instructions.indexOf(cleanup.start) > beginIndex);
        assertTrue(postEventIndex > beginIndex);
        assertTrue(newMethod.instructions.indexOf(cleanup.end) > postEventIndex);
        assertHook(nextCall(cleanup.handler), "endLiving", "()V");
        verify(after);
    }

    @Test
    public void observesEveryPinnedModelDisplayListAtItsExistingMatrix() throws Exception {
        byte[] original = classBytes(MODEL);
        ClassNode before = read(original);
        ClassNode after = read(transform(MODEL, original));
        int captures = 0;
        for (int i = 0; i < before.methods.size(); i++) {
            MethodNode oldMethod = before.methods.get(i);
            MethodNode newMethod = after.methods.get(i);
            assertEquals(frameCount(oldMethod), frameCount(newMethod));
            int count = hookCount(newMethod, "capturePart");
            if (newMethod.name.equals("render")) { assertEquals(3, count); }
            else if (newMethod.name.equals("renderWithRotation")) { assertEquals(1, count); }
            else { assertEquals(0, count); }
            for (AbstractInsnNode instruction : newMethod.instructions.toArray()) {
                if (!isHook(instruction, "capturePart")) { continue; }
                assertHook(instruction, "capturePart", "(Ljava/lang/Object;F)V");
                MethodInsnNode draw = (MethodInsnNode) instruction.getNext();
                assertEquals(GL_STATE, draw.owner);
                assertEquals("callList", draw.name);
                assertEquals("(I)V", draw.desc);
            }
            captures += count;
        }
        assertEquals(4, captures);
        verify(after);
    }

    @Test
    public void recordsOnlySuccessfulPinnedTextureBindings() throws Exception {
        byte[] original = classBytes(TEXTURES);
        ClassNode before = read(original);
        ClassNode after = read(transform(TEXTURES, original));
        int hooks = 0;
        for (int i = 0; i < before.methods.size(); i++) {
            MethodNode oldMethod = before.methods.get(i);
            MethodNode newMethod = after.methods.get(i);
            assertEquals(frameCount(oldMethod), frameCount(newMethod));
            for (AbstractInsnNode instruction : newMethod.instructions.toArray()) {
                if (!isHook(instruction, "boundTexture")) { continue; }
                assertEquals("bindTexture", newMethod.name);
                assertHook(instruction, "boundTexture", "(Ljava/lang/Object;)V");
                assertEquals(Opcodes.RETURN, instruction.getNext().getOpcode());
                hooks++;
            }
        }
        assertEquals(1, hooks);
        verify(after);
    }

    @Test
    public void optionalAdvancedModelAndSrgNamesUseTheSameObservationContract() throws Exception {
        byte[] original = partFixture(ADVANCED, "func_78785_a", "func_179148_o");
        ClassNode after = read(transform(ADVANCED, original));
        assertEquals(1, hookCount(method(after, "func_78785_a", "(F)V"), "capturePart"));
        verify(after);

        byte[] vanilla = partFixture(MODEL, "func_78791_b", "func_179148_o");
        assertEquals(1, hookCount(method(read(transform(MODEL, vanilla)),
                "func_78791_b", "(F)V"), "capturePart"));
    }

    @Test
    public void generatedFinallyPassesJvmVerificationAndRunsOnceOnReturnOrThrow() throws Exception {
        for (String methodName : new String[] { "doRender", "func_76986_a" }) {
            FixtureLoader loader = new FixtureLoader();
            loader.add(ENTITY, emptyClass(ENTITY));
            loader.add(GL_STATE, glFixture());
            loader.add(HOOKS, hooksFixture());
            loader.add(LIVING, transform(LIVING, livingFixture(methodName)));
            Class<?> living = loader.loadClass(LIVING.replace('/', '.'));
            Class<?> entity = loader.loadClass(ENTITY.replace('/', '.'));
            Class<?> hooks = loader.loadClass(HOOKS.replace('/', '.'));
            Object renderer = living.newInstance();
            Method render = living.getMethod(methodName, entity, double.class, double.class,
                    double.class, float.class, float.class);

            for (int mode = 0; mode < 4; mode++) {
                living.getField("mode").setInt(null, mode);
                hooks.getField("begins").setInt(null, 0);
                hooks.getField("ends").setInt(null, 0);
                try {
                    render.invoke(renderer, entity.newInstance(), 2.0, 3.0, 4.0, 5.0f, 0.5f);
                    assertTrue("Exceptional render must rethrow", mode < 2);
                } catch (InvocationTargetException error) {
                    assertEquals(mode == 2 ? IllegalStateException.class : AssertionError.class,
                            error.getCause().getClass());
                }
                assertEquals(mode == 1 ? 0 : 1, hooks.getField("begins").getInt(null));
                assertEquals(mode == 1 ? 0 : 1, hooks.getField("ends").getInt(null));
            }
        }
    }

    @Test
    public void doesNotTransformTwiceOrTouchUnrelatedClasses() throws IOException {
        byte[] original = classBytes(MODEL);
        byte[] transformed = transform(MODEL, original);
        assertSame(transformed, transform(MODEL, transformed));
        assertSame(original, transformer.transform(MODEL, "other.Class", original));
        assertSame(original, transformer.transform(MODEL, null, original));
        assertNull(transform(MODEL, null));
    }

    @Test
    public void registersAfterForgeRuntimeNameRemapping() {
        assertEquals(1001, RemixCorePlugin.class.getAnnotation(IFMLLoadingPlugin.SortingIndex.class).value());
        assertArrayEquals(new String[] {
                "com.nvidia.rtxremix.forge.core.DisplayLifecycleTransformer",
                "com.nvidia.rtxremix.forge.core.EntityCaptureTransformer"
        }, new RemixCorePlugin().getASMTransformerClass());
    }

    private byte[] transform(String type, byte[] original) {
        return transformer.transform("obfuscated", type.replace('/', '.'), original);
    }

    private static byte[] classBytes(String type) throws IOException {
        try (InputStream input = EntityCaptureTransformerTest.class.getClassLoader()
                .getResourceAsStream(type + ".class")) {
            assertNotNull("Pinned mapped Forge dependency must be on the test classpath", input);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) { output.write(buffer, 0, count); }
            return output.toByteArray();
        }
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM5);
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode type, String name, String descriptor) {
        for (MethodNode method : type.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) { return method; }
        }
        throw new AssertionError("Missing " + name + descriptor);
    }

    private static int frameCount(MethodNode method) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof FrameNode) { count++; }
        }
        return count;
    }

    private static int hookCount(MethodNode method, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (isHook(instruction, name)) { count++; }
        }
        return count;
    }

    private static boolean isHook(AbstractInsnNode instruction, String name) {
        if (!(instruction instanceof MethodInsnNode)) { return false; }
        MethodInsnNode call = (MethodInsnNode) instruction;
        return HOOKS.equals(call.owner) && name.equals(call.name);
    }

    private static void assertHook(AbstractInsnNode instruction, String name, String descriptor) {
        assertTrue(isHook(instruction, name));
        assertEquals(Opcodes.INVOKESTATIC, instruction.getOpcode());
        assertEquals(descriptor, ((MethodInsnNode) instruction).desc);
    }

    private static MethodInsnNode nextCall(AbstractInsnNode instruction) {
        for (AbstractInsnNode next = instruction.getNext(); next != null; next = next.getNext()) {
            if (next instanceof MethodInsnNode) { return (MethodInsnNode) next; }
        }
        throw new AssertionError("No following invocation");
    }

    private static void verify(ClassNode type) throws Exception {
        for (MethodNode method : type.methods) {
            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0) {
                new Analyzer<>(new BasicVerifier()).analyze(type.name, method);
            }
        }
    }

    private static ClassWriter fixture(String type) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, type, null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        return writer;
    }

    private static byte[] emptyClass(String type) {
        ClassWriter writer = fixture(type);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] partFixture(String type, String methodName, String callName) {
        ClassWriter writer = fixture(type);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, methodName, "(F)V", null, null);
        method.visitCode();
        method.visitInsn(Opcodes.ICONST_1);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, GL_STATE, callName, "(I)V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] livingFixture(String name) {
        ClassWriter writer = fixture(LIVING);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "mode", "I", null, null).visitEnd();
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, name, LIVING_DESCRIPTOR, null, null);
        method.visitCode();
        Label scope = new Label();
        method.visitFieldInsn(Opcodes.GETSTATIC, LIVING, "mode", "I");
        method.visitInsn(Opcodes.ICONST_1);
        method.visitJumpInsn(Opcodes.IF_ICMPNE, scope);
        method.visitInsn(Opcodes.RETURN);
        method.visitLabel(scope);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, GL_STATE, "pushMatrix", "()V", false);
        for (int mode = 2; mode <= 3; mode++) {
            Label next = new Label();
            method.visitFieldInsn(Opcodes.GETSTATIC, LIVING, "mode", "I");
            method.visitInsn(mode == 2 ? Opcodes.ICONST_2 : Opcodes.ICONST_3);
            method.visitJumpInsn(Opcodes.IF_ICMPNE, next);
            String exception = mode == 2 ? "java/lang/IllegalStateException" : "java/lang/AssertionError";
            method.visitTypeInsn(Opcodes.NEW, exception);
            method.visitInsn(Opcodes.DUP);
            method.visitMethodInsn(Opcodes.INVOKESPECIAL, exception, "<init>", "()V", false);
            method.visitInsn(Opcodes.ATHROW);
            method.visitLabel(next);
        }
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] glFixture() {
        ClassWriter writer = fixture(GL_STATE);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "pushMatrix", "()V", null, null);
        method.visitCode();
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] hooksFixture() {
        ClassWriter writer = fixture(HOOKS);
        String[] names = { "beginLiving", "endLiving" };
        String[] descriptors = { "(Ljava/lang/Object;)V", "()V" };
        String[] counters = { "begins", "ends" };
        for (int i = 0; i < names.length; i++) {
            writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, counters[i], "I", null, null).visitEnd();
            MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    names[i], descriptors[i], null, null);
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

        @Override
        protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!definitions.containsKey(name)) { return super.loadClass(name, resolve); }
            Class<?> type = findLoadedClass(name);
            if (type == null) {
                byte[] bytes = definitions.get(name);
                type = defineClass(name, bytes, 0, bytes.length);
            }
            if (resolve) { resolveClass(type); }
            return type;
        }
    }
}
