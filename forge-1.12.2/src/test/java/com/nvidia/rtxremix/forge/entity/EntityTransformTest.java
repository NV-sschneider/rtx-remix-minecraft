/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

import org.junit.Test;
import org.lwjgl.util.vector.Matrix4f;
import org.lwjgl.util.vector.Vector3f;
import static org.junit.Assert.assertArrayEquals;

public class EntityTransformTest {
    @Test
    public void removesCameraMotionAndRestoresWorldOrigin() {
        Matrix4f view = new Matrix4f();
        view.rotate(0.6f, new Vector3f(0, 1, 0));
        view.translate(new Vector3f(-2, -3, -4));
        Matrix4f local = new Matrix4f();
        local.translate(new Vector3f(5, 6, 7));
        local.scale(new Vector3f(-2, -3, 4));
        float[] actual = new float[12];
        EntityTransform.world(Matrix4f.invert(view, null), Matrix4f.mul(view, local, null),
                new Matrix4f(), 100, 200, 300, actual);
        assertArrayEquals(new float[] {-2, 0, 0, 105, 0, -3, 0, 206, 0, 0, 4, 307}, actual, 0.0001f);
    }

    @Test
    public void preservesChildRotationAndNonuniformScale() {
        Matrix4f local = new Matrix4f();
        local.translate(new Vector3f(2, 3, 4));
        local.rotate((float) (Math.PI / 2), new Vector3f(0, 0, 1));
        local.scale(new Vector3f(2, 3, 4));
        float[] actual = new float[12];
        EntityTransform.world(new Matrix4f(), local, new Matrix4f(), 0, 0, 0, actual);
        assertArrayEquals(new float[] {0, -3, 0, 2, 2, 0, 0, 3, 0, 0, 4, 4}, actual, 0.0001f);
    }
}
