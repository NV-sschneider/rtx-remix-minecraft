/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.lwjgl.util.vector.Matrix4f;
import org.lwjgl.util.vector.Vector3f;
import org.lwjgl.util.vector.Vector4f;
import static org.junit.Assert.*;

public class FirstPersonTransformTest {
    @Test public void observedHandProjectionSurvivesWorldFovBobbingAndCameraRotation() {
        Matrix4f view = new Matrix4f();
        view.rotate(0.8f, new Vector3f(0, 1, 0));
        view.rotate(-0.3f, new Vector3f(1, 0, 0));
        view.translate(new Vector3f(0.2f, -1.7f, 0.1f));
        Matrix4f inverse = Matrix4f.invert(view, null);
        Matrix4f worldProjection = projection(105, 1.7f), handProjection = projection(70, 1.7f);
        worldProjection.m20 = 0.02f; worldProjection.m21 = -0.01f;
        handProjection.m20 = -0.04f; handProjection.m21 = 0.03f;
        Matrix4f actualHand = new Matrix4f();
        actualHand.translate(new Vector3f(0.2f, -0.3f, -1.0f));
        actualHand.rotate(0.13f, new Vector3f(0, 0, 1));
        Matrix4f root = new Matrix4f();
        assertTrue(FirstPersonTransform.root(inverse, worldProjection, handProjection, new Matrix4f(), root));
        Matrix4f captured = Matrix4f.mul(root, actualHand, null);
        for (Vector4f vertex : new Vector4f[] {new Vector4f(0, 0, 0, 1), new Vector4f(0.2f, 0.1f, -0.2f, 1)}) {
            Vector4f vanilla = Matrix4f.transform(handProjection, Matrix4f.transform(actualHand, vertex, null), null);
            Vector4f remix = Matrix4f.transform(worldProjection,
                    Matrix4f.transform(view, Matrix4f.transform(captured, vertex, null), null), null);
            assertEquals(vanilla.x / vanilla.w, remix.x / remix.w, 0.00001f);
            assertEquals(vanilla.y / vanilla.w, remix.y / remix.w, 0.00001f);
            assertEquals(FirstPersonTransform.SCALE * vanilla.w, remix.w, 0.00001f);
        }
    }

    @Test public void invalidProjectionIsRejectedBeforePublishingATransform() {
        Matrix4f world = projection(90, 1), hand = projection(70, 1), output = new Matrix4f();
        world.m00 = 0;
        assertFalse(FirstPersonTransform.root(new Matrix4f(), world, hand, new Matrix4f(), output));
        world.m00 = 1; hand.m11 = Float.NaN;
        assertFalse(FirstPersonTransform.root(new Matrix4f(), world, hand, new Matrix4f(), output));
        assertEquals(1, output.m00, 0);
    }

    @Test public void ownerCategoriesUseIdentityAndDoNotMutateStoredLookupKeys() {
        Map<CaptureOwnerKey, String> owners = new HashMap<>();
        Object first = new String("player"), second = new String("player");
        owners.put(new CaptureOwnerKey().set(first, 1), "body");
        owners.put(new CaptureOwnerKey().set(first, 2), "hand");
        owners.put(new CaptureOwnerKey().set(second, 1), "other");
        CaptureOwnerKey probe = new CaptureOwnerKey();
        assertEquals("body", owners.get(probe.set(first, 1)));
        assertEquals("hand", owners.get(probe.set(first, 2)));
        assertEquals("other", owners.get(probe.set(second, 1)));
        owners.remove(probe.set(first, 2));
        assertEquals("body", owners.get(probe.set(first, 1)));
        assertEquals(2, owners.size());
    }

    private static Matrix4f projection(float fov, float aspect) {
        Matrix4f result = new Matrix4f(); result.setZero();
        result.m11 = 1 / (float) Math.tan(Math.toRadians(fov) / 2);
        result.m00 = result.m11 / aspect;
        result.m22 = -1.0001f; result.m23 = -1; result.m32 = -0.10001f;
        return result;
    }
}
