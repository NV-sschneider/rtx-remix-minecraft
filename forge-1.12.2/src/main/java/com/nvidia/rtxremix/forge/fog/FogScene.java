/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.fog;

import com.nvidia.rtxremix.CameraInfoParameterizedEXT;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * Applies observed world fog to finite primary hits before Minecraft UI is composited.
 * The depth texture is borrowed from the currently acquired native frame; no texture is owned here.
 * Reflection interiors and alpha surfaces without primary depth cannot receive independent fog distances.
 */
public final class FogScene implements AutoCloseable {
    private static final String VERTEX = "#version 120\nvoid main(){gl_Position=gl_Vertex;}\n";
    private static final String FRAGMENT = "#version 120\n"
            + "uniform sampler2D PrimaryDepth;\nuniform vec2 FrameSize;\n"
            + "uniform vec4 Camera;\nuniform vec3 FogColor;\nuniform vec3 FogValues;\n"
            + "uniform int FogMode;\nuniform int Radial;\n"
            + "void main(){\n"
            + " vec2 screen=gl_FragCoord.xy/FrameSize;\n"
            + " float depth=texture2D(PrimaryDepth,vec2(screen.x,1.0-screen.y)).r;\n"
            + " if(!(depth>=0.0 && depth<1.0))discard;\n"
            + " float z=Camera.x*Camera.y/(Camera.y-depth*(Camera.y-Camera.x));\n"
            + " vec2 ray=(screen*2.0-1.0)*vec2(Camera.z*Camera.w,Camera.z);\n"
            + " float distance=Radial!=0 ? z*sqrt(1.0+dot(ray,ray)) : z;\n"
            + " float transmission;\n"
            + " if(FogMode==0)transmission=clamp((FogValues.z-distance)/(FogValues.z-FogValues.y),0.0,1.0);\n"
            + " else{float opticalDepth=FogValues.x*distance;\n"
            + " transmission=exp(FogMode==1 ? -opticalDepth : -opticalDepth*opticalDepth);}\n"
            + " gl_FragColor=vec4(FogColor,1.0-transmission);\n}\n";

    private final Thread owner = Thread.currentThread();
    private final IntBuffer viewport = BufferUtils.createIntBuffer(16);
    private final ByteBuffer colorMask = BufferUtils.createByteBuffer(16);
    private final FogState last = new FogState();
    private int program, depthUniform, sizeUniform, cameraUniform, colorUniform, valuesUniform, modeUniform, radialUniform;
    private long applied, missingDepth;

    /** Must be called while depth is acquired, after native color copy and before world HUD/GUI overlays. */
    public boolean draw(FogState fog, int depthTexture, int width, int height, CameraInfoParameterizedEXT camera) {
        checkThread();
        if (fog == null || !fog.enabled()) return false;
        if (depthTexture == 0) { missingDepth++; return false; }
        if (width <= 0 || height <= 0 || camera == null || !Float.isFinite(camera.nearPlane)
                || !Float.isFinite(camera.farPlane) || camera.nearPlane <= 0 || camera.farPlane <= camera.nearPlane
                || !Float.isFinite(camera.fovYInDegrees) || camera.fovYInDegrees <= 0 || camera.fovYInDegrees >= 180
                || !Float.isFinite(camera.aspect) || camera.aspect <= 0)
            throw new IllegalArgumentException("Fog needs the submitted native camera and a nonempty framebuffer");
        if (program == 0) createProgram();
        composite(fog, depthTexture, width, height, camera);
        last.set(true, fog.mode(), fog.radial(), fog.red(), fog.green(), fog.blue(), fog.density(), fog.start(), fog.end());
        applied++; return true;
    }

    private void composite(FogState fog, int depthTexture, int width, int height, CameraInfoParameterizedEXT camera) {
        int oldProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM), active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB), dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        int srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA), dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        int eqRgb = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB), eqAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND), depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean alpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST), cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST), stencil = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
        boolean srgb = GL11.glIsEnabled(GL30.GL_FRAMEBUFFER_SRGB), depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        viewport.clear(); GL11.glGetInteger(GL11.GL_VIEWPORT, viewport);
        colorMask.clear(); GL11.glGetBoolean(GL11.GL_COLOR_WRITEMASK, colorMask);
        GL13.glActiveTexture(GL13.GL_TEXTURE0); int binding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            // Raw GL is restored before returning, so Minecraft's cached state remains unchanged.
            GL11.glDisable(GL11.GL_DEPTH_TEST); GL11.glDepthMask(false); GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE); GL11.glDisable(GL11.GL_SCISSOR_TEST); GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB); GL11.glColorMask(true, true, true, false);
            GL11.glViewport(0, 0, width, height); GL11.glEnable(GL11.GL_BLEND);
            GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ZERO, GL11.GL_ONE);
            GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);
            GL20.glUseProgram(program); GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTexture);
            GL20.glUniform1i(depthUniform, 0); GL20.glUniform2f(sizeUniform, width, height);
            GL20.glUniform4f(cameraUniform, camera.nearPlane, camera.farPlane,
                    (float) Math.tan(Math.toRadians(camera.fovYInDegrees) * 0.5), camera.aspect);
            GL20.glUniform3f(colorUniform, fog.red(), fog.green(), fog.blue());
            GL20.glUniform3f(valuesUniform, fog.density(), fog.start(), fog.end());
            GL20.glUniform1i(modeUniform, fog.mode() == FogState.LINEAR ? 0 : fog.mode() == FogState.EXP ? 1 : 2);
            GL20.glUniform1i(radialUniform, fog.radial() ? 1 : 0);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glVertex2f(-1, -1); GL11.glVertex2f(1, -1); GL11.glVertex2f(1, 1); GL11.glVertex2f(-1, 1);
            GL11.glEnd();
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, binding); GL13.glActiveTexture(active); GL20.glUseProgram(oldProgram);
            GL14.glBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha); GL20.glBlendEquationSeparate(eqRgb, eqAlpha);
            restore(GL11.GL_BLEND, blend); restore(GL11.GL_DEPTH_TEST, depth); GL11.glDepthMask(depthMask);
            restore(GL11.GL_ALPHA_TEST, alpha); restore(GL11.GL_CULL_FACE, cull); restore(GL11.GL_SCISSOR_TEST, scissor);
            restore(GL11.GL_STENCIL_TEST, stencil); restore(GL30.GL_FRAMEBUFFER_SRGB, srgb);
            GL11.glColorMask(colorMask.get(0) != 0, colorMask.get(1) != 0, colorMask.get(2) != 0, colorMask.get(3) != 0);
            GL11.glViewport(viewport.get(0), viewport.get(1), viewport.get(2), viewport.get(3));
        }
    }

    private void createProgram() {
        int vertex = compile(GL20.GL_VERTEX_SHADER, VERTEX), fragment = 0, linked = 0;
        try {
            fragment = compile(GL20.GL_FRAGMENT_SHADER, FRAGMENT); linked = GL20.glCreateProgram();
            GL20.glAttachShader(linked, vertex); GL20.glAttachShader(linked, fragment); GL20.glLinkProgram(linked);
            if (GL20.glGetProgrami(linked, GL20.GL_LINK_STATUS) == GL11.GL_FALSE)
                throw new IllegalStateException("Cannot link world fog shader: " + GL20.glGetProgramInfoLog(linked, 4096));
            program = linked; linked = 0;
            depthUniform = uniform("PrimaryDepth"); sizeUniform = uniform("FrameSize"); cameraUniform = uniform("Camera");
            colorUniform = uniform("FogColor"); valuesUniform = uniform("FogValues"); modeUniform = uniform("FogMode");
            radialUniform = uniform("Radial");
        } finally {
            GL20.glDeleteShader(vertex); if (fragment != 0) GL20.glDeleteShader(fragment);
            if (linked != 0) GL20.glDeleteProgram(linked);
        }
    }

    private int uniform(String name) {
        int location = GL20.glGetUniformLocation(program, name);
        if (location < 0) { GL20.glDeleteProgram(program); program = 0; throw new IllegalStateException("Missing fog uniform " + name); }
        return location;
    }

    private static int compile(int kind, String source) {
        int shader = GL20.glCreateShader(kind); GL20.glShaderSource(shader, source); GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader, 4096); GL20.glDeleteShader(shader);
            throw new IllegalStateException("Cannot compile world fog shader: " + log);
        }
        return shader;
    }

    public long appliedFrames() { return applied; }
    public long missingDepthFrames() { return missingDepth; }
    /** Last applied values remain readable after the capture frame's borrowed state is cleared. */
    public FogState lastApplied() { return last; }
    @Override public void close() { checkThread(); if (program != 0) { GL20.glDeleteProgram(program); program = 0; } last.clear(); }
    private void checkThread() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("World fog requires its creating render thread");
    }
    private static void restore(int capability, boolean enabled) { if (enabled) GL11.glEnable(capability); else GL11.glDisable(capability); }
}
