/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.jar.JarFile;
import javax.imageio.ImageIO;
import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import static org.junit.Assert.*;

/** Audits optional installed artifacts without linking or copying their implementation. */
public class RlcraftEntityContractTest {
    private static final String OBJ = "com/lycanitesmobs/client/obj/";

    @Test public void installedRahovartOpaqueBaseUsesTheModelsSharedAlphaBlendPath() throws Exception {
        String path = System.getProperty("rtxremix.rlcraftMods"); Assume.assumeNotNull(path);
        try (JarFile jar = new JarFile(new File(path, "lycanitesmobs-1.12.2-2.0.8.9.jar"))) {
            for (String suffix : new String[] { "", "_dark", "_lux" }) {
                try (InputStream stream = jar.getInputStream(jar.getJarEntry(
                        "assets/lycanitesmobs/textures/entity/rahovart" + suffix + ".png"))) {
                    BufferedImage image = ImageIO.read(stream); assertNotNull(image);
                    int[] pixels = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
                    int alpha = 255; for (int pixel : pixels) alpha &= pixel >>> 24;
                    assertEquals("Boss base texture is fully opaque: " + suffix, 255, alpha);
                }
            }
            for (String type : new String[] { "ModelObjOld", "creature/ModelRahovart" })
                for (MethodNode method : read(jar, "com/lycanitesmobs/client/model/" + type).methods)
                    assertFalse("Boss inherits ordinary part color", method.name.equals("getBasePartColor") || method.name.equals("getPartColor"));
            int whiteColors = 0;
            for (MethodNode method : read(jar, "com/lycanitesmobs/client/model/ModelCustom").methods) {
                if (!method.name.equals("getBasePartColor")) continue;
                for (AbstractInsnNode node : method.instructions.toArray()) {
                    if (!(node instanceof MethodInsnNode)) continue;
                    MethodInsnNode call = (MethodInsnNode) node;
                    if (!call.owner.equals("javax/vecmath/Vector4f") || !call.name.equals("<init>")) continue;
                    assertEquals("(FFFF)V", call.desc);
                    AbstractInsnNode component = call;
                    for (int i = 0; i < 4; i++) { component = previous(component); assertEquals(Opcodes.FCONST_1, component.getOpcode()); }
                    whiteColors++;
                }
            }
            assertEquals("Base model passes fully opaque white to its VBO renderer", 1, whiteColors);
            boolean enablesBlend = false, sourceAlpha = false, inverseSourceAlpha = false;
            for (MethodNode method : read(jar, "com/lycanitesmobs/client/model/ModelObjOld").methods) {
                if (!method.name.equals("onRenderStart")) continue;
                for (AbstractInsnNode node : method.instructions.toArray()) {
                    if (node instanceof MethodInsnNode) {
                        MethodInsnNode call = (MethodInsnNode) node;
                        enablesBlend |= call.owner.equals("net/minecraft/client/renderer/GlStateManager") && call.name.equals("func_179147_l");
                    } else if (node instanceof FieldInsnNode) {
                        FieldInsnNode field = (FieldInsnNode) node;
                        sourceAlpha |= field.name.equals("SRC_ALPHA"); inverseSourceAlpha |= field.name.equals("ONE_MINUS_SRC_ALPHA");
                    }
                }
            }
            assertTrue("Shared OBJ model enables ordinary alpha blending even for its opaque boss texture", enablesBlend && sourceAlpha && inverseSourceAlpha);
        }
    }

    @Test public void installedDragonAndBossCullingMatchesTheOffscreenFixture() throws Exception {
        String path = System.getProperty("rtxremix.rlcraftMods"); Assume.assumeNotNull(path);
        try (JarFile jar = new JarFile(new File(path, "iceandfire-1.7.1-1.12.2.jar"))) {
            for (String type : new String[] { "EntityFireDragon", "EntityIceDragon" }) {
                boolean ignoresFrustum = false;
                for (MethodNode method : read(jar, "com/github/alexthe666/iceandfire/entity/" + type).methods) {
                    if (!method.name.equals("<init>")) continue;
                    for (AbstractInsnNode node : method.instructions.toArray()) {
                        if (!(node instanceof FieldInsnNode)) continue;
                        FieldInsnNode field = (FieldInsnNode) node;
                        if (field.getOpcode() == Opcodes.PUTFIELD && field.name.equals("field_70158_ak")
                                && field.desc.equals("Z") && previous(field).getOpcode() == Opcodes.ICONST_1)
                            ignoresFrustum = true;
                    }
                }
                assertTrue(type + " stays in vanilla's normal draw pass behind the camera", ignoresFrustum);
            }
        }
        try (JarFile jar = new JarFile(new File(path, "RLMixins-1.2.15.jar"))) {
            int boundedBoxes = 0, replacements = 0;
            for (MethodNode method : read(jar, "rlmixins/mixin/lycanites/boundingbox/EntityRahovartRenderMixin").methods) {
                if (!method.name.equals("rlmixins_entityRahovart_getRenderBoundingBox")) continue;
                for (AbstractInsnNode node : method.instructions.toArray()) {
                    if (!(node instanceof MethodInsnNode)) continue;
                    MethodInsnNode call = (MethodInsnNode) node;
                    if (call.owner.equals("net/minecraft/util/math/AxisAlignedBB") && call.name.equals("func_72314_b")) {
                        assertEquals("(DDD)Lnet/minecraft/util/math/AxisAlignedBB;", call.desc);
                        AbstractInsnNode z = previous(call), y = previous(z), x = previous(y);
                        assertEquals(7.5D, ((LdcInsnNode) x).cst); assertEquals(Opcodes.DCONST_1, y.getOpcode());
                        assertEquals(7.5D, ((LdcInsnNode) z).cst); boundedBoxes++;
                    }
                    if (call.owner.equals("org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable")
                            && call.name.equals("setReturnValue")) replacements++;
                }
            }
            assertEquals("The installed patch bounds the boss box instead of growing it by 200 blocks", 1, boundedBoxes);
            assertEquals("The patch replaces vanilla's returned render box", 1, replacements);
        }
    }

    @Test public void installedRahovartHasAnEmptyDefaultGroupThatReachesTheActualVboDraw() throws Exception {
        String path = System.getProperty("rtxremix.rlcraftMods"); Assume.assumeNotNull(path);
        try (JarFile jar = new JarFile(new File(path, "lycanitesmobs-1.12.2-2.0.8.9.jar"))) {
            boolean defaultGroup = false;
            for (MethodNode method : read(jar, OBJ + "OBJLoader").methods) {
                if (!method.name.equals("loadModel")) continue;
                for (AbstractInsnNode node : method.instructions.toArray()) {
                    if (!(node instanceof MethodInsnNode)) continue;
                    MethodInsnNode call = (MethodInsnNode) node;
                    if (call.owner.equals(OBJ + "ObjObject") && call.name.equals("<init>")) {
                        AbstractInsnNode name = previous(call);
                        if (name instanceof LdcInsnNode && "main".equals(((LdcInsnNode) name).cst)) defaultGroup = true;
                    }
                }
            }
            assertTrue("The loader adds its default main group", defaultGroup);
            boolean named = false; int unnamedFaces = 0, namedFaces = 0;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    jar.getInputStream(jar.getJarEntry("assets/lycanitesmobs/models/entity/rahovart.obj")), StandardCharsets.UTF_8))) {
                for (String line; (line = reader.readLine()) != null;) {
                    line = line.trim();
                    if (line.startsWith("o ") || line.startsWith("g ")) named = true;
                    if (line.startsWith("f ")) { if (named) namedFaces++; else unnamedFaces++; }
                }
            }
            assertEquals("The default group has no faces", 0, unnamedFaces);
            assertTrue("The same model has populated groups", namedFaces > 0);
            int draws = 0;
            for (MethodNode method : read(jar, OBJ + "VBOModel").methods) {
                if (!method.name.equals("renderGroupImpl")) continue;
                for (AbstractInsnNode node : method.instructions.toArray()) {
                    if (!(node instanceof MethodInsnNode)) continue;
                    MethodInsnNode call = (MethodInsnNode) node;
                    if (!call.owner.equals("org/lwjgl/opengl/GL11") || !call.name.equals("glDrawArrays")) continue;
                    assertEquals("(III)V", call.desc);
                    AbstractInsnNode length = previous(call); assertEquals(Opcodes.ARRAYLENGTH, length.getOpcode());
                    FieldInsnNode indices = (FieldInsnNode) previous(length);
                    assertEquals(OBJ + "Mesh", indices.owner); assertEquals("indices", indices.name); assertEquals("[I", indices.desc);
                    draws++;
                }
            }
            assertEquals("The real draw consumes the group count directly, including zero", 1, draws);
        }
    }
    private static AbstractInsnNode previous(AbstractInsnNode node) {
        do { node = node.getPrevious(); } while (node != null && node.getOpcode() < 0);
        return node;
    }
    private static ClassNode read(JarFile jar, String name) throws Exception {
        assertNotNull(jar.getJarEntry(name + ".class"));
        try (InputStream stream = jar.getInputStream(jar.getJarEntry(name + ".class"))) {
            ClassNode node = new ClassNode(Opcodes.ASM5); new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG); return node;
        }
    }
}
