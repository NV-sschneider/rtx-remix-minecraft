/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TransformTest {
    @Test
    void componentQuaternionMatchesTheClientsRotationLibrary() {
        Quaternionf rotation = new Quaternionf().rotateXYZ(0.2f, -0.5f, 1.1f);
        Transform transform = Transform.fromTRS(3, 4, 5,
                rotation.x, rotation.y, rotation.z, rotation.w, 2);
        for (Vector3f point : new Vector3f[] {
                new Vector3f(0, 0, 0), new Vector3f(1, 0, 0),
                new Vector3f(0, 1, 0), new Vector3f(0, 0, 1), new Vector3f(1, 2, -3)}) {
            Vector3f expected = new Vector3f(point).mul(2).rotate(rotation).add(3, 4, 5);
            assertEquals(expected.x, apply(transform, 0, point), 0.00001f);
            assertEquals(expected.y, apply(transform, 1, point), 0.00001f);
            assertEquals(expected.z, apply(transform, 2, point), 0.00001f);
        }
    }

    private static float apply(Transform transform, int row, Vector3f point) {
        return transform.get(row, 0) * point.x + transform.get(row, 1) * point.y
                + transform.get(row, 2) * point.z + transform.get(row, 3);
    }
}
