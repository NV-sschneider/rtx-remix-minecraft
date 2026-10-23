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

public class EffectCaptureTransformerTest {
    private static final String HOOKS = "com/nvidia/rtxremix/forge/effect/EffectCaptureHooks";
    private static final String PARTICLES = "net/minecraft/client/particle/ParticleManager";
    private static final String WEATHER = "net/minecraft/client/renderer/EntityRenderer";
    private static final String UPLOADER = "net/minecraft/client/renderer/WorldVertexBufferUploader";
    private static final String TEXTURES = "net/minecraft/client/renderer/texture/TextureManager";
    private static final String PARTICLE_DESC = "(Lnet/minecraft/entity/Entity;F)V";
    private static final String BUFFER_DESC = "(Lnet/minecraft/client/renderer/BufferBuilder;)V";
    private static final String TEXTURE_DESC = "(Lnet/minecraft/util/ResourceLocation;)V";
    private final EffectCaptureTransformer transformer = new EffectCaptureTransformer();

    @Test public void bracketsPinnedPassesWithoutRemovingVanillaDrawsOrCrashHandlers() throws Exception {
        ClassNode original = read(bytes(PARTICLES));
        byte[] patched = transform(PARTICLES, bytes(PARTICLES));
        ClassNode particles = read(patched);
        for (String name : new String[] {"renderParticles", "renderLitParticles"}) {
            MethodNode before = method(original, name, PARTICLE_DESC), after = method(particles, name, PARTICLE_DESC);
            assertEquals(1, calls(after, HOOKS, "beginParticles"));
            assertEquals(2, calls(after, HOOKS, "endPass"));
            assertEquals(before.tryCatchBlocks.size() + 1, after.tryCatchBlocks.size());
            assertEquals(calls(before, "net/minecraft/client/particle/Particle", "renderParticle"),
                    calls(after, "net/minecraft/client/particle/Particle", "renderParticle"));
            if (!before.tryCatchBlocks.isEmpty()) assertEquals(before.tryCatchBlocks.get(0).type, after.tryCatchBlocks.get(0).type);
        }
        verify(particles);
        assertArrayEquals(patched, transform(PARTICLES, patched));
        ClassNode weather = read(transform(WEATHER, bytes(WEATHER)));
        MethodNode rain = method(weather, "renderRainSnow", "(F)V");
        assertEquals(1, calls(rain, HOOKS, "beginWeather"));
        assertEquals(2, calls(rain, HOOKS, "endPass"));
        assertEquals(1, calls(rain, "net/minecraftforge/client/IRenderHandler", "render"));
        assertEquals(3, calls(rain, "net/minecraft/client/renderer/Tessellator", "draw"));
        verify(weather);
    }

    @Test public void observesUploaderBeforeBufferResetAndCoexistsWithEntityTextureHook() throws Exception {
        ClassNode uploader = read(transform(UPLOADER, bytes(UPLOADER)));
        MethodNode draw = method(uploader, "draw", BUFFER_DESC);
        assertEquals(1, calls(draw, HOOKS, "drawBuffer"));
        boolean hookSeen = false;
        for (AbstractInsnNode instruction : draw.instructions.toArray()) if (instruction instanceof MethodInsnNode) {
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (call.owner.equals(HOOKS)) hookSeen = true;
            else assertTrue("Observe before vanilla consumes and resets the buffer", hookSeen);
        }
        verify(uploader);
        byte[] textures = new EntityCaptureTransformer().transform("obfuscated", TEXTURES.replace('/', '.'), bytes(TEXTURES));
        textures = transform(TEXTURES, textures);
        MethodNode bind = method(read(textures), "bindTexture", TEXTURE_DESC);
        assertEquals(1, calls(bind, HOOKS, "boundTexture"));
        assertEquals(1, calls(bind, "com/nvidia/rtxremix/forge/entity/EntityCaptureHooks", "boundTexture"));
        assertArrayEquals(textures, transform(TEXTURES, textures));
        verify(read(textures));
    }

    @Test public void matchesAllPinnedSrgEntryPoints() throws Exception {
        String[][] targets = {
            {PARTICLES, "renderParticles", "func_78874_a", PARTICLE_DESC, "beginParticles"},
            {PARTICLES, "renderLitParticles", "func_78872_b", PARTICLE_DESC, "beginParticles"},
            {WEATHER, "renderRainSnow", "func_78474_d", "(F)V", "beginWeather"},
            {UPLOADER, "draw", "func_181679_a", BUFFER_DESC, "drawBuffer"},
            {TEXTURES, "bindTexture", "func_110577_a", TEXTURE_DESC, "boundTexture"}
        };
        for (String[] target : targets) {
            ClassNode original = read(bytes(target[0]));
            method(original, target[1], target[3]).name = target[2];
            ClassWriter writer = new ClassWriter(0); original.accept(writer);
            ClassNode transformed = read(transform(target[0], writer.toByteArray()));
            assertEquals(1, calls(method(transformed, target[2], target[3]), HOOKS, target[4]));
            verify(transformed);
        }
    }

    @Test public void realJvmClosesCustomWeatherEarlyReturnAndOriginalException() throws Exception {
        FixtureLoader loader = new FixtureLoader();
        loader.add(WEATHER, transform(WEATHER, weatherFixture())); loader.add(HOOKS, hooksFixture());
        Class<?> weather = loader.loadClass(WEATHER.replace('/', '.')), hooks = loader.loadClass(HOOKS.replace('/', '.'));
        Object instance = weather.newInstance();
        weather.getMethod("renderRainSnow", float.class).invoke(instance, 0f);
        assertEquals(1, hooks.getField("begins").getInt(null));
        assertEquals(1, hooks.getField("ends").getInt(null));
        try { weather.getMethod("renderRainSnow", float.class).invoke(instance, 1f); fail(); }
        catch (InvocationTargetException expected) { assertTrue(expected.getCause() instanceof IllegalArgumentException); }
        assertEquals(2, hooks.getField("begins").getInt(null));
        assertEquals(2, hooks.getField("ends").getInt(null));
    }

    private byte[] transform(String name, byte[] bytes) { return transformer.transform("obfuscated", name.replace('/', '.'), bytes); }
    private static byte[] bytes(String name) throws Exception {
        try (InputStream input = EffectCaptureTransformerTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(name, input);
            ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }
    private static ClassNode read(byte[] bytes) { ClassNode node = new ClassNode(Opcodes.ASM5); new ClassReader(bytes).accept(node, 0); return node; }
    private static MethodNode method(ClassNode node, String name, String descriptor) {
        for (MethodNode method : node.methods) if (name.equals(method.name) && descriptor.equals(method.desc)) return method;
        throw new AssertionError("Missing " + name + descriptor);
    }
    private static int calls(MethodNode method, String owner, String name) {
        int result = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction instanceof MethodInsnNode) {
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (owner.equals(call.owner) && name.equals(call.name)) result++;
        }
        return result;
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
    private static byte[] weatherFixture() {
        ClassWriter writer = fixture(WEATHER);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "renderRainSnow", "(F)V", null, null);
        method.visitCode(); method.visitVarInsn(Opcodes.FLOAD, 1); method.visitInsn(Opcodes.FCONST_0); method.visitInsn(Opcodes.FCMPL);
        Label throwing = new Label(); method.visitJumpInsn(Opcodes.IFNE, throwing); method.visitInsn(Opcodes.RETURN);
        method.visitLabel(throwing); method.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalArgumentException"); method.visitInsn(Opcodes.DUP);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalArgumentException", "<init>", "()V", false);
        method.visitInsn(Opcodes.ATHROW); method.visitMaxs(0, 0); method.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }
    private static byte[] hooksFixture() {
        ClassWriter writer = fixture(HOOKS);
        String[] names = {"beginWeather", "endPass"}, descriptors = {"(F)V", "()V"}, fields = {"begins", "ends"};
        for (int i = 0; i < names.length; i++) {
            writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, fields[i], "I", null, null).visitEnd();
            MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, names[i], descriptors[i], null, null);
            method.visitCode(); method.visitFieldInsn(Opcodes.GETSTATIC, HOOKS, fields[i], "I"); method.visitInsn(Opcodes.ICONST_1);
            method.visitInsn(Opcodes.IADD); method.visitFieldInsn(Opcodes.PUTSTATIC, HOOKS, fields[i], "I");
            method.visitInsn(Opcodes.RETURN); method.visitMaxs(0, 0); method.visitEnd();
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
