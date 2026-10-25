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
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Gates only pinned Minecraft/Forge input consumers; native Windows input remains available to Remix. */
public final class OverlayInputTransformer implements IClassTransformer {
    private static final String MINECRAFT = "net.minecraft.client.Minecraft";
    private static final String SCREEN = "net.minecraft.client.gui.GuiScreen";
    private static final String MOUSE = "net.minecraft.util.MouseHelper";
    private static final String FORGE = "net.minecraftforge.client.ForgeHooksClient";
    private static final String HOOKS = "com/nvidia/rtxremix/forge/overlay/OverlayHooks";
    private static final String ACCESS = "com/nvidia/rtxremix/forge/overlay/Overlay";

    @Override public byte[] transform(String name, String transformedName, byte[] bytes) {
        if (bytes == null || !(MINECRAFT.equals(transformedName) || SCREEN.equals(transformedName)
                || MOUSE.equals(transformedName) || FORGE.equals(transformedName))) return bytes;
        ClassReader reader = new ClassReader(bytes); ClassNode type = new ClassNode(Opcodes.ASM5); reader.accept(type, 0);
        boolean changed = false;
        for (MethodNode method : type.methods) {
            if (MINECRAFT.equals(transformedName) && "()V".equals(method.desc)) {
                if (named(method.name, "runGameLoop", "func_71411_J")) {
                    changed |= head(method, "beginFrame"); changed |= beforePresent(method);
                }
                if (named(method.name, "updateDisplay", "func_175601_h")) changed |= display(method);
                if (named(method.name, "toggleFullscreen", "func_71352_k")
                        || named(method.name, "updateDisplayMode", "func_110441_Q")) changed |= fullscreen(method);
                if (named(method.name, "runTickKeyboard", "func_184118_az")) changed |= events(method, "Keyboard", "nextKeyboardEvent");
                if (named(method.name, "runTickMouse", "func_184124_aB")) changed |= events(method, "Mouse", "nextMouseEvent");
                if (named(method.name, "processKeyBinds", "func_184117_aA") || named(method.name, "setIngameFocus", "func_71381_h")) changed |= guard(method, false);
            } else if (SCREEN.equals(transformedName) && "()V".equals(method.desc)) {
                if (named(method.name, "handleInput", "func_146269_k")) {
                    changed |= events(method, "Keyboard", "nextKeyboardEvent"); changed |= events(method, "Mouse", "nextMouseEvent");
                }
                if (named(method.name, "handleMouseInput", "func_146274_d") || named(method.name, "handleKeyboardInput", "func_146282_l")) changed |= guard(method, false);
            } else if (MOUSE.equals(transformedName) && "()V".equals(method.desc)) {
                if (named(method.name, "grabMouseCursor", "func_74372_a")) changed |= guard(method, false);
                if (named(method.name, "mouseXYChange", "func_74374_c")) changed |= guard(method, true);
            } else if (FORGE.equals(transformedName) && "drawScreen".equals(method.name)
                    && "(Lnet/minecraft/client/gui/GuiScreen;IIF)V".equals(method.desc) && !hasHook(method, "screenCoordinate")) {
                InsnList list = new InsnList();
                for (int local = 1; local <= 2; local++) {
                    list.add(new VarInsnNode(Opcodes.ILOAD, local)); list.add(hook("screenCoordinate", "(I)I"));
                    list.add(new VarInsnNode(Opcodes.ISTORE, local));
                }
                method.instructions.insert(list); changed = true;
            }
        }
        if (MINECRAFT.equals(transformedName)) changed |= minecraftAccess(type);
        if (SCREEN.equals(transformedName)) changed |= screenAccess(type);
        if (!changed) return bytes;
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS); type.accept(writer); return writer.toByteArray();
    }

    private static boolean beforePresent(MethodNode method) {
        if (hasHook(method, "beforePresent")) return false;
        int sites = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction instanceof MethodInsnNode) {
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (call.getOpcode() == Opcodes.INVOKEVIRTUAL && "net/minecraft/client/shader/Framebuffer".equals(call.owner)
                    && named(call.name, "framebufferRender", "func_147615_c") && "(II)V".equals(call.desc)) {
                method.instructions.insertBefore(instruction, hook("beforePresent", "()V")); sites++;
            }
        }
        if (sites != 1) throw new IllegalStateException("Expected one main framebuffer presentation, found " + sites);
        return true;
    }

    private static boolean display(MethodNode method) {
        String owner = "com/nvidia/rtxremix/forge/overlay/PresentationHooks";
        int sites = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction instanceof MethodInsnNode) {
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (owner.equals(call.owner) && "updateDisplay".equals(call.name)) return false;
            if (call.getOpcode() == Opcodes.INVOKESTATIC && "org/lwjgl/opengl/Display".equals(call.owner)
                    && "update".equals(call.name) && "()V".equals(call.desc)) {
                method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, owner, "updateDisplay", "()V", false)); sites++;
            }
        }
        if (sites != 1) throw new IllegalStateException("Expected one Display.update call, found " + sites);
        return true;
    }

    private static boolean fullscreen(MethodNode method) {
        String owner = "com/nvidia/rtxremix/forge/overlay/FullscreenPresentation";
        int modes = 0, toggles = 0, resize = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction instanceof MethodInsnNode) {
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (owner.equals(call.owner)) return false;
            if (call.getOpcode() != Opcodes.INVOKESTATIC || !"org/lwjgl/opengl/Display".equals(call.owner)) continue;
            if ("setDisplayMode".equals(call.name) && "(Lorg/lwjgl/opengl/DisplayMode;)V".equals(call.desc)) {
                method.instructions.insertBefore(call, new VarInsnNode(Opcodes.ALOAD, 0));
                method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, owner, call.name,
                        "(Lorg/lwjgl/opengl/DisplayMode;Lnet/minecraft/client/Minecraft;)V", false)); modes++;
            } else if ("(Z)V".equals(call.desc) && ("setFullscreen".equals(call.name) || "setResizable".equals(call.name))) {
                if ("setFullscreen".equals(call.name)) toggles++; else resize++;
                method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, owner, call.name, call.desc, false));
            }
        }
        boolean toggle = named(method.name, "toggleFullscreen", "func_71352_k");
        if (modes != 1 || toggles != (toggle ? 1 : 0) || resize != (toggle ? 2 : 0))
            throw new IllegalStateException("Unexpected pinned fullscreen display calls in " + method.name);
        return true;
    }

    private static boolean events(MethodNode method, String source, String target) {
        boolean changed = false;
        for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction instanceof MethodInsnNode) {
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (call.getOpcode() == Opcodes.INVOKESTATIC && ("org/lwjgl/input/" + source).equals(call.owner)
                    && "next".equals(call.name) && "()Z".equals(call.desc)) {
                method.instructions.set(call, hook(target, "()Z")); changed = true;
            }
        }
        return changed;
    }
    private static boolean head(MethodNode method, String name) {
        if (hasHook(method, name)) return false;
        method.instructions.insert(hook(name, "()V")); return true;
    }
    private static boolean guard(MethodNode method, boolean mouseDelta) {
        if (hasHook(method, "capturing")) return false;
        InsnList list = new InsnList(); LabelNode original = new LabelNode();
        list.add(hook("capturing", "()Z")); list.add(new JumpInsnNode(Opcodes.IFEQ, original));
        if (mouseDelta) { list.add(new VarInsnNode(Opcodes.ALOAD, 0)); list.add(hook("clearMouseDelta", "(Ljava/lang/Object;)V")); }
        list.add(new InsnNode(Opcodes.RETURN)); list.add(original); list.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        method.instructions.insert(list); return true;
    }

    private static boolean minecraftAccess(ClassNode type) {
        if (type.interfaces.contains(ACCESS + "MinecraftAccess")) return false;
        String crash = field(type, "debugCrashKeyPressTime", "field_83002_am", "J");
        String action = field(type, "actionKeyF3", "field_184129_aV", "Z");
        type.interfaces.add(ACCESS + "MinecraftAccess");
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "rtxremix$resetOverlayDebugState", "()V", null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0)); method.instructions.add(new LdcInsnNode(-1L));
        method.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, type.name, crash, "J"));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0)); method.instructions.add(new InsnNode(Opcodes.ICONST_0));
        method.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, type.name, action, "Z")); method.instructions.add(new InsnNode(Opcodes.RETURN));
        type.methods.add(method); return true;
    }

    private static boolean screenAccess(ClassNode type) {
        if (type.interfaces.contains(ACCESS + "ScreenAccess")) return false;
        String button = field(type, "eventButton", "field_146287_f", "I");
        String touch = field(type, "touchValue", "field_146298_h", "I");
        String last = field(type, "lastMouseEvent", "field_146288_g", "J");
        String selected = field(type, "selectedButton", "field_146290_a", "Lnet/minecraft/client/gui/GuiButton;");
        String release = method(type, "mouseReleased", "func_146286_b", "(III)V");
        type.interfaces.add(ACCESS + "ScreenAccess");
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "rtxremix$releaseOverlayMouse", "(II)V", null, null);
        InsnList list = method.instructions;
        list.add(new VarInsnNode(Opcodes.ALOAD, 0)); list.add(new FieldInsnNode(Opcodes.GETFIELD, type.name, button, "I"));
        list.add(new VarInsnNode(Opcodes.ISTORE, 3));
        setField(list, type.name, button, "I", Opcodes.ICONST_M1); setField(list, type.name, touch, "I", Opcodes.ICONST_0);
        setField(list, type.name, last, "J", Opcodes.LCONST_0);
        LabelNode start = new LabelNode(), end = new LabelNode(), cleanup = new LabelNode(), handler = new LabelNode();
        list.add(new VarInsnNode(Opcodes.ILOAD, 3)); list.add(new JumpInsnNode(Opcodes.IFLT, cleanup)); list.add(start);
        list.add(new VarInsnNode(Opcodes.ALOAD, 0)); list.add(new VarInsnNode(Opcodes.ILOAD, 1)); list.add(new VarInsnNode(Opcodes.ILOAD, 2));
        list.add(new VarInsnNode(Opcodes.ILOAD, 3)); list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, type.name, release, "(III)V", false));
        list.add(end); list.add(new JumpInsnNode(Opcodes.GOTO, cleanup));
        list.add(handler); list.add(new FrameNode(Opcodes.F_FULL, 4, new Object[] {type.name, Opcodes.INTEGER, Opcodes.INTEGER, Opcodes.INTEGER}, 1, new Object[] {"java/lang/Throwable"}));
        setField(list, type.name, selected, "Lnet/minecraft/client/gui/GuiButton;", Opcodes.ACONST_NULL); list.add(new InsnNode(Opcodes.ATHROW));
        list.add(cleanup); list.add(new FrameNode(Opcodes.F_FULL, 4, new Object[] {type.name, Opcodes.INTEGER, Opcodes.INTEGER, Opcodes.INTEGER}, 0, new Object[0]));
        setField(list, type.name, selected, "Lnet/minecraft/client/gui/GuiButton;", Opcodes.ACONST_NULL); list.add(new InsnNode(Opcodes.RETURN));
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null)); type.methods.add(method); return true;
    }

    private static void setField(InsnList list, String owner, String field, String descriptor, int value) {
        list.add(new VarInsnNode(Opcodes.ALOAD, 0)); list.add(new InsnNode(value)); list.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, field, descriptor));
    }
    private static String field(ClassNode type, String named, String srg, String descriptor) {
        for (FieldNode field : type.fields) if (named(field.name, named, srg) && descriptor.equals(field.desc)) return field.name;
        throw new IllegalStateException("Pinned overlay field missing: " + type.name + "." + named);
    }
    private static String method(ClassNode type, String named, String srg, String descriptor) {
        for (MethodNode method : type.methods) if (named(method.name, named, srg) && descriptor.equals(method.desc)) return method.name;
        throw new IllegalStateException("Pinned overlay method missing: " + type.name + "." + named);
    }
    private static boolean named(String value, String named, String srg) { return named.equals(value) || srg.equals(value); }
    private static boolean hasHook(MethodNode method, String name) {
        for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction instanceof MethodInsnNode) {
            MethodInsnNode call = (MethodInsnNode) instruction; if (HOOKS.equals(call.owner) && name.equals(call.name)) return true;
        }
        return false;
    }
    private static MethodInsnNode hook(String name, String descriptor) { return new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, name, descriptor, false); }
}
