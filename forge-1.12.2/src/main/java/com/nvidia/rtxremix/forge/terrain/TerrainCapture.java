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
import com.nvidia.rtxremix.Float3D;
import com.nvidia.rtxremix.forge.light.BlockLightScene;
import com.nvidia.rtxremix.forge.light.SectionLights;
import com.nvidia.rtxremix.forge.texture.AtlasAnimations;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.block.state.IBlockState;
import net.minecraft.block.material.Material;
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
 * Render-thread capture of loaded chunk sections inside Minecraft's render distance.
 * Discovery and block-model evaluation are incremental; native pages are uploaded one
 * per frame, and an old section stays visible until its complete replacement is ready.
 * This owns no world or chunk loads. Baked models and vanilla liquids share publication;
 * custom non-baked renderers require their own capture integration.
 *
 * <p>The 2 ms budget is cooperative: a model callback or native call cannot be preempted.
 * Each page is at most 1.1 MiB, each section at most 128 pages, and retirement is capped
 * at four pages per frame. Atomic section publication may add/remove up to 128 instances.
 * Mesh bytes include staging and retirement; reaching 512 MiB pauses capture explicitly.
 */
public final class TerrainCapture implements AutoCloseable {
    private static final Logger LOGGER = LogManager.getLogger("RLCraftRemixTerrain");
    private static final int SECTION_COUNT = 16;
    private static final long MAX_ATLAS_BYTES = 256L * 1024 * 1024;
    private static final AtomicLong HASHES = new AtomicLong(0x524c544552520000L);
    private static final BlockRenderLayer[] LAYERS = BlockRenderLayer.values();
    private static final int WATER = LAYERS.length;
    private static final int LAVA = WATER + 1;
    private static final int CLEAR_GLASS = LAVA + 1;
    private static final int STAINED_GLASS = CLEAR_GLASS + 1;
    private static final int MATERIAL_COUNT = STAINED_GLASS + 1;
    private static final EnumFacing[] FACES = EnumFacing.values();

    private final RemixAPI api;
    private final Minecraft mc;
    private final WorldClient world;
    private final BlockLightScene lights;
    private final FluidCapture fluids;
    private final IWorldEventListener worldUpdates = new WorldUpdates();
    private final TerrainSchedule schedule = new TerrainSchedule();
    private final TerrainBudget budget = new TerrainBudget();
    private final TerrainClose closing = new TerrainClose();
    private final ArrayDeque<Page> retired = new ArrayDeque<Page>();
    private final TerrainSchedule.RemovedColumn removeColumn = new TerrainSchedule.RemovedColumn() {
        @Override public void remove(TerrainSchedule.Column column) { retireColumn(column); }
    };
    private final MeshPacking[] builders = new MeshPacking[MATERIAL_COUNT];
    private final int[] checkpoints = new int[MATERIAL_COUNT];
    private final BlockPos.MutableBlockPos blockPosition = new BlockPos.MutableBlockPos();
    private final long[] materials = new long[MATERIAL_COUNT];
    private final float[] positions = new float[12];
    private final float[] uvs = new float[8];
    private final int[] colors = new int[4];
    private long atlas;
    private AtlasAnimations animations;
    private long vertexColorExtension;
    private boolean texturesDirty = true;
    private boolean allDirty;
    private boolean closed;
    private boolean warnedUnsupportedModel;
    private boolean warnedUnsupportedLight;
    private boolean warnedUnsupportedFluid;
    private boolean capacityLimited;
    private boolean warnedCapacity;
    private int columnCount;
    private int capturedColumns;
    private int sectionCount;
    private int pageResources;
    private long publishedSections;
    private long retiredSections;
    private long discardedBuilds;
    private long waterVertices;
    private long lavaVertices;
    private long clearGlassVertices;
    private long stainedGlassVertices;
    private Build active;

    public TerrainCapture(RemixAPI api, Minecraft mc) {
        if (api == null || mc == null || mc.world == null) {
            throw new IllegalArgumentException("TerrainCapture requires Remix and a joined client world");
        }
        this.api = api;
        this.mc = mc;
        this.world = mc.world;
        this.lights = new BlockLightScene(api, world);
        this.fluids = new FluidCapture(mc);
        world.addEventListener(worldUpdates);
    }

    /** Advances bounded discovery, capture, upload and retirement before Remix submit. */
    public void update() {
        checkThread();
        if (closed) return;
        if (mc.world != world) { close(); return; }
        Entity view = mc.getRenderViewEntity();
        if (view == null) return;
        if (texturesDirty) {
            clearTerrain();
            clearTextureResources();
            uploadAtlas();
            texturesDirty = false;
        }
        animations.flush();
        long deadline = System.nanoTime() + TerrainBudget.FRAME_NANOS;
        schedule.move(MathHelper.floor(view.posX) >> 4,
                MathHelper.clamp(MathHelper.floor(view.posY) >> 4, 0, SECTION_COUNT - 1),
                MathHelper.floor(view.posZ) >> 4, mc.gameSettings.renderDistanceChunks, removeColumn);
        for (int i = 0; i < TerrainBudget.RETIREMENTS_PER_FRAME && !retired.isEmpty(); i++) {
            release(retired.peekFirst());
            retired.removeFirst();
            if (System.nanoTime() >= deadline) break;
        }
        int probes = Math.min(TerrainBudget.COLUMNS_PER_FRAME, schedule.windowSize());
        for (int i = 0; i < probes; i++) reconcile(schedule.nextDiscovery());
        if (allDirty) { schedule.invalidateAll(); allDirty = false; }
        if (active != null && !valid(active)) discardBuild();
        if (System.nanoTime() >= deadline) return;
        capacityLimited = false;
        int blocks = 0;
        int emptySections = 0;
        while (blocks < TerrainBudget.BLOCKS_PER_FRAME && System.nanoTime() < deadline) {
            if (active == null) {
                TerrainSchedule.Section section = schedule.next();
                if (section == null) return;
                reconcile(section.column);
                if (section.column.source == null) { schedule.discard(section); continue; }
                active = new Build(section, (Chunk) section.column.source);
                ExtendedBlockStorage storage = active.chunk.getBlockStorageArray()[section.y];
                if (storage == null || storage.isEmpty()) {
                    publishBuild();
                    if (++emptySections >= 64) return;
                    continue;
                }
            }
            if (active.block < 4096) {
                int visited = captureBlocks(Math.min(32, TerrainBudget.BLOCKS_PER_FRAME - blocks));
                blocks += visited;
                continue;
            }
            if (uploadPage()) return; // At most one mesh allocation per frame.
            if (capacityLimited) return;
            publishBuild();
        }
    }

    public void markBlock(BlockPos pos) {
        checkThread();
        if (closed || pos == null) return;
        markRange(pos.getX(), pos.getY(), pos.getZ(), pos.getX(), pos.getY(), pos.getZ());
    }
    public void invalidateAll() { checkThread(); allDirty = true; }
    public void refreshTextures() { checkThread(); texturesDirty = true; }
    public void updateLights(Float3D camera) { lights.update(camera); }
    public int getBlockLightCount() { return lights.resourceCount(); }
    public boolean isBlockLightCaptured(BlockPos pos) { return lights.contains(pos); }
    /** Borrowed by world item/effect scenes; those must close before this atlas. */
    public long getAtlasTexture() { return atlas; }
    public long getAtlasTextureUpdates() { return animations == null ? 0 : animations.updateCount(); }
    public long getAtlasSpriteUpdates(String name) {
        TextureAtlasSprite sprite = mc.getTextureMapBlocks().getAtlasSprite(name);
        return animations == null ? 0 : animations.rectangleUpdates(sprite.getOriginX(), sprite.getOriginY(),
                sprite.getIconWidth(), sprite.getIconHeight());
    }
    public long getAtlasSpriteHash(String name) {
        TextureAtlasSprite sprite = mc.getTextureMapBlocks().getAtlasSprite(name);
        return animations == null ? 0 : animations.rectangleHash(sprite.getOriginX(), sprite.getOriginY(),
                sprite.getIconWidth(), sprite.getIconHeight());
    }

    public int getRadius() { return Math.max(0, schedule.radius()); }
    public int getColumnCount() { return columnCount; }
    public int getCapturedColumnCount() { return capturedColumns; }
    public int getSectionCount() { return sectionCount; }
    public int getPendingSectionCount() { return schedule.pending() + (active == null ? 0 : 1); }
    public int getRetirementCount() { return retired.size(); }
    public long getPublishedSectionCount() { return publishedSections; }
    public long getRetiredSectionCount() { return retiredSections; }
    public long getDiscardedBuildCount() { return discardedBuilds; }
    public long getCapturedBytes() { return budget.bytes(); }
    public long getWaterVertexCount() { return waterVertices; }
    public long getLavaVertexCount() { return lavaVertices; }
    public long getClearGlassVertexCount() { return clearGlassVertices; }
    public long getStainedGlassVertexCount() { return stainedGlassVertices; }
    public boolean isCapacityLimited() { return capacityLimited || schedule.failures() != 0; }
    public int getResourceCount() {
        int result = lights.resourceCount() + pageResources + (atlas == 0 ? 0 : 1) + (vertexColorExtension == 0 ? 0 : 1);
        for (long material : materials) if (material != 0) result++;
        return result;
    }
    public boolean isColumnCaptured(int x, int z) {
        TerrainSchedule.Column column = schedule.column(x, z);
        return column != null && column.capturedSections != 0;
    }
    public boolean isSectionReady(int x, int y, int z) {
        TerrainSchedule.Section section = section(x, y, z);
        return section != null && section.ready();
    }
    public long getSectionRevision(int x, int y, int z) {
        TerrainSchedule.Section section = section(x, y, z);
        return section == null ? 0 : section.publication;
    }
    private TerrainSchedule.Section section(int x, int y, int z) {
        TerrainSchedule.Column column = schedule.column(x, z);
        return column == null || y < 0 || y >= SECTION_COUNT ? null : column.sections[y];
    }

    private void reconcile(TerrainSchedule.Column column) {
        Chunk loaded = world.getChunkProvider().getLoadedChunk(column.x, column.z);
        if (loaded == column.source) return;
        boolean wasLoaded = column.source != null;
        column.source = loaded;
        if (loaded != null) {
            if (!wasLoaded) columnCount++;
            schedule.invalidate(column);
        } else {
            if (wasLoaded) columnCount--;
            retireColumnAssets(column);
            for (TerrainSchedule.Section section : column.sections) {
                schedule.clearFailure(section);
                section.revision++;
                section.publication = 0;
            }
        }
        // Face culling and extended states can inspect diagonal neighbouring columns.
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx != 0 || dz != 0) schedule.invalidate(schedule.column(column.x + dx, column.z + dz));
            }
        }
    }

    private void markRange(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        checkThread();
        if (closed || schedule.radius() < 0) return;
        int radius = schedule.radius();
        int fromX = Math.max(schedule.centerX() - radius, (minX - 1) >> 4);
        int toX = Math.min(schedule.centerX() + radius, (maxX + 1) >> 4);
        int fromY = Math.max(0, (minY - 1) >> 4);
        int toY = Math.min(SECTION_COUNT - 1, (maxY + 1) >> 4);
        int fromZ = Math.max(schedule.centerZ() - radius, (minZ - 1) >> 4);
        int toZ = Math.min(schedule.centerZ() + radius, (maxZ + 1) >> 4);
        for (int x = fromX; x <= toX; x++) {
            for (int z = fromZ; z <= toZ; z++) {
                TerrainSchedule.Column column = schedule.column(x, z);
                reconcile(column);
                for (int y = fromY; y <= toY; y++) schedule.invalidate(column.sections[y]);
            }
        }
    }

    private boolean valid(Build build) {
        return schedule.valid(build.section, build.chunk, build.revision)
                && world.getChunkProvider().getLoadedChunk(build.section.column.x, build.section.column.z) == build.chunk;
    }

    private int captureBlocks(int limit) {
        Build build = active;
        TerrainSchedule.Section section = build.section;
        ExtendedBlockStorage storage = build.chunk.getBlockStorageArray()[section.y];
        BlockRenderLayer previousLayer = MinecraftForgeClient.getRenderLayer();
        int count = 0;
        try {
            while (count < limit && build.block < 4096) {
                int index = build.block++;
                count++;
                int x = index & 15, z = (index >> 4) & 15, y = index >> 8;
                IBlockState state = storage.get(x, y, z);
                blockPosition.setPos(section.column.x * 16 + x, section.y * 16 + y, section.column.z * 16 + z);
                try { lights.sample(build.lights, state, blockPosition); }
                catch (RuntimeException error) {
                    if (!warnedUnsupportedLight) {
                        warnedUnsupportedLight = true;
                        LOGGER.warn("A block light value could not be captured; further warnings are suppressed", error);
                    }
                }
                EnumBlockRenderType type = state.getRenderType();
                if (type != EnumBlockRenderType.MODEL && type != EnumBlockRenderType.LIQUID) continue;
                if (type == EnumBlockRenderType.LIQUID && !FluidCapture.supported(state)) {
                    if (!warnedUnsupportedFluid) {
                        warnedUnsupportedFluid = true;
                        LOGGER.warn("Unsupported LIQUID renderer for {}; only BlockLiquid water/lava is captured; further warnings are suppressed",
                                state.getBlock().getRegistryName());
                    }
                    continue;
                }
                for (int i = 0; i < builders.length; i++) checkpoints[i] = builders[i] == null ? 0 : builders[i].vertexCount();
                try {
                    if (type == EnumBlockRenderType.LIQUID) {
                        int layer = state.getMaterial() == Material.LAVA ? LAVA : WATER;
                        if (builders[layer] == null) builders[layer] = new MeshPacking();
                        fluids.capture(state, world, blockPosition, section.column.x, section.y, section.column.z, builders[layer]);
                    } else {
                        emitBlock(builders, state, blockPosition, x, y, z);
                    }
                } catch (MeshPacking.CapacityExceededException error) {
                    LOGGER.error("Terrain section {},{},{} exceeds the bounded model buffer; keeping its previous geometry",
                            section.column.x, section.y, section.column.z, error);
                    discardBuild();
                    // Capacity failures are retried only when the section changes again.
                    schedule.fail(section);
                    return count;
                } catch (RuntimeException error) {
                    for (int i = 0; i < builders.length; i++) if (builders[i] != null) builders[i].truncate(checkpoints[i]);
                    if (!warnedUnsupportedModel) {
                        warnedUnsupportedModel = true;
                        LOGGER.warn("A terrain block could not be captured; further model warnings are suppressed", error);
                    }
                }
            }
        } finally { ForgeHooksClient.setRenderLayer(previousLayer); }
        return count;
    }

    private boolean uploadPage() {
        Build build = active;
        while (build.layer < builders.length) {
            MeshPacking packed = builders[build.layer];
            if (packed == null || build.vertex == packed.vertexCount()) {
                build.layer++;
                build.vertex = 0;
                continue;
            }
            int count = Math.min(TerrainBudget.PAGE_VERTICES, packed.vertexCount() - build.vertex);
            long bytes = TerrainBudget.bytes(count);
            if (!budget.canReserve(bytes)) {
                capacityLimited = true;
                if (!warnedCapacity) {
                    warnedCapacity = true;
                    LOGGER.warn("Terrain capture paused at the {} MiB mesh budget; reduce Minecraft render distance to free meshes",
                            TerrainBudget.MAX_MESH_BYTES / (1024 * 1024));
                }
                return false;
            }
            MeshInfoSurfaceTriangles surface = new MeshInfoSurfaceTriangles();
            surface.vertexBuffer = packed.vertices(build.vertex, count);
            surface.vertexCount = count;
            surface.indexBuffer = packed.indices(count);
            surface.indexCount = count / 4 * 6;
            surface.material = materials[build.layer];
            surface.flags = MeshInfoSurfaceTriangles.TEXCOORD_BOUNDS;
            MeshInfo mesh = new MeshInfo(HASHES.incrementAndGet());
            mesh.surfaces = new MeshInfoSurfaceTriangles[] {surface};
            long handle = api.createMesh(mesh);
            budget.reserve(bytes);
            pageResources++;
            build.pages.add(new Page(handle, bytes));
            build.vertex += count;
            return true;
        }
        return false;
    }

    private void publishBuild() {
        Build build = active;
        if (!valid(build)) { discardBuild(); return; }
        TerrainSchedule.Section section = build.section;
        float[] transform = {1, 0, 0, section.column.x * 16f, 0, 1, 0, section.y * 16f, 0, 0, 1, section.column.z * 16f};
        // Mesh staging is budgeted; instances become visible together before the next submit.
        for (Page page : build.pages) {
            page.instance = api.sceneInstanceAdd(page.mesh, transform, 0, 1, vertexColorExtension);
            pageResources++;
        }
        SectionAsset old = (SectionAsset) section.asset;
        if (old != null) {
            for (Page page : old.pages) detach(page);
            retire(old);
        }
        if (build.pages.isEmpty()) {
            section.asset = null;
            if (old != null) {
                sectionCount--;
                if (--section.column.capturedSections == 0) capturedColumns--;
            }
        } else {
            long waterCount = builders[WATER] == null ? 0 : builders[WATER].vertexCount();
            long lavaCount = builders[LAVA] == null ? 0 : builders[LAVA].vertexCount();
            long clearGlassCount = builders[CLEAR_GLASS] == null ? 0 : builders[CLEAR_GLASS].vertexCount();
            long stainedGlassCount = builders[STAINED_GLASS] == null ? 0 : builders[STAINED_GLASS].vertexCount();
            section.asset = new SectionAsset(build.pages, waterCount, lavaCount, clearGlassCount, stainedGlassCount);
            waterVertices += waterCount;
            lavaVertices += lavaCount;
            clearGlassVertices += clearGlassCount;
            stainedGlassVertices += stainedGlassCount;
            if (old == null) {
                sectionCount++;
                if (section.column.capturedSections++ == 0) capturedColumns++;
            }
        }
        lights.publish(section.column.x, section.y, section.column.z, build.lights);
        schedule.publish(section, build.revision);
        publishedSections++;
        active = null;
        resetBuilders();
    }

    private void discardBuild() {
        if (active == null) return;
        retired.addAll(active.pages);
        schedule.discard(active.section);
        active = null;
        discardedBuilds++;
        resetBuilders();
    }
    private void resetBuilders() { for (MeshPacking packed : builders) if (packed != null) packed.reset(); }

    private void emitBlock(MeshPacking[] layers, IBlockState raw, BlockPos pos, int x, int y, int z) {
        IBlockState actual = raw.getActualState(world, pos);
        IBlockState extended = actual.getBlock().getExtendedState(actual, world, pos);
        Vec3d offset = actual.getOffset(world, pos);
        long seed = MathHelper.getPositionRandom(pos);
        GlassMaterials.Kind glass = GlassMaterials.kind(actual.getBlock().getRegistryName());
        for (BlockRenderLayer layer : LAYERS) {
            if (!actual.getBlock().canRenderInLayer(actual, layer)) continue;
            ForgeHooksClient.setRenderLayer(layer);
            IBakedModel model = mc.getBlockRendererDispatcher().getModelForState(actual);
            int layerIndex = glass == GlassMaterials.Kind.CLEAR ? CLEAR_GLASS
                    : glass == GlassMaterials.Kind.STAINED ? STAINED_GLASS : layer.ordinal();
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
            animations = new AtlasAnimations(api, mc.getTextureMapBlocks(), atlas, width, height);
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
            materials[WATER] = api.createMaterial(FluidMaterials.water(HASHES.incrementAndGet(), atlas));
            materials[LAVA] = api.createMaterial(FluidMaterials.lava(HASHES.incrementAndGet(), atlas));
            materials[CLEAR_GLASS] = api.createMaterial(GlassMaterials.clear(HASHES.incrementAndGet(), atlas));
            materials[STAINED_GLASS] = api.createMaterial(GlassMaterials.stained(HASHES.incrementAndGet(), atlas));
            fluids.refreshSprites();
            vertexColorExtension = InstanceInfoBlendEXT.textureTimesVertexColor().allocNative(0);
            LOGGER.info("Captured block atlas {}x{}; terrain follows Minecraft render distance", width, height);
        } finally {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, oldPackBuffer);
            for (int i = 0; i < packNames.length; i++) GL11.glPixelStorei(packNames[i], packValues[i]);
            GlStateManager.bindTexture(oldTexture);
            GlStateManager.setActiveTexture(oldActive);
        }
    }

    private void retireColumn(TerrainSchedule.Column column) {
        if (column.source != null) columnCount--;
        column.source = null;
        retireColumnAssets(column);
    }
    private void retireColumnAssets(TerrainSchedule.Column column) {
        lights.removeColumn(column.x, column.z);
        for (TerrainSchedule.Section section : column.sections) {
            if (section.asset == null) continue;
            retire((SectionAsset) section.asset);
            section.asset = null;
            sectionCount--;
        }
        if (column.capturedSections != 0) capturedColumns--;
        column.capturedSections = 0;
    }
    private void retire(SectionAsset asset) {
        retired.addAll(asset.pages);
        waterVertices -= asset.waterVertices;
        lavaVertices -= asset.lavaVertices;
        clearGlassVertices -= asset.clearGlassVertices;
        stainedGlassVertices -= asset.stainedGlassVertices;
        retiredSections++;
    }
    private void detach(Page page) {
        if (page.instance == 0) return;
        api.sceneInstanceRemove(page.instance);
        page.instance = 0;
        pageResources--;
    }
    private void release(Page page) {
        Throwable failure = null;
        try { detach(page); }
        catch (RuntimeException | LinkageError error) { failure = error; }
        if (page.mesh != 0 && page.instance == 0) {
            try {
                api.destroyMesh(page.mesh);
                page.mesh = 0;
                pageResources--;
                budget.release(page.bytes);
            } catch (RuntimeException | LinkageError error) { failure = appendFailure(failure, error); }
        }
        if (failure != null) throw new IllegalStateException("Cannot release terrain page", failure);
    }
    private void clearTerrain() {
        discardBuild();
        schedule.clear(removeColumn);
        Throwable failure = null;
        int count = retired.size();
        for (int i = 0; i < count; i++) {
            Page page = retired.removeFirst();
            try { release(page); }
            catch (RuntimeException | LinkageError error) { failure = appendFailure(failure, error); }
            if (page.mesh != 0 || page.instance != 0) retired.addLast(page);
        }
        capacityLimited = warnedCapacity = false;
        try { lights.clear(); }
        catch (RuntimeException | LinkageError error) { failure = appendFailure(failure, error); }
        if (failure != null) throw new IllegalStateException("Cannot clear terrain", failure);
    }

    private void clearTextureResources() {
        if (animations != null) { animations.close(); animations = null; }
        if (pageResources != 0) throw new IllegalStateException("Terrain pages still reference atlas materials");
        Throwable failure = null;
        if (vertexColorExtension != 0) {
            try {
                RemixBridge.freeExt(vertexColorExtension);
                vertexColorExtension = 0;
            } catch (RuntimeException | LinkageError error) { failure = error; }
        }
        for (int i = 0; i < materials.length; i++) {
            if (materials[i] == 0) continue;
            try {
                api.destroyMaterial(materials[i]);
                materials[i] = 0;
            } catch (RuntimeException | LinkageError error) { failure = appendFailure(failure, error); }
        }
        boolean materialsReleased = true;
        for (long material : materials) if (material != 0) materialsReleased = false;
        if (atlas != 0 && materialsReleased) {
            try {
                api.destroyTexture(atlas);
                atlas = 0;
            } catch (RuntimeException | LinkageError error) { failure = appendFailure(failure, error); }
        }
        if (failure != null) throw new IllegalStateException("Cannot release terrain textures", failure);
    }

    @Override public void close() {
        checkThread();
        // Stop new capture immediately, but keep retrying any native owners whose
        // release failed. Only the coordinator marks the teardown complete.
        closed = true;
        if (animations != null) { animations.close(); animations = null; }
        closing.close(() -> world.removeEventListener(worldUpdates), this::clearTerrain,
                this::clearTextureResources, lights::close);
    }

    private static Throwable appendFailure(Throwable first, Throwable next) {
        if (first == null) return next;
        if (first != next) first.addSuppressed(next);
        return first;
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

    private static final class Build {
        final TerrainSchedule.Section section;
        final Chunk chunk;
        final long revision;
        final List<Page> pages = new ArrayList<Page>();
        final SectionLights lights = new SectionLights();
        int block;
        int layer;
        int vertex;
        Build(TerrainSchedule.Section section, Chunk chunk) {
            this.section = section;
            this.chunk = chunk;
            this.revision = section.revision;
        }
    }
    private static final class Page {
        long mesh;
        final long bytes;
        long instance;
        Page(long mesh, long bytes) { this.mesh = mesh; this.bytes = bytes; }
    }
    private static final class SectionAsset {
        final List<Page> pages;
        final long waterVertices;
        final long lavaVertices;
        final long clearGlassVertices;
        final long stainedGlassVertices;
        SectionAsset(List<Page> pages, long waterVertices, long lavaVertices,
                     long clearGlassVertices, long stainedGlassVertices) {
            this.pages = pages;
            this.waterVertices = waterVertices;
            this.lavaVertices = lavaVertices;
            this.clearGlassVertices = clearGlassVertices;
            this.stainedGlassVertices = stainedGlassVertices;
        }
    }
}
