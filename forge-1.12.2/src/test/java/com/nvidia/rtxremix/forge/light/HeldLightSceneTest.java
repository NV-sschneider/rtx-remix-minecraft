/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.light;

import com.nvidia.rtxremix.CameraInfoParameterizedEXT;
import com.nvidia.rtxremix.InstanceCategoryBit;
import com.nvidia.rtxremix.LightInfo;
import com.nvidia.rtxremix.forge.item.HeldItemLights;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.util.math.AxisAlignedBB;
import org.junit.Test;
import static org.junit.Assert.*;

public class HeldLightSceneTest {
    @Test public void bodyAndViewShareOneLightAndUnchangedLightsOnlyDraw() {
        Fake api = new Fake(); AtomicLong time = new AtomicLong(1);
        HeldLightScene scene = new HeldLightScene(api, (s, c, x, z) -> 0.72, time::get);
        HeldItemLights.Sample body = sample(new Object(), 0, 2), hand = sample(body.owner, 0, 0.1f);
        body.localFirstPerson = hand.localFirstPerson = true;
        hand.id = body.id; hand.category = InstanceCategoryBit.VIEW_MODEL;
        scene.sync(Arrays.asList(body, hand), (o, c, n) -> true, camera());
        assertEquals(1, scene.count()); assertEquals(1, scene.localCount()); assertEquals(1, api.creates);
        Snapshot light = api.lights.values().iterator().next();
        assertEquals(0.72f, light.z, 0.00001f); assertEquals(0, light.volumetric, 0);
        time.addAndGet(16666667); scene.sync(Arrays.asList(body, hand), (o, c, n) -> true, camera());
        assertEquals(1, api.creates); assertEquals(2, scene.drawCount());
        scene.close(); assertTrue(api.lights.isEmpty());
    }

    @Test public void unadmittedHousingCannotCreateOrKeepALight() {
        Fake api = new Fake(); HeldLightScene scene = new HeldLightScene(api, (s, c, x, z) -> 0.72, () -> 1);
        HeldItemLights.Sample sample = sample(new Object(), 0, 3);
        scene.sync(Collections.singletonList(sample), (o, c, n) -> false, camera()); assertEquals(0, scene.count());
        scene.sync(Collections.singletonList(sample), (o, c, n) -> true, camera()); assertEquals(1, scene.count());
        Snapshot light = api.lights.values().iterator().next(); assertEquals(3, light.z, 0); assertEquals(1, light.volumetric, 0);
        scene.sync(Collections.singletonList(sample), (o, c, n) -> false, camera()); assertEquals(0, scene.count());
        assertTrue(api.lights.isEmpty()); scene.close();
    }

    @Test public void firstPersonCollisionPullsInImmediatelyAndReleasesSmoothly() {
        Fake api = new Fake(); AtomicLong time = new AtomicLong(1); double[] limit = {0.72};
        HeldLightScene scene = new HeldLightScene(api, (s, c, x, z) -> limit[0], time::get);
        HeldItemLights.Sample sample = sample(new Object(), 0, 0.1f); sample.localFirstPerson = true;
        List<HeldItemLights.Sample> samples = Collections.singletonList(sample);
        scene.sync(samples, (o, c, n) -> true, camera());
        limit[0] = 0.1; time.addAndGet(16666667); scene.sync(samples, (o, c, n) -> true, camera());
        assertEquals(0.1f, api.lights.values().iterator().next().z, 0.00001f);
        limit[0] = 0.72; time.addAndGet(16666667); scene.sync(samples, (o, c, n) -> true, camera());
        assertEquals(0.15f, api.lights.values().iterator().next().z, 0.00001f);
        assertEquals(1, api.lights.size()); assertEquals(3, api.creates); scene.close();
    }

    @Test public void admissionIsClosestFirstBoundedAndHandsAreIndependent() {
        Fake api = new Fake(); HeldLightScene scene = new HeldLightScene(api, (s, c, x, z) -> 0.72, () -> 1);
        List<HeldItemLights.Sample> samples = new ArrayList<>();
        for (int i = 32; i >= 1; i--) samples.add(sample(new Object(), 0, i));
        HeldItemLights.Sample main = sample(new Object(), 0, 0.1f), off = sample(main.owner, 1, 0.1f);
        off.id = main.id; samples.add(main); samples.add(off);
        scene.sync(samples, (o, c, n) -> true, camera());
        assertEquals(HeldLightScene.MAX_LIGHTS, scene.count());
        assertTrue(scene.contains(main.id, 0)); assertTrue(scene.contains(main.id, 1));
        for (Snapshot light : api.lights.values()) assertTrue(light.z <= 14);
        scene.sync(Collections.emptyList(), (o, c, n) -> true, camera()); assertEquals(0, scene.count()); scene.close();
    }

    @Test public void failedDestroyRemainsOwnedAndCloseRetriesOnlyLiveHandles() {
        Fake api = new Fake(); HeldLightScene scene = new HeldLightScene(api, (s, c, x, z) -> 0.72, () -> 1);
        scene.sync(Arrays.asList(sample(new Object(), 0, 1), sample(new Object(), 0, 2)), (o, c, n) -> true, camera());
        api.failDestroy = api.lights.keySet().iterator().next();
        try { scene.close(); fail(); } catch (IllegalStateException expected) { }
        assertEquals(1, scene.count()); assertEquals(1, api.lights.size());
        scene.close(); scene.close(); assertTrue(api.lights.isEmpty());
    }

    @Test public void burningPolicyKeepsLocalToggleSeparateFromOtherHoldersAndElectricItems() {
        assertFalse(HeldLightState.isLit(true, true, false, false, false));
        assertTrue(HeldLightState.isLit(false, true, false, false, false));
        assertFalse(HeldLightState.isLit(false, false, true, true, false));
        assertTrue(HeldLightState.isLit(false, false, false, true, false));
        assertTrue(HeldLightState.isLit(false, false, true, true, true));
    }

    @Test public void sphereClearanceIntersectsExpandedBoxesIncludingParallelAndInsideCases() {
        AxisAlignedBB wall = new AxisAlignedBB(-1, -1, 0.5, 1, 1, 1.5);
        assertEquals(0.38, HeldLightPlacement.entry(0, 0, 0, 0, 1, 0.72, wall, 0.12), 0.000001);
        assertEquals(Double.POSITIVE_INFINITY, HeldLightPlacement.entry(2, 0, 0, 0, 1, 0.72, wall, 0.12), 0);
        assertEquals(Double.POSITIVE_INFINITY, HeldLightPlacement.entry(0, 2, 0, 0, 1, 0.72, wall, 0.12), 0);
        assertEquals(0, HeldLightPlacement.entry(0, 0, 0.6, 0, 1, 0.72, wall, 0.12), 0);
        assertEquals(0.3, HeldLightPlacement.advance(0, 0.72, 20), 0.000001);
        assertEquals(0, HeldLightPlacement.advance(0, 0.72, -1), 0);
    }

    private static CameraInfoParameterizedEXT camera() {
        CameraInfoParameterizedEXT camera = new CameraInfoParameterizedEXT(); camera.forward.x = camera.forward.y = 0; camera.forward.z = 1;
        camera.position.x = camera.position.y = camera.position.z = 0; return camera;
    }
    private static HeldItemLights.Sample sample(Object owner, int hand, float z) {
        HeldItemLights.Sample sample = new HeldItemLights.Sample(); sample.owner = owner; sample.id = UUID.randomUUID();
        sample.hand = hand; sample.z = z; sample.radius = 0.1f; sample.red = sample.green = sample.blue = 10; return sample;
    }
    private static final class Snapshot {
        final float x, y, z, volumetric;
        Snapshot(LightInfo info) { x = info.sphereExt.position.x; y = info.sphereExt.position.y; z = info.sphereExt.position.z; volumetric = info.sphereExt.volumetricRadianceScale; }
    }
    private static final class Fake implements HeldLightScene.Backend {
        final Map<Long, Snapshot> lights = new HashMap<>();
        final Set<Long> destroyed = new HashSet<>();
        long next = 1, failDestroy; int creates;
        public long create(LightInfo info) { long handle = next++; lights.put(handle, new Snapshot(info)); creates++; return handle; }
        public void destroy(long handle) {
            if (handle == failDestroy) { failDestroy = 0; throw new IllegalStateException("release"); }
            assertNotNull(lights.remove(handle)); assertTrue(destroyed.add(handle));
        }
        public void draw(long handle) { assertTrue(lights.containsKey(handle)); }
    }
}
