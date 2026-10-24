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
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Adapts the MIT-licensed 1.12 ModelRenderer's lighting boundary, without replacing its renderer. */
public final class BetterFoliageCaptureTransformer implements IClassTransformer {
    private static final String MODEL = "mods.octarinecore.client.render.ModelRenderer";
    private static final String VERTEX = "mods/octarinecore/client/render/RenderVertex";
    private static final String HOOKS = "com/nvidia/rtxremix/forge/terrain/BetterFoliageCaptureHooks";
    private static final String RENDER = "(Lnet/minecraft/client/renderer/BufferBuilder;Lmods/octarinecore/client/render/Model;"
            + "Lmods/octarinecore/common/Rotation;Lmods/octarinecore/common/Double3;ZLkotlin/jvm/functions/Function2;"
            + "Lkotlin/jvm/functions/Function3;Lkotlin/jvm/functions/Function6;)V";

    @Override public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null || !MODEL.equals(transformedName)) return basicClass;
        ClassReader reader = new ClassReader(basicClass);
        ClassNode type = new ClassNode(Opcodes.ASM5); reader.accept(type, 0);
        for (FieldNode field : type.fields) if ("rtxremixCaptureContract".equals(field.name)) return basicClass;
        boolean hasVertex = false;
        for (FieldNode field : type.fields) if ("temp".equals(field.name) && ("L" + VERTEX + ";").equals(field.desc)) hasVertex = true;
        MethodNode render = null;
        for (MethodNode method : type.methods) if ("render".equals(method.name) && RENDER.equals(method.desc)) render = method;
        // A changed optional-mod contract remains vanilla-compatible; the adapter reports it when Remix starts.
        if (!hasVertex || render == null || !compatible(render)) return basicClass;
        for (AbstractInsnNode instruction : render.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (ambient(call)) {
                InsnList patch = new InsnList(); patch.add(new VarInsnNode(Opcodes.ALOAD, 1));
                patch.add(hook("ambient", "(ZLjava/lang/Object;)Z")); render.instructions.insert(call, patch);
            } else if (reservation(call)) {
                InsnList patch = new InsnList(); patch.add(new InsnNode(Opcodes.DUP2));
                patch.add(hook("reserve", "(Ljava/lang/Object;I)V")); render.instructions.insertBefore(call, patch);
            } else if (sprite(call)) {
                InsnList patch = new InsnList(); patch.add(new InsnNode(Opcodes.DUP));
                patch.add(new VarInsnNode(Opcodes.ALOAD, 1)); patch.add(hook("sprite", "(Ljava/lang/Object;Ljava/lang/Object;)V"));
                render.instructions.insertBefore(call, patch);
            } else if (shade(call)) {
                InsnList patch = new InsnList();
                for (String color : new String[] {"Red", "Green", "Blue"}) {
                    patch.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    patch.add(new FieldInsnNode(Opcodes.GETFIELD, type.name, "temp", "L" + VERTEX + ";"));
                    patch.add(new InsnNode(Opcodes.DUP));
                    patch.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, VERTEX, "get" + color, "()F", false));
                    patch.add(new VarInsnNode(Opcodes.ALOAD, 1)); patch.add(new VarInsnNode(Opcodes.ILOAD, 5));
                    patch.add(hook("lighting", "(FLjava/lang/Object;Z)F"));
                    patch.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, VERTEX, "set" + color, "(F)V", false));
                }
                render.instructions.insert(call, patch);
            }
        }
        type.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "rtxremixCaptureContract", "I", null, 1));
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS); type.accept(writer); return writer.toByteArray();
    }

    private static boolean compatible(MethodNode method) {
        int ao = 0, reserve = 0, sprite = 0, shade = 0, post = 0;
        boolean shaded = false;
        for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction instanceof MethodInsnNode) {
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (ambient(call)) ao++;
            if (reservation(call)) reserve++;
            if (sprite(call)) sprite++;
            if (shade(call)) { shade++; shaded = true; }
            if ("kotlin/jvm/functions/Function6".equals(call.owner) && "invoke".equals(call.name)) {
                if (!shaded) return false; post++;
            }
        }
        return ao == 1 && reserve == 1 && sprite == 1 && shade == 1 && post == 1;
    }
    private static boolean ambient(MethodInsnNode call) {
        return call.getOpcode() == Opcodes.INVOKESTATIC && "net/minecraft/client/Minecraft".equals(call.owner)
                && ("isAmbientOcclusionEnabled".equals(call.name) || "func_71379_u".equals(call.name)) && "()Z".equals(call.desc);
    }
    private static boolean reservation(MethodInsnNode call) {
        return call.getOpcode() == Opcodes.INVOKESTATIC && "mods/octarinecore/client/render/ModelRendererKt".equals(call.owner)
                && "ensureSpaceForQuads".equals(call.name) && "(Lnet/minecraft/client/renderer/BufferBuilder;I)V".equals(call.desc);
    }
    private static boolean sprite(MethodInsnNode call) {
        return call.getOpcode() == Opcodes.INVOKEVIRTUAL && "net/minecraft/client/renderer/texture/TextureAtlasSprite".equals(call.owner)
                && ("getMaxU".equals(call.name) || "func_94212_f".equals(call.name)) && "()F".equals(call.desc);
    }
    private static boolean shade(MethodInsnNode call) {
        return call.getOpcode() == Opcodes.INVOKEINTERFACE && "mods/octarinecore/client/render/Shader".equals(call.owner)
                && "shade".equals(call.name) && ("(Lmods/octarinecore/client/render/ShadingContext;L" + VERTEX + ";)V").equals(call.desc);
    }
    private static MethodInsnNode hook(String name, String descriptor) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, name, descriptor, false);
    }
}
