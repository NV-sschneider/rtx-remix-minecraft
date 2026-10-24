/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.overlay;

import org.junit.Test;
import org.lwjgl.input.Keyboard;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.nvidia.rtxremix.forge.overlay.OverlayInputGate.KeyDecision.BLOCK;
import static com.nvidia.rtxremix.forge.overlay.OverlayInputGate.KeyDecision.PASS;
import static com.nvidia.rtxremix.forge.overlay.OverlayInputGate.KeyDecision.TOGGLE_MENU;
import static com.nvidia.rtxremix.forge.overlay.OverlayInputGate.VisibilityChange.CLOSED;
import static com.nvidia.rtxremix.forge.overlay.OverlayInputGate.VisibilityChange.NONE;
import static com.nvidia.rtxremix.forge.overlay.OverlayInputGate.VisibilityChange.OPENED;
import static com.nvidia.rtxremix.forge.overlay.OverlayInputGate.VisibilityChange.OWN_WRITE_OVERDUE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class OverlayInputGateTest {

    @Test
    public void altXOpensWheneverCaptureIsClosed() {
        OverlayInputGate gate = new OverlayInputGate();

        assertSame(TOGGLE_MENU, key(gate, Keyboard.KEY_X, 1, true));
        assertSame(PASS, key(gate, Keyboard.KEY_X, 2, true));
        assertSame(PASS, key(gate, Keyboard.KEY_X, 0, true));
        assertSame(PASS, key(gate, Keyboard.KEY_X, 1, false));
        assertSame(PASS, key(gate, Keyboard.KEY_W, 1, true));
    }

    @Test
    public void openSessionBlocksEveryOtherKeyboardEventAndAllCharacters() {
        OverlayInputGate gate = new OverlayInputGate();
        open(gate);

        assertSame(BLOCK, key(gate, Keyboard.KEY_W, 1, false));
        assertSame(BLOCK, key(gate, Keyboard.KEY_W, 1, true));
        assertSame(BLOCK, key(gate, Keyboard.KEY_W, 2, false));
        assertSame(BLOCK, key(gate, Keyboard.KEY_W, 0, false));
        assertSame(BLOCK, key(gate, Keyboard.KEY_ESCAPE, 1, false));
        assertTrue(gate.isCaptureSessionOpen());
    }

    @Test
    public void altXClosesWheneverCaptureIsOpen() {
        OverlayInputGate gate = new OverlayInputGate();
        open(gate);

        assertSame(TOGGLE_MENU, key(gate, Keyboard.KEY_X, 1, true));
        assertSame(BLOCK, key(gate, Keyboard.KEY_X, 2, true));
        assertSame(BLOCK, key(gate, Keyboard.KEY_X, 0, true));
        assertSame(BLOCK, key(gate, Keyboard.KEY_X, 1, false));
    }

    @Test
    public void controlAltXDoesNotToggle() {
        OverlayInputGate gate = new OverlayInputGate();

        assertSame(PASS, key(gate, Keyboard.KEY_X, 1, true, true));
        assertSame(PASS, key(gate, Keyboard.KEY_X, 1, false, true));
        assertSame(PASS, key(gate, Keyboard.KEY_X, 2, true, true));
        assertSame(PASS, key(gate, Keyboard.KEY_X, 0, true, true));

        open(gate);

        assertSame(BLOCK, key(gate, Keyboard.KEY_X, 1, true, true));
        assertSame(BLOCK, key(gate, Keyboard.KEY_X, 1, false, true));
        assertSame(BLOCK, key(gate, Keyboard.KEY_X, 2, true, true));
        assertSame(BLOCK, key(gate, Keyboard.KEY_X, 0, true, true));
        assertTrue(gate.isCaptureSessionOpen());
    }

    @Test
    public void closingReturnsHeldKeyEventsToMinecraftImmediately() {
        OverlayInputGate gate = new OverlayInputGate();
        assertSame(PASS, key(gate, Keyboard.KEY_W, 1, false));
        open(gate);
        assertSame(BLOCK, key(gate, Keyboard.KEY_W, 2, false));

        close(gate);

        assertSame(PASS, key(gate, Keyboard.KEY_W, 2, false));
        assertSame(PASS, key(gate, Keyboard.KEY_W, 0, false));
    }

    @Test
    public void keyPressedInsideOverlayPassesAfterClose() {
        OverlayInputGate gate = new OverlayInputGate();
        open(gate);
        assertSame(BLOCK, key(gate, Keyboard.KEY_E, 1, false));

        close(gate);

        assertSame(PASS, key(gate, Keyboard.KEY_E, 2, false));
        assertSame(PASS, key(gate, Keyboard.KEY_E, 0, false));
        assertSame(PASS, key(gate, Keyboard.KEY_E, 1, false));
    }

    @Test
    public void menuClosedFromTheRuntimeReleasesCapture() {
        OverlayInputGate gate = new OverlayInputGate();
        open(gate);
        assertSame(NONE, follow(gate, "2"));

        assertSame(CLOSED, follow(gate, "0"));

        assertFalse(gate.isCaptureSessionOpen());
        assertSame(NONE, follow(gate, "0"));
    }

    @Test
    public void menuOpenedByTheRuntimeTakesCapture() {
        OverlayInputGate gate = new OverlayInputGate();
        assertSame(NONE, follow(gate, "0"));

        assertSame(OPENED, follow(gate, "3"));

        assertTrue(gate.isCaptureSessionOpen());
        assertSame(NONE, follow(gate, "3"));
    }

    @Test
    public void readFromBeforeTheModOpenedDoesNotClose() {
        OverlayInputGate gate = new OverlayInputGate();
        open(gate);

        assertSame(NONE, follow(gate, "0"));
        assertSame(NONE, follow(gate, "0"));

        assertTrue(gate.isCaptureSessionOpen());
        assertSame(NONE, follow(gate, "2"));
        assertSame(CLOSED, follow(gate, "0"));
    }

    @Test
    public void readFromBeforeTheModClosedDoesNotReopen() {
        OverlayInputGate gate = new OverlayInputGate();
        open(gate);
        assertSame(NONE, follow(gate, "2"));
        close(gate);

        assertSame(NONE, follow(gate, "2"));

        assertFalse(gate.isCaptureSessionOpen());
        assertSame(NONE, follow(gate, "0"));
        assertSame(OPENED, follow(gate, "2"));
    }

    @Test
    public void overdueOpenReleasesCaptureWhileNoMenuIsShown() {
        OverlayInputGate gate = new OverlayInputGate();
        open(gate);
        for (int frame = 1; frame < OverlayInputGate.OWN_WRITE_OVERDUE_FRAMES; frame++) {
            assertSame(NONE, follow(gate, "0"));
        }

        assertSame(OWN_WRITE_OVERDUE, follow(gate, "0"));
        assertTrue(gate.isCaptureSessionOpen());

        assertSame(CLOSED, follow(gate, "0"));
        assertFalse(gate.isCaptureSessionOpen());
        assertSame(NONE, follow(gate, "0"));
    }

    @Test
    public void overdueCloseTakesCaptureWhileAMenuIsShown() {
        OverlayInputGate gate = new OverlayInputGate();
        open(gate);
        assertSame(NONE, follow(gate, "2"));
        close(gate);
        for (int frame = 1; frame < OverlayInputGate.OWN_WRITE_OVERDUE_FRAMES; frame++) {
            assertSame(NONE, follow(gate, "2"));
        }

        assertSame(OWN_WRITE_OVERDUE, follow(gate, "2"));
        assertFalse(gate.isCaptureSessionOpen());

        assertSame(OPENED, follow(gate, "2"));
        assertTrue(gate.isCaptureSessionOpen());
        assertSame(NONE, follow(gate, "2"));
    }

    @Test
    public void eachOwnWriteGetsItsOwnOverdueCount() {
        OverlayInputGate gate = new OverlayInputGate();
        open(gate);
        for (int frame = 1; frame < OverlayInputGate.OWN_WRITE_OVERDUE_FRAMES; frame++) {
            assertSame(NONE, follow(gate, "0"));
        }
        close(gate);

        for (int frame = 1; frame < OverlayInputGate.OWN_WRITE_OVERDUE_FRAMES; frame++) {
            assertSame(NONE, follow(gate, "2"));
        }

        assertSame(OWN_WRITE_OVERDUE, follow(gate, "2"));
        assertFalse(gate.isCaptureSessionOpen());
    }

    @Test
    public void switchingMenusKeepsCapture() {
        OverlayInputGate gate = new OverlayInputGate();
        gate.open(OverlayInputGate.Menu.GRAPHICS_SETTINGS, (option, value) -> { });
        assertSame(NONE, follow(gate, "1"));

        assertSame(NONE, follow(gate, "2"));

        assertTrue(gate.isCaptureSessionOpen());
    }

    @Test
    public void anyShownMenuResolvesTheModsOpen() {
        OverlayInputGate gate = new OverlayInputGate();
        open(gate);

        assertSame(NONE, follow(gate, "3"));

        assertSame(CLOSED, follow(gate, "0"));
    }

    @Test
    public void menuStillShownAfterAFailedCloseTakesCaptureBack() {
        OverlayInputGate gate = new OverlayInputGate();
        open(gate);
        assertSame(NONE, follow(gate, "2"));
        assertThrows(IllegalStateException.class,
                () -> gate.close((option, value) -> {
                    throw new IllegalStateException("close failed");
                }));

        assertSame(OPENED, follow(gate, "2"));

        assertTrue(gate.isCaptureSessionOpen());
    }

    @Test
    public void gateOwnsTheNativeVisibilityValues() {
        OverlayInputGate gate = new OverlayInputGate();
        Map<String, String> writes = new HashMap<>();

        gate.open(OverlayInputGate.Menu.DEVELOPER, writes::put);
        assertEquals("2", writes.get("rtx.showUI"));
        gate.close(writes::put);
        assertEquals("0", writes.get("rtx.showUI"));

        gate.open(OverlayInputGate.Menu.GRAPHICS_SETTINGS, writes::put);
        assertEquals("1", writes.get("rtx.showUI"));
        gate.close(writes::put);

        assertEquals("0", writes.get("rtx.showUI"));
        assertFalse(gate.isCaptureSessionOpen());
        gate.followVisibility((option, buffer) -> {
            assertEquals("rtx.showUI", option);
            return resolve("0", buffer);
        });
    }

    @Test
    public void aValueTooLongForTheReadBufferIsAShownMenu() {
        OverlayInputGate gate = new OverlayInputGate();

        assertSame(OPENED, follow(gate, "10"));

        assertSame(CLOSED, follow(gate, "0"));
    }

    @Test
    public void everyReadReusesTheSameBuffer() {
        OverlayInputGate gate = new OverlayInputGate();
        List<byte[]> buffers = new ArrayList<>();

        for (int frame = 0; frame < 3; frame++) {
            gate.followVisibility((option, buffer) -> {
                buffers.add(buffer);
                return resolve("0", buffer);
            });
        }

        assertSame(buffers.get(0), buffers.get(1));
        assertSame(buffers.get(0), buffers.get(2));
    }

    @Test
    public void openingWhileOpenKeepsTheMenuAlreadyShown() {
        OverlayInputGate gate = new OverlayInputGate();
        Map<String, String> writes = new HashMap<>();
        gate.open(OverlayInputGate.Menu.DEVELOPER, writes::put);

        gate.open(OverlayInputGate.Menu.GRAPHICS_SETTINGS, writes::put);

        assertEquals("2", writes.get("rtx.showUI"));
        assertTrue(gate.isCaptureSessionOpen());
    }

    @Test
    public void failedVisibilityWritesDoNotCommitCaptureState() {
        OverlayInputGate gate = new OverlayInputGate();

        assertThrows(IllegalStateException.class,
                () -> gate.open(OverlayInputGate.Menu.DEVELOPER, (option, value) -> {
                    throw new IllegalStateException("open failed");
                }));
        assertFalse(gate.isCaptureSessionOpen());

        open(gate);
        assertThrows(IllegalStateException.class,
                () -> gate.close((option, value) -> {
                    throw new IllegalStateException("close failed");
                }));
        assertFalse(gate.isCaptureSessionOpen());
    }

    private static void open(OverlayInputGate gate) {
        gate.open(OverlayInputGate.Menu.DEVELOPER, (option, value) -> { });
    }

    private static void close(OverlayInputGate gate) {
        gate.close((option, value) -> { });
    }

    private static OverlayInputGate.VisibilityChange follow(OverlayInputGate gate, String visibility) {
        return gate.followVisibility((option, buffer) -> resolve(visibility, buffer));
    }

    /**
     * Writes {@code value} the way {@code GetConfigVariableResolved} does: UTF-8 and a NUL byte
     * when they fit, and the size they need either way.
     */
    private static int resolve(String value, byte[] buffer) {
        byte[] resolved = (value + '\0').getBytes(StandardCharsets.UTF_8);
        if (resolved.length <= buffer.length) {
            System.arraycopy(resolved, 0, buffer, 0, resolved.length);
        }
        return resolved.length;
    }

    private static OverlayInputGate.KeyDecision key(
            OverlayInputGate gate,
            int key,
            int action,
            boolean altDown) {
        return key(gate, key, action, altDown, false);
    }

    private static OverlayInputGate.KeyDecision key(
            OverlayInputGate gate,
            int key,
            int action,
            boolean altDown,
            boolean controlDown) {
        return gate.onKey(key, action, altDown, controlDown);
    }
}
