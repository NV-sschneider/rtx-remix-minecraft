/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.core;

import com.nvidia.rtxremix.forge.overlay.OverlayScreenAccess;
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
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import static org.junit.Assert.*;

public class OverlayInputTransformerTest {
    private static final String MC = "net/minecraft/client/Minecraft", SCREEN = "net/minecraft/client/gui/GuiScreen";
    private static final String MOUSE = "net/minecraft/util/MouseHelper", FORGE = "net/minecraftforge/client/ForgeHooksClient";
    private static final String BUTTON = "net/minecraft/client/gui/GuiButton", HOOKS = "com/nvidia/rtxremix/forge/overlay/OverlayHooks";
    private final OverlayInputTransformer transformer = new OverlayInputTransformer();

    @Test public void pinnedConsumersAreGatedAndPresentRunsAfterTheLastVanillaUi() throws Exception {
        byte[] base = bytes(MC);
        byte[] lifecycle = new DisplayLifecycleTransformer().transform("bib", MC.replace('/', '.'), base);
        ClassNode minecraft = read(transform(MC, lifecycle)); verify(minecraft);
        MethodNode loop = method(minecraft, "runGameLoop", "()V");
        assertEquals(1, calls(loop, HOOKS, "beginFrame")); assertEquals(1, calls(loop, HOOKS, "beforePresent"));
        boolean chart = false, present = false;
        for (AbstractInsnNode instruction : loop.instructions.toArray()) if (instruction instanceof MethodInsnNode) {
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (call.name.equals("displayDebugInfo")) chart = true;
            if (call.owner.equals(HOOKS) && call.name.equals("beforePresent")) { assertTrue(chart); present = true; }
            if (call.name.equals("framebufferRender")) assertTrue(present);
        }
        assertEquals(1, calls(method(minecraft, "runTickKeyboard", "()V"), HOOKS, "nextKeyboardEvent"));
        assertEquals(1, calls(method(minecraft, "runTickMouse", "()V"), HOOKS, "nextMouseEvent"));
        assertEquals(1, calls(method(minecraft, "processKeyBinds", "()V"), HOOKS, "capturing"));
        assertEquals(1, calls(method(minecraft, "setIngameFocus", "()V"), HOOKS, "capturing"));
        assertEquals(2, calls(method(minecraft, "shutdownMinecraftApplet", "()V"),
                "com/nvidia/rtxremix/forge/client/RendererLifecycle", "beforeDisplayDestroy"));
        for (String type : new String[] {MC, SCREEN, MOUSE, FORGE}) {
            byte[] patched = transform(type, bytes(type)); verify(read(patched)); assertArrayEquals(patched, transform(type, patched));
        }
        ClassNode screen = read(transform(SCREEN, bytes(SCREEN)));
        assertEquals(1, calls(method(screen, "handleInput", "()V"), HOOKS, "nextKeyboardEvent"));
        assertEquals(1, calls(method(screen, "handleInput", "()V"), HOOKS, "nextMouseEvent"));
        assertEquals(1, calls(method(screen, "handleKeyboardInput", "()V"), HOOKS, "capturing"));
        assertEquals(1, calls(method(screen, "handleMouseInput", "()V"), HOOKS, "capturing"));
        ClassNode mouse = read(transform(MOUSE, bytes(MOUSE)));
        assertEquals(1, calls(method(mouse, "grabMouseCursor", "()V"), HOOKS, "capturing"));
        assertEquals(1, calls(method(mouse, "mouseXYChange", "()V"), HOOKS, "clearMouseDelta"));
        MethodNode draw = method(read(transform(FORGE, bytes(FORGE))), "drawScreen", "(L" + SCREEN + ";IIF)V");
        assertEquals(2, calls(draw, HOOKS, "screenCoordinate")); assertEquals(1, calls(draw, SCREEN, "drawScreen"));
    }

    @Test public void pinnedSrgNamesAndFieldsProduceTheSameHooks() throws Exception {
        String[][] methods = {{MC, "runGameLoop", "func_71411_J", "beginFrame"},
                {MC, "runTickKeyboard", "func_184118_az", "nextKeyboardEvent"},
                {MC, "runTickMouse", "func_184124_aB", "nextMouseEvent"},
                {MC, "processKeyBinds", "func_184117_aA", "capturing"}, {MC, "setIngameFocus", "func_71381_h", "capturing"},
                {SCREEN, "handleInput", "func_146269_k", "nextKeyboardEvent"},
                {SCREEN, "handleMouseInput", "func_146274_d", "capturing"}, {SCREEN, "handleKeyboardInput", "func_146282_l", "capturing"},
                {MOUSE, "grabMouseCursor", "func_74372_a", "capturing"}, {MOUSE, "mouseXYChange", "func_74374_c", "clearMouseDelta"}};
        for (String[] target : methods) {
            ClassNode node = read(bytes(target[0])); method(node, target[1], "()V").name = target[2];
            for (MethodNode method : node.methods) for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof MethodInsnNode && ((MethodInsnNode) instruction).name.equals("framebufferRender"))
                    ((MethodInsnNode) instruction).name = "func_147615_c";
            }
            ClassNode patched = read(transform(target[0], write(node))); verify(patched);
            assertEquals(1, calls(method(patched, target[2], "()V"), HOOKS, target[3]));
        }
        ClassNode screen = read(bytes(SCREEN));
        String[][] fields = {{"eventButton", "field_146287_f"}, {"touchValue", "field_146298_h"},
                {"lastMouseEvent", "field_146288_g"}, {"selectedButton", "field_146290_a"}};
        for (FieldNode field : screen.fields) for (String[] names : fields) if (field.name.equals(names[0])) field.name = names[1];
        method(screen, "mouseReleased", "(III)V").name = "func_146286_b";
        ClassNode patched = read(transform(SCREEN, write(screen))); verify(patched);
        assertEquals(1, calls(method(patched, "rtxremix$releaseOverlayMouse", "(II)V"), SCREEN, "func_146286_b"));
    }

    @Test public void realJvmFinalizesTheActiveClickEvenWhenCustomReleaseThrows() throws Exception {
        FixtureLoader loader = new FixtureLoader(); loader.add(SCREEN, transform(SCREEN, screenFixture()));
        ClassWriter button = fixture(BUTTON); button.visitEnd(); loader.add(BUTTON, button.toByteArray());
        Class<?> type = loader.loadClass(SCREEN.replace('/', '.')); Object screen = type.newInstance();
        type.getField("eventButton").setInt(screen, 0); type.getField("touchValue").setInt(screen, 2);
        type.getField("lastMouseEvent").setLong(screen, 123); type.getField("throwOnRelease").setBoolean(screen, true);
        type.getField("selectedButton").set(screen, loader.loadClass(BUTTON.replace('/', '.')).newInstance());
        try { type.getMethod("rtxremix$releaseOverlayMouse", int.class, int.class).invoke(screen, 40, 50); fail(); }
        catch (InvocationTargetException expected) { assertTrue(expected.getCause() instanceof IllegalStateException); }
        assertEquals(-1, type.getField("eventButton").getInt(screen)); assertEquals(0, type.getField("touchValue").getInt(screen));
        assertEquals(0, type.getField("lastMouseEvent").getLong(screen)); assertNull(type.getField("selectedButton").get(screen));
        assertEquals(40, type.getField("releasedX").getInt(screen)); assertEquals(50, type.getField("releasedY").getInt(screen));
        ((OverlayScreenAccess) screen).rtxremix$releaseOverlayMouse(60, 70);
        assertEquals("An already finalized click must never dispatch again", 1, type.getField("releases").getInt(screen));
    }

    private byte[] transform(String name, byte[] bytes) { return transformer.transform("obfuscated", name.replace('/', '.'), bytes); }
    private static byte[] bytes(String name) throws Exception {
        try (InputStream input = OverlayInputTransformerTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(name, input); ByteArrayOutputStream result = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) >= 0) result.write(buffer, 0, count); return result.toByteArray();
        }
    }
    private static ClassNode read(byte[] bytes) { ClassNode node = new ClassNode(Opcodes.ASM5); new ClassReader(bytes).accept(node, 0); return node; }
    private static byte[] write(ClassNode node) { ClassWriter writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray(); }
    private static MethodNode method(ClassNode node, String name, String descriptor) {
        for (MethodNode method : node.methods) if (name.equals(method.name) && descriptor.equals(method.desc)) return method;
        throw new AssertionError("Missing " + name + descriptor);
    }
    private static int calls(MethodNode method, String owner, String name) {
        int count = 0; for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction instanceof MethodInsnNode) {
            MethodInsnNode call = (MethodInsnNode) instruction; if (owner.equals(call.owner) && name.equals(call.name)) count++;
        } return count;
    }
    private static void verify(ClassNode node) throws Exception {
        for (MethodNode method : node.methods) if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0)
            new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
    }
    private static ClassWriter fixture(String name) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null); init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0); init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN); init.visitMaxs(0, 0); init.visitEnd(); return writer;
    }
    private static byte[] screenFixture() {
        ClassWriter writer = fixture(SCREEN);
        for (String name : new String[] {"eventButton", "touchValue", "releasedX", "releasedY", "releases"})
            writer.visitField(Opcodes.ACC_PUBLIC, name, "I", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC, "lastMouseEvent", "J", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC, "selectedButton", "L" + BUTTON + ";", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC, "throwOnRelease", "Z", null, null).visitEnd();
        MethodVisitor release = writer.visitMethod(Opcodes.ACC_PROTECTED, "mouseReleased", "(III)V", null, null); release.visitCode();
        release.visitVarInsn(Opcodes.ALOAD, 0); release.visitInsn(Opcodes.DUP); release.visitFieldInsn(Opcodes.GETFIELD, SCREEN, "releases", "I");
        release.visitInsn(Opcodes.ICONST_1); release.visitInsn(Opcodes.IADD); release.visitFieldInsn(Opcodes.PUTFIELD, SCREEN, "releases", "I");
        for (int i = 1; i <= 2; i++) {
            release.visitVarInsn(Opcodes.ALOAD, 0); release.visitVarInsn(Opcodes.ILOAD, i);
            release.visitFieldInsn(Opcodes.PUTFIELD, SCREEN, i == 1 ? "releasedX" : "releasedY", "I");
        }
        release.visitVarInsn(Opcodes.ALOAD, 0); release.visitFieldInsn(Opcodes.GETFIELD, SCREEN, "throwOnRelease", "Z");
        Label done = new Label(); release.visitJumpInsn(Opcodes.IFEQ, done);
        release.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException"); release.visitInsn(Opcodes.DUP);
        release.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "()V", false); release.visitInsn(Opcodes.ATHROW);
        release.visitLabel(done); release.visitInsn(Opcodes.RETURN); release.visitMaxs(0, 0); release.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }
    private static final class FixtureLoader extends ClassLoader {
        final Map<String, byte[]> definitions = new HashMap<String, byte[]>();
        void add(String name, byte[] bytes) { definitions.put(name.replace('/', '.'), bytes); }
        @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!definitions.containsKey(name)) return super.loadClass(name, resolve);
            Class<?> result = findLoadedClass(name); if (result == null) { byte[] value = definitions.get(name); result = defineClass(name, value, 0, value.length); }
            if (resolve) resolveClass(result); return result;
        }
    }
}
