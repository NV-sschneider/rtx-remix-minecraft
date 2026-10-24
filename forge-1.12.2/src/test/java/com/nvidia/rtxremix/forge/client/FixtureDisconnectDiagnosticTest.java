/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import java.lang.reflect.Field;
import java.util.Arrays;
import net.minecraft.client.gui.GuiDisconnected;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import org.junit.Test;
import static org.junit.Assert.*;

public class FixtureDisconnectDiagnosticTest {
    @Test public void nestedAuthenticationCategoriesRetainKeysWithoutFormattingArguments() {
        Object secret = new Object() {
            @Override public String toString() { throw new AssertionError("Diagnostic formatted private data"); }
        };
        ITextComponent message = new TextComponentTranslation("disconnect.loginFailedInfo",
                new TextComponentTranslation("disconnect.loginFailedInfo.invalidSession", secret),
                "access-token-private", "username-private");
        assertEquals("disconnectKeys=disconnect.loginFailedInfo,disconnect.loginFailedInfo.invalidSession"
                + " disconnectOpaque=false disconnectTruncated=false", FixtureDisconnectDiagnostic.describe(message));
    }

    @Test public void unknownKeysLiteralTextAndSiblingsCannotExposePrivateData() {
        TextComponentTranslation message = new TextComponentTranslation("disconnect.loginFailedInfo.token-private",
                "account-private");
        message.getSiblings().add(new TextComponentString("session-private"));
        message.getSiblings().add(new TextComponentTranslation("disconnect.timeout"));
        assertEquals("disconnectKeys=disconnect.timeout disconnectOpaque=true disconnectTruncated=false",
                FixtureDisconnectDiagnostic.describe(message));
        char[] oversized = new char[100000]; Arrays.fill(oversized, 'x');
        assertEquals("disconnectKeys=none disconnectOpaque=true disconnectTruncated=false",
                FixtureDisconnectDiagnostic.describe(new TextComponentTranslation(new String(oversized))));
    }

    @Test public void cyclesAndRepeatedComponentsAreVisitedOnce() {
        ITextComponent root = new TextComponentTranslation("disconnect.endOfStream");
        ITextComponent child = new TextComponentTranslation("disconnect.endOfStream");
        root.getSiblings().add(root); root.getSiblings().add(child); root.getSiblings().add(child);
        child.getSiblings().add(root);
        assertEquals("disconnectKeys=disconnect.endOfStream disconnectOpaque=false disconnectTruncated=false",
                FixtureDisconnectDiagnostic.describe(root));
    }

    @Test public void deepComponentsAndWideArgumentsHaveIndependentHardBudgets() {
        ITextComponent root = new TextComponentString("private-root"), previous = root;
        for (int index = 0; index < FixtureDisconnectDiagnostic.MAX_COMPONENTS * 3; index++) {
            ITextComponent next = new TextComponentString("private-child");
            previous.getSiblings().add(next); previous = next;
        }
        previous.getSiblings().add(new TextComponentTranslation("disconnect.timeout"));
        assertEquals("disconnectKeys=none disconnectOpaque=true disconnectTruncated=true",
                FixtureDisconnectDiagnostic.describe(root));
        Object[] arguments = new Object[FixtureDisconnectDiagnostic.MAX_EDGES + 10];
        Arrays.fill(arguments, "private-argument");
        arguments[arguments.length - 1] = new TextComponentTranslation("disconnect.timeout");
        assertEquals("disconnectKeys=disconnect.genericReason disconnectOpaque=false disconnectTruncated=true",
                FixtureDisconnectDiagnostic.describe(new TextComponentTranslation("disconnect.genericReason", arguments)));
    }

    @Test public void distinctKeysAndOutputLengthAreCapped() {
        String[] keys = {"connect.failed", "disconnect.genericReason", "disconnect.disconnected", "disconnect.lost",
                "disconnect.kicked", "disconnect.timeout", "disconnect.closed", "disconnect.loginFailed",
                "disconnect.loginFailedInfo"};
        ITextComponent root = new TextComponentTranslation(keys[0]);
        for (int index = 1; index < keys.length; index++) root.getSiblings().add(new TextComponentTranslation(keys[index]));
        String result = FixtureDisconnectDiagnostic.describe(root);
        assertTrue(result.endsWith("disconnectOpaque=false disconnectTruncated=true"));
        assertFalse(result.contains("disconnect.loginFailedInfo"));
        assertTrue(result.length() < 600);
        assertEquals(FixtureDisconnectDiagnostic.MAX_KEYS, result.substring(15, result.indexOf(' ')).split(",").length);
    }

    @Test public void disconnectedMessageUsesPinnedMcpAndSrgFieldContract() {
        Field message = ReflectionHelper.findField(GuiDisconnected.class, new String[] {"message", "field_146304_f"});
        assertEquals(ITextComponent.class, message.getType());
        assertEquals("disconnectKeys=none disconnectOpaque=false disconnectTruncated=false",
                FixtureDisconnectDiagnostic.describe(null));
    }
}
