/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

import com.nvidia.rtxremix.CameraInfoParameterizedEXT;
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
        Matrix4f correction = new Matrix4f();
        assertTrue(FirstPersonTransform.root(worldProjection, handProjection, correction));
        float[] local = new float[12];
        FirstPersonTransform.capture(correction, actualHand, new Matrix4f(), local);
        Matrix4f captured = Matrix4f.mul(inverse, matrix(local), null);
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
        assertFalse(FirstPersonTransform.root(world, hand, output));
        world.m00 = 1; hand.m11 = Float.NaN;
        assertFalse(FirstPersonTransform.root(world, hand, output));
        assertEquals(1, output.m00, 0);
    }

    @Test public void worldTravelNeverQuantizesOrOffsetsTheRetainedHandPose() {
        CameraInfoParameterizedEXT world = new CameraInfoParameterizedEXT();
        ViewModelCamera viewModel = new ViewModelCamera();
        float[] oriented = new float[12], expectedOriented = null;
        Matrix4f worldProjection = projection(95, 1.7f), handProjection = projection(70, 1.7f);
        world.fovYInDegrees = 95; world.aspect = 1.7f; world.nearPlane = 0.05f; world.farPlane = 1024;
        Matrix4f correction = new Matrix4f(), hand = new Matrix4f();
        hand.translate(new Vector3f(0.27f, -0.38f, -0.9f));
        hand.rotate(0.07f, new Vector3f(0, 0, 1));
        float[] expected = new float[12], actual = new float[12];
        assertTrue(FirstPersonTransform.root(worldProjection, handProjection, correction));
        FirstPersonTransform.capture(correction, hand, new Matrix4f(), expected);
        for (float origin : new float[] {0, 4096.125f, -16384.25f, 29999984}) {
            world.position.x = origin; world.position.y = 170.5f; world.position.z = -origin;
            world.forward.x = 0.6f; world.forward.z = -0.8f;
            world.right.x = 0.8f; world.right.z = 0.6f;
            viewModel.prepare(world);
            FirstPersonTransform.capture(correction, hand, new Matrix4f(), actual);
            assertArrayEquals(expected, actual, 0);
            viewModel.pose(actual, oriented);
            if (expectedOriented == null) expectedOriented = oriented.clone();
            assertArrayEquals(expectedOriented, oriented, 0);
            assertEquals(1, viewModel.anchor()[0], 0); assertEquals(0, viewModel.anchor()[1], 0);
            assertEquals(1, viewModel.anchor()[5], 0); assertEquals(1, viewModel.anchor()[10], 0);
            assertEquals("view-model setup cannot move the world camera", origin, world.position.x, 0);
            Vector4f clip = Matrix4f.transform(worldProjection, Matrix4f.transform(matrix(actual), new Vector4f(0, 0, 0, 1), null), null);
            Vector4f vanilla = Matrix4f.transform(handProjection, Matrix4f.transform(hand, new Vector4f(0, 0, 0, 1), null), null);
            assertEquals(vanilla.x / vanilla.w, clip.x / clip.w, 0.000001f);
            assertEquals(vanilla.y / vanilla.w, clip.y / clip.w, 0.000001f);
        }
        // Small real sway remains visible even where an absolute float translation rounds it away.
        hand.m30 += 0.0002f;
        FirstPersonTransform.capture(correction, hand, new Matrix4f(), actual);
        assertTrue(actual[3] > expected[3]);
        assertEquals(16384f + expected[3], 16384f + actual[3], 0);
    }

    @Test public void heldLightWorldPoseUsesTheOriginalCameraAndLeavesTheHandLocal() {
        Matrix4f inverse = new Matrix4f();
        inverse.rotate(0.9f, new Vector3f(0, 1, 0));
        inverse.rotate(-0.2f, new Vector3f(1, 0, 0));
        inverse.m30 = 0.1f; inverse.m31 = 1.62f; inverse.m32 = -0.1f;
        Matrix4f correction = new Matrix4f(), hand = new Matrix4f();
        hand.translate(new Vector3f(0.25f, -0.3f, -0.85f));
        hand.rotate(-0.12f, new Vector3f(0, 0, 1));
        assertTrue(FirstPersonTransform.root(projection(95, 1.7f), projection(70, 1.7f), correction));
        float[] local = new float[12], world = new float[12];
        FirstPersonTransform.capture(correction, hand, new Matrix4f(), local);
        float[] saved = local.clone();
        FirstPersonTransform.world(inverse, local, 8192.125, 64, -4096.5, world);
        assertArrayEquals(saved, local, 0);
        Vector4f point = new Vector4f(0.5f, 0.72f, 0.5f, 1);
        Vector4f expected = Matrix4f.transform(inverse, Matrix4f.transform(matrix(local), point, null), null);
        Vector4f actual = Matrix4f.transform(matrix(world), point, null);
        assertEquals((float) (8192.125 + expected.x), actual.x, 0.002f);
        assertEquals(64 + expected.y, actual.y, 0.00001f);
        assertEquals((float) (-4096.5 + expected.z), actual.z, 0.001f);
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

    private static Matrix4f matrix(float[] transform) {
        Matrix4f result = new Matrix4f();
        result.m00 = transform[0]; result.m10 = transform[1]; result.m20 = transform[2]; result.m30 = transform[3];
        result.m01 = transform[4]; result.m11 = transform[5]; result.m21 = transform[6]; result.m31 = transform[7];
        result.m02 = transform[8]; result.m12 = transform[9]; result.m22 = transform[10]; result.m32 = transform[11];
        return result;
    }
}
