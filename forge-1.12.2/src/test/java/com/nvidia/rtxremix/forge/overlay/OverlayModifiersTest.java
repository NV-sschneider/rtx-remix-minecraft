/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.overlay;

import org.junit.Test;
import org.lwjgl.input.Keyboard;
import static org.junit.Assert.*;

public class OverlayModifiersTest {
    @Test public void quickChordUsesEventOrderAndEitherSideOfEachModifier() {
        OverlayModifiers keys = new OverlayModifiers(); OverlayInputGate gate = new OverlayInputGate();
        keys.event(Keyboard.KEY_LMENU, true); keys.event(Keyboard.KEY_RMENU, true); keys.event(Keyboard.KEY_LMENU, false);
        assertEquals(OverlayInputGate.KeyDecision.TOGGLE_MENU, gate.onKey(Keyboard.KEY_X, 1, keys.alt(), keys.control()));
        keys.event(Keyboard.KEY_LCONTROL, true); keys.event(Keyboard.KEY_RCONTROL, true); keys.event(Keyboard.KEY_LCONTROL, false);
        assertEquals(OverlayInputGate.KeyDecision.PASS, gate.onKey(Keyboard.KEY_X, 1, keys.alt(), keys.control()));
        keys.event(Keyboard.KEY_RCONTROL, false); keys.event(Keyboard.KEY_RMENU, false);
        assertEquals(OverlayInputGate.KeyDecision.PASS, gate.onKey(Keyboard.KEY_X, 1, keys.alt(), keys.control()));
    }

    @Test public void resetReleasesCaptureAndForgetsOutstandingWritesWithoutNativeIo() {
        OverlayInputGate gate = new OverlayInputGate(); gate.open(OverlayInputGate.Menu.DEVELOPER, (key, value) -> { });
        gate.reset(); assertFalse(gate.isCaptureSessionOpen());
        assertEquals(OverlayInputGate.VisibilityChange.OPENED, gate.followVisibility((key, out) -> { out[0] = '2'; out[1] = 0; return 2; }));
    }
}
