/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.List;
import net.minecraft.client.renderer.texture.ITextureObject;
import org.junit.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Matrix4f;
import static org.junit.Assert.*;

/** Exercises the actual observer without a GL context: empty draws must never query native state. */
public class EntityVboDrawTest {
    @Test public void emptyRootRetainsChildrenOtherOwnersAndTheActiveScopeWithoutNativeWork() throws Exception {
        try (Capture capture = new Capture()) {
            CapturedEntityPart child = capture.add(capture.owner);
            long fingerprint = capture.triangles.fingerprint();
            long draws = EntityCaptureHooks.vboDrawCount(), readbacks = EntityCaptureHooks.vboReadbackCount();
            long bytes = EntityCaptureHooks.vboCacheBytes();
            EntityCaptureHooks.captureVboTriangles(GL11.GL_TRIANGLES, 0, 0, new Object(), new Object());
            // No geometry also means that a VBO snapshot or group metadata is unnecessary.
            EntityCaptureHooks.captureVboTriangles(GL11.GL_TRIANGLES, 0, 0, null, null);
            assertSame(capture.owner, capture.scope.owner());
            assertEquals(3, capture.parts.size()); assertSame(child, capture.parts.get(2));
            assertEquals(9, capture.triangles.count()); assertEquals(fingerprint, capture.triangles.fingerprint());
            assertEquals(0, EntityCaptureHooks.unsupportedTriangleDraws());
            assertEquals(0, EntityCaptureHooks.rejectedTriangleDraws());
            assertEquals(draws, EntityCaptureHooks.vboDrawCount());
            assertEquals(readbacks, EntityCaptureHooks.vboReadbackCount());
            assertEquals(bytes, EntityCaptureHooks.vboCacheBytes());
            assertNull(get("failure"));
            capture.add(capture.scope.owner());
            assertEquals(4, capture.parts.size()); assertEquals(12, capture.triangles.count());
            assertEquals(4, EntityCaptureHooks.finishFrame().size());
        }
    }

    @Test public void malformedNonemptyDrawsStillRejectOnlyTheirOwnersPartialCapture() throws Exception {
        int[][] draws = {
            {GL11.GL_TRIANGLES, 0, -3}, {GL11.GL_TRIANGLES, 0, 1},
            {GL11.GL_TRIANGLES, 1, 3}, {GL11.GL_QUADS, 0, 3},
            {GL11.GL_TRIANGLES, 0, 3}, {GL11.GL_TRIANGLES, 0, 3},
            {GL11.GL_TRIANGLES, 0, EntityTriangles.MAX_GROUP_VERTICES + 3}
        };
        for (int i = 0; i < draws.length; i++) {
            try (Capture capture = new Capture()) {
                CapturedEntityPart rejected = capture.parts.get(1);
                int[] draw = draws[i];
                EntityCaptureHooks.captureVboTriangles(draw[0], draw[1], draw[2],
                        i == 4 ? null : new Object(), i == 5 ? null : new Object());
                assertNull(capture.scope.owner()); assertNull(rejected.owner);
                assertEquals(1, capture.parts.size()); assertSame(capture.previous, capture.parts.get(0));
                assertEquals(3, capture.triangles.count()); assertNotNull(capture.previous.owner);
                assertEquals(1, EntityCaptureHooks.unsupportedTriangleDraws());
                assertEquals(1, EntityCaptureHooks.rejectedTriangleDraws());
                assertNull(get("failure"));
            }
        }
    }

    private static final class Capture implements AutoCloseable {
        final Object owner = new Object();
        final ModelCaptureScope scope;
        final EntityTriangles triangles;
        final List<CapturedEntityPart> parts;
        final CapturedEntityPart previous;
        final int warnings;

        @SuppressWarnings("unchecked")
        Capture() throws Exception {
            EntityCaptureHooks.discard();
            scope = (ModelCaptureScope) get("SCOPE"); triangles = (EntityTriangles) get("TRIANGLES");
            parts = (List<CapturedEntityPart>) get("PARTS"); warnings = (Integer) get("triangleWarnings");
            // Logging GL state is covered live; these tests isolate owner rollback without native calls.
            set("triangleWarnings", 16);
            previous = add(new Object());
            set("ownerStart", parts.size()); set("ownerTriangleStart", triangles.count());
            assertTrue(scope.enter(owner, true)); add(owner);
            set("armed", true);
            set("texture", Proxy.newProxyInstance(ITextureObject.class.getClassLoader(),
                    new Class<?>[] {ITextureObject.class}, (proxy, method, args) -> {
                        throw new AssertionError("A rejected or empty draw must not inspect a texture");
                    }));
        }
        CapturedEntityPart add(Object partOwner) {
            ByteBuffer source = EntityMesh.direct(3 * EntityVboCache.STRIDE);
            for (int i = 0; i < 3; i++) source.putFloat(i == 1 ? 1 : 0).putFloat(i == 2 ? 1 : 0).putFloat(0)
                    .putFloat(0).putFloat(0).put((byte) 0).put((byte) 0).put((byte) 127).put((byte) 0);
            source.flip(); int first = triangles.count();
            assertTrue(triangles.appendVbo(source, 3, -1, new Matrix4f()));
            CapturedEntityPart part = new CapturedEntityPart(); part.owner = partOwner;
            part.triangles = triangles; part.firstVertex = first; part.vertexCount = 3;
            parts.add(part); return part;
        }
        @Override public void close() throws Exception {
            EntityCaptureHooks.discard(); set("triangleWarnings", warnings);
        }
    }
    private static Object get(String name) throws Exception { return field(name).get(null); }
    private static void set(String name, Object value) throws Exception { field(name).set(null, value); }
    private static Field field(String name) throws Exception {
        Field field = EntityCaptureHooks.class.getDeclaredField(name); field.setAccessible(true); return field;
    }
}
