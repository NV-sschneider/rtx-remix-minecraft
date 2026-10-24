/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import java.util.List;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;

/** Bounded, untranslated disconnect categories; component text and format values never enter logs. */
final class FixtureDisconnectDiagnostic {
    static final int MAX_COMPONENTS = 32, MAX_EDGES = 64, MAX_KEYS = 8;
    private FixtureDisconnectDiagnostic() {}

    static String describe(ITextComponent root) {
        ITextComponent[] pending = new ITextComponent[MAX_COMPONENTS];
        String[] keys = new String[MAX_KEYS];
        int head = 0, tail = 0, keyCount = 0, edges = 0;
        boolean opaque = false, truncated = false;
        if (root != null) pending[tail++] = root;
        while (head < tail) {
            ITextComponent current = pending[head++];
            if (current instanceof TextComponentTranslation) {
                TextComponentTranslation translation = (TextComponentTranslation) current;
                String key = knownKey(translation.getKey());
                if (key == null) opaque = true;
                else {
                    int index = 0;
                    while (index < keyCount && !keys[index].equals(key)) index++;
                    if (index == keyCount) {
                        if (keyCount == MAX_KEYS) truncated = true;
                        else keys[keyCount++] = key;
                    }
                }
                Object[] arguments = translation.getFormatArgs();
                for (int index = 0; index < arguments.length; index++) {
                    if (edges++ == MAX_EDGES) { truncated = true; break; }
                    if (!(arguments[index] instanceof ITextComponent)) continue;
                    ITextComponent child = (ITextComponent) arguments[index];
                    if (contains(pending, tail, child)) continue;
                    if (tail == MAX_COMPONENTS) { truncated = true; break; }
                    pending[tail++] = child;
                }
            } else opaque = true;
            if (edges > MAX_EDGES) break;
            List<ITextComponent> siblings = current.getSiblings();
            for (int index = 0; index < siblings.size(); index++) {
                if (edges++ == MAX_EDGES) { truncated = true; break; }
                ITextComponent child = siblings.get(index);
                if (child == null || contains(pending, tail, child)) continue;
                if (tail == MAX_COMPONENTS) { truncated = true; break; }
                pending[tail++] = child;
            }
            if (edges > MAX_EDGES) break;
        }
        StringBuilder result = new StringBuilder("disconnectKeys=");
        if (keyCount == 0) result.append("none");
        for (int index = 0; index < keyCount; index++) {
            if (index != 0) result.append(',');
            result.append(keys[index]);
        }
        return result.append(" disconnectOpaque=").append(opaque)
                .append(" disconnectTruncated=").append(truncated).toString();
    }

    private static boolean contains(ITextComponent[] values, int length, ITextComponent value) {
        for (int index = 0; index < length; index++) if (values[index] == value) return true;
        return false;
    }

    private static String knownKey(String value) {
        if (value == null || value.length() > 64) return null;
        // Exact pinned vanilla keys only: even a translation key can contain arbitrary server data.
        switch (value) {
            case "connect.failed":
            case "disconnect.genericReason":
            case "disconnect.disconnected":
            case "disconnect.lost":
            case "disconnect.kicked":
            case "disconnect.timeout":
            case "disconnect.closed":
            case "disconnect.loginFailed":
            case "disconnect.loginFailedInfo":
            case "disconnect.loginFailedInfo.serversUnavailable":
            case "disconnect.loginFailedInfo.invalidSession":
            case "disconnect.quitting":
            case "disconnect.endOfStream":
            case "disconnect.overflow":
            case "disconnect.spam":
            case "multiplayer.disconnect.authservers_down":
            case "multiplayer.disconnect.banned":
            case "multiplayer.disconnect.duplicate_login":
            case "multiplayer.disconnect.flying":
            case "multiplayer.disconnect.generic":
            case "multiplayer.disconnect.idling":
            case "multiplayer.disconnect.illegal_characters":
            case "multiplayer.disconnect.invalid_entity_attacked":
            case "multiplayer.disconnect.invalid_player_movement":
            case "multiplayer.disconnect.invalid_vehicle_movement":
            case "multiplayer.disconnect.ip_banned":
            case "multiplayer.disconnect.kicked":
            case "multiplayer.disconnect.outdated_client":
            case "multiplayer.disconnect.outdated_server":
            case "multiplayer.disconnect.server_shutdown":
            case "multiplayer.disconnect.slow_login":
            case "multiplayer.disconnect.unverified_username": return value;
            default: return null;
        }
    }
}
