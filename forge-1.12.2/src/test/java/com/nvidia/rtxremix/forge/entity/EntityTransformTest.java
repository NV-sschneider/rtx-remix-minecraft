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

    @Test public void viewSnapshotBeforePreEventPreservesItsWholeBodyMotion() {
        Matrix4f view = new Matrix4f();
        view.rotate(0.4f, new Vector3f(0, 1, 0));
        Matrix4f eventTransform = new Matrix4f();
        eventTransform.translate(new Vector3f(2, 3, 4));
        eventTransform.rotate((float) (Math.PI / 2), new Vector3f(0, 0, 1));
        Matrix4f part = new Matrix4f();
        part.translate(new Vector3f(1, 0, 0));
        Matrix4f actualDraw = Matrix4f.mul(Matrix4f.mul(view, eventTransform, null), part, null);
        float[] actual = new float[12];
        EntityTransform.world(Matrix4f.invert(view, null), actualDraw, new Matrix4f(), 100, 200, 300, actual);
        assertArrayEquals(new float[] {0, -1, 0, 102, 1, 0, 0, 204, 0, 0, 1, 304}, actual, 0.0001f);
    }

    @Test public void tileDrawCoordinatesRestoreWorldPositionAcrossCameraMovement() {
        for (float cameraX : new float[] {-120.25f, -118.75f}) {
            float tileX = -116, tileY = 65, tileZ = 224;
            float drawX = tileX - cameraX, drawY = 1.5f, drawZ = -2.25f;
            Matrix4f view = new Matrix4f();
            view.rotate(0.45f, new Vector3f(0, 1, 0));
            Matrix4f local = new Matrix4f();
            local.translate(new Vector3f(drawX + 0.5f, drawY + 0.75f, drawZ + 0.5f));
            local.scale(new Vector3f(1, -1, -1));
            float[] actual = new float[12];
            EntityTransform.world(Matrix4f.invert(view, null), Matrix4f.mul(view, local, null),
                    new Matrix4f(), tileX - drawX, tileY - drawY, tileZ - drawZ, actual);
            assertArrayEquals(new float[] {1, 0, 0, -115.5f, 0, -1, 0, 65.75f, 0, 0, -1, 224.5f}, actual, 0.0001f);
        }
    }
}
