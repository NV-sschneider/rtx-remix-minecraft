/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

import com.nvidia.rtxremix.CameraInfoParameterizedEXT;
import org.junit.Test;
import static org.junit.Assert.*;

public class ViewModelCameraTest {
    @Test public void anchorMatchesTheNativeFloatViewRoundTripAtRotatedFarCoordinates() {
        ViewModelCamera capture = new ViewModelCamera();
        CameraInfoParameterizedEXT camera = rotated(15000); capture.prepare(camera);
        float[] anchor = capture.anchor();
        assertEquals(14999.9990234375f, anchor[3], 0);
        assertEquals(64.00003051757812f, anchor[7], 0); assertEquals(-15000, anchor[11], 0);
        assertEquals(1, anchor[0], 0); assertEquals(1, anchor[5], 0); assertEquals(1, anchor[10], 0);
        assertEquals(0, anchor[1], 0); assertEquals(0, anchor[2], 0); assertEquals(0, anchor[4], 0);
        assertEquals(0, anchor[6], 0); assertEquals(0, anchor[8], 0); assertEquals(0, anchor[9], 0);
        capture.prepare(rotated(-15000));
        assertEquals(-14999.9990234375f, anchor[3], 0);
        assertEquals(63.999969482421875f, anchor[7], 0); assertEquals(15000, anchor[11], 0);
    }

    @Test public void cameraTranslationCannotEnterPoseVerticesAndOrientationIsNormalizedOnce() {
        ViewModelCamera capture = new ViewModelCamera();
        float[] local = {0.1f, 0, 0, 0.02f, 0, 0.1f, 0, -0.03f, 0, 0, 0.1f, -0.1f};
        float[] near = new float[12], far = new float[12];
        CameraInfoParameterizedEXT camera = rotated(0); capture.prepare(camera); capture.pose(local, near);
        camera.position.x = 15000; camera.position.z = -15000; capture.prepare(camera); capture.pose(local, far);
        assertArrayEquals(near, far, 0);
        assertEquals(camera.right.x * local[3] + camera.up.x * local[7] - camera.forward.x * local[11], far[3], 0.00000001f);
        camera.right.x *= 2; camera.right.y *= 2; camera.right.z *= 2;
        camera.up.x *= 2; camera.up.y *= 2; camera.up.z *= 2;
        camera.forward.x *= 2; camera.forward.y *= 2; camera.forward.z *= 2;
        capture.prepare(camera); capture.pose(local, far); assertArrayEquals(near, far, 0);
        float before = far[3]; local[3] += 0.000004f; capture.pose(local, far);
        assertTrue(far[3] > before); assertEquals(15000 + before, 15000 + far[3], 0);
    }

    private static CameraInfoParameterizedEXT rotated(float origin) {
        float cy = (float) Math.cos(0.8), sy = (float) Math.sin(0.8), cp = (float) Math.cos(0.3), sp = (float) Math.sin(0.3);
        return CameraInfoParameterizedEXT.builder().position(origin, 64, -origin)
                .forward(-sy * cp, sp, -cy * cp).right(cy, 0, -sy).up(sy * sp, cp, cy * sp).build();
    }
}
