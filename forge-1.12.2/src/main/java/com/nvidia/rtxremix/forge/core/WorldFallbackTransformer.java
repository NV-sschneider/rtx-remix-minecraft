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
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Observes the completed world pass independently of first-person capture and before any HUD/screen work. */
public final class WorldFallbackTransformer implements IClassTransformer {
    private static final String TARGET = "net.minecraft.client.renderer.EntityRenderer";
    private static final String HOOKS = "com/nvidia/rtxremix/forge/frame/WorldFallbackHooks";
    @Override public byte[] transform(String name, String transformedName, byte[] bytes) {
        if (bytes == null || !TARGET.equals(transformedName)) return bytes;
        ClassReader reader = new ClassReader(bytes); ClassNode type = new ClassNode(Opcodes.ASM5); reader.accept(type, 0);
        int methods = 0, sites = 0, hooks = 0;
        MethodNode update = null;
        MethodInsnNode world = null, existing = null;
        for (MethodNode method : type.methods) if (named(method.name, "updateCameraAndRender", "func_181560_a") && "(FJ)V".equals(method.desc)) {
            methods++; update = method;
            for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (call.getOpcode() == Opcodes.INVOKESTATIC && HOOKS.equals(call.owner) && "beforeHud".equals(call.name) && "()V".equals(call.desc)) {
                    hooks++; existing = call;
                }
                if (call.getOpcode() == Opcodes.INVOKEVIRTUAL && type.name.equals(call.owner)
                        && named(call.name, "renderWorld", "func_78471_a") && "(FJ)V".equals(call.desc)) {
                    world = call; sites++;
                }
            }
        }
        if (methods != 1 || sites != 1 || hooks > 1) throw new IllegalStateException("Unsupported pre-HUD world completion layout: " + methods + "/" + sites + "/" + hooks);
        if (existing != null) {
            AbstractInsnNode previous = existing.getPrevious();
            while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
            if (previous != world) throw new IllegalStateException("Pre-HUD fallback hook moved away from world completion");
            return bytes;
        }
        update.instructions.insert(world, new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "beforeHud", "()V", false));
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS); type.accept(writer); return writer.toByteArray();
    }
    private static boolean named(String name, String named, String srg) { return named.equals(name) || srg.equals(name); }
}
