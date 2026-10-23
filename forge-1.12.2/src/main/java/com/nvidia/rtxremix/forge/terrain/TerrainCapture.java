/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.terrain;

import com.nvidia.rtxremix.InstanceInfoBlendEXT;
import com.nvidia.rtxremix.MaterialInfo;
import com.nvidia.rtxremix.MaterialInfoOpaqueEXT;
import com.nvidia.rtxremix.MeshInfo;
import com.nvidia.rtxremix.MeshInfoSurfaceTriangles;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.RemixBridge;
import com.nvidia.rtxremix.TextureFromMemoryInfo;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraft.world.IWorldEventListener;
import net.minecraft.world.World;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.client.MinecraftForgeClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;

/**
 * First Forge 1.12.2 terrain path. All methods run on the Minecraft render thread.
 * Captures one section per update in a 3x3 column area and keeps native instances
 * until their section changes or leaves that area. This owns no world or chunk loads.
 *
 * <p>Only baked block models are captured. Fluids, tile/entity renderers, animation
 * forwarding and custom non-baked rendering need their own integrations. The atlas
 * is a snapshot; call refreshTextures after resource reload, on the render thread.
 */
public final class TerrainCapture implements AutoCloseable {
    private static final Logger LOGGER = LogManager.getLogger("RLCraftRemixTerrain");
    private static final int RADIUS = 1;
    private static final int SECTION_COUNT = 16;
    private static final long MAX_ATLAS_BYTES = 256L * 1024 * 1024;
    private static final AtomicLong HASHES = new AtomicLong(0x524c544552520000L);
    private static final BlockRenderLayer[] LAYERS = BlockRenderLayer.values();
    private static final EnumFacing[] FACES = EnumFacing.values();

    private final RemixAPI api;
    private final Minecraft mc;
    private final WorldClient world;
    private final IWorldEventListener worldUpdates = new WorldUpdates();
    private final Map<ColumnKey, Chunk> columns = new HashMap<ColumnKey, Chunk>();
    private final Map<SectionKey, SectionAsset> sections = new HashMap<SectionKey, SectionAsset>();
    private final Set<SectionKey> dirty = new LinkedHashSet<SectionKey>();
    private final long[] materials = new long[LAYERS.length];
    private final float[] positions = new float[12];
    private final float[] uvs = new float[8];
    private final int[] colors = new int[4];
    private long atlas;
    private long vertexColorExtension;
    private boolean texturesDirty = true;
    private boolean allDirty = true;
    private boolean closed;
    private boolean warnedUnsupportedModel;
    private int centerX;
    private int centerY;
    private int centerZ;

    public TerrainCapture(RemixAPI api, Minecraft mc) {
        if (api == null || mc == null || mc.world == null) {
            throw new IllegalArgumentException("TerrainCapture requires Remix and a joined client world");
        }
        this.api = api;
        this.mc = mc;
        this.world = mc.world;
        world.addEventListener(worldUpdates);
    }

    /** Call before Remix submit, on the render thread. Performs at most one section rebuild. */
    public void update() {
        checkThread();
        if (closed) return;
        if (mc.world != world) {
            close();
            return;
        }
        Entity view = mc.getRenderViewEntity();
        if (view == null) return;
        centerX = MathHelper.floor(view.posX) >> 4;
        centerY = MathHelper.clamp(MathHelper.floor(view.posY) >> 4, 0, SECTION_COUNT - 1);
        centerZ = MathHelper.floor(view.posZ) >> 4;
        if (texturesDirty) {
            clearSections();
            clearTextureResources();
            uploadAtlas();
            texturesDirty = false;
            allDirty = true;
        }
        reconcileColumns();
        if (allDirty) {
            for (ColumnKey column : columns.keySet()) queueColumn(column.x, column.z);
            allDirty = false;
        }
        SectionKey next = nearestDirtySection();
        if (next != null) rebuild(next);
    }

    /** Invalidate changed blocks and sections containing their immediately adjacent faces. */
    public void markBlock(BlockPos pos) {
        checkThread();
        if (closed || pos == null) return;
        queueBlock(pos.getX(), pos.getY(), pos.getZ());
        for (EnumFacing face : FACES) {
            queueBlock(pos.getX() + face.getXOffset(), pos.getY() + face.getYOffset(),
                    pos.getZ() + face.getZOffset());
        }
    }

    public void invalidateAll() {
        checkThread();
        allDirty = true;
    }

    /** Replaces atlas, materials and terrain lazily in the next update; call after resource reload. */
    public void refreshTextures() {
        checkThread();
        texturesDirty = true;
    }

    public int getSectionCount() { return sections.size(); }
    public int getPendingSectionCount() { return dirty.size(); }

    private void reconcileColumns() {
        Set<ColumnKey> wanted = new HashSet<ColumnKey>();
        for (int x = centerX - RADIUS; x <= centerX + RADIUS; x++) {
            for (int z = centerZ - RADIUS; z <= centerZ + RADIUS; z++) {
                Chunk chunk = world.getChunkProvider().getLoadedChunk(x, z);
                if (chunk == null) continue;
                ColumnKey key = new ColumnKey(x, z);
                wanted.add(key);
                if (columns.get(key) != chunk) {
                    columns.put(key, chunk);
                    queueColumnAndNeighbours(x, z);
                }
            }
        }
        Iterator<ColumnKey> old = columns.keySet().iterator();
        while (old.hasNext()) {
            ColumnKey key = old.next();
            if (!wanted.contains(key)) {
                old.remove();
                removeColumn(key);
                queueColumnAndNeighbours(key.x, key.z);
            }
        }
        Iterator<SectionKey> pending = dirty.iterator();
        while (pending.hasNext()) {
            SectionKey key = pending.next();
            if (!columns.containsKey(new ColumnKey(key.x, key.z))) pending.remove();
        }
    }

    private void queueColumnAndNeighbours(int x, int z) {
        queueColumn(x, z);
        queueColumn(x - 1, z);
        queueColumn(x + 1, z);
        queueColumn(x, z - 1);
        queueColumn(x, z + 1);
    }

    private void queueColumn(int x, int z) {
        if (Math.abs((long) x - centerX) > RADIUS || Math.abs((long) z - centerZ) > RADIUS) return;
        for (int y = 0; y < SECTION_COUNT; y++) dirty.add(new SectionKey(x, y, z));
    }

    private void queueBlock(int x, int y, int z) {
        if (y < 0 || y >= 256) return;
        int cx = x >> 4;
        int cz = z >> 4;
        if (Math.abs((long) cx - centerX) <= RADIUS && Math.abs((long) cz - centerZ) <= RADIUS) {
            dirty.add(new SectionKey(cx, y >> 4, cz));
        }
    }

    private void markRange(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        checkThread();
        if (closed) return;
        // Include boundary neighbours, but never scan blocks or uncaptured section coordinates.
        int fromX = Math.max(centerX - RADIUS, (minX - 1) >> 4);
        int toX = Math.min(centerX + RADIUS, (maxX + 1) >> 4);
        int fromY = Math.max(0, (minY - 1) >> 4);
        int toY = Math.min(SECTION_COUNT - 1, (maxY + 1) >> 4);
        int fromZ = Math.max(centerZ - RADIUS, (minZ - 1) >> 4);
        int toZ = Math.min(centerZ + RADIUS, (maxZ + 1) >> 4);
        for (int x = fromX; x <= toX; x++) {
            for (int y = fromY; y <= toY; y++) {
                for (int z = fromZ; z <= toZ; z++) dirty.add(new SectionKey(x, y, z));
            }
        }
    }

    private SectionKey nearestDirtySection() {
        SectionKey nearest = null;
        int distance = Integer.MAX_VALUE;
        for (SectionKey key : dirty) {
            int dx = key.x - centerX;
            int dy = key.y - centerY;
            int dz = key.z - centerZ;
            int candidate = dx * dx + dy * dy + dz * dz;
            if (candidate < distance) {
                distance = candidate;
                nearest = key;
            }
        }
        if (nearest != null) dirty.remove(nearest);
        return nearest;
    }

    private void rebuild(SectionKey key) {
        Chunk chunk = columns.get(new ColumnKey(key.x, key.z));
        if (chunk == null) return;
        ExtendedBlockStorage storage = chunk.getBlockStorageArray()[key.y];
        if (storage == null || storage.isEmpty()) {
            removeSection(key);
            return;
        }
        MeshPacking[] layers = new MeshPacking[LAYERS.length];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockRenderLayer previousLayer = MinecraftForgeClient.getRenderLayer();
        try {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        pos.setPos(key.x * 16 + x, key.y * 16 + y, key.z * 16 + z);
                        IBlockState state = storage.get(x, y, z);
                        if (state.getRenderType() != EnumBlockRenderType.MODEL) continue;
                        try {
                            emitBlock(layers, state, pos, x, y, z);
                        } catch (RuntimeException exception) {
                            if (!warnedUnsupportedModel) {
                                warnedUnsupportedModel = true;
                                LOGGER.warn("A baked block model could not be captured; further model warnings are suppressed", exception);
                            }
                        }
                    }
                }
            }
        } finally {
            ForgeHooksClient.setRenderLayer(previousLayer);
        }
        List<MeshInfoSurfaceTriangles> surfaces = new ArrayList<MeshInfoSurfaceTriangles>();
        for (int i = 0; i < layers.length; i++) {
            MeshPacking packed = layers[i];
            if (packed == null || packed.vertexCount() == 0) continue;
            MeshInfoSurfaceTriangles surface = new MeshInfoSurfaceTriangles();
            surface.vertexBuffer = packed.vertices();
            surface.vertexCount = packed.vertexCount();
            surface.indexBuffer = packed.indices();
            surface.indexCount = packed.vertexCount() / 4 * 6;
            surface.material = materials[i];
            surface.flags = MeshInfoSurfaceTriangles.TEXCOORD_BOUNDS;
            surfaces.add(surface);
        }
        if (surfaces.isEmpty()) {
            removeSection(key);
            return;
        }
        MeshInfo meshInfo = new MeshInfo(HASHES.incrementAndGet());
        meshInfo.surfaces = surfaces.toArray(new MeshInfoSurfaceTriangles[surfaces.size()]);
        long mesh = api.createMesh(meshInfo);
        long instance;
        try {
            float[] transform = {1, 0, 0, key.x * 16f, 0, 1, 0, key.y * 16f, 0, 0, 1, key.z * 16f};
            instance = api.sceneInstanceAdd(mesh, transform, 0, 1, vertexColorExtension);
        } catch (RuntimeException failure) {
            api.destroyMesh(mesh);
            throw failure;
        }
        SectionAsset old = sections.put(key, new SectionAsset(mesh, instance));
        if (old != null) release(old);
    }

    private void emitBlock(MeshPacking[] layers, IBlockState raw, BlockPos pos, int x, int y, int z) {
        IBlockState actual = raw.getActualState(world, pos);
        IBlockState extended = actual.getBlock().getExtendedState(actual, world, pos);
        Vec3d offset = actual.getOffset(world, pos);
        long seed = MathHelper.getPositionRandom(pos);
        for (BlockRenderLayer layer : LAYERS) {
            if (!actual.getBlock().canRenderInLayer(actual, layer)) continue;
            ForgeHooksClient.setRenderLayer(layer);
            IBakedModel model = mc.getBlockRendererDispatcher().getModelForState(actual);
            int layerIndex = layer.ordinal();
            if (layers[layerIndex] == null) layers[layerIndex] = new MeshPacking();
            for (EnumFacing side : FACES) {
                if (actual.shouldSideBeRendered(world, pos, side)) {
                    emitQuads(layers[layerIndex], model.getQuads(extended, side, seed), actual, pos,
                            x + (float) offset.x, y + (float) offset.y, z + (float) offset.z);
                }
            }
            emitQuads(layers[layerIndex], model.getQuads(extended, null, seed), actual, pos,
                    x + (float) offset.x, y + (float) offset.y, z + (float) offset.z);
        }
    }

    private void emitQuads(MeshPacking target, List<BakedQuad> quads, IBlockState state,
                           BlockPos pos, float x, float y, float z) {
        for (BakedQuad quad : quads) {
            VertexFormat format = quad.getFormat();
            if (!format.hasUvOffset(0)) continue;
            int[] data = quad.getVertexData();
            int stride = format.getIntegerSize();
            int uv = format.getUvOffsetById(0) / 4;
            if (stride < 3 || data.length < stride * 4 || uv + 1 >= stride) continue;
            int tint = quad.hasTintIndex() ? mc.getBlockColors().colorMultiplier(state, world, pos, quad.getTintIndex()) : 0xFFFFFF;
            for (int v = 0; v < 4; v++) {
                int base = v * stride;
                positions[v * 3] = Float.intBitsToFloat(data[base]) + x;
                positions[v * 3 + 1] = Float.intBitsToFloat(data[base + 1]) + y;
                positions[v * 3 + 2] = Float.intBitsToFloat(data[base + 2]) + z;
                uvs[v * 2] = Float.intBitsToFloat(data[base + uv]);
                uvs[v * 2 + 1] = Float.intBitsToFloat(data[base + uv + 1]);
                if (format.hasColor()) {
                    int colorByte = base * 4 + format.getColorOffset();
                    colors[v] = MeshPacking.tintRgba(byteAt(data, colorByte), byteAt(data, colorByte + 1),
                            byteAt(data, colorByte + 2), byteAt(data, colorByte + 3), tint);
                } else {
                    colors[v] = 0xFF000000 | (tint & 0xFFFFFF);
                }
            }
            float ax = positions[3] - positions[0], ay = positions[4] - positions[1], az = positions[5] - positions[2];
            float bx = positions[6] - positions[0], by = positions[7] - positions[1], bz = positions[8] - positions[2];
            float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
            float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (length > 0.00001f) {
                nx /= length; ny /= length; nz /= length;
            } else if (quad.getFace() != null) {
                nx = quad.getFace().getXOffset(); ny = quad.getFace().getYOffset(); nz = quad.getFace().getZOffset();
            } else continue;
            TextureAtlasSprite sprite = quad.getSprite();
            target.quad(positions, uvs, colors, nx, ny, nz,
                    sprite == null ? 0 : sprite.getMinU(), sprite == null ? 0 : sprite.getMinV(),
                    sprite == null ? 1 : sprite.getMaxU(), sprite == null ? 1 : sprite.getMaxV());
        }
    }

    private static int byteAt(int[] data, int offset) {
        return (data[offset / 4] >>> ((offset & 3) * 8)) & 255;
    }

    private void uploadAtlas() {
        int oldActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        int oldTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int oldPackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        int[] packNames = {GL11.GL_PACK_ALIGNMENT, GL11.GL_PACK_ROW_LENGTH, GL11.GL_PACK_SKIP_ROWS, GL11.GL_PACK_SKIP_PIXELS};
        int[] packValues = new int[packNames.length];
        for (int i = 0; i < packNames.length; i++) packValues[i] = GL11.glGetInteger(packNames[i]);
        try {
            mc.getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
            int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            long bytes = (long) width * height * 4;
            if (width <= 0 || height <= 0 || bytes > MAX_ATLAS_BYTES) {
                throw new IllegalStateException("Unsupported block atlas dimensions: " + width + "x" + height);
            }
            ByteBuffer pixels = MeshPacking.direct((int) bytes);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            for (int name : packNames) GL11.glPixelStorei(name, name == GL11.GL_PACK_ALIGNMENT ? 1 : 0);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            atlas = api.createTextureFromMemory(TextureFromMemoryInfo.builder()
                    .hash(HASHES.incrementAndGet()).pixelData(pixels).size(width, height).build());
            for (BlockRenderLayer layer : LAYERS) {
                boolean cutout = layer == BlockRenderLayer.CUTOUT || layer == BlockRenderLayer.CUTOUT_MIPPED;
                MaterialInfo material = new MaterialInfo(HASHES.incrementAndGet());
                material.albedoTexture = atlas;
                material.filterMode = 0;
                material.wrapModeU = MaterialInfo.WRAP_MODE_CLAMP;
                material.wrapModeV = MaterialInfo.WRAP_MODE_CLAMP;
                material.opaqueExt = MaterialInfoOpaqueEXT.builder()
                        .albedoConstant(1, 1, 1).roughnessConstant(1).metallicConstant(0)
                        .useDrawCallAlphaState(false)
                        .alphaTestType(cutout ? 4 : 7).alphaReferenceValue(cutout ? 128 : 0)
                        .blendEnabled(layer == BlockRenderLayer.TRANSLUCENT).build();
                materials[layer.ordinal()] = api.createMaterial(material);
            }
            vertexColorExtension = InstanceInfoBlendEXT.textureTimesVertexColor().allocNative(0);
            LOGGER.info("Captured block atlas {}x{}; terrain radius is {} chunks", width, height, RADIUS);
        } finally {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, oldPackBuffer);
            for (int i = 0; i < packNames.length; i++) GL11.glPixelStorei(packNames[i], packValues[i]);
            GlStateManager.bindTexture(oldTexture);
            GlStateManager.setActiveTexture(oldActive);
        }
    }

    private void removeColumn(ColumnKey column) {
        for (int y = 0; y < SECTION_COUNT; y++) removeSection(new SectionKey(column.x, y, column.z));
    }

    private void removeSection(SectionKey key) {
        SectionAsset old = sections.remove(key);
        if (old != null) release(old);
    }

    private void release(SectionAsset asset) {
        try { api.sceneInstanceRemove(asset.instance); }
        catch (RuntimeException error) { LOGGER.warn("Could not remove terrain instance", error); }
        try { api.destroyMesh(asset.mesh); }
        catch (RuntimeException error) { LOGGER.warn("Could not destroy terrain mesh", error); }
    }

    private void clearSections() {
        for (SectionAsset asset : sections.values()) release(asset);
        sections.clear();
        dirty.clear();
    }

    private void clearTextureResources() {
        if (vertexColorExtension != 0) {
            RemixBridge.freeExt(vertexColorExtension);
            vertexColorExtension = 0;
        }
        for (int i = 0; i < materials.length; i++) {
            if (materials[i] == 0) continue;
            try { api.destroyMaterial(materials[i]); }
            catch (RuntimeException error) { LOGGER.warn("Could not destroy terrain material", error); }
            materials[i] = 0;
        }
        if (atlas != 0) {
            try { api.destroyTexture(atlas); }
            catch (RuntimeException error) { LOGGER.warn("Could not destroy block atlas", error); }
            atlas = 0;
        }
    }

    @Override public void close() {
        checkThread();
        if (closed) return;
        closed = true;
        world.removeEventListener(worldUpdates);
        clearSections();
        clearTextureResources();
        columns.clear();
    }

    private void checkThread() {
        if (!mc.isCallingFromMinecraftThread()) {
            throw new IllegalStateException("Terrain capture must run on the Minecraft render thread");
        }
    }

    private final class WorldUpdates implements IWorldEventListener {
        @Override public void notifyBlockUpdate(World source, BlockPos pos, IBlockState oldState,
                                                IBlockState newState, int flags) {
            if (source == world) markBlock(pos);
        }
        @Override public void markBlockRangeForRenderUpdate(int minX, int minY, int minZ,
                                                            int maxX, int maxY, int maxZ) {
            markRange(minX, minY, minZ, maxX, maxY, maxZ);
        }
        @Override public void notifyLightSet(BlockPos pos) { }
        @Override public void playSoundToAllNearExcept(EntityPlayer player, SoundEvent sound, SoundCategory category,
                                                       double x, double y, double z, float volume, float pitch) { }
        @Override public void playRecord(SoundEvent sound, BlockPos pos) { }
        @Override public void spawnParticle(int id, boolean ignoreRange, double x, double y, double z,
                                            double vx, double vy, double vz, int... args) { }
        @Override public void spawnParticle(int id, boolean ignoreRange, boolean minimumParticles,
                                            double x, double y, double z, double vx, double vy, double vz,
                                            int... args) { }
        @Override public void onEntityAdded(Entity entity) { }
        @Override public void onEntityRemoved(Entity entity) { }
        @Override public void broadcastSound(int id, BlockPos pos, int data) { }
        @Override public void playEvent(EntityPlayer player, int type, BlockPos pos, int data) { }
        @Override public void sendBlockBreakProgress(int breakerId, BlockPos pos, int progress) { }
    }

    private static final class SectionAsset {
        final long mesh;
        final long instance;
        SectionAsset(long mesh, long instance) { this.mesh = mesh; this.instance = instance; }
    }

    private static final class ColumnKey {
        final int x;
        final int z;
        ColumnKey(int x, int z) { this.x = x; this.z = z; }
        @Override public int hashCode() { return 31 * x + z; }
        @Override public boolean equals(Object other) {
            if (!(other instanceof ColumnKey)) return false;
            ColumnKey key = (ColumnKey) other;
            return x == key.x && z == key.z;
        }
    }

    private static final class SectionKey {
        final int x;
        final int y;
        final int z;
        SectionKey(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
        @Override public int hashCode() { return (31 * x + y) * 31 + z; }
        @Override public boolean equals(Object other) {
            if (!(other instanceof SectionKey)) return false;
            SectionKey key = (SectionKey) other;
            return x == key.x && y == key.y && z == key.z;
        }
    }
}
