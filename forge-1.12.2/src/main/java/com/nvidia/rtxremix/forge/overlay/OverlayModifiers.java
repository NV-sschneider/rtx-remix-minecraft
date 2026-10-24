/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.overlay;

import org.lwjgl.input.Keyboard;

/** Reconstructs chord modifiers in event order instead of using the end-of-poll keyboard state. */
final class OverlayModifiers {
    private boolean leftAlt, rightAlt, leftControl, rightControl;
    void event(int key, boolean down) {
        if (key == Keyboard.KEY_LMENU) leftAlt = down;
        if (key == Keyboard.KEY_RMENU) rightAlt = down;
        if (key == Keyboard.KEY_LCONTROL) leftControl = down;
        if (key == Keyboard.KEY_RCONTROL) rightControl = down;
    }
    boolean alt() { return leftAlt || rightAlt; }
    boolean control() { return leftControl || rightControl; }
}
