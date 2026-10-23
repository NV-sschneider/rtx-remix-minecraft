/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import static org.junit.Assert.*;

public class EntityCaptureTransformerTest {
    private static final String LIVING = "net/minecraft/client/renderer/entity/RenderLivingBase";
    private static final String MODEL = "net/minecraft/client/model/ModelRenderer";
    private static final String ADVANCED = "net/ilexiconn/llibrary/client/model/tools/AdvancedModelRenderer";
    private static final String BENDS_MODEL = "goblinbob/mobends/core/client/model/ModelPart";
    private static final String BENDS_EXTENDED = "goblinbob/mobends/core/client/model/ModelPartExtended";
    private static final String OBJ_MODEL = "com/lycanitesmobs/client/obj/TessellatorModel";
    private static final String VBO_MODEL = "com/lycanitesmobs/client/obj/VBOModel";
    private static final String OBJ_MESH = "com/lycanitesmobs/client/obj/Mesh";
    private static final String OBJ_GROUP = "com/lycanitesmobs/client/obj/ObjObject";
    private static final String BUFFER = "net/minecraft/client/renderer/BufferBuilder";
    private static final String VEC4 = "javax/vecmath/Vector4f", VEC2 = "javax/vecmath/Vector2f";
    private static final String FORMAT = "net/minecraft/client/renderer/vertex/VertexFormat";
    private static final String OBJ_DESCRIPTOR = "(L" + OBJ_GROUP + ";L" + VEC4 + ";L" + VEC2 + ";L" + FORMAT + ";)V";
    private static final String TEXTURES = "net/minecraft/client/renderer/texture/TextureManager";
    private static final String TILES = "net/minecraft/client/renderer/tileentity/TileEntityRendererDispatcher";
    private static final String TESR = "net/minecraft/client/renderer/tileentity/TileEntitySpecialRenderer";
    private static final String TILE = "net/minecraft/tileentity/TileEntity";
    private static final String TILE_DESCRIPTOR = "(L" + TILE + ";DDDFIF)V";
    private static final String ENTITY = "net/minecraft/entity/EntityLivingBase";
    private static final String GL_STATE = "net/minecraft/client/renderer/GlStateManager";
    private static final String HOOKS = "com/nvidia/rtxremix/forge/entity/EntityCaptureHooks";
    private static final String GL11 = "org/lwjgl/opengl/GL11", GL15 = "org/lwjgl/opengl/GL15";
    private static final String VBO_PROBE = Type.getInternalName(VboProbe.class);
    private static final String LIVING_DESCRIPTOR = "(L" + ENTITY + ";DDDFF)V";
    private final EntityCaptureTransformer transformer = new EntityCaptureTransformer();

    @Test
    public void snapshotsBeforeForgePreAndActivatesOnlyAcceptedLivingBodies() throws Exception {
        byte[] original = classBytes(LIVING);
        ClassNode before = read(original);
        ClassNode after = read(transform(LIVING, original));
        MethodNode oldMethod = method(before, "doRender", LIVING_DESCRIPTOR);
        MethodNode newMethod = method(after, "doRender", LIVING_DESCRIPTOR);
        assertEquals(before.methods.size(), after.methods.size());
        assertEquals(oldMethod.maxLocals, newMethod.maxLocals);
        assertEquals(frameCount(oldMethod) + 2, frameCount(newMethod));
        assertEquals(oldMethod.tryCatchBlocks.size() + 1, newMethod.tryCatchBlocks.size());
        assertEquals(1, hookCount(newMethod, "beginLiving"));
        assertEquals(1, hookCount(newMethod, "activateLiving"));
        assertEquals(2, hookCount(newMethod, "endLiving"));

        int beginIndex = -1;
        int activationIndex = -1;
        int preEventIndex = -1;
        int postEventIndex = -1;
        for (AbstractInsnNode instruction : newMethod.instructions.toArray()) {
            if (isHook(instruction, "beginLiving")) {
                beginIndex = newMethod.instructions.indexOf(instruction);
                assertEquals("(Ljava/lang/Object;)V", ((MethodInsnNode) instruction).desc);
            } else if (isHook(instruction, "activateLiving")) {
                activationIndex = newMethod.instructions.indexOf(instruction);
                MethodInsnNode nextCall = nextCall(instruction);
                assertEquals(GL_STATE, nextCall.owner);
                assertEquals("pushMatrix", nextCall.name);
            }
            if (instruction instanceof MethodInsnNode && ((MethodInsnNode) instruction).owner
                    .equals("net/minecraftforge/client/event/RenderLivingEvent$Pre")) {
                preEventIndex = newMethod.instructions.indexOf(instruction);
            }
            if (instruction instanceof MethodInsnNode && ((MethodInsnNode) instruction).owner
                    .equals("net/minecraftforge/client/event/RenderLivingEvent$Post")) {
                postEventIndex = newMethod.instructions.indexOf(instruction);
            }
        }
        assertTrue(beginIndex >= 0);
        assertTrue(preEventIndex > beginIndex);
        assertTrue(activationIndex > preEventIndex);
        TryCatchBlockNode cleanup = newMethod.tryCatchBlocks.get(newMethod.tryCatchBlocks.size() - 1);
        assertNull(cleanup.type);
        assertTrue(newMethod.instructions.indexOf(cleanup.start) > beginIndex);
        assertTrue(postEventIndex > beginIndex);
        assertTrue(newMethod.instructions.indexOf(cleanup.end) > postEventIndex);
        assertHook(nextCall(cleanup.handler), "endLiving", "()V");
        verify(after);
    }

    @Test
    public void observesEveryPinnedModelDisplayListAtItsExistingMatrix() throws Exception {
        byte[] original = classBytes(MODEL);
        ClassNode before = read(original);
        ClassNode after = read(transform(MODEL, original));
        int captures = 0;
        for (int i = 0; i < before.methods.size(); i++) {
            MethodNode oldMethod = before.methods.get(i);
            MethodNode newMethod = after.methods.get(i);
            assertEquals(frameCount(oldMethod), frameCount(newMethod));
            int count = hookCount(newMethod, "capturePart");
            if (newMethod.name.equals("render")) { assertEquals(3, count); }
            else if (newMethod.name.equals("renderWithRotation")) { assertEquals(1, count); }
            else { assertEquals(0, count); }
            for (AbstractInsnNode instruction : newMethod.instructions.toArray()) {
                if (!isHook(instruction, "capturePart")) { continue; }
                assertHook(instruction, "capturePart", "(ILjava/lang/Object;F)V");
                MethodInsnNode draw = (MethodInsnNode) instruction.getNext();
                assertEquals(GL_STATE, draw.owner);
                assertEquals("callList", draw.name);
                assertEquals("(I)V", draw.desc);
            }
            captures += count;
        }
        assertEquals(4, captures);
        verify(after);
    }

    @Test public void bracketsPinnedNormalTileDispatchAndLeavesFastRenderingInactive() throws Exception {
        byte[] original = classBytes(TILES);
        ClassNode before = read(original);
        ClassNode after = read(transform(TILES, original));
        MethodNode oldMethod = method(before, "render", TILE_DESCRIPTOR);
        MethodNode newMethod = method(after, "render", TILE_DESCRIPTOR);
        assertEquals(oldMethod.maxLocals, newMethod.maxLocals);
        assertEquals(frameCount(oldMethod) + 2, frameCount(newMethod));
        assertEquals(oldMethod.tryCatchBlocks.size() + 1, newMethod.tryCatchBlocks.size());
        assertEquals(1, hookCount(newMethod, "beginTile"));
        assertEquals(1, hookCount(newMethod, "activateTile"));
        assertEquals(2, hookCount(newMethod, "endTile"));
        int fastCalls = 0;
        for (AbstractInsnNode instruction : newMethod.instructions.toArray()) {
            if (isHook(instruction, "activateTile")) {
                MethodInsnNode next = (MethodInsnNode) instruction.getNext();
                assertEquals(TESR, next.owner);
                assertEquals("render", next.name);
                assertEquals(TILE_DESCRIPTOR, next.desc);
            }
            if (instruction instanceof MethodInsnNode && ((MethodInsnNode) instruction).name.equals("renderTileEntityFast")) fastCalls++;
        }
        assertEquals(1, fastCalls);
        verify(after);
        byte[] transformed = transform(TILES, original);
        assertSame(transformed, transform(TILES, transformed));
    }

    @Test public void tileFinallyVerifiesAndRunsForNormalFastAndExceptionalDispatch() throws Exception {
        for (boolean srg : new boolean[] {false, true}) {
            String dispatchName = srg ? "func_192854_a" : "render";
            String rendererName = srg ? "func_192841_a" : "render";
            FixtureLoader loader = new FixtureLoader();
            loader.add(TILE, emptyClass(TILE));
            loader.add(TESR, tileRendererFixture(rendererName));
            loader.add(HOOKS, tileHooksFixture());
            loader.add(TILES, transform(TILES, tileDispatcherFixture(dispatchName, rendererName)));
            Class<?> dispatcher = loader.loadClass(TILES.replace('/', '.'));
            Class<?> tile = loader.loadClass(TILE.replace('/', '.'));
            Class<?> hooks = loader.loadClass(HOOKS.replace('/', '.'));
            Class<?> tesr = loader.loadClass(TESR.replace('/', '.'));
            Method render = dispatcher.getMethod(dispatchName, tile, double.class, double.class,
                    double.class, float.class, int.class, float.class);
            for (int mode = 0; mode < 3; mode++) {
                dispatcher.getField("mode").setInt(null, mode);
                tesr.getField("mode").setInt(null, mode);
                for (String field : new String[] {"begins", "activations", "ends"}) hooks.getField(field).setInt(null, 0);
                try {
                    render.invoke(dispatcher.newInstance(), tile.newInstance(), 1.0, 2.0, 3.0, 0.5f, -1, 1.0f);
                    assertTrue(mode < 2);
                } catch (InvocationTargetException error) { assertEquals(IllegalStateException.class, error.getCause().getClass()); }
                assertEquals(1, hooks.getField("begins").getInt(null));
                assertEquals(mode == 1 ? 0 : 1, hooks.getField("activations").getInt(null));
                assertEquals(1, hooks.getField("ends").getInt(null));
            }
        }
    }

    @Test
    public void recordsOnlySuccessfulPinnedTextureBindings() throws Exception {
        byte[] original = classBytes(TEXTURES);
        ClassNode before = read(original);
        ClassNode after = read(transform(TEXTURES, original));
        int hooks = 0;
        for (int i = 0; i < before.methods.size(); i++) {
            MethodNode oldMethod = before.methods.get(i);
            MethodNode newMethod = after.methods.get(i);
            assertEquals(frameCount(oldMethod), frameCount(newMethod));
            for (AbstractInsnNode instruction : newMethod.instructions.toArray()) {
                if (!isHook(instruction, "boundTexture")) { continue; }
                assertEquals("bindTexture", newMethod.name);
                assertHook(instruction, "boundTexture", "(Ljava/lang/Object;)V");
                assertEquals(Opcodes.RETURN, instruction.getNext().getOpcode());
                hooks++;
            }
        }
        assertEquals(1, hooks);
        verify(after);
    }

    @Test
    public void optionalAdvancedModelAndSrgNamesUseTheSameObservationContract() throws Exception {
        byte[] original = partFixture(ADVANCED, "func_78785_a", "func_179148_o");
        ClassNode after = read(transform(ADVANCED, original));
        assertEquals(1, hookCount(method(after, "func_78785_a", "(F)V"), "capturePart"));
        verify(after);

        byte[] vanilla = partFixture(MODEL, "func_78791_b", "func_179148_o");
        assertEquals(1, hookCount(method(read(transform(MODEL, vanilla)),
                "func_78791_b", "(F)V"), "capturePart"));
    }

    @Test public void optionalBendsHooksObserveEachDrawOnceAndPreserveItsDisplayListArgument() throws Exception {
        for (String type : new String[] {BENDS_MODEL, BENDS_EXTENDED}) {
            for (String glName : new String[] {"callList", "func_179148_o"}) {
                byte[] original = bendsFixture(type, glName);
                byte[] transformed = transform(type, original);
                ClassNode after = read(transformed);
                assertEquals(1, hookCount(method(after, "renderPart", "(F)V"), "capturePart"));
                assertEquals(1, hookCount(method(after, "renderJustPart", "(F)V"), "capturePart"));
                assertEquals(0, hookCount(method(after, "func_78785_a", "(F)V"), "capturePart"));
                assertEquals(0, hookCount(method(after, "func_78791_b", "(F)V"), "capturePart"));
                verify(after);
                assertSame(transformed, transform(type, transformed));

                FixtureLoader loader = new FixtureLoader();
                loader.add(type, transformed);
                loader.add(GL_STATE, drawFixture(GL_STATE, glName, "(I)V"));
                loader.add(HOOKS, drawFixture(HOOKS, "capturePart", "(ILjava/lang/Object;F)V"));
                Class<?> model = loader.loadClass(type.replace('/', '.'));
                Class<?> gl = loader.loadClass(GL_STATE.replace('/', '.'));
                Class<?> hooks = loader.loadClass(HOOKS.replace('/', '.'));
                Object part = model.newInstance();
                String[] entries = {"renderPart", "renderJustPart", "func_78785_a", "func_78791_b"};
                for (int i = 0; i < entries.length; i++) {
                    model.getMethod(entries[i], float.class).invoke(part, 0.25f);
                    assertEquals(i + 1, gl.getField("draws").getInt(null));
                    assertEquals(i + 1, hooks.getField("draws").getInt(null));
                    assertEquals(i == 1 ? 47 : 31, hooks.getField("lastList").getInt(null));
                    assertEquals(gl.getField("lastList").getInt(null), hooks.getField("lastList").getInt(null));
                    assertEquals(0.25f, hooks.getField("scale").getFloat(null), 0);
                    assertSame(part, hooks.getField("part").get(null));
                }
            }
        }
    }

    @Test public void optionalObjHookSeesOnlyFinishedBufferBeforeProducerReset() throws Exception {
        for (String finishName : new String[] {"finishDrawing", "func_178977_d"}) {
            byte[] original = objFixture(OBJ_MODEL, finishName);
            byte[] transformed = transform(OBJ_MODEL, original);
            ClassNode after = read(transformed);
            MethodNode method = method(after, "renderGroupImpl", OBJ_DESCRIPTOR);
            assertEquals(1, hookCount(method, "captureTriangles"));
            assertSame(transformed, transform(OBJ_MODEL, transformed));
            verify(after);
            FixtureLoader loader = new FixtureLoader();
            for (String type : new String[] {OBJ_GROUP, VEC4, VEC2, FORMAT}) loader.add(type, emptyClass(type));
            loader.add(BUFFER, objBufferFixture(finishName));
            loader.add(HOOKS, objHooksFixture());
            loader.add(OBJ_MODEL, transformed);
            Class<?> model = loader.loadClass(OBJ_MODEL.replace('/', '.'));
            Class<?> buffer = loader.loadClass(BUFFER.replace('/', '.'));
            Class<?> group = loader.loadClass(OBJ_GROUP.replace('/', '.'));
            Class<?> hooks = loader.loadClass(HOOKS.replace('/', '.'));
            Method render = model.getMethod("renderGroupImpl", group, loader.loadClass(VEC4.replace('/', '.')),
                    loader.loadClass(VEC2.replace('/', '.')), loader.loadClass(FORMAT.replace('/', '.')));
            Object observed = group.newInstance();
            render.invoke(model.newInstance(), observed, null, null, null);
            assertEquals(1, hooks.getField("calls").getInt(null));
            assertEquals(1, hooks.getField("finishedAtCapture").getInt(null));
            assertEquals(0, hooks.getField("resetsAtCapture").getInt(null));
            assertEquals(1, buffer.getField("resets").getInt(null));
            assertSame(observed, hooks.getField("group").get(null));
            buffer.getField("fail").setBoolean(null, true);
            try {
                render.invoke(model.newInstance(), observed, null, null, null);
                fail("The original finish failure must propagate");
            } catch (InvocationTargetException expected) { assertTrue(expected.getCause() instanceof IllegalStateException); }
            assertEquals(1, hooks.getField("calls").getInt(null));
        }
    }

    @Test public void optionalVboUploadAndDeleteObserveOnlySuccessfulNativeCalls() throws Exception {
        FixtureLoader loader = vboLoader();
        Class<?> meshType = loader.loadClass(OBJ_MESH.replace('/', '.'));
        Object mesh = meshType.newInstance();
        ByteBuffer bytes = ByteBuffer.allocateDirect(24);
        bytes.position(4); bytes.limit(20);
        meshType.getField("bytes").set(mesh, bytes);
        Method getVbo = meshType.getMethod("getVbo"), delete = meshType.getMethod("delete");

        VboProbe.reset();
        assertEquals(73, getVbo.invoke(mesh));
        assertEquals(Arrays.asList("upload", "captureUpload"), VboProbe.events);
        assertEquals(34962, VboProbe.target); assertEquals(35044, VboProbe.usage);
        assertSame(bytes, VboProbe.uploaded); assertSame(bytes, VboProbe.capturedBytes);
        assertSame(mesh, VboProbe.capturedMesh);
        assertEquals(4, bytes.position()); assertEquals(20, bytes.limit());

        VboProbe.reset(); VboProbe.skip = true;
        assertEquals(73, getVbo.invoke(mesh));
        assertTrue("An already uploaded mesh must not be observed again", VboProbe.events.isEmpty());

        VboProbe.reset(); VboProbe.failUpload = true;
        assertNativeFailure(getVbo, mesh);
        assertEquals(Arrays.asList("upload"), VboProbe.events);
        assertNull(VboProbe.capturedBytes);

        VboProbe.reset();
        delete.invoke(mesh);
        assertEquals(Arrays.asList("delete", "captureDelete"), VboProbe.events);
        assertEquals(73, VboProbe.deleted); assertSame(mesh, VboProbe.capturedMesh);

        VboProbe.reset(); VboProbe.failDelete = true;
        assertNativeFailure(delete, mesh);
        assertEquals(Arrays.asList("delete"), VboProbe.events);
        assertNull(VboProbe.capturedMesh);
    }

    @Test public void optionalVboDrawPreservesArgumentsOwnerAndFramesWithoutLoadingModClasses() throws Exception {
        FixtureLoader loader = vboLoader();
        Class<?> modelType = loader.loadClass(VBO_MODEL.replace('/', '.'));
        Class<?> groupType = loader.loadClass(OBJ_GROUP.replace('/', '.'));
        Object group = groupType.newInstance();
        Object mesh = loader.loadClass(OBJ_MESH.replace('/', '.')).newInstance();
        groupType.getField("mesh").set(group, mesh);
        Method render = modelType.getMethod("renderGroupImpl", groupType, loader.loadClass(VEC4.replace('/', '.')),
                loader.loadClass(VEC2.replace('/', '.')), loader.loadClass(FORMAT.replace('/', '.')));
        Object model = modelType.newInstance();
        VboProbe.reset();
        render.invoke(model, group, null, null, null);
        assertEquals(Arrays.asList("captureDraw", "draw"), VboProbe.events);
        assertArrayEquals(new int[] {4, 3, 9}, VboProbe.drawArguments);
        assertArrayEquals(VboProbe.drawArguments, VboProbe.capturedArguments);
        assertSame(group, VboProbe.capturedGroup); assertSame(mesh, VboProbe.capturedMesh);

        VboProbe.reset(); VboProbe.skip = true;
        render.invoke(model, group, null, null, null);
        assertTrue("A skipped OpenGL draw must not publish a Remix occurrence", VboProbe.events.isEmpty());
        // Optional references remain symbolic throughout transformation and verification.
        assertNull(getClass().getClassLoader().getResource(OBJ_GROUP + ".class"));
        assertNull(getClass().getClassLoader().getResource(OBJ_MESH + ".class"));
        byte[] original = vboModelFixture();
        assertSame(original, transform("unrelated/OptionalRenderer", original));
    }

    @Test
    public void generatedFinallyPassesJvmVerificationAndRunsOnceOnReturnOrThrow() throws Exception {
        for (String methodName : new String[] { "doRender", "func_76986_a" }) {
            FixtureLoader loader = new FixtureLoader();
            loader.add(ENTITY, emptyClass(ENTITY));
            loader.add(GL_STATE, glFixture());
            loader.add(HOOKS, hooksFixture());
            loader.add(LIVING, transform(LIVING, livingFixture(methodName)));
            Class<?> living = loader.loadClass(LIVING.replace('/', '.'));
            Class<?> entity = loader.loadClass(ENTITY.replace('/', '.'));
            Class<?> hooks = loader.loadClass(HOOKS.replace('/', '.'));
            Object renderer = living.newInstance();
            Method render = living.getMethod(methodName, entity, double.class, double.class,
                    double.class, float.class, float.class);

            for (int mode = 0; mode < 4; mode++) {
                living.getField("mode").setInt(null, mode);
                hooks.getField("begins").setInt(null, 0);
                hooks.getField("ends").setInt(null, 0);
                hooks.getField("activations").setInt(null, 0);
                try {
                    render.invoke(renderer, entity.newInstance(), 2.0, 3.0, 4.0, 5.0f, 0.5f);
                    assertTrue("Exceptional render must rethrow", mode < 2);
                } catch (InvocationTargetException error) {
                    assertEquals(mode == 2 ? IllegalStateException.class : AssertionError.class,
                            error.getCause().getClass());
                }
                assertEquals(1, hooks.getField("begins").getInt(null));
                assertEquals(1, hooks.getField("ends").getInt(null));
                assertEquals(mode == 1 ? 0 : 1, hooks.getField("activations").getInt(null));
            }
        }
    }

    @Test
    public void doesNotTransformTwiceOrTouchUnrelatedClasses() throws IOException {
        byte[] original = classBytes(MODEL);
        byte[] transformed = transform(MODEL, original);
        assertSame(transformed, transform(MODEL, transformed));
        assertSame(original, transformer.transform(MODEL, "other.Class", original));
        assertSame(original, transformer.transform(MODEL, null, original));
        assertNull(transform(MODEL, null));
    }

    @Test
    public void registersAfterForgeRuntimeNameRemapping() {
        assertEquals(1001, RemixCorePlugin.class.getAnnotation(IFMLLoadingPlugin.SortingIndex.class).value());
        assertArrayEquals(new String[] {
                "com.nvidia.rtxremix.forge.core.DisplayLifecycleTransformer",
                "com.nvidia.rtxremix.forge.core.EntityCaptureTransformer",
                "com.nvidia.rtxremix.forge.core.AtlasAnimationTransformer",
                "com.nvidia.rtxremix.forge.core.ItemCaptureTransformer",
                "com.nvidia.rtxremix.forge.core.EffectCaptureTransformer"
        }, new RemixCorePlugin().getASMTransformerClass());
    }

    private byte[] transform(String type, byte[] original) {
        return transformer.transform("obfuscated", type.replace('/', '.'), original);
    }

    private static byte[] classBytes(String type) throws IOException {
        try (InputStream input = EntityCaptureTransformerTest.class.getClassLoader()
                .getResourceAsStream(type + ".class")) {
            assertNotNull("Pinned mapped Forge dependency must be on the test classpath", input);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) { output.write(buffer, 0, count); }
            return output.toByteArray();
        }
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM5);
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode type, String name, String descriptor) {
        for (MethodNode method : type.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) { return method; }
        }
        throw new AssertionError("Missing " + name + descriptor);
    }

    private static int frameCount(MethodNode method) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof FrameNode) { count++; }
        }
        return count;
    }

    private static int hookCount(MethodNode method, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (isHook(instruction, name)) { count++; }
        }
        return count;
    }

    private static boolean isHook(AbstractInsnNode instruction, String name) {
        if (!(instruction instanceof MethodInsnNode)) { return false; }
        MethodInsnNode call = (MethodInsnNode) instruction;
        return HOOKS.equals(call.owner) && name.equals(call.name);
    }

    private static void assertHook(AbstractInsnNode instruction, String name, String descriptor) {
        assertTrue(isHook(instruction, name));
        assertEquals(Opcodes.INVOKESTATIC, instruction.getOpcode());
        assertEquals(descriptor, ((MethodInsnNode) instruction).desc);
    }

    private static MethodInsnNode nextCall(AbstractInsnNode instruction) {
        for (AbstractInsnNode next = instruction.getNext(); next != null; next = next.getNext()) {
            if (next instanceof MethodInsnNode) { return (MethodInsnNode) next; }
        }
        throw new AssertionError("No following invocation");
    }

    private static void verify(ClassNode type) throws Exception {
        for (MethodNode method : type.methods) {
            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0) {
                new Analyzer<>(new BasicVerifier()).analyze(type.name, method);
            }
        }
    }

    private static ClassWriter fixture(String type) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, type, null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        return writer;
    }

    private static byte[] emptyClass(String type) {
        ClassWriter writer = fixture(type);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] partFixture(String type, String methodName, String callName) {
        ClassWriter writer = fixture(type);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, methodName, "(F)V", null, null);
        method.visitCode();
        method.visitInsn(Opcodes.ICONST_1);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, GL_STATE, callName, "(I)V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] bendsFixture(String type, String callName) {
        ClassWriter writer = fixture(type);
        String[] names = {"renderPart", "renderJustPart", "func_78785_a", "func_78791_b"};
        for (int i = 0; i < names.length; i++) {
            MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, names[i], "(F)V", null, null);
            method.visitCode();
            if (i < 2) {
                method.visitIntInsn(Opcodes.BIPUSH, i == 0 ? 31 : 47);
                method.visitMethodInsn(Opcodes.INVOKESTATIC, GL_STATE, callName, "(I)V", false);
            } else {
                method.visitVarInsn(Opcodes.ALOAD, 0);
                method.visitVarInsn(Opcodes.FLOAD, 1);
                method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, type, "renderPart", "(F)V", false);
            }
            method.visitInsn(Opcodes.RETURN);
            method.visitMaxs(0, 0); method.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] drawFixture(String type, String name, String descriptor) {
        ClassWriter writer = fixture(type);
        for (String field : new String[] {"draws", "lastList"}) {
            writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, field, "I", null, null).visitEnd();
        }
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "scale", "F", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "part", "Ljava/lang/Object;", null, null).visitEnd();
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, descriptor, null, null);
        method.visitCode();
        method.visitFieldInsn(Opcodes.GETSTATIC, type, "draws", "I");
        method.visitInsn(Opcodes.ICONST_1); method.visitInsn(Opcodes.IADD);
        method.visitFieldInsn(Opcodes.PUTSTATIC, type, "draws", "I");
        method.visitVarInsn(Opcodes.ILOAD, 0);
        method.visitFieldInsn(Opcodes.PUTSTATIC, type, "lastList", "I");
        if (type.equals(HOOKS)) {
            method.visitVarInsn(Opcodes.ALOAD, 1);
            method.visitFieldInsn(Opcodes.PUTSTATIC, type, "part", "Ljava/lang/Object;");
            method.visitVarInsn(Opcodes.FLOAD, 2);
            method.visitFieldInsn(Opcodes.PUTSTATIC, type, "scale", "F");
        }
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0); method.visitEnd(); writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] objFixture(String type, String finishName) {
        ClassWriter writer = fixture(type);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "renderGroupImpl", OBJ_DESCRIPTOR, null, null);
        method.visitCode();
        if (finishName != null) {
            method.visitTypeInsn(Opcodes.NEW, BUFFER); method.visitInsn(Opcodes.DUP);
            method.visitMethodInsn(Opcodes.INVOKESPECIAL, BUFFER, "<init>", "()V", false);
            method.visitVarInsn(Opcodes.ASTORE, 5);
            method.visitVarInsn(Opcodes.ALOAD, 5);
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BUFFER, finishName, "()V", false);
            method.visitVarInsn(Opcodes.ALOAD, 5);
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BUFFER, "reset", "()V", false);
        }
        method.visitInsn(Opcodes.RETURN); method.visitMaxs(0, 0); method.visitEnd(); writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] objBufferFixture(String finishName) {
        ClassWriter writer = fixture(BUFFER);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "fail", "Z", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "finished", "I", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "resets", "I", null, null).visitEnd();
        for (String name : new String[] {finishName, "reset"}) {
            MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, name, "()V", null, null);
            method.visitCode();
            if (name.equals(finishName)) {
                Label okay = new Label();
                method.visitFieldInsn(Opcodes.GETSTATIC, BUFFER, "fail", "Z");
                method.visitJumpInsn(Opcodes.IFEQ, okay);
                method.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException"); method.visitInsn(Opcodes.DUP);
                method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "()V", false);
                method.visitInsn(Opcodes.ATHROW); method.visitLabel(okay);
            }
            method.visitInsn(Opcodes.ICONST_1);
            method.visitFieldInsn(Opcodes.PUTSTATIC, BUFFER, name.equals(finishName) ? "finished" : "resets", "I");
            method.visitInsn(Opcodes.RETURN); method.visitMaxs(0, 0); method.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] objHooksFixture() {
        ClassWriter writer = fixture(HOOKS);
        for (String field : new String[] {"calls", "finishedAtCapture", "resetsAtCapture"}) {
            writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, field, "I", null, null).visitEnd();
        }
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "group", "Ljava/lang/Object;", null, null).visitEnd();
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "captureTriangles",
                "(Ljava/lang/Object;Ljava/lang/Object;)V", null, null);
        method.visitCode();
        method.visitFieldInsn(Opcodes.GETSTATIC, HOOKS, "calls", "I");
        method.visitInsn(Opcodes.ICONST_1); method.visitInsn(Opcodes.IADD);
        method.visitFieldInsn(Opcodes.PUTSTATIC, HOOKS, "calls", "I");
        method.visitFieldInsn(Opcodes.GETSTATIC, BUFFER, "finished", "I");
        method.visitFieldInsn(Opcodes.PUTSTATIC, HOOKS, "finishedAtCapture", "I");
        method.visitFieldInsn(Opcodes.GETSTATIC, BUFFER, "resets", "I");
        method.visitFieldInsn(Opcodes.PUTSTATIC, HOOKS, "resetsAtCapture", "I");
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitFieldInsn(Opcodes.PUTSTATIC, HOOKS, "group", "Ljava/lang/Object;");
        method.visitInsn(Opcodes.RETURN); method.visitMaxs(0, 0); method.visitEnd(); writer.visitEnd();
        return writer.toByteArray();
    }

    private FixtureLoader vboLoader() throws Exception {
        FixtureLoader loader = new FixtureLoader();
        for (String type : new String[] {VEC4, VEC2, FORMAT}) loader.add(type, emptyClass(type));
        ClassWriter group = fixture(OBJ_GROUP);
        group.visitField(Opcodes.ACC_PUBLIC, "mesh", "L" + OBJ_MESH + ";", null, null).visitEnd();
        group.visitEnd(); loader.add(OBJ_GROUP, group.toByteArray());
        byte[] mesh = transform(OBJ_MESH, vboMeshFixture());
        byte[] model = transform(VBO_MODEL, vboModelFixture());
        verify(read(mesh)); verify(read(model));
        assertSame(mesh, transform(OBJ_MESH, mesh)); assertSame(model, transform(VBO_MODEL, model));
        assertEquals(1, hookCount(method(read(mesh), "getVbo", "()I"), "captureVboUpload"));
        assertEquals(1, hookCount(method(read(mesh), "delete", "()V"), "deleteVbo"));
        assertEquals(1, hookCount(method(read(model), "renderGroupImpl", OBJ_DESCRIPTOR), "captureVboTriangles"));
        loader.add(OBJ_MESH, mesh); loader.add(VBO_MODEL, model);

        ClassWriter gl15 = fixture(GL15);
        forwardProbe(gl15, "glBufferData", "(ILjava/nio/ByteBuffer;I)V", "upload");
        forwardProbe(gl15, "glDeleteBuffers", "(I)V", "delete");
        gl15.visitEnd(); loader.add(GL15, gl15.toByteArray());
        ClassWriter gl11 = fixture(GL11);
        forwardProbe(gl11, "glDrawArrays", "(III)V", "draw");
        gl11.visitEnd(); loader.add(GL11, gl11.toByteArray());
        ClassWriter hooks = fixture(HOOKS);
        forwardProbe(hooks, "captureVboUpload", "(Ljava/lang/Object;Ljava/lang/Object;)V", "captureUpload");
        forwardProbe(hooks, "deleteVbo", "(Ljava/lang/Object;)V", "captureDelete");
        forwardProbe(hooks, "captureVboTriangles", "(IIILjava/lang/Object;Ljava/lang/Object;)V", "captureDraw");
        hooks.visitEnd(); loader.add(HOOKS, hooks.toByteArray());
        return loader;
    }

    private static byte[] vboMeshFixture() {
        ClassWriter writer = fixture(OBJ_MESH);
        writer.visitField(Opcodes.ACC_PUBLIC, "bytes", "Ljava/nio/ByteBuffer;", null, null).visitEnd();
        MethodVisitor get = writer.visitMethod(Opcodes.ACC_PUBLIC, "getVbo", "()I", null, null);
        get.visitCode();
        get.visitIntInsn(Opcodes.BIPUSH, 73); get.visitVarInsn(Opcodes.ISTORE, 1);
        Label done = new Label();
        get.visitFieldInsn(Opcodes.GETSTATIC, VBO_PROBE, "skip", "Z"); get.visitJumpInsn(Opcodes.IFNE, done);
        get.visitLdcInsn(34962);
        get.visitVarInsn(Opcodes.ALOAD, 0); get.visitFieldInsn(Opcodes.GETFIELD, OBJ_MESH, "bytes", "Ljava/nio/ByteBuffer;");
        get.visitLdcInsn(35044);
        get.visitMethodInsn(Opcodes.INVOKESTATIC, GL15, "glBufferData", "(ILjava/nio/ByteBuffer;I)V", false);
        get.visitLabel(done); get.visitVarInsn(Opcodes.ILOAD, 1); get.visitInsn(Opcodes.IRETURN);
        get.visitMaxs(0, 0); get.visitEnd();
        MethodVisitor delete = writer.visitMethod(Opcodes.ACC_PUBLIC, "delete", "()V", null, null);
        delete.visitCode(); delete.visitIntInsn(Opcodes.BIPUSH, 73);
        delete.visitMethodInsn(Opcodes.INVOKESTATIC, GL15, "glDeleteBuffers", "(I)V", false);
        delete.visitInsn(Opcodes.RETURN); delete.visitMaxs(0, 0); delete.visitEnd();
        writer.visitEnd(); return writer.toByteArray();
    }

    private static byte[] vboModelFixture() {
        ClassWriter writer = fixture(VBO_MODEL);
        MethodVisitor render = writer.visitMethod(Opcodes.ACC_PUBLIC, "renderGroupImpl", OBJ_DESCRIPTOR, null, null);
        render.visitCode();
        Label done = new Label();
        render.visitFieldInsn(Opcodes.GETSTATIC, VBO_PROBE, "skip", "Z"); render.visitJumpInsn(Opcodes.IFNE, done);
        render.visitInsn(Opcodes.ICONST_4); render.visitInsn(Opcodes.ICONST_3); render.visitIntInsn(Opcodes.BIPUSH, 9);
        render.visitMethodInsn(Opcodes.INVOKESTATIC, GL11, "glDrawArrays", "(III)V", false);
        render.visitLabel(done); render.visitInsn(Opcodes.RETURN);
        render.visitMaxs(0, 0); render.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }

    private static void forwardProbe(ClassWriter writer, String methodName, String descriptor, String probeName) {
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, methodName, descriptor, null, null);
        method.visitCode();
        int local = 0;
        for (Type parameter : Type.getArgumentTypes(descriptor)) {
            method.visitVarInsn(parameter.getOpcode(Opcodes.ILOAD), local); local += parameter.getSize();
        }
        method.visitMethodInsn(Opcodes.INVOKESTATIC, VBO_PROBE, probeName, descriptor, false);
        method.visitInsn(Opcodes.RETURN); method.visitMaxs(0, 0); method.visitEnd();
    }

    private static void assertNativeFailure(Method method, Object receiver) throws Exception {
        try { method.invoke(receiver); fail("The original native-call failure must propagate"); }
        catch (InvocationTargetException expected) { assertTrue(expected.getCause() instanceof IllegalStateException); }
    }

    /** Shared by fixture-loaded hook/GL classes, without LWJGL or optional mod initialization. */
    public static final class VboProbe {
        public static boolean skip, failUpload, failDelete;
        static final List<String> events = new ArrayList<>();
        static int target, usage, deleted;
        static Object uploaded, capturedBytes, capturedMesh, capturedGroup;
        static int[] drawArguments, capturedArguments;
        static void reset() {
            skip = failUpload = failDelete = false; events.clear(); target = usage = deleted = 0;
            uploaded = capturedBytes = capturedMesh = capturedGroup = null;
            drawArguments = capturedArguments = null;
        }
        public static void upload(int target, ByteBuffer bytes, int usage) {
            events.add("upload"); VboProbe.target = target; VboProbe.usage = usage; uploaded = bytes;
            if (failUpload) throw new IllegalStateException("upload");
        }
        public static void captureUpload(Object bytes, Object mesh) {
            events.add("captureUpload"); capturedBytes = bytes; capturedMesh = mesh;
        }
        public static void delete(int handle) {
            events.add("delete"); deleted = handle;
            if (failDelete) throw new IllegalStateException("delete");
        }
        public static void captureDelete(Object mesh) { events.add("captureDelete"); capturedMesh = mesh; }
        public static void draw(int mode, int first, int count) {
            events.add("draw"); drawArguments = new int[] {mode, first, count};
        }
        public static void captureDraw(int mode, int first, int count, Object group, Object mesh) {
            events.add("captureDraw"); capturedArguments = new int[] {mode, first, count};
            capturedGroup = group; capturedMesh = mesh;
        }
    }

    private static byte[] livingFixture(String name) {
        ClassWriter writer = fixture(LIVING);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "mode", "I", null, null).visitEnd();
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, name, LIVING_DESCRIPTOR, null, null);
        method.visitCode();
        Label scope = new Label();
        method.visitFieldInsn(Opcodes.GETSTATIC, LIVING, "mode", "I");
        method.visitInsn(Opcodes.ICONST_1);
        method.visitJumpInsn(Opcodes.IF_ICMPNE, scope);
        method.visitInsn(Opcodes.RETURN);
        method.visitLabel(scope);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, GL_STATE, "pushMatrix", "()V", false);
        for (int mode = 2; mode <= 3; mode++) {
            Label next = new Label();
            method.visitFieldInsn(Opcodes.GETSTATIC, LIVING, "mode", "I");
            method.visitInsn(mode == 2 ? Opcodes.ICONST_2 : Opcodes.ICONST_3);
            method.visitJumpInsn(Opcodes.IF_ICMPNE, next);
            String exception = mode == 2 ? "java/lang/IllegalStateException" : "java/lang/AssertionError";
            method.visitTypeInsn(Opcodes.NEW, exception);
            method.visitInsn(Opcodes.DUP);
            method.visitMethodInsn(Opcodes.INVOKESPECIAL, exception, "<init>", "()V", false);
            method.visitInsn(Opcodes.ATHROW);
            method.visitLabel(next);
        }
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] glFixture() {
        ClassWriter writer = fixture(GL_STATE);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "pushMatrix", "()V", null, null);
        method.visitCode();
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] tileDispatcherFixture(String name, String rendererName) {
        ClassWriter writer = fixture(TILES);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "mode", "I", null, null).visitEnd();
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, name, TILE_DESCRIPTOR, null, null);
        method.visitCode();
        Label normal = new Label();
        method.visitFieldInsn(Opcodes.GETSTATIC, TILES, "mode", "I");
        method.visitInsn(Opcodes.ICONST_1);
        method.visitJumpInsn(Opcodes.IF_ICMPNE, normal);
        method.visitInsn(Opcodes.RETURN);
        method.visitLabel(normal);
        method.visitTypeInsn(Opcodes.NEW, TESR);
        method.visitInsn(Opcodes.DUP);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, TESR, "<init>", "()V", false);
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitVarInsn(Opcodes.DLOAD, 2);
        method.visitVarInsn(Opcodes.DLOAD, 4);
        method.visitVarInsn(Opcodes.DLOAD, 6);
        method.visitVarInsn(Opcodes.FLOAD, 8);
        method.visitVarInsn(Opcodes.ILOAD, 9);
        method.visitVarInsn(Opcodes.FLOAD, 10);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TESR, rendererName, TILE_DESCRIPTOR, false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0); method.visitEnd(); writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] tileRendererFixture(String name) {
        ClassWriter writer = fixture(TESR);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "mode", "I", null, null).visitEnd();
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, name, TILE_DESCRIPTOR, null, null);
        method.visitCode();
        Label normal = new Label();
        method.visitFieldInsn(Opcodes.GETSTATIC, TESR, "mode", "I");
        method.visitInsn(Opcodes.ICONST_2);
        method.visitJumpInsn(Opcodes.IF_ICMPNE, normal);
        method.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException");
        method.visitInsn(Opcodes.DUP);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "()V", false);
        method.visitInsn(Opcodes.ATHROW);
        method.visitLabel(normal); method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0); method.visitEnd(); writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] tileHooksFixture() {
        ClassWriter writer = fixture(HOOKS);
        String[] names = {"beginTile", "activateTile", "endTile"};
        String[] descriptors = {"(Ljava/lang/Object;DDDI)V", "()V", "()V"};
        String[] counters = {"begins", "activations", "ends"};
        for (int i = 0; i < names.length; i++) {
            writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, counters[i], "I", null, null).visitEnd();
            MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, names[i], descriptors[i], null, null);
            method.visitCode();
            method.visitFieldInsn(Opcodes.GETSTATIC, HOOKS, counters[i], "I");
            method.visitInsn(Opcodes.ICONST_1); method.visitInsn(Opcodes.IADD);
            method.visitFieldInsn(Opcodes.PUTSTATIC, HOOKS, counters[i], "I");
            method.visitInsn(Opcodes.RETURN); method.visitMaxs(0, 0); method.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] hooksFixture() {
        ClassWriter writer = fixture(HOOKS);
        String[] names = { "beginLiving", "activateLiving", "endLiving" };
        String[] descriptors = { "(Ljava/lang/Object;)V", "()V", "()V" };
        String[] counters = { "begins", "activations", "ends" };
        for (int i = 0; i < names.length; i++) {
            writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, counters[i], "I", null, null).visitEnd();
            MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    names[i], descriptors[i], null, null);
            method.visitCode();
            method.visitFieldInsn(Opcodes.GETSTATIC, HOOKS, counters[i], "I");
            method.visitInsn(Opcodes.ICONST_1);
            method.visitInsn(Opcodes.IADD);
            method.visitFieldInsn(Opcodes.PUTSTATIC, HOOKS, counters[i], "I");
            method.visitInsn(Opcodes.RETURN);
            method.visitMaxs(0, 0);
            method.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static final class FixtureLoader extends ClassLoader {
        private final Map<String, byte[]> definitions = new HashMap<>();

        void add(String name, byte[] bytes) { definitions.put(name.replace('/', '.'), bytes); }

        @Override
        protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!definitions.containsKey(name)) { return super.loadClass(name, resolve); }
            Class<?> type = findLoadedClass(name);
            if (type == null) {
                byte[] bytes = definitions.get(name);
                type = defineClass(name, bytes, 0, bytes.length);
            }
            if (resolve) { resolveClass(type); }
            return type;
        }
    }
}
