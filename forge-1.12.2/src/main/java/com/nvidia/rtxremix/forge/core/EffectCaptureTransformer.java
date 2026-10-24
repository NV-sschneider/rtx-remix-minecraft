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
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Observes completed effect buffers inside world passes, preserving vanilla draws and exceptions. */
public final class EffectCaptureTransformer implements IClassTransformer {
    private static final String PARTICLES = "net.minecraft.client.particle.ParticleManager";
    private static final String WEATHER = "net.minecraft.client.renderer.EntityRenderer";
    private static final String DAMAGE = "net.minecraft.client.renderer.RenderGlobal";
    private static final String UPLOADER = "net.minecraft.client.renderer.WorldVertexBufferUploader";
    private static final String TEXTURES = "net.minecraft.client.renderer.texture.TextureManager";
    private static final String HOOKS = "com/nvidia/rtxremix/forge/effect/EffectCaptureHooks";

    @Override public byte[] transform(String name, String transformedName, byte[] bytes) {
        if (bytes == null || !(PARTICLES.equals(transformedName) || WEATHER.equals(transformedName)
                || DAMAGE.equals(transformedName) || UPLOADER.equals(transformedName) || TEXTURES.equals(transformedName))) return bytes;
        ClassReader reader = new ClassReader(bytes);
        ClassNode type = new ClassNode(Opcodes.ASM5);
        reader.accept(type, 0);
        boolean changed = false;
        for (MethodNode method : type.methods) {
            if (PARTICLES.equals(transformedName) && "(Lnet/minecraft/entity/Entity;F)V".equals(method.desc)
                    && (named(method.name, "renderParticles", "func_78874_a")
                    || named(method.name, "renderLitParticles", "func_78872_b"))) changed |= scope(method, false);
            else if (WEATHER.equals(transformedName) && "(F)V".equals(method.desc)
                    && named(method.name, "renderRainSnow", "func_78474_d")) changed |= scope(method, true);
            else if (DAMAGE.equals(transformedName)
                    && "(Lnet/minecraft/client/renderer/Tessellator;Lnet/minecraft/client/renderer/BufferBuilder;Lnet/minecraft/entity/Entity;F)V".equals(method.desc)
                    && named(method.name, "drawBlockDamageTexture", "func_174981_a"))
                changed |= scope(method, "beginDamage", 3, 4);
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

    private static boolean scope(MethodNode method, boolean weather) {
        return scope(method, weather ? "beginWeather" : "beginParticles", weather ? -1 : 1, weather ? 1 : 2);
    }
    private static boolean scope(MethodNode method, String beginHook, int entitySlot, int partialTicksSlot) {
        if (hasHook(method, beginHook)) return false;
        InsnList begin = new InsnList();
        if (entitySlot >= 0) begin.add(new VarInsnNode(Opcodes.ALOAD, entitySlot));
        begin.add(new VarInsnNode(Opcodes.FLOAD, partialTicksSlot));
        begin.add(hook(beginHook, entitySlot < 0 ? "(F)V" : "(Ljava/lang/Object;F)V"));
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
        // Vanilla's particle crash-report handlers retain priority over this cleanup.
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
