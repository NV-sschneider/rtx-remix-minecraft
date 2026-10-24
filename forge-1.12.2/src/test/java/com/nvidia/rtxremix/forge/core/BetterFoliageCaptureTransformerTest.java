/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.core;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.jar.JarFile;
import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import static org.junit.Assert.*;

public class BetterFoliageCaptureTransformerTest {
    private static final String MODEL = "mods.octarinecore.client.render.ModelRenderer";
    private static final String RENDER = "(Lnet/minecraft/client/renderer/BufferBuilder;Lmods/octarinecore/client/render/Model;"
            + "Lmods/octarinecore/common/Rotation;Lmods/octarinecore/common/Double3;ZLkotlin/jvm/functions/Function2;"
            + "Lkotlin/jvm/functions/Function3;Lkotlin/jvm/functions/Function6;)V";
    private static final String HOOKS = "com/nvidia/rtxremix/forge/terrain/BetterFoliageCaptureHooks";

    @Test public void adaptsMcpAndSrgShadingBeforePostprocessWithoutReplacingGeometryCalls() throws Exception {
        for (boolean srg : new boolean[] {false, true}) assertContract(transform(synthetic(srg)));
    }

    @Test public void optionalModWithChangedContractRemainsUnmodifiedAndHasNoMarker() {
        ClassNode input = read(synthetic(false));
        for (MethodNode method : input.methods) for (AbstractInsnNode node : method.instructions.toArray())
            if (node instanceof MethodInsnNode && ((MethodInsnNode) node).name.equals("ensureSpaceForQuads"))
                ((MethodInsnNode) node).name = "unknownReservation";
        byte[] changed = write(input); assertSame(changed, transform(changed));
        assertFalse(marker(read(changed)));
    }

    /** Runs against a locally supplied installed artifact, without adding it as a build or runtime dependency. */
    @Test public void installedReleaseMatchesTheAuditedPublicHookAndShaderContracts() throws Exception {
        String path = System.getProperty("rtxremix.betterFoliageJar"); Assume.assumeNotNull(path);
        try (JarFile jar = new JarFile(path)) {
            ClassNode hooks = read(bytes(jar, "mods/betterfoliage/client/Hooks.class"));
            assertTrue(hasMethod(hooks, "renderWorldBlock", "(Lnet/minecraft/client/renderer/BlockRendererDispatcher;"
                    + "Lnet/minecraft/block/state/IBlockState;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/world/IBlockAccess;"
                    + "Lnet/minecraft/client/renderer/BufferBuilder;Lnet/minecraft/util/BlockRenderLayer;)Z"));
            assertTrue(hasMethod(hooks, "canRenderBlockInLayer", "(Lnet/minecraft/block/Block;Lnet/minecraft/block/state/IBlockState;"
                    + "Lnet/minecraft/util/BlockRenderLayer;)Z"));
            assertContract(transform(bytes(jar, MODEL.replace('.', '/') + ".class")));
        }
    }

    private static void assertContract(byte[] bytes) throws Exception {
        ClassNode type = read(bytes); assertTrue(marker(type)); assertSame(bytes, transform(bytes));
        MethodNode render = null;
        for (MethodNode method : type.methods) if ("render".equals(method.name) && RENDER.equals(method.desc)) render = method;
        assertNotNull(render); new Analyzer<>(new BasicVerifier()).analyze(type.name, render);
        int ambient = 0, reserve = 0, sprite = 0, lighting = 0, shadedAt = -1, postAt = -1, index = 0;
        for (AbstractInsnNode node : render.instructions.toArray()) {
            if (node instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) node;
                if (call.owner.equals("mods/octarinecore/client/render/Shader") && call.name.equals("shade")) shadedAt = index;
                if (call.owner.equals("kotlin/jvm/functions/Function6") && call.name.equals("invoke")) postAt = index;
                if (call.owner.equals(HOOKS)) {
                    if (call.name.equals("ambient")) ambient++;
                    if (call.name.equals("reserve")) reserve++;
                    if (call.name.equals("sprite")) sprite++;
                    if (call.name.equals("lighting")) { assertTrue(shadedAt >= 0); assertEquals(-1, postAt); lighting++; }
                }
            }
            index++;
        }
        assertEquals(1, ambient); assertEquals(1, reserve); assertEquals(1, sprite); assertEquals(3, lighting);
        assertTrue(postAt > shadedAt);
    }

    private static byte[] synthetic(boolean srg) {
        ClassWriter type = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        type.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, MODEL.replace('.', '/'), null, "mods/octarinecore/client/render/ShadingContext", null);
        type.visitField(Opcodes.ACC_PRIVATE, "temp", "Lmods/octarinecore/client/render/RenderVertex;", null, null).visitEnd();
        MethodVisitor m = type.visitMethod(Opcodes.ACC_PUBLIC, "render", RENDER, null, null); m.visitCode();
        m.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/client/Minecraft", srg ? "func_71379_u" : "isAmbientOcclusionEnabled", "()Z", false);
        m.visitInsn(Opcodes.POP); m.visitVarInsn(Opcodes.ALOAD, 1); m.visitInsn(Opcodes.ICONST_1);
        m.visitMethodInsn(Opcodes.INVOKESTATIC, "mods/octarinecore/client/render/ModelRendererKt", "ensureSpaceForQuads",
                "(Lnet/minecraft/client/renderer/BufferBuilder;I)V", false);
        m.visitInsn(Opcodes.ACONST_NULL); m.visitVarInsn(Opcodes.ALOAD, 0); m.visitInsn(Opcodes.ACONST_NULL);
        m.visitMethodInsn(Opcodes.INVOKEINTERFACE, "mods/octarinecore/client/render/Shader", "shade",
                "(Lmods/octarinecore/client/render/ShadingContext;Lmods/octarinecore/client/render/RenderVertex;)V", true);
        m.visitVarInsn(Opcodes.ALOAD, 8); for (int i = 0; i < 6; i++) m.visitInsn(Opcodes.ACONST_NULL);
        m.visitMethodInsn(Opcodes.INVOKEINTERFACE, "kotlin/jvm/functions/Function6", "invoke",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
        m.visitInsn(Opcodes.POP); m.visitInsn(Opcodes.ACONST_NULL);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/client/renderer/texture/TextureAtlasSprite", srg ? "func_94212_f" : "getMaxU", "()F", false);
        m.visitInsn(Opcodes.POP); m.visitInsn(Opcodes.RETURN); m.visitMaxs(0, 0); m.visitEnd(); type.visitEnd(); return type.toByteArray();
    }
    private static boolean marker(ClassNode type) {
        for (FieldNode field : type.fields) if (field.name.equals("rtxremixCaptureContract") && Integer.valueOf(1).equals(field.value)) return true;
        return false;
    }
    private static boolean hasMethod(ClassNode type, String name, String descriptor) {
        for (MethodNode method : type.methods) if (method.name.equals(name) && method.desc.equals(descriptor)) return true;
        return false;
    }
    private static byte[] transform(byte[] data) { return new BetterFoliageCaptureTransformer().transform(MODEL, MODEL, data); }
    private static ClassNode read(byte[] data) { ClassNode type = new ClassNode(Opcodes.ASM5); new ClassReader(data).accept(type, 0); return type; }
    private static byte[] write(ClassNode type) { ClassWriter writer = new ClassWriter(0); type.accept(writer); return writer.toByteArray(); }
    private static byte[] bytes(JarFile jar, String name) throws Exception {
        assertNotNull(jar.getJarEntry(name));
        try (InputStream stream = jar.getInputStream(jar.getJarEntry(name))) {
            ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int read;
            while ((read = stream.read(buffer)) >= 0) output.write(buffer, 0, read); return output.toByteArray();
        }
    }
}
