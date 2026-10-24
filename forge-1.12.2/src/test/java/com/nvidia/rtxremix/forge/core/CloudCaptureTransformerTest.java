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

public class CloudCaptureTransformerTest {
    private static final String HOOKS = "com/nvidia/rtxremix/forge/cloud/CloudCaptureHooks";
    private static final String RENDER = "net/minecraft/client/renderer/RenderGlobal";
    private static final String UPLOADER = "net/minecraft/client/renderer/WorldVertexBufferUploader";
    private static final String TEXTURES = "net/minecraft/client/renderer/texture/TextureManager";
    private static final String FORGE = "net/minecraftforge/client/CloudRenderer";
    private final CloudCaptureTransformer transformer = new CloudCaptureTransformer();

    @Test public void scopesCloudsBeforeForgeCallbackAndPreservesAllVanillaDraws() throws Exception {
        ClassNode original = read(bytes(RENDER));
        byte[] patched = transform(RENDER, bytes(RENDER)); ClassNode changed = read(patched);
        for (String name : new String[] {"renderClouds", "renderSky"}) {
            String descriptor = name.equals("renderClouds") ? "(FIDDD)V" : "(FI)V";
            MethodNode before = method(original, name, descriptor), after = method(changed, name, descriptor);
            assertEquals(1, calls(after, HOOKS, name.equals("renderClouds") ? "beginClouds" : "beginSky"));
            assertEquals(2, calls(after, HOOKS, "endPass"));
            assertEquals(before.tryCatchBlocks.size() + 1, after.tryCatchBlocks.size());
            assertEquals(calls(before, "net/minecraft/client/renderer/Tessellator", "draw"),
                    calls(after, "net/minecraft/client/renderer/Tessellator", "draw"));
            assertEquals(calls(before, "net/minecraftforge/client/IRenderHandler", "render"),
                    calls(after, "net/minecraftforge/client/IRenderHandler", "render"));
        }
        assertEquals(0, calls(method(changed, "renderCloudsFancy", "(FIDDD)V"), HOOKS, "beginClouds"));
        verify(changed); assertArrayEquals(patched, transform(RENDER, patched));
    }

    @Test public void observesCompletedBuffersAlongsideParticleCaptureAndMatchesSrgNames() throws Exception {
        String[][] methods = {{RENDER, "renderClouds", "func_180447_b", "(FIDDD)V", "beginClouds"},
                {RENDER, "renderSky", "func_174976_a", "(FI)V", "beginSky"},
                {UPLOADER, "draw", "func_181679_a", "(Lnet/minecraft/client/renderer/BufferBuilder;)V", "drawBuffer"},
                {TEXTURES, "bindTexture", "func_110577_a", "(Lnet/minecraft/util/ResourceLocation;)V", "boundTexture"}};
        for (String[] target : methods) {
            ClassNode original = read(bytes(target[0])); method(original, target[1], target[3]).name = target[2];
            ClassWriter writer = new ClassWriter(0); original.accept(writer);
            ClassNode changed = read(transform(target[0], writer.toByteArray()));
            assertEquals(1, calls(method(changed, target[2], target[3]), HOOKS, target[4])); verify(changed);
        }
        byte[] effects = new EffectCaptureTransformer().transform("obfuscated", UPLOADER.replace('/', '.'), bytes(UPLOADER));
        MethodNode draw = method(read(transform(UPLOADER, effects)), "draw", "(Lnet/minecraft/client/renderer/BufferBuilder;)V");
        assertEquals(1, calls(draw, HOOKS, "drawBuffer"));
        assertEquals(1, calls(draw, "com/nvidia/rtxremix/forge/effect/EffectCaptureHooks", "drawBuffer"));
        boolean observed = false;
        for (AbstractInsnNode instruction : draw.instructions.toArray()) if (instruction instanceof MethodInsnNode) {
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (call.owner.equals(HOOKS)) observed = true;
            if (call.name.equals("reset")) assertTrue("Capture precedes buffer reset", observed);
        }
    }

    @Test public void realJvmClosesEarlyReturnAndThrowingCustomCloudHandler() throws Exception {
        FixtureLoader loader = new FixtureLoader(); loader.add(RENDER, transform(RENDER, renderFixture())); loader.add(HOOKS, hooksFixture());
        Class<?> render = loader.loadClass(RENDER.replace('/', '.')), hooks = loader.loadClass(HOOKS.replace('/', '.'));
        Object instance = render.newInstance();
        render.getMethod("renderClouds", float.class, int.class, double.class, double.class, double.class).invoke(instance, 0f, 2, 1d, 2d, 3d);
        assertEquals(1, hooks.getField("begins").getInt(null)); assertEquals(1, hooks.getField("ends").getInt(null));
        try {
            render.getMethod("renderClouds", float.class, int.class, double.class, double.class, double.class).invoke(instance, 1f, 2, 1d, 2d, 3d); fail();
        } catch (InvocationTargetException expected) { assertTrue(expected.getCause() instanceof IllegalArgumentException); }
        assertEquals(2, hooks.getField("begins").getInt(null)); assertEquals(2, hooks.getField("ends").getInt(null));
    }

    @Test public void forgeCompiledCloudsCaptureBuildDisposeAndBothActualDrawPaths() throws Exception {
        byte[] original = bytes(FORGE), patched = transform(FORGE, original);
        ClassNode node = read(patched);
        assertEquals(1, calls(method(node, "vertices", "(Lnet/minecraft/client/renderer/BufferBuilder;)V"), HOOKS, "forgeCloudBuilt"));
        assertEquals(1, calls(method(node, "dispose", "()V"), HOOKS, "forgeCloudDisposed"));
        MethodNode render = method(node, "render", "(IF)Z");
        assertEquals(4, calls(render, HOOKS, "drawForgeCloud"));
        assertEquals(2, calls(render, "net/minecraft/client/renderer/vertex/VertexBuffer", "drawArrays"));
        assertEquals(2, calls(render, "net/minecraft/client/renderer/GlStateManager", "callList"));
        verify(node); assertArrayEquals(patched, transform(FORGE, patched));
        ClassNode srg = read(original);
        for (AbstractInsnNode instruction : method(srg, "render", "(IF)Z").instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (call.name.equals("drawArrays")) call.name = "func_177358_a";
            else if (call.name.equals("callList")) call.name = "func_179148_o";
        }
        ClassWriter writer = new ClassWriter(0); srg.accept(writer);
        ClassNode patchedSrg = read(transform(FORGE, writer.toByteArray())); verify(patchedSrg);
        assertEquals(4, calls(method(patchedSrg, "render", "(IF)Z"), HOOKS, "drawForgeCloud"));
    }

    private byte[] transform(String name, byte[] bytes) { return transformer.transform("obfuscated", name.replace('/', '.'), bytes); }
    private static byte[] bytes(String name) throws Exception {
        try (InputStream input = CloudCaptureTransformerTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(name, input); ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count); return output.toByteArray();
        }
    }
    private static ClassNode read(byte[] bytes) { ClassNode node = new ClassNode(Opcodes.ASM5); new ClassReader(bytes).accept(node, 0); return node; }
    private static MethodNode method(ClassNode node, String name, String descriptor) {
        for (MethodNode method : node.methods) if (name.equals(method.name) && descriptor.equals(method.desc)) return method;
        throw new AssertionError("Missing " + name + descriptor);
    }
    private static int calls(MethodNode method, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction instanceof MethodInsnNode) {
            MethodInsnNode call = (MethodInsnNode) instruction; if (owner.equals(call.owner) && name.equals(call.name)) count++;
        }
        return count;
    }
    private static void verify(ClassNode node) throws Exception {
        for (MethodNode method : node.methods) if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0)
            new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
    }
    private static ClassWriter fixture(String name) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode(); constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN); constructor.visitMaxs(0, 0); constructor.visitEnd(); return writer;
    }
    private static byte[] renderFixture() {
        ClassWriter writer = fixture(RENDER);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "renderClouds", "(FIDDD)V", null, null);
        method.visitCode(); method.visitVarInsn(Opcodes.FLOAD, 1); method.visitInsn(Opcodes.FCONST_0); method.visitInsn(Opcodes.FCMPL);
        Label throwing = new Label(); method.visitJumpInsn(Opcodes.IFNE, throwing); method.visitInsn(Opcodes.RETURN);
        method.visitLabel(throwing); method.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalArgumentException"); method.visitInsn(Opcodes.DUP);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalArgumentException", "<init>", "()V", false);
        method.visitInsn(Opcodes.ATHROW); method.visitMaxs(0, 0); method.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }
    private static byte[] hooksFixture() {
        ClassWriter writer = fixture(HOOKS);
        String[] names = {"beginClouds", "endPass"}, desc = {"(FDDD)V", "()V"}, fields = {"begins", "ends"};
        for (int i = 0; i < names.length; i++) {
            writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, fields[i], "I", null, null).visitEnd();
            MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, names[i], desc[i], null, null);
            method.visitCode(); method.visitFieldInsn(Opcodes.GETSTATIC, HOOKS, fields[i], "I"); method.visitInsn(Opcodes.ICONST_1);
            method.visitInsn(Opcodes.IADD); method.visitFieldInsn(Opcodes.PUTSTATIC, HOOKS, fields[i], "I"); method.visitInsn(Opcodes.RETURN);
            method.visitMaxs(0, 0); method.visitEnd();
        }
        writer.visitEnd(); return writer.toByteArray();
    }
    private static final class FixtureLoader extends ClassLoader {
        final Map<String, byte[]> definitions = new HashMap<String, byte[]>();
        void add(String name, byte[] bytes) { definitions.put(name.replace('/', '.'), bytes); }
        @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!definitions.containsKey(name)) return super.loadClass(name, resolve);
            Class<?> result = findLoadedClass(name);
            if (result == null) { byte[] bytes = definitions.get(name); result = defineClass(name, bytes, 0, bytes.length); }
            if (resolve) resolveClass(result); return result;
        }
    }
}
