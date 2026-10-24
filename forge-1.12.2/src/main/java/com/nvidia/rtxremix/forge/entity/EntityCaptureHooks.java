/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

import com.nvidia.rtxremix.forge.item.ItemCaptureHooks;
import java.nio.FloatBuffer;
import java.nio.ByteBuffer;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.util.vector.Matrix4f;

/** Observes existing model draws. All entry points run on the client render thread. */
public final class EntityCaptureHooks {
    static final int MAX_PARTS = 1280;
    private static final int MAX_WORLD_PARTS = 1024;
    private static final int MAX_OWNER_PARTS = 256;
    private static final Field ORIGIN_X = ReflectionHelper.findField(RenderManager.class, new String[] {"renderPosX", "field_78725_b"});
    private static final Field ORIGIN_Y = ReflectionHelper.findField(RenderManager.class, new String[] {"renderPosY", "field_78726_c"});
    private static final Field ORIGIN_Z = ReflectionHelper.findField(RenderManager.class, new String[] {"renderPosZ", "field_78723_d"});
    private static final List<CapturedEntityPart> PARTS = new ArrayList<>();
    private static final List<CapturedEntityPart> POOL = new ArrayList<>();
    private static final List<CapturedEntityPart> FRAME_VIEW = Collections.unmodifiableList(PARTS);
    private static final FloatBuffer BUFFER = BufferUtils.createFloatBuffer(16);
    private static final Matrix4f VIEW = new Matrix4f();
    private static final Matrix4f INVERSE = new Matrix4f();
    private static final Matrix4f PART_VIEW = new Matrix4f();
    private static final Matrix4f WORLD = new Matrix4f();
    private static final Matrix4f VBO_TEXTURE = new Matrix4f();
    private static final ModelCaptureScope SCOPE = new ModelCaptureScope();
    private static final EntityTriangles TRIANGLES = new EntityTriangles();
    private static final EntityVboCache VBO_CACHE = new EntityVboCache();
    private static final OffscreenEntityCapture OFFSCREEN = new OffscreenEntityCapture();
    private static final boolean[] MANAGED_SCOPES = new boolean[64];
    private static final Object[] BUFFER_GROUPS = new Object[MAX_OWNER_PARTS];
    static { for (int i = 0; i < BUFFER_GROUPS.length; i++) BUFFER_GROUPS[i] = new Object(); }
    private static ByteBuffer vboReadback;
    private static Object vboWorld;
    private static long vboUploads, vboReadbacks, vboDraws, deferredVboDraws;
    private static final Logger LOGGER = LogManager.getLogger("RLCraftRemixEntities");
    private static boolean armed;
    private static int ownerStart;
    private static int ownerTriangleStart;
    private static int occurrence;
    private static int managedDepth, nameDepth;
    private static Object observedTriangleBuffer;
    private static long nonlivingBuffers;
    private static double originX, originY, originZ;
    private static ITextureObject texture;
    private static Throwable failure;
    private static long unsupportedTriangles, rejectedTriangles;
    private static boolean warnedTriangles;

    private EntityCaptureHooks() { }

    public static void beginFrame(boolean capture) {
        discard();
        Object world = capture ? Minecraft.getMinecraft().world : null;
        if (world != vboWorld || !capture) { clearVboCache(); vboWorld = world; }
        VBO_CACHE.beginFrame();
        EntityGlintHooks.beginFrame();
        OFFSCREEN.beginFrame();
        armed = capture;
    }

    /** Stops observing after the world and hand draws, before screen overlays and GUI previews. */
    public static List<CapturedEntityPart> finishFrame() {
        armed = false;
        SCOPE.clear();
        texture = null;
        if (failure != null) {
            throw new IllegalStateException("Model capture failed", failure);
        }
        return FRAME_VIEW;
    }

    public static void discard() {
        armed = false;
        SCOPE.clear();
        texture = null;
        failure = null;
        for (int i = 0; i < POOL.size(); i++) { POOL.get(i).clear(); }
        PARTS.clear();
        TRIANGLES.reset();
        unsupportedTriangles = rejectedTriangles = 0;
        managedDepth = nameDepth = 0;
        observedTriangleBuffer = null;
        FirstPersonCapture.reset();
        EntityGlintHooks.reset();
    }

    /** Runs before finishFrame, with entity/item observers still armed and the world view intact. */
    public static void captureOffscreen(float partialTicks) {
        if (armed && failure == null && !SCOPE.isOpen()) OFFSCREEN.capture(partialTicks);
    }
    public static int offscreenEntityCount() { return OFFSCREEN.capturedCount(); }
    public static long offscreenFailureCount() { return OFFSCREEN.failureCount(); }
    public static long nonlivingBufferCount() { return nonlivingBuffers; }
    public static boolean normallyRendered(UUID id) { return OFFSCREEN.normallyRendered(id); }
    static boolean isArmed() { return armed && failure == null; }
    static void captureLocalPlayer(float partialTicks) { if (isArmed() && !SCOPE.isOpen()) OFFSCREEN.captureLocalPlayer(partialTicks); }

    static void beginFirstPerson(Entity player) {
        if (!isArmed() || !SCOPE.enter(player, true)) return;
        ownerStart = PARTS.size(); ownerTriangleStart = TRIANGLES.count(); occurrence = 0;
        texture = null; observedTriangleBuffer = null;
    }
    static void endFirstPerson() { endScope(); }

    /** The item observer borrows its holder's exact pose and category, including the hidden body. */
    public static Object activeOwner() { return isArmed() && nameDepth == 0 ? SCOPE.owner() : null; }
    public static int activeCategory() { return FirstPersonCapture.category(); }
    public static void currentTransform(Matrix4f modelView, Matrix4f scratch, float[] destination) {
        if (FirstPersonCapture.hand()) FirstPersonCapture.transform(modelView, scratch, destination);
        else EntityTransform.world(INVERSE, modelView, scratch, originX, originY, originZ, destination);
    }
    static void beginSupplementary() { ownerStart = PARTS.size(); ownerTriangleStart = TRIANGLES.count(); }
    static void discardSupplementary() {
        for (int i = PARTS.size() - 1; i >= ownerStart; i--) PARTS.remove(i).clear();
        TRIANGLES.truncate(ownerTriangleStart);
        SCOPE.clear();
        texture = null;
        managedDepth = nameDepth = 0;
    }

    /** Marks real manager dispatches, and scopes only nonliving primary renderer bodies. */
    public static void beginManaged(Object rendered) {
        if (!armed || failure != null) return;
        try {
            if (managedDepth == MANAGED_SCOPES.length) throw new IllegalStateException("Entity render nesting exceeds capture bound");
            boolean open = managedDepth != 0 || !(rendered instanceof EntityLivingBase);
            MANAGED_SCOPES[managedDepth++] = open;
            if (rendered instanceof Entity && ((Entity) rendered).world == Minecraft.getMinecraft().world) OFFSCREEN.observe(rendered);
            if (!open || !SCOPE.enter(rendered, false)) return;
            if (!(rendered instanceof Entity)
                    || ((Entity) rendered).world != Minecraft.getMinecraft().world || ((Entity) rendered).isDead) {
                SCOPE.reject(); return;
            }
            prepareOwner();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) { fail(error); }
    }

    public static void activateManaged() {
        if (armed && failure == null && managedDepth == 1 && MANAGED_SCOPES[0]) SCOPE.activate();
    }
    public static void deactivateManaged() {
        if (armed && failure == null && managedDepth == 1 && MANAGED_SCOPES[0]) SCOPE.deactivate();
    }
    public static void endManaged() {
        if (!armed || failure != null || managedDepth == 0) return;
        if (MANAGED_SCOPES[--managedDepth]) endScope();
    }
    public static void beginName() { if (armed) nameDepth++; }
    public static void endName() { if (armed && nameDepth > 0) nameDepth--; }

    private static void prepareOwner() throws IllegalAccessException {
        ownerStart = PARTS.size();
        ownerTriangleStart = TRIANGLES.count();
        occurrence = 0;
        texture = null;
        observedTriangleBuffer = null;
        readView(VIEW);
        if (Matrix4f.invert(VIEW, INVERSE) == null) throw new IllegalStateException("Entity base view is singular");
        RenderManager manager = Minecraft.getMinecraft().getRenderManager();
        originX = ORIGIN_X.getDouble(manager);
        originY = ORIGIN_Y.getDouble(manager);
        originZ = ORIGIN_Z.getDouble(manager);
    }

    // Object descriptors remain valid in both MCP development and SRG production classes.
    public static void beginLiving(Object rendered) {
        if (!armed || failure != null) { return; }
        if (!SCOPE.enter(rendered, false)) { return; }
        try {
            if (!(rendered instanceof EntityLivingBase)) { SCOPE.reject(); return; }
            prepareOwner();
            OFFSCREEN.observe(rendered);
            // Snapshot before Forge Pre modifies the view; its body rotations and
            // offsets must remain in the captured part matrix. The render manager's
            // origin also preserves translations supplied through the render arguments.
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) { fail(ex); }
    }

    public static void endLiving() {
        endScope();
    }

    /** Forge Pre has accepted this draw; canceled renders keep their scope inactive. */
    public static void activateLiving() {
        if (armed && failure == null) SCOPE.activate();
    }

    /** Opens an inactive dispatcher scope; fast renderers never activate model capture. */
    public static void beginTile(Object rendered, double x, double y, double z, int destroyStage) {
        if (!armed || failure != null) return;
        if (!SCOPE.enter(rendered, false)) return;
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (!(rendered instanceof TileEntity) || destroyStage >= 0 || mc.world == null) {
                SCOPE.reject();
                return;
            }
            TileEntity tile = (TileEntity) rendered;
            BlockPos pos = tile.getPos();
            if (tile.isInvalid() || tile.getWorld() != mc.world || !mc.world.isBlockLoaded(pos)
                    || mc.world.getTileEntity(pos) != tile) {
                SCOPE.reject();
                return;
            }
            ownerStart = PARTS.size();
            ownerTriangleStart = TRIANGLES.count();
            occurrence = 0;
            texture = null;
            originX = pos.getX() - x;
            originY = pos.getY() - y;
            originZ = pos.getZ() - z;
        } catch (RuntimeException | LinkageError ex) { fail(ex); }
    }

    /** Called immediately before the normal TESR invocation, while its caller's matrix is intact. */
    public static void activateTile() {
        if (!armed || failure != null || SCOPE.outerOwner() == null) return;
        try {
            readView(VIEW);
            if (Matrix4f.invert(VIEW, INVERSE) == null) throw new IllegalStateException("Tile-entity base view is singular");
            SCOPE.activate();
        } catch (RuntimeException | LinkageError ex) { fail(ex); }
    }

    public static void endTile() { endScope(); }

    private static void endScope() {
        if (!armed || failure != null) return;
        SCOPE.leave();
        if (!SCOPE.isOpen()) texture = null;
    }

    public static void boundTexture(Object location) {
        if (!armed || SCOPE.owner() == null || failure != null) { return; }
        try {
            texture = Minecraft.getMinecraft().getTextureManager().getTexture((ResourceLocation) location);
        } catch (RuntimeException | LinkageError ex) { fail(ex); }
    }

    public static void capturePart(int displayList, Object renderedPart, float scale) {
        if (!armed || SCOPE.owner() == null || texture == null || failure != null) { return; }
        try {
            ModelRenderer model = (ModelRenderer) renderedPart;
            if (model.cubeList.isEmpty()) { return; }
            CapturedEntityPart part = captureState(false);
            if (part == null) return;
            part.modelPart = model;
            part.displayList = displayList;
            part.scale = scale;
            if (part.surface != EntitySurface.ORDINARY) {
                int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
                try {
                    OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
                    BUFFER.clear(); GL11.glGetFloat(GL11.GL_TEXTURE_MATRIX, BUFFER); VBO_TEXTURE.load(BUFFER);
                } finally { OpenGlHelper.setActiveTexture(active); }
                if (VBO_TEXTURE.m03 != 0 || VBO_TEXTURE.m13 != 0 || VBO_TEXTURE.m33 != 1) return;
                float[] uv = part.textureTransform;
                uv[0] = VBO_TEXTURE.m00; uv[1] = VBO_TEXTURE.m10; uv[2] = VBO_TEXTURE.m30;
                uv[3] = VBO_TEXTURE.m01; uv[4] = VBO_TEXTURE.m11; uv[5] = VBO_TEXTURE.m31;
                for (float value : uv) if (!Float.isFinite(value)) return;
                part.transformedTexture = uv[0] != 1 || uv[1] != 0 || uv[2] != 0 || uv[3] != 0 || uv[4] != 1 || uv[5] != 0;
            }
            PARTS.add(part);
            occurrence++;
        } catch (RuntimeException | LinkageError ex) { fail(ex); }
    }

    /** Observes only Lycanites' completed fixed-function CPU triangle path. */
    public static void captureTriangles(Object completed, Object group) {
        if (!armed || SCOPE.owner() == null || texture == null || failure != null) return;
        try {
            BufferBuilder buffer = (BufferBuilder) completed;
            if (buffer.getVertexCount() == 0) return;
            VertexFormat format = buffer.getVertexFormat();
            if (group == null || buffer.getDrawMode() != GL11.GL_TRIANGLES
                    || !DefaultVertexFormats.POSITION_TEX_COLOR_NORMAL.equals(format)
                    || GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) != 0) {
                rejectTriangles();
                return;
            }
            CapturedEntityPart part = captureState(true);
            if (part == null) return;
            int first = TRIANGLES.count();
            if (!TRIANGLES.append(buffer.getByteBuffer(), buffer.getVertexCount(), format.getSize(),
                    format.getUvOffsetById(0), format.getColorOffset(), format.getNormalOffset())) {
                rejectTriangles();
                return;
            }
            part.triangles = TRIANGLES;
            part.triangleGroup = group;
            part.firstVertex = first;
            part.vertexCount = buffer.getVertexCount();
            part.geometryFingerprint = TRIANGLES.fingerprint();
            part.scale = 1;
            PARTS.add(part);
            occurrence++;
            observedTriangleBuffer = completed;
        } catch (RuntimeException | LinkageError ex) { fail(ex); }
    }

    /** Observes completed CPU geometry in a validated entity, hand, or ordinary TESR draw. */
    public static void captureEntityBuffer(Object completed) {
        Object owner = SCOPE.owner();
        if (!armed || failure != null || nameDepth != 0 || !(owner instanceof Entity || owner instanceof TileEntity)
                || owner instanceof EntityLivingBase && !FirstPersonCapture.hand() && !EntityGlintHooks.active()
                || owner instanceof EntityItem && !EntityGlintHooks.active()
                || texture == null || ItemCaptureHooks.ownsModelBuffer()) return;
        if (completed == observedTriangleBuffer) { observedTriangleBuffer = null; return; }
        try {
            BufferBuilder buffer = (BufferBuilder) completed;
            if (buffer.getVertexCount() == 0 || GL11.glGetInteger(GL11.GL_LIST_INDEX) != 0) return;
            VertexFormat format = buffer.getVertexFormat();
            int mode = buffer.getDrawMode();
            if (mode != GL11.GL_TRIANGLES && mode != GL11.GL_QUADS
                    || !(DefaultVertexFormats.POSITION_TEX.equals(format) || DefaultVertexFormats.POSITION_TEX_COLOR.equals(format)
                    || DefaultVertexFormats.POSITION_TEX_NORMAL.equals(format) || DefaultVertexFormats.POSITION_TEX_COLOR_NORMAL.equals(format)
                    || DefaultVertexFormats.ITEM.equals(format) || DefaultVertexFormats.BLOCK.equals(format))
                    || GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) != 0) {
                unsupportedTriangles++;
                return;
            }
            CapturedEntityPart part = captureState(true);
            if (part == null) return;
            BUFFER.clear(); GL11.glGetFloat(GL11.GL_CURRENT_COLOR, BUFFER);
            int argb = channel(BUFFER.get(3)) << 24 | channel(BUFFER.get(0)) << 16
                    | channel(BUFFER.get(1)) << 8 | channel(BUFFER.get(2));
            int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            try {
                OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
                BUFFER.clear(); GL11.glGetFloat(GL11.GL_TEXTURE_MATRIX, BUFFER); VBO_TEXTURE.load(BUFFER);
            } finally { OpenGlHelper.setActiveTexture(active); }
            int start = TRIANGLES.count();
            if (part.surface == EntitySurface.GLINT && EntityGlintHooks.hasItemQuads()) {
                if (mode != GL11.GL_QUADS || !EntityGlintHooks.append(TRIANGLES, buffer, format.getSize(),
                        format.getUvOffsetById(0), format.hasColor() ? format.getColorOffset() : -1,
                        format.hasNormal() ? format.getNormalOffset() : -1, argb, VBO_TEXTURE, part.alphaReference)) return;
            } else if (!TRIANGLES.appendPrimitives(buffer.getByteBuffer(), buffer.getVertexCount(), mode == GL11.GL_QUADS ? 4 : 3,
                    format.getSize(), format.getUvOffsetById(0), format.hasColor() ? format.getColorOffset() : -1,
                    format.hasNormal() ? format.getNormalOffset() : -1, argb, VBO_TEXTURE,
                    part.surface == EntitySurface.GLINT ? EntitySurface.ITEM_GLINT_SHELL_OFFSET : 0)) {
                rejectTriangles(); return;
            }
            if (TRIANGLES.count() == start) return;
            part.categoryFlags = EntitySurface.categories(FirstPersonCapture.category(), part.surface,
                    part.surface == EntitySurface.GLINT);
            part.triangles = TRIANGLES;
            part.triangleGroup = BUFFER_GROUPS[occurrence];
            part.firstVertex = start;
            part.vertexCount = TRIANGLES.count() - start;
            part.geometryFingerprint = TRIANGLES.fingerprint();
            part.scale = 1;
            PARTS.add(part);
            occurrence++;
            nonlivingBuffers++;
        } catch (RuntimeException | LinkageError error) { fail(error); }
    }

    /** Observed only after the exact Lycanites Mesh upload has succeeded. */
    public static void captureVboUpload(Object completedBytes, Object mesh) {
        if (!armed || failure != null) return;
        try {
            if (VBO_CACHE.put(mesh, GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING), (ByteBuffer) completedBytes)) vboUploads++;
        } catch (Throwable ex) { fail(ex); }
    }

    /** The mesh's successful GL deletion invalidates snapshots even if the GL id is recycled. */
    public static void deleteVbo(Object mesh) {
        try { VBO_CACHE.remove(mesh); }
        catch (Throwable ex) { if (armed) fail(ex); }
    }

    /** Current fixed-function state is observed at VBOModel's actual array draw. */
    public static void captureVboTriangles(int mode, int first, int count, Object group, Object mesh) {
        if (!armed || SCOPE.owner() == null || texture == null || failure != null) return;
        try {
            if (mode != GL11.GL_TRIANGLES || first != 0 || count <= 0 || count % 3 != 0
                    || count > EntityTriangles.MAX_GROUP_VERTICES || group == null || mesh == null
                    || GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) != 0 || GL11.glIsEnabled(GL11.GL_COLOR_ARRAY)
                    || GL11.glGetInteger(GL13.GL_CLIENT_ACTIVE_TEXTURE) != OpenGlHelper.defaultTexUnit) {
                unsupportedTriangles++;
                rejectTriangles();
                return;
            }
            int bufferId = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            if (bufferId == 0) { rejectTriangles(); return; }
            EntityVboCache.Snapshot snapshot = VBO_CACHE.get(mesh, bufferId, count);
            if (snapshot == null) {
                if (!VBO_CACHE.reserveReadback(count)) {
                    deferredVboDraws++;
                    rejectOwner();
                    return;
                }
                int bytes = count * EntityVboCache.STRIDE;
                if (GL15.glGetBufferParameteri(GL15.GL_ARRAY_BUFFER, GL15.GL_BUFFER_SIZE) < bytes) {
                    rejectTriangles();
                    return;
                }
                if (vboReadback == null || vboReadback.capacity() < bytes) vboReadback = EntityMesh.direct(bytes);
                vboReadback.clear(); vboReadback.limit(bytes);
                // The actual bound VBO is read once after a cache miss, under a one-read/frame cap.
                GL15.glGetBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, vboReadback);
                vboReadbacks++;
                if (!VBO_CACHE.put(mesh, bufferId, vboReadback)) { rejectTriangles(); return; }
                snapshot = VBO_CACHE.get(mesh, bufferId, count);
            }
            CapturedEntityPart part = captureState(true);
            if (part == null) return;
            BUFFER.clear();
            GL11.glGetFloat(GL11.GL_CURRENT_COLOR, BUFFER);
            int argb = channel(BUFFER.get(3)) << 24 | channel(BUFFER.get(0)) << 16
                    | channel(BUFFER.get(1)) << 8 | channel(BUFFER.get(2));
            int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            try {
                OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
                BUFFER.clear(); GL11.glGetFloat(GL11.GL_TEXTURE_MATRIX, BUFFER); VBO_TEXTURE.load(BUFFER);
            } finally { OpenGlHelper.setActiveTexture(active); }
            int start = TRIANGLES.count();
            if (!TRIANGLES.appendVbo(snapshot.vertices, count, argb, VBO_TEXTURE)) { rejectTriangles(); return; }
            part.triangles = TRIANGLES;
            part.triangleGroup = group;
            part.firstVertex = start;
            part.vertexCount = count;
            part.geometryFingerprint = TRIANGLES.fingerprint();
            part.scale = 1;
            PARTS.add(part);
            occurrence++;
            vboDraws++;
        } catch (Throwable ex) { fail(ex); }
    }

    /** Called by reload/release, not the per-frame discard that keeps reusable VBO snapshots. */
    public static void clearVboCache() {
        VBO_CACHE.clear(); vboReadback = null; vboWorld = null; OFFSCREEN.release(!armed); EntityGlintHooks.clearCache();
    }
    public static long vboUploadSnapshots() { return vboUploads; }
    public static long vboReadbackCount() { return vboReadbacks; }
    public static long vboDrawCount() { return vboDraws; }
    public static long deferredVboDrawCount() { return deferredVboDraws; }
    public static long vboCacheBytes() { return VBO_CACHE.bytes(); }

    public static long unsupportedTriangleDraws() { return unsupportedTriangles; }
    public static long rejectedTriangleDraws() { return rejectedTriangles; }

    private static void rejectTriangles() {
        rejectedTriangles++;
        if (!warnedTriangles) {
            warnedTriangles = true;
            LOGGER.warn("OBJ capture rejected an unsupported or over-budget triangle group; that owner's partial capture is omitted");
        }
        rejectOwner();
    }

    private static void rejectOwner() {
        for (int i = PARTS.size() - 1; i >= ownerStart; i--) PARTS.remove(i).clear();
        TRIANGLES.truncate(ownerTriangleStart);
        SCOPE.reject();
    }

    private static CapturedEntityPart captureState(boolean vertexColors) {
        if (nameDepth != 0 || SCOPE.owner() instanceof EntityItem && !EntityGlintHooks.active()) return null;
        boolean alphaBlend = GL11.glIsEnabled(GL11.GL_BLEND);
        int surface = EntitySurface.classify(alphaBlend, alphaBlend ? GL11.glGetInteger(GL11.GL_BLEND_SRC) : 0,
                alphaBlend ? GL11.glGetInteger(GL11.GL_BLEND_DST) : 0,
                alphaBlend ? GL11.glGetInteger(GL14.GL_BLEND_EQUATION) : 0, EntityGlintHooks.active());
        if (surface < 0) return null;
        BUFFER.clear();
        GL11.glGetFloat(GL11.GL_CURRENT_COLOR, BUFFER);
        if (!vertexColors && BUFFER.get(3) < 0.999f) { return null; }
        int argb = vertexColors ? 0xffffffff : channel(BUFFER.get(3)) << 24 | channel(BUFFER.get(0)) << 16
                | channel(BUFFER.get(1)) << 8 | channel(BUFFER.get(2));
        int limit = FirstPersonCapture.hand() ? MAX_PARTS : MAX_WORLD_PARTS;
        if (PARTS.size() >= limit || occurrence == MAX_OWNER_PARTS) {
            rejectOwner();
            return null;
        }
        int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int textureId;
        try {
            OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
            if (!GL11.glIsEnabled(GL11.GL_TEXTURE_2D)) { return null; }
            textureId = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        } finally { OpenGlHelper.setActiveTexture(active); }
        if (textureId == 0 || texture.getGlTextureId() != textureId) { return null; }
        if (PARTS.size() == POOL.size()) { POOL.add(new CapturedEntityPart()); }
        CapturedEntityPart part = POOL.get(PARTS.size());
        part.clear();
        part.owner = SCOPE.owner();
        part.occurrence = occurrence;
        part.categoryFlags = EntitySurface.categories(FirstPersonCapture.category(), surface, false);
        part.surface = surface;
        part.textureId = textureId;
        part.textureIdentity = texture;
        part.argb = argb;
        part.alphaBlend = alphaBlend;
        part.alphaCompare = GL11.glIsEnabled(GL11.GL_ALPHA_TEST)
                ? GL11.glGetInteger(GL11.GL_ALPHA_TEST_FUNC) - GL11.GL_NEVER : 7;
        part.alphaReference = (int) (GL11.glGetFloat(GL11.GL_ALPHA_TEST_REF) * 255);
        readView(PART_VIEW);
        currentTransform(PART_VIEW, WORLD, part.transform);
        return part;
    }

    private static int channel(float value) { return Math.max(0, Math.min(255, Math.round(value * 255))); }

    private static void readView(Matrix4f destination) {
        BUFFER.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, BUFFER);
        destination.load(BUFFER);
    }

    static void fail(Throwable error) {
        failure = error;
        SCOPE.reject();
        texture = null;
    }
}
