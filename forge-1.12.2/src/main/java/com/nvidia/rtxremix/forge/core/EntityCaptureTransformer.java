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

/** Observes living model draws without replacing their animation or OpenGL work. */
public final class EntityCaptureTransformer implements IClassTransformer {
    private static final String LIVING = "net.minecraft.client.renderer.entity.RenderLivingBase";
    private static final String MODEL = "net.minecraft.client.model.ModelRenderer";
    private static final String ADVANCED_MODEL = "net.ilexiconn.llibrary.client.model.tools.AdvancedModelRenderer";
    private static final String TEXTURES = "net.minecraft.client.renderer.texture.TextureManager";
    private static final String GL_STATE = "net/minecraft/client/renderer/GlStateManager";
    private static final String HOOKS = "com/nvidia/rtxremix/forge/entity/EntityCaptureHooks";
    private static final String LIVING_DESCRIPTOR = "(Lnet/minecraft/entity/EntityLivingBase;DDDFF)V";
    private static final String TEXTURE_DESCRIPTOR = "(Lnet/minecraft/util/ResourceLocation;)V";

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null || !(LIVING.equals(transformedName) || MODEL.equals(transformedName)
                || ADVANCED_MODEL.equals(transformedName) || TEXTURES.equals(transformedName))) {
            return basicClass;
        }
        ClassReader reader = new ClassReader(basicClass);
        ClassNode type = new ClassNode(Opcodes.ASM5);
        reader.accept(type, 0);
        boolean changed = false;
        for (MethodNode method : type.methods) {
            if (LIVING.equals(transformedName) && LIVING_DESCRIPTOR.equals(method.desc)
                    && named(method.name, "doRender", "func_76986_a")) {
                changed |= scopeLiving(method);
            } else if (TEXTURES.equals(transformedName) && TEXTURE_DESCRIPTOR.equals(method.desc)
                    && named(method.name, "bindTexture", "func_110577_a")) {
                changed |= observeTexture(method);
            } else if ((MODEL.equals(transformedName) || ADVANCED_MODEL.equals(transformedName))
                    && "(F)V".equals(method.desc)
                    && (named(method.name, "render", "func_78785_a")
                    || MODEL.equals(transformedName) && named(method.name, "renderWithRotation", "func_78791_b"))) {
                changed |= observePart(method);
            }
        }
        if (!changed) { return basicClass; }

        // Existing frames stay valid at the observation sites. The two new cleanup
        // targets have explicit frames and use no locals; resolving Minecraft's class
        // hierarchy here would recursively load classes inside LaunchWrapper.
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        type.accept(writer);
        return writer.toByteArray();
    }

    private static boolean scopeLiving(MethodNode method) {
        if (hasHook(method, "beginLiving")) { return false; }
        MethodInsnNode firstPush = null;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (glCall(instruction, "pushMatrix", "func_179094_E", "()V")) {
                firstPush = (MethodInsnNode) instruction;
                break;
            }
        }
        if (firstPush == null) {
            throw new IllegalStateException("Living entity capture requires the world model matrix scope");
        }

        InsnList begin = new InsnList();
        begin.add(new VarInsnNode(Opcodes.ALOAD, 1));
        begin.add(hook("beginLiving", "(Ljava/lang/Object;)V"));
        LabelNode start = new LabelNode();
        begin.add(start);
        method.instructions.insertBefore(firstPush, begin);

        LabelNode end = new LabelNode();
        boolean inScope = false;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction == start) { inScope = true; }
            if (inScope && instruction.getOpcode() == Opcodes.RETURN) {
                method.instructions.set(instruction, new JumpInsnNode(Opcodes.GOTO, end));
            }
        }
        method.instructions.add(end);
        method.instructions.add(new FrameNode(Opcodes.F_FULL, 0, new Object[0], 0, new Object[0]));
        method.instructions.add(hook("endLiving", "()V"));
        method.instructions.add(new InsnNode(Opcodes.RETURN));

        LabelNode handler = new LabelNode();
        method.instructions.add(handler);
        method.instructions.add(new FrameNode(Opcodes.F_FULL, 0, new Object[0], 1,
                new Object[] { "java/lang/Throwable" }));
        method.instructions.add(hook("endLiving", "()V"));
        method.instructions.add(new InsnNode(Opcodes.ATHROW));
        // Appending preserves the renderer's own exception handling before our finally.
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));
        return true;
    }

    private static boolean observePart(MethodNode method) {
        if (hasHook(method, "capturePart")) { return false; }
        boolean changed = false;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!glCall(instruction, "callList", "func_179148_o", "(I)V")) { continue; }
            InsnList capture = new InsnList();
            capture.add(new VarInsnNode(Opcodes.ALOAD, 0));
            capture.add(new VarInsnNode(Opcodes.FLOAD, 1));
            capture.add(hook("capturePart", "(Ljava/lang/Object;F)V"));
            method.instructions.insertBefore(instruction, capture);
            changed = true;
        }
        if (!changed) {
            throw new IllegalStateException("Living model capture requires a display-list draw in " + method.name);
        }
        return true;
    }

    private static boolean observeTexture(MethodNode method) {
        if (hasHook(method, "boundTexture")) { return false; }
        boolean changed = false;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() != Opcodes.RETURN) { continue; }
            InsnList capture = new InsnList();
            capture.add(new VarInsnNode(Opcodes.ALOAD, 1));
            capture.add(hook("boundTexture", "(Ljava/lang/Object;)V"));
            method.instructions.insertBefore(instruction, capture);
            changed = true;
        }
        return changed;
    }

    private static boolean hasHook(MethodNode method, String name) {
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (HOOKS.equals(call.owner) && name.equals(call.name)) { return true; }
            }
        }
        return false;
    }

    private static boolean glCall(AbstractInsnNode instruction, String mapped, String srg, String descriptor) {
        if (!(instruction instanceof MethodInsnNode)) { return false; }
        MethodInsnNode call = (MethodInsnNode) instruction;
        return call.getOpcode() == Opcodes.INVOKESTATIC && GL_STATE.equals(call.owner)
                && named(call.name, mapped, srg) && descriptor.equals(call.desc);
    }

    private static boolean named(String name, String mapped, String srg) {
        return mapped.equals(name) || srg.equals(name);
    }

    private static MethodInsnNode hook(String name, String descriptor) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, name, descriptor, false);
    }
}
