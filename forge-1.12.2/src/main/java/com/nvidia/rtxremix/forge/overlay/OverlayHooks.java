/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.overlay;

import com.nvidia.rtxremix.forge.RemixForgeMod;
import java.util.function.BiConsumer;
import java.util.function.ToIntBiFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.MouseHelper;
import net.minecraftforge.common.MinecraftForge;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;

/** One render-thread owner for native menu requests, visibility following, and Minecraft input capture. */
public final class OverlayHooks {
    /** Implementations must refuse availability once the world/renderer session starts closing. */
    public interface Backend {
        boolean available();
        void write(String key, String value);
        int read(String key, byte[] value);
    }
    private static final OverlayInputGate GATE = new OverlayInputGate();
    private static final OverlayEvents EVENTS = new OverlayEvents();
    private static Backend backend;
    private static BiConsumer<String, String> writer;
    private static ToIntBiFunction<String, byte[]> reader;
    private static Runnable presentCallback;
    private static OverlayInputGate.Menu pending;
    private static boolean registered, readFailed;
    private static final OverlayModifiers MODIFIERS = new OverlayModifiers();
    private static long frame, session;

    private OverlayHooks() {}

    public static void attach(Backend value) {
        if (backend != null) throw new IllegalStateException("Overlay backend is already attached");
        backend = value; writer = value::write; reader = value::read;
        refreshModifiers();
        if (!registered) { MinecraftForge.EVENT_BUS.register(EVENTS); registered = true; }
    }

    public static boolean available() { return backend != null && backend.available(); }
    public static boolean capturing() { return GATE.isCaptureSessionOpen(); }
    public static long frameId() { return frame; }
    public static long sessionId() { return session; }
    public static void setPresentCallback(Runnable callback) { presentCallback = callback; }

    /** Runs after all Minecraft UI, immediately before its main framebuffer reaches the window. */
    public static void beforePresent() {
        if (available() && capturing() && presentCallback != null) presentCallback.run();
    }

    /** Queue a menu so its initiating click/command finishes before capture starts. */
    public static boolean requestMenu(OverlayInputGate.Menu menu) {
        if (!available()) return false;
        pending = menu; return true;
    }

    /** Called at runGameLoop entry, before Minecraft consumes this frame's queued input. */
    public static void beginFrame() {
        frame++;
        if (!available()) { releaseUnavailable(); return; }
        if (pending != null) {
            OverlayInputGate.Menu menu = pending; pending = null; open(menu);
        }
        try {
            OverlayInputGate.VisibilityChange change = GATE.followVisibility(reader);
            readFailed = false;
            if (change == OverlayInputGate.VisibilityChange.OPENED) acquire();
            else if (change == OverlayInputGate.VisibilityChange.CLOSED) release();
            else if (change == OverlayInputGate.VisibilityChange.OWN_WRITE_OVERDUE) {
                RemixForgeMod.LOG.warn("Remix menu visibility did not follow the requested value after {} frames; following resolved settings. Check higher-priority rtx.showUI overrides.",
                        OverlayInputGate.OWN_WRITE_OVERDUE_FRAMES);
            }
        } catch (RuntimeException | LinkageError error) {
            if (!readFailed) RemixForgeMod.LOG.warn("Cannot read Remix menu visibility; keeping the current input capture until the read succeeds", error);
            readFailed = true;
        }
    }

    /** Hide/release while native calls are still available, before renderer world/window teardown. */
    public static void releaseSession() {
        pending = null;
        boolean open = capturing();
        try { if (open && available()) GATE.close(writer); }
        catch (RuntimeException | LinkageError error) { RemixForgeMod.LOG.warn("Cannot hide Remix menu during session cleanup", error); }
        finally { GATE.reset(); if (open) release(); }
    }

    private static void releaseUnavailable() {
        pending = null;
        if (capturing()) { GATE.reset(); release(); }
    }

    private static void open(OverlayInputGate.Menu menu) {
        if (!available() || capturing()) return;
        try { GATE.open(menu, writer); acquire(); }
        catch (RuntimeException | LinkageError error) {
            GATE.reset(); release(); RemixForgeMod.LOG.warn("Cannot open Remix menu", error);
        }
    }

    private static void toggle() {
        if (!available()) return;
        if (!capturing()) { open(OverlayInputGate.Menu.DEVELOPER); return; }
        try { GATE.close(writer); }
        catch (RuntimeException | LinkageError error) { RemixForgeMod.LOG.warn("Cannot hide Remix menu", error); }
        finally { release(); }
    }

    public static boolean nextKeyboardEvent() {
        while (Keyboard.next()) if (!consumeCurrentKeyboardEvent()) return true;
        return false;
    }

    /** Also handles Forge pre-events from a custom screen that owns its own keyboard iterator. */
    public static boolean consumeCurrentKeyboardEvent() {
        int key = Keyboard.getEventKey(); boolean down = Keyboard.getEventKeyState();
        MODIFIERS.event(key, down);
        // Event-tracked modifiers retain a quick Alt+X chord even when poll() has already
        // consumed its later Alt release. Initial state is sampled when the backend attaches.
        OverlayInputGate.KeyDecision decision = GATE.onKey(key, down && !Keyboard.isRepeatEvent() ? 1 : 0, MODIFIERS.alt(), MODIFIERS.control());
        if (decision == OverlayInputGate.KeyDecision.TOGGLE_MENU && available()) { toggle(); return true; }
        return decision == OverlayInputGate.KeyDecision.BLOCK || decision == OverlayInputGate.KeyDecision.TOGGLE_MENU && available();
    }

    public static boolean nextMouseEvent() {
        while (Mouse.next()) if (!capturing()) return true;
        return false;
    }

    public static int screenCoordinate(int coordinate) { return capturing() ? -10000 : coordinate; }

    public static void clearMouseDelta(Object value) {
        MouseHelper helper = (MouseHelper) value;
        if (Mouse.isCreated()) { Mouse.getDX(); Mouse.getDY(); }
        helper.deltaX = helper.deltaY = 0;
    }

    private static void acquire() {
        session++;
        Minecraft mc = Minecraft.getMinecraft();
        KeyBinding.unPressAllKeys();
        ((OverlayMinecraftAccess) mc).rtxremix$resetOverlayDebugState();
        try {
            if (mc.playerController != null && mc.player != null) {
                mc.playerController.resetBlockRemoving();
                if (mc.player.isHandActive()) mc.playerController.onStoppedUsingItem(mc.player);
            }
            GuiScreen screen = mc.currentScreen;
            if (screen != null && Mouse.isCreated()) {
                int x = Mouse.getX() * screen.width / Math.max(1, mc.displayWidth);
                int y = screen.height - Mouse.getY() * screen.height / Math.max(1, mc.displayHeight) - 1;
                ((OverlayScreenAccess) screen).rtxremix$releaseOverlayMouse(x, y);
            }
        } finally {
            mc.setIngameNotInFocus();
            if (Mouse.isCreated() && Mouse.isGrabbed()) mc.mouseHelper.ungrabMouseCursor();
            clearMouseDelta(mc.mouseHelper);
        }
    }

    private static void release() {
        session++;
        Minecraft mc = Minecraft.getMinecraft();
        KeyBinding.unPressAllKeys();
        if (Keyboard.isCreated()) while (Keyboard.next()) { }
        refreshModifiers();
        if (Mouse.isCreated()) while (Mouse.next()) { }
        clearMouseDelta(mc.mouseHelper);
        if (mc.currentScreen == null && mc.world != null && Display.isCreated() && Display.isActive()) mc.setIngameFocus();
    }

    private static void refreshModifiers() {
        if (!Keyboard.isCreated()) return;
        MODIFIERS.event(Keyboard.KEY_LMENU, Keyboard.isKeyDown(Keyboard.KEY_LMENU));
        MODIFIERS.event(Keyboard.KEY_RMENU, Keyboard.isKeyDown(Keyboard.KEY_RMENU));
        MODIFIERS.event(Keyboard.KEY_LCONTROL, Keyboard.isKeyDown(Keyboard.KEY_LCONTROL));
        MODIFIERS.event(Keyboard.KEY_RCONTROL, Keyboard.isKeyDown(Keyboard.KEY_RCONTROL));
    }
}
