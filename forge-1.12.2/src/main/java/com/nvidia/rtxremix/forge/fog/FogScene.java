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
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;

/**
 * Applies observed world fog to finite primary hits before Minecraft UI is composited.
 * The depth texture is borrowed from the currently acquired native frame. Two bounded R16F images
 * retain fog coverage only for an unchanged camera; they never retain native color or depth ownership.
 * Fog opacity is reconstructed before resolving its coverage to output resolution. Interpolating
 * projected depth instead would invent intermediate surfaces at silhouettes and sky boundaries.
 * Reflection interiors and alpha surfaces without primary depth cannot receive independent fog distances.
 */
public final class FogScene implements AutoCloseable {
    private static final org.apache.logging.log4j.Logger LOGGER = org.apache.logging.log4j.LogManager.getLogger("RLCraftRemixFog");
    private static final String VERTEX = "#version 120\nvoid main(){gl_Position=gl_Vertex;}\n";
    private static final String FRAGMENT = "#version 120\n"
            + "uniform sampler2D PrimaryDepth;\nuniform vec2 FrameSize;\nuniform vec2 DepthSize;\n"
            + "uniform vec4 Camera;\nuniform vec3 FogColor;\nuniform vec3 FogValues;\n"
            + "uniform int FogMode;\nuniform int Radial;\nuniform int NearestControl;\n"
            + "uniform sampler2D HistoryOpacity;\nuniform int HistoryPass;\nuniform float HistoryWeight;\n"
            + "float opacity(vec2 screen){\n"
            + " float depth=texture2D(PrimaryDepth,vec2(screen.x,1.0-screen.y)).r;\n"
            + " if(!(depth>=0.0 && depth<1.0))return 0.0;\n"
            + " float z=Camera.x*Camera.y/(Camera.y-depth*(Camera.y-Camera.x));\n"
            + " vec2 ray=(screen*2.0-1.0)*vec2(Camera.z*Camera.w,Camera.z);\n"
            + " float distance=Radial!=0 ? z*sqrt(1.0+dot(ray,ray)) : z;\n"
            + " float transmission;\n"
            + " if(FogMode==0)transmission=clamp((FogValues.z-distance)/(FogValues.z-FogValues.y),0.0,1.0);\n"
            + " else{float opticalDepth=FogValues.x*distance;\n"
            + " transmission=exp(FogMode==1 ? -opticalDepth : -opticalDepth*opticalDepth);}\n"
            + " return 1.0-transmission;\n}\n"
            + "float opacityAt(vec2 pixel){\n"
            + " return opacity((clamp(pixel,vec2(0.0),DepthSize-1.0)+0.5)/DepthSize);\n}\n"
            + "void main(){\n"
            + " vec2 screen=gl_FragCoord.xy/FrameSize;\n"
            + " float alpha;\n"
            + " if(HistoryPass==1){\n"
            + "  alpha=opacity(screen);\n"
            + "  if(HistoryWeight<1.0){\n"
            + "   float low=alpha, high=alpha;\n"
            + "   vec2 pixel=floor(gl_FragCoord.xy);\n"
            + "   for(int y=-1;y<=1;y++)for(int x=-1;x<=1;x++){\n"
            + "    float current=opacityAt(pixel+vec2(float(x),float(y)));\n"
            + "    low=min(low,current);high=max(high,current);\n"
            + "   }\n"
            + "   float previous=clamp(texture2D(HistoryOpacity,screen).r,low,high);\n"
            + "   alpha=mix(previous,alpha,HistoryWeight);\n"
            + "  }\n"
            + "  gl_FragColor=vec4(alpha,0.0,0.0,1.0);return;\n"
            + " }\n"
            + " if(HistoryPass==2)alpha=texture2D(HistoryOpacity,screen).r;\n"
            + " else if(NearestControl!=0)alpha=opacity(screen);\n"
            + " else{\n"
            + "  vec2 pixel=screen*DepthSize-0.5;\n"
            + "  vec2 origin=floor(pixel), weight=fract(pixel);\n"
            + "  float low=mix(opacityAt(origin),opacityAt(origin+vec2(1.0,0.0)),weight.x);\n"
            + "  float high=mix(opacityAt(origin+vec2(0.0,1.0)),opacityAt(origin+vec2(1.0,1.0)),weight.x);\n"
            + "  alpha=mix(low,high,weight.y);\n"
            + " }\n"
            + " if(alpha<=0.0)discard;\n"
            + " gl_FragColor=vec4(FogColor,alpha);\n}\n";

    private final Thread owner = Thread.currentThread();
    private final IntBuffer viewport = BufferUtils.createIntBuffer(16);
    private final ByteBuffer colorMask = BufferUtils.createByteBuffer(16);
    private final FogState last = new FogState();
    private final FogHistory history = new FogHistory();
    private final int[] historyTextures = new int[2];
    private int historyFramebuffer, historyWidth, historyHeight, historyIndex;
    private int failedWidth, failedHeight;
    private int program, depthUniform, sizeUniform, depthSizeUniform, cameraUniform, colorUniform, valuesUniform;
    private int modeUniform, radialUniform, nearestUniform;
    private int historyUniform, historyPassUniform, historyWeightUniform;
    private long applied, missingDepth;
    private final boolean edgeDiagnostics = Boolean.getBoolean("rlcraftremix.smoke.fogEdges");
    private final boolean nearestControl = Boolean.getBoolean("rlcraftremix.smoke")
            && Boolean.getBoolean("rlcraftremix.smoke.fogNearest");
    private int observedWidth, observedHeight, observedDepthWidth, observedDepthHeight;

    /** Must be called while depth is acquired, after native color copy and before world HUD/GUI overlays. */
    public boolean draw(FogState fog, int depthTexture, int depthWidth, int depthHeight,
                        int width, int height, CameraInfoParameterizedEXT camera) {
        checkThread();
        if (fog == null || !fog.enabled()) { history.discard(); return false; }
        if (depthTexture == 0) { history.discard(); missingDepth++; return false; }
        if (width <= 0 || height <= 0 || depthWidth <= 0 || depthHeight <= 0
                || camera == null || !Float.isFinite(camera.nearPlane)
                || !Float.isFinite(camera.farPlane) || camera.nearPlane <= 0 || camera.farPlane <= camera.nearPlane
                || !Float.isFinite(camera.fovYInDegrees) || camera.fovYInDegrees <= 0 || camera.fovYInDegrees >= 180
                || !Float.isFinite(camera.aspect) || camera.aspect <= 0)
            throw new IllegalArgumentException("Fog needs the submitted native camera and a nonempty framebuffer");
        if (program == 0) createProgram();
        composite(fog, depthTexture, depthWidth, depthHeight, width, height, camera);
        last.set(true, fog.mode(), fog.radial(), fog.red(), fog.green(), fog.blue(), fog.density(), fog.start(), fog.end());
        applied++; return true;
    }

    private void composite(FogState fog, int depthTexture, int depthWidth, int depthHeight,
                           int width, int height, CameraInfoParameterizedEXT camera) {
        int oldProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM), active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int framebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
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
        GL13.glActiveTexture(GL13.GL_TEXTURE1); int binding1 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        try {
            // Raw GL is restored before returning, so Minecraft's cached state remains unchanged.
            GL11.glDisable(GL11.GL_DEPTH_TEST); GL11.glDepthMask(false); GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE); GL11.glDisable(GL11.GL_SCISSOR_TEST); GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB); GL11.glColorMask(true, true, true, false);
            GL11.glViewport(0, 0, width, height); GL11.glEnable(GL11.GL_BLEND);
            GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ZERO, GL11.GL_ONE);
            GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);
            GL20.glUseProgram(program); GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTexture);
            if (edgeDiagnostics && (observedWidth != width || observedHeight != height
                    || observedDepthWidth != depthWidth || observedDepthHeight != depthHeight)) {
                LOGGER.info("FOG_DEPTH_INPUT output={}x{} primaryDepth={}x{} minFilter={} magFilter={} resolve={}",
                        width, height, depthWidth, depthHeight,
                        GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER),
                        GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER),
                        nearestControl ? "nearest-control" : "stationary-coverage");
                observedWidth = width; observedHeight = height;
                observedDepthWidth = depthWidth; observedDepthHeight = depthHeight;
            }
            GL20.glUniform1i(depthUniform, 0); GL20.glUniform2f(sizeUniform, width, height);
            GL20.glUniform1i(historyUniform, 1);
            GL20.glUniform2f(depthSizeUniform, depthWidth, depthHeight);
            GL20.glUniform1i(nearestUniform, nearestControl ? 1 : 0);
            GL20.glUniform4f(cameraUniform, camera.nearPlane, camera.farPlane,
                    (float) Math.tan(Math.toRadians(camera.fovYInDegrees) * 0.5), camera.aspect);
            GL20.glUniform3f(colorUniform, fog.red(), fog.green(), fog.blue());
            GL20.glUniform3f(valuesUniform, fog.density(), fog.start(), fog.end());
            GL20.glUniform1i(modeUniform, fog.mode() == FogState.LINEAR ? 0 : fog.mode() == FogState.EXP ? 1 : 2);
            GL20.glUniform1i(radialUniform, fog.radial() ? 1 : 0);
            boolean accumulating = !nearestControl && ensureHistory(depthWidth, depthHeight);
            if (accumulating) {
                float weight = history.observe(fog, camera, width, height, depthWidth, depthHeight);
                int next = 1 - historyIndex;
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, historyFramebuffer);
                GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                        GL11.GL_TEXTURE_2D, historyTextures[next], 0);
                GL13.glActiveTexture(GL13.GL_TEXTURE1); GL11.glBindTexture(GL11.GL_TEXTURE_2D, historyTextures[historyIndex]);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                GL11.glDisable(GL11.GL_BLEND); GL11.glViewport(0, 0, depthWidth, depthHeight);
                GL20.glUniform2f(sizeUniform, depthWidth, depthHeight);
                GL20.glUniform1i(historyPassUniform, 1); GL20.glUniform1f(historyWeightUniform, weight);
                quad(); historyIndex = next;
                GL13.glActiveTexture(GL13.GL_TEXTURE1); GL11.glBindTexture(GL11.GL_TEXTURE_2D, historyTextures[historyIndex]);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
            } else { history.discard(); }
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
            GL11.glEnable(GL11.GL_BLEND); GL11.glViewport(0, 0, width, height);
            GL20.glUniform2f(sizeUniform, width, height);
            GL20.glUniform1i(historyPassUniform, accumulating ? 2 : 0);
            quad();
        } finally {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
            GL13.glActiveTexture(GL13.GL_TEXTURE1); GL11.glBindTexture(GL11.GL_TEXTURE_2D, binding1);
            GL13.glActiveTexture(GL13.GL_TEXTURE0); GL11.glBindTexture(GL11.GL_TEXTURE_2D, binding);
            GL13.glActiveTexture(active); GL20.glUseProgram(oldProgram);
            GL14.glBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha); GL20.glBlendEquationSeparate(eqRgb, eqAlpha);
            restore(GL11.GL_BLEND, blend); restore(GL11.GL_DEPTH_TEST, depth); GL11.glDepthMask(depthMask);
            restore(GL11.GL_ALPHA_TEST, alpha); restore(GL11.GL_CULL_FACE, cull); restore(GL11.GL_SCISSOR_TEST, scissor);
            restore(GL11.GL_STENCIL_TEST, stencil); restore(GL30.GL_FRAMEBUFFER_SRGB, srgb);
            GL11.glColorMask(colorMask.get(0) != 0, colorMask.get(1) != 0, colorMask.get(2) != 0, colorMask.get(3) != 0);
            GL11.glViewport(viewport.get(0), viewport.get(1), viewport.get(2), viewport.get(3));
        }
    }

    /** Called inside composite's complete GL-state guard. Failed sizes are not retried each frame. */
    private boolean ensureHistory(int width, int height) {
        if (failedWidth == width && failedHeight == height) return false;
        if (historyFramebuffer != 0 && historyWidth == width && historyHeight == height) return true;
        history.discard(); releaseHistory();
        failedWidth = width; failedHeight = height;
        if (!FogHistory.fits(width, height)) {
            LOGGER.warn("Fog coverage history {}x{} exceeds its bounded pixel capacity; using spatial coverage", width, height);
            return false;
        }
        int unpack = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        try {
            if (width > GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE) || height > GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE))
                throw new IllegalStateException("Fog coverage exceeds the OpenGL texture limit");
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            historyFramebuffer = GL30.glGenFramebuffers();
            if (historyFramebuffer == 0) throw new IllegalStateException("Cannot allocate fog coverage framebuffer");
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, historyFramebuffer);
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
            for (int i = 0; i < historyTextures.length; i++) {
                historyTextures[i] = GL11.glGenTextures();
                if (historyTextures[i] == 0) throw new IllegalStateException("Cannot allocate fog coverage texture");
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, historyTextures[i]);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R16F, width, height, 0,
                        GL11.GL_RED, GL11.GL_FLOAT, (ByteBuffer) null);
                GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                        GL11.GL_TEXTURE_2D, historyTextures[i], 0);
                if (GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE)
                    throw new IllegalStateException("Fog coverage framebuffer is incomplete");
            }
            historyWidth = width; historyHeight = height; historyIndex = 0;
            failedWidth = failedHeight = 0;
            return true;
        } catch (RuntimeException error) {
            releaseHistory();
            LOGGER.warn("Cannot retain stationary fog coverage; using spatial coverage", error);
            return false;
        } finally { GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, unpack); }
    }

    private void releaseHistory() {
        if (historyFramebuffer != 0) { GL30.glDeleteFramebuffers(historyFramebuffer); historyFramebuffer = 0; }
        for (int i = 0; i < historyTextures.length; i++) if (historyTextures[i] != 0) {
            GL11.glDeleteTextures(historyTextures[i]); historyTextures[i] = 0;
        }
        historyWidth = historyHeight = 0;
    }

    private static void quad() {
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(-1, -1); GL11.glVertex2f(1, -1); GL11.glVertex2f(1, 1); GL11.glVertex2f(-1, 1);
        GL11.glEnd();
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
            depthSizeUniform = uniform("DepthSize"); nearestUniform = uniform("NearestControl");
            historyUniform = uniform("HistoryOpacity"); historyPassUniform = uniform("HistoryPass");
            historyWeightUniform = uniform("HistoryWeight");
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
    public void discardHistory() { checkThread(); history.discard(); }
    public int historySamples() { return history.samples(); }
    public long historyResets() { return history.resets(); }
    public int historyResourceCount() {
        return (historyFramebuffer == 0 ? 0 : 1) + (historyTextures[0] == 0 ? 0 : 1) + (historyTextures[1] == 0 ? 0 : 1);
    }
    /** Last applied values remain readable after the capture frame's borrowed state is cleared. */
    public FogState lastApplied() { return last; }
    @Override public void close() {
        checkThread(); history.discard(); releaseHistory();
        if (program != 0) { GL20.glDeleteProgram(program); program = 0; }
        last.clear();
    }
    private void checkThread() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("World fog requires its creating render thread");
    }
    private static void restore(int capability, boolean enabled) { if (enabled) GL11.glEnable(capability); else GL11.glDisable(capability); }
}
