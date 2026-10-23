/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

import com.nvidia.rtxremix.MeshInfoSurfaceTriangles;
import java.nio.ByteBuffer;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.model.ModelBox;
import net.minecraft.client.model.ModelRenderer;
import org.junit.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import static org.junit.Assert.*;

public class ModelBoxFacesTest {
    private static final String MUTATED = "goblinbob/mobends/core/client/model/MutatedBox";
    private static final String BOX = "net/minecraft/client/model/ModelBox";
    private static final String RENDERER = "net/minecraft/client/model/ModelRenderer";

    @Test public void optionalFaceContractPreservesSelectedUvsAndNormals() throws Exception {
        ModelRenderer ordinary = part();
        ordinary.addBox(0, 0, 0, 2, 4, 6);
        EntityMesh baseline = new EntityMesh();
        assertTrue(baseline.prepare(ordinary, 0.5f, 3));
        ByteBuffer expected = baseline.info(1).surfaces[0].vertexBuffer;

        ModelRenderer selected = part();
        selected.cubeList.add(fixture(MUTATED, true, selected, new boolean[] {false, true, false, false, true, false}));
        EntityMesh mesh = new EntityMesh();
        assertTrue(mesh.prepare(selected, 0.5f, 3));
        MeshInfoSurfaceTriangles surface = mesh.info(2).surfaces[0];
        assertEquals(8, surface.vertexCount);
        assertEquals(12, surface.indexCount);
        for (int face = 0; face < 2; face++) {
            int originalFace = face == 0 ? 1 : 4;
            for (int offset = 0; offset < 4 * 64; offset++) {
                assertEquals(expected.get(originalFace * 4 * 64 + offset), surface.vertexBuffer.get(face * 4 * 64 + offset));
            }
        }
    }

    @Test public void hiddenFacesStayAbsentAndUnknownSubclassIsNotGuessed() throws Exception {
        ModelRenderer hidden = part();
        hidden.cubeList.add(fixture(MUTATED, true, hidden, new boolean[6]));
        assertFalse(new EntityMesh().prepare(hidden, 1, 1));
        ModelRenderer unknown = part();
        unknown.cubeList.add(fixture("example/OtherBox", true, unknown,
                new boolean[] {true, true, true, true, true, true}));
        assertFalse(new EntityMesh().prepare(unknown, 1, 1));
    }

    @Test public void incompatibleNamedAdapterFailsExplicitly() throws Exception {
        ModelRenderer part = part();
        part.cubeList.add(fixture(MUTATED, false, part, new boolean[6]));
        try {
            new EntityMesh().prepare(part, 1, 1);
            fail("An incompatible optional API must not silently render all faces");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("face visibility contract"));
        }
    }

    @Test public void meshCacheSeparatesDisplayListRevisionsWithSamePartAndTexture() {
        ModelRenderer part = part();
        EntityTextures.Asset texture = new EntityTextures.Asset(new Object(), 1, 2, 3, 4);
        EntityScene.MeshKey original = new EntityScene.MeshKey();
        EntityScene.MeshKey probe = new EntityScene.MeshKey();
        original.set(part, 10, 0.5f, texture);
        probe.set(part, 10, 0.5f, texture);
        assertEquals(original, probe);
        assertEquals(original.hashCode(), probe.hashCode());
        probe.set(part, 11, 0.5f, texture);
        assertNotEquals(original, probe);
        probe.set(part, 10, 1, texture);
        assertNotEquals(original, probe);
        probe.set(part(), 10, 0.5f, texture);
        assertNotEquals(original, probe);
    }

    private static ModelRenderer part() { return new ModelRenderer(new ModelBase() {}, 0, 0); }

    /** Synthetic public API contract only; no optional mod classes or renderer code are bundled. */
    private static ModelBox fixture(String name, boolean withVisibility, ModelRenderer part, boolean[] visible) throws Exception {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, BOX, null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "visible", "[Z", null, null).visitEnd();
        MethodVisitor ctor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(L" + RENDERER + ";[Z)V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitVarInsn(Opcodes.ALOAD, 1);
        ctor.visitInsn(Opcodes.ICONST_0); ctor.visitInsn(Opcodes.ICONST_0);
        ctor.visitInsn(Opcodes.FCONST_0); ctor.visitInsn(Opcodes.FCONST_0); ctor.visitInsn(Opcodes.FCONST_0);
        ctor.visitInsn(Opcodes.ICONST_2); ctor.visitInsn(Opcodes.ICONST_4); ctor.visitIntInsn(Opcodes.BIPUSH, 6);
        ctor.visitInsn(Opcodes.FCONST_0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, BOX, "<init>", "(L" + RENDERER + ";IIFFFIIIF)V", false);
        ctor.visitVarInsn(Opcodes.ALOAD, 0); ctor.visitVarInsn(Opcodes.ALOAD, 2);
        ctor.visitFieldInsn(Opcodes.PUTFIELD, name, "visible", "[Z");
        ctor.visitInsn(Opcodes.RETURN); ctor.visitMaxs(0, 0); ctor.visitEnd();
        if (withVisibility) {
            MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "isFaceVisible", "(I)Z", null, null);
            method.visitCode();
            method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitFieldInsn(Opcodes.GETFIELD, name, "visible", "[Z");
            method.visitVarInsn(Opcodes.ILOAD, 1); method.visitInsn(Opcodes.BALOAD);
            method.visitInsn(Opcodes.IRETURN); method.visitMaxs(0, 0); method.visitEnd();
        }
        writer.visitEnd();
        final byte[] bytes = writer.toByteArray();
        Class<?> type = new ClassLoader(ModelBoxFacesTest.class.getClassLoader()) {
            Class<?> fixtureType() { return defineClass(null, bytes, 0, bytes.length); }
        }.fixtureType();
        return (ModelBox) type.getConstructor(ModelRenderer.class, boolean[].class).newInstance(part, visible);
    }
}
