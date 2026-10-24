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
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

/** Captures vanilla and Forge replacement hands once, then submits before screen-space overlays. */
public final class FirstPersonCaptureTransformer implements IClassTransformer {
    private static final String TARGET = "net.minecraft.client.renderer.EntityRenderer";
    private static final String HOOKS = "com/nvidia/rtxremix/forge/entity/FirstPersonCapture";

    @Override public byte[] transform(String name, String transformedName, byte[] bytes) {
        if (bytes == null || !TARGET.equals(transformedName)) return bytes;
        ClassReader reader = new ClassReader(bytes);
        ClassNode type = new ClassNode(Opcodes.ASM5);
        reader.accept(type, 0);
        boolean changed = false;
        for (MethodNode method : type.methods) {
            if ("(FI)V".equals(method.desc) && named(method.name, "renderHand", "func_78476_b")) changed |= hand(method);
            else if ("(IFJ)V".equals(method.desc) && named(method.name, "renderWorldPass", "func_175068_a")) changed |= tail(method);
        }
        if (!changed) return bytes;
        // The appended exception handler reads no locals; retaining game frames avoids
        // resolving Minecraft hierarchies recursively inside LaunchWrapper.
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        type.accept(writer);
        return writer.toByteArray();
    }

    private static boolean hand(MethodNode method) {
        if (hasHook(method, "beginHand")) return false;
        MethodInsnNode forge = null, pop = null, overlays = null;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if ("net/minecraftforge/client/ForgeHooksClient".equals(call.owner) && "renderFirstPersonHand".equals(call.name)
                    && "(Lnet/minecraft/client/renderer/RenderGlobal;FI)Z".equals(call.desc)) {
                if (forge != null) throw new IllegalStateException("First-person capture requires one Forge hand event");
                forge = call;
            } else if (forge != null && pop == null && "net/minecraft/client/renderer/GlStateManager".equals(call.owner)
                    && named(call.name, "popMatrix", "func_179121_F") && "()V".equals(call.desc)) pop = call;
            else if ("net/minecraft/client/renderer/ItemRenderer".equals(call.owner)
                    && named(call.name, "renderOverlays", "func_78447_b") && "(F)V".equals(call.desc)) overlays = call;
        }
        if (forge == null || pop == null || overlays == null) throw new IllegalStateException("Unsupported EntityRenderer hand layout");
        method.instructions.insertBefore(forge, hook("beginHand"));
        method.instructions.insertBefore(pop, hook("endHand"));
        method.instructions.insertBefore(overlays, hook("submitFrame"));
        LabelNode start = new LabelNode(), end = new LabelNode(), handler = new LabelNode();
        method.instructions.insert(start);
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() == Opcodes.RETURN) method.instructions.insertBefore(instruction, hook("endHand"));
        }
        InsnList cleanup = new InsnList();
        cleanup.add(end); cleanup.add(handler);
        cleanup.add(new FrameNode(Opcodes.F_FULL, 0, new Object[0], 1, new Object[] {"java/lang/Throwable"}));
        cleanup.add(hook("endHand")); cleanup.add(new InsnNode(Opcodes.ATHROW));
        method.instructions.add(cleanup);
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));
        return true;
    }

    private static boolean tail(MethodNode method) {
        if (hasHook(method, "submitFrame")) return false;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() == Opcodes.RETURN) method.instructions.insertBefore(instruction, hook("submitFrame"));
        }
        return true;
    }
    private static boolean hasHook(MethodNode method, String name) {
        for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction instanceof MethodInsnNode) {
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (HOOKS.equals(call.owner) && name.equals(call.name)) return true;
        }
        return false;
    }
    private static boolean named(String actual, String mapped, String srg) { return mapped.equals(actual) || srg.equals(actual); }
    private static MethodInsnNode hook(String name) { return new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, name, "()V", false); }
}
