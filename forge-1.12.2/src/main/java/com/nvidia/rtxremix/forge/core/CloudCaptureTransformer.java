/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.core;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Observes completed cloud and custom-sky buffers inside world passes, preserving vanilla draws and exceptions. */
public final class CloudCaptureTransformer implements IClassTransformer {
    private static final String RENDER = "net.minecraft.client.renderer.RenderGlobal";
    private static final String UPLOADER = "net.minecraft.client.renderer.WorldVertexBufferUploader";
    private static final String TEXTURES = "net.minecraft.client.renderer.texture.TextureManager";
    private static final String FORGE = "net.minecraftforge.client.CloudRenderer";
    private static final String HOOKS = "com/nvidia/rtxremix/forge/cloud/CloudCaptureHooks";

    @Override public byte[] transform(String name, String transformedName, byte[] bytes) {
        if (bytes == null || !(RENDER.equals(transformedName)
                || UPLOADER.equals(transformedName) || TEXTURES.equals(transformedName) || FORGE.equals(transformedName))) return bytes;
        ClassReader reader = new ClassReader(bytes);
        ClassNode type = new ClassNode(Opcodes.ASM5);
        reader.accept(type, 0);
        boolean changed = false;
        if (FORGE.equals(transformedName)) changed = forge(type);
        for (MethodNode method : type.methods) {
            if (RENDER.equals(transformedName) && "(FIDDD)V".equals(method.desc)
                    && named(method.name, "renderClouds", "func_180447_b")) changed |= scope(method, false);
            else if (RENDER.equals(transformedName) && "(FI)V".equals(method.desc)
                    && named(method.name, "renderSky", "func_174976_a")) changed |= scope(method, true);
            else if (UPLOADER.equals(transformedName) && "(Lnet/minecraft/client/renderer/BufferBuilder;)V".equals(method.desc)
                    && named(method.name, "draw", "func_181679_a")) changed |= observe(method, "drawBuffer", true);
            else if (TEXTURES.equals(transformedName) && "(Lnet/minecraft/util/ResourceLocation;)V".equals(method.desc)
                    && named(method.name, "bindTexture", "func_110577_a")) changed |= observe(method, "boundTexture", false);
        }
        if (!changed) return bytes;
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        type.accept(writer);
        return writer.toByteArray();
    }

    private static boolean forge(ClassNode type) {
        boolean changed = false;
        for (MethodNode method : type.methods) {
            if ("vertices".equals(method.name) && "(Lnet/minecraft/client/renderer/BufferBuilder;)V".equals(method.desc)
                    && !hasHook(method, "forgeCloudBuilt")) {
                for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction.getOpcode() == Opcodes.RETURN) {
                    InsnList observer = new InsnList();
                    observer.add(new VarInsnNode(Opcodes.ALOAD, 0)); observer.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    observer.add(hook("forgeCloudBuilt", "(Ljava/lang/Object;Ljava/lang/Object;)V"));
                    method.instructions.insertBefore(instruction, observer);
                }
                changed = true;
            } else if ("dispose".equals(method.name) && "()V".equals(method.desc) && !hasHook(method, "forgeCloudDisposed")) {
                InsnList observer = new InsnList(); observer.add(new VarInsnNode(Opcodes.ALOAD, 0));
                observer.add(hook("forgeCloudDisposed", "(Ljava/lang/Object;)V")); method.instructions.insert(observer); changed = true;
            } else if ("render".equals(method.name) && "(IF)Z".equals(method.desc) && !hasHook(method, "drawForgeCloud")) {
                int sites = 0;
                for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) instruction;
                    if (!"(I)V".equals(call.desc)) continue;
                    boolean vbo = "net/minecraft/client/renderer/vertex/VertexBuffer".equals(call.owner)
                            && named(call.name, "drawArrays", "func_177358_a") && call.getOpcode() == Opcodes.INVOKEVIRTUAL;
                    boolean list = "net/minecraft/client/renderer/GlStateManager".equals(call.owner)
                            && named(call.name, "callList", "func_179148_o") && call.getOpcode() == Opcodes.INVOKESTATIC;
                    if (!vbo && !list) continue;
                    InsnList observer = new InsnList(); observer.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    observer.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    observer.add(new FieldInsnNode(Opcodes.GETFIELD, type.name, "COLOR_TEX", "Lnet/minecraft/client/renderer/texture/DynamicTexture;"));
                    observer.add(hook("drawForgeCloud", "(Ljava/lang/Object;Ljava/lang/Object;)V"));
                    method.instructions.insertBefore(instruction, observer); sites++;
                }
                if (sites != 4) throw new IllegalStateException("Expected Forge cloud depth/color VBO and display-list draws, found " + sites);
                changed = true;
            }
        }
        return changed;
    }

    private static boolean scope(MethodNode method, boolean sky) {
        String beginHook = sky ? "beginSky" : "beginClouds";
        if (hasHook(method, beginHook)) return false;
        InsnList begin = new InsnList();
        begin.add(new VarInsnNode(Opcodes.FLOAD, 1));
        if (!sky) {
            begin.add(new VarInsnNode(Opcodes.DLOAD, 3));
            begin.add(new VarInsnNode(Opcodes.DLOAD, 5));
            begin.add(new VarInsnNode(Opcodes.DLOAD, 7));
        }
        begin.add(hook(beginHook, sky ? "(F)V" : "(FDDD)V"));
        LabelNode start = new LabelNode(), end = new LabelNode(), handler = new LabelNode();
        begin.add(start);
        method.instructions.insert(begin);
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() == Opcodes.RETURN) method.instructions.set(instruction, new JumpInsnNode(Opcodes.GOTO, end));
        }
        method.instructions.add(end);
        method.instructions.add(new FrameNode(Opcodes.F_FULL, 0, new Object[0], 0, new Object[0]));
        method.instructions.add(hook("endPass", "()V"));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.instructions.add(handler);
        method.instructions.add(new FrameNode(Opcodes.F_FULL, 0, new Object[0], 1, new Object[] {"java/lang/Throwable"}));
        method.instructions.add(hook("endPass", "()V"));
        method.instructions.add(new InsnNode(Opcodes.ATHROW));
        // Preserve existing handlers before the unconditional scope cleanup.
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));
        return true;
    }
    private static boolean observe(MethodNode method, String name, boolean head) {
        if (hasHook(method, name)) return false;
        if (head) method.instructions.insert(observer(name));
        else for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() == Opcodes.RETURN) method.instructions.insertBefore(instruction, observer(name));
        }
        return true;
    }
    private static InsnList observer(String name) {
        InsnList list = new InsnList();
        list.add(new VarInsnNode(Opcodes.ALOAD, 1));
        list.add(hook(name, "(Ljava/lang/Object;)V"));
        return list;
    }
    private static boolean named(String name, String mapped, String srg) { return mapped.equals(name) || srg.equals(name); }
    private static boolean hasHook(MethodNode method, String name) {
        for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction instanceof MethodInsnNode) {
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (HOOKS.equals(call.owner) && name.equals(call.name)) return true;
        }
        return false;
    }
    private static MethodInsnNode hook(String name, String descriptor) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, name, descriptor, false);
    }
}
