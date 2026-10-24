/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import com.nvidia.rtxremix.forge.overlay.OverlayHooks;
import com.nvidia.rtxremix.forge.overlay.OverlayInputGate;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.gui.inventory.GuiContainerCreative;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.inventory.Container;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.world.WorldServer;
import org.lwjgl.LWJGLException;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;

/** Opt-in live native-menu, preserved-container and window-transition checks in an owned smoke save. */
final class FixtureWindowOverlay implements AutoCloseable {
    private static final long TIMEOUT = TimeUnit.SECONDS.toNanos(120), PROGRESS = TimeUnit.SECONDS.toNanos(10);
    private static final int SETTLE_FRAMES = 20;
    private static final long SETTLE_NANOS = TimeUnit.MILLISECONDS.toNanos(1500);
    private enum Phase { BASELINE, GAME_GRAPHICS, GAME_DEVELOPER, GAME_CLOSED, INVENTORY,
        INVENTORY_GRAPHICS, INVENTORY_DEVELOPER, INVENTORY_CLOSED, FULLSCREEN, WINDOWED,
        RECREATED_MENU, RESIZED, RESIZED_MENU, RESTORE, RETURN, DONE }
    private final Minecraft mc;
    private final ClientEvents events;
    private final IntegratedServer server;
    private final WorldClient clientWorld;
    private final String saveName;
    private final UUID playerId;
    private final FixtureTerrain.Output output;
    private final GuiScreen originalScreen;
    private final Container originalContainer;
    private final boolean originalFullscreen, originalFullscreenSetting;
    private final int originalWidth, originalHeight, originalX, originalY;
    private final byte[] config = new byte[16];
    private final long originalWindowChanges;
    private GuiContainer inventory;
    private Container inventoryContainer;
    private boolean inventoryClosed;
    private Phase phase = Phase.BASELINE;
    private long since = System.nanoTime(), progress = since, readyFrame = -1;
    private long readySince;
    private int resolvedMenu, resizedWidth, resizedHeight;
    private boolean closed;

    FixtureWindowOverlay(Minecraft mc, ClientEvents events, IntegratedServer server, WorldServer world,
            String saveName, FixtureTerrain.Output output) {
        if (!saveName.startsWith("rlcraft-remix-smoke-") || !saveName.equals(server.getFolderName())
                || mc.getIntegratedServer() != server || world != server.getWorld(0) || mc.world == null || mc.player == null
                || mc.currentScreen != null || OverlayHooks.capturing()) {
            throw new IllegalArgumentException("Window fixture requires its dedicated smoke world in gameplay");
        }
        this.mc = mc; this.events = events; this.server = server; this.saveName = saveName; this.output = output;
        clientWorld = mc.world; playerId = mc.player.getUniqueID(); originalScreen = mc.currentScreen;
        originalContainer = mc.player.openContainer;
        originalFullscreen = mc.isFullScreen(); originalFullscreenSetting = mc.gameSettings.fullScreen;
        originalWidth = mc.displayWidth; originalHeight = mc.displayHeight; originalX = Display.getX(); originalY = Display.getY();
        originalWindowChanges = events.windowChanges(); log("STARTED");
        output.log("WINDOW_CAPTURE_CONTRACT", "Framebuffer PNGs contain GL content only; native menus are composed during Vulkan Present. Capture the owned foreground game client area to verify native GUI pixels.");
    }

    boolean tick() throws Exception {
        if (closed || mc.world != clientWorld || mc.getIntegratedServer() != server || mc.player == null
                || !playerId.equals(mc.player.getUniqueID()) || !saveName.equals(server.getFolderName())
                || !events.isEnabled() || events.failureMessage() != null || !OverlayHooks.available()) {
            throw new IllegalStateException("Window fixture renderer/world unavailable");
        }
        resolvedMenu = readMenu();
        if (System.nanoTime() - since >= TIMEOUT) {
            capture("window-timeout-" + phase.name().toLowerCase(Locale.ROOT)); log("TIMEOUT");
            throw new IllegalStateException("Window fixture timed out: " + status());
        }
        if (System.nanoTime() - progress >= PROGRESS) { log("PROGRESS"); progress = System.nanoTime(); }
        switch (phase) {
            case BASELINE:
                if (settled(menuClosed() && gameplay())) {
                    KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), true);
                    KeyBinding.onTick(mc.gameSettings.keyBindForward.getKeyCode());
                    openGraphics(); transition(Phase.GAME_GRAPHICS);
                }
                break;
            case GAME_GRAPHICS:
                if (settled(menuOpen(1) && gameplay() && !mc.gameSettings.keyBindForward.isKeyDown())) {
                    if (mc.gameSettings.keyBindForward.isPressed()) throw new IllegalStateException("Overlay left a queued held-key press");
                    capture("overlay-gameplay-graphics"); log("GAME_GRAPHICS_PASS");
                    nativeMenu(2); transition(Phase.GAME_DEVELOPER);
                }
                break;
            case GAME_DEVELOPER:
                if (settled(menuOpen(2) && gameplay())) {
                    capture("overlay-gameplay-developer"); log("GAME_DEVELOPER_PASS"); nativeMenu(0); transition(Phase.GAME_CLOSED);
                }
                break;
            case GAME_CLOSED:
                if (settled(menuClosed() && gameplay())) {
                    capture("overlay-gameplay-closed"); log("GAME_CLOSE_PASS");
                    inventory = mc.playerController.isInCreativeMode() ? new GuiContainerCreative(mc.player) : new GuiInventory(mc.player);
                    mc.displayGuiScreen(inventory);
                    inventoryContainer = mc.player.openContainer; transition(Phase.INVENTORY);
                }
                break;
            case INVENTORY:
                if (settled(menuClosed() && sameInventory())) {
                    capture("overlay-inventory-before"); openGraphics(); transition(Phase.INVENTORY_GRAPHICS);
                }
                break;
            case INVENTORY_GRAPHICS:
                if (settled(menuOpen(1) && sameInventory())) {
                    capture("overlay-inventory-graphics"); log("INVENTORY_GRAPHICS_PASS"); nativeMenu(2); transition(Phase.INVENTORY_DEVELOPER);
                }
                break;
            case INVENTORY_DEVELOPER:
                if (settled(menuOpen(2) && sameInventory())) {
                    capture("overlay-inventory-developer"); log("INVENTORY_DEVELOPER_PASS"); nativeMenu(0); transition(Phase.INVENTORY_CLOSED);
                }
                break;
            case INVENTORY_CLOSED:
                if (settled(menuClosed() && sameInventory())) {
                    capture("overlay-inventory-restored"); log("INVENTORY_CLOSE_PASS");
                    mc.player.closeScreen();
                    inventoryClosed = true;
                    if (!mc.isFullScreen()) mc.toggleFullscreen(); transition(Phase.FULLSCREEN);
                }
                break;
            case FULLSCREEN:
                if (settled(mc.isFullScreen() && Display.isFullscreen() && gameplay())) {
                    capture("window-fullscreen"); log("FULLSCREEN_PASS"); mc.toggleFullscreen(); transition(Phase.WINDOWED);
                }
                break;
            case WINDOWED:
                if (settled(!mc.isFullScreen() && !Display.isFullscreen() && gameplay())) {
                    capture("window-returned"); log("WINDOWED_PASS"); openGraphics(); transition(Phase.RECREATED_MENU);
                }
                break;
            case RECREATED_MENU:
                if (settled(menuOpen(1) && gameplay())) {
                    capture("overlay-recreated-window"); log("RECREATED_MENU_PASS");
                    resizedWidth = mc.displayWidth > 960 ? mc.displayWidth - 160 : mc.displayWidth + 120;
                    resizedHeight = mc.displayHeight > 640 ? mc.displayHeight - 90 : mc.displayHeight + 80;
                    resize(resizedWidth, resizedHeight); transition(Phase.RESIZED);
                }
                break;
            case RESIZED:
                if (settled(dimensions(resizedWidth, resizedHeight) && gameplay())) {
                    // A driver may recreate HWND during resize; the renderer releases that old menu session.
                    openGraphics(); transition(Phase.RESIZED_MENU);
                }
                break;
            case RESIZED_MENU:
                if (settled(dimensions(resizedWidth, resizedHeight) && menuOpen(1) && gameplay())) {
                    capture("overlay-resized-window"); log("RESIZE_PASS"); nativeMenu(0); transition(Phase.RESTORE);
                }
                break;
            case RESTORE:
                if (settled(menuClosed() && gameplay())) { restoreWindow(); transition(Phase.RETURN); }
                break;
            case RETURN:
                if (settled(menuClosed() && gameplay() && mc.isFullScreen() == originalFullscreen
                        && dimensions(originalWidth, originalHeight))) {
                    capture("window-restored"); transition(Phase.DONE); log("PASS");
                }
                break;
            default: break;
        }
        return phase == Phase.DONE;
    }

    private void openGraphics() {
        if (!OverlayHooks.requestMenu(OverlayInputGate.Menu.GRAPHICS_SETTINGS)) throw new IllegalStateException("Native graphics menu unavailable");
    }
    /** Simulate the runtime's own switch/close write; only the normal resolved-state follower changes the gate. */
    private void nativeMenu(int menu) { events.fixtureWriteConfig("rtx.showUI", Integer.toString(menu)); }
    private int readMenu() {
        int length = events.fixtureReadConfig("rtx.showUI", config);
        if (length < 2 || length > config.length || config[length - 1] != 0) throw new IllegalStateException("Invalid resolved native menu value");
        return Integer.parseInt(new String(config, 0, length - 1, StandardCharsets.UTF_8));
    }
    private boolean menuOpen(int value) { return resolvedMenu == value && OverlayHooks.capturing() && !Mouse.isGrabbed() && !mc.inGameHasFocus; }
    private boolean menuClosed() { return resolvedMenu == 0 && !OverlayHooks.capturing(); }
    private boolean gameplay() {
        // Vanilla intentionally restores inventoryContainer when a creative container closes.
        // Exact openContainer identity is required only while the overlay preserves that screen.
        return mc.currentScreen == originalScreen && mc.player.openContainer ==
                (inventoryClosed ? mc.player.inventoryContainer : originalContainer);
    }
    private boolean sameInventory() { return mc.currentScreen == inventory && mc.player.openContainer == inventoryContainer && inventory.inventorySlots == inventoryContainer; }
    private boolean dimensions(int width, int height) { return mc.displayWidth == width && mc.displayHeight == height && Display.getWidth() == width && Display.getHeight() == height; }
    private boolean settled(boolean ready) {
        if (!ready) { readyFrame = -1; readySince = 0; return false; }
        if (readyFrame < 0) { readyFrame = events.renderedFrames(); readySince = System.nanoTime(); }
        return events.renderedFrames() - readyFrame >= SETTLE_FRAMES && System.nanoTime() - readySince >= SETTLE_NANOS;
    }
    private void resize(int width, int height) throws LWJGLException {
        Display.setDisplayMode(new DisplayMode(width, height)); mc.resize(width, height);
    }
    private void restoreWindow() throws LWJGLException {
        if (mc.isFullScreen() != originalFullscreen) mc.toggleFullscreen();
        if (!originalFullscreen) { resize(originalWidth, originalHeight); Display.setLocation(originalX, originalY); }
        mc.gameSettings.fullScreen = originalFullscreenSetting;
    }
    private void transition(Phase next) { phase = next; since = progress = System.nanoTime(); readyFrame = -1; readySince = 0; log("PHASE"); }
    private void capture(String name) throws Exception { output.log("WINDOW_CAPTURE", status() + " file=" + output.capture(name, output.image())); }
    private void log(String event) { output.log("WINDOW_" + event, status()); }
    String status() {
        return "windowPhase=" + phase + " menu=" + resolvedMenu + " capture=" + OverlayHooks.capturing()
                + " grabbed=" + Mouse.isGrabbed() + " focused=" + mc.inGameHasFocus + " fullscreen=" + mc.isFullScreen()
                + " displayFullscreen=" + Display.isFullscreen() + " screen=" + identity(mc.currentScreen)
                + " container=" + identity(mc.player.openContainer) + " originalContainer=" + identity(originalContainer)
                + " playerInventory=" + identity(mc.player.inventoryContainer) + " gameplay=" + gameplay()
                + " width=" + mc.displayWidth + " height=" + mc.displayHeight + " frames=" + events.renderedFrames()
                + " windowChanges=" + (events.windowChanges() - originalWindowChanges)
                + " inventoryPreserved=" + (inventory != null && sameInventory());
    }
    private static String identity(Object value) {
        return value == null ? "none" : value.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(value));
    }

    @Override public void close() {
        if (closed) return;
        try {
            OverlayHooks.releaseSession();
        } finally {
            try {
                KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), false);
                if (mc.world == clientWorld && mc.player != null && playerId.equals(mc.player.getUniqueID())
                        && mc.currentScreen == inventory) mc.player.closeScreen();
                restoreWindow(); closed = true;
            } catch (LWJGLException failure) { throw new IllegalStateException("Cannot restore smoke fixture window", failure); }
        }
    }
}
