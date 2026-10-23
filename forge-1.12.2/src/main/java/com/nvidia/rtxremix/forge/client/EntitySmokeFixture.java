/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import java.util.UUID;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.passive.EntityCow;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

/** Mutates only its two explicitly spawned creatures in its uniquely owned smoke save. */
final class EntitySmokeFixture {
    private final String saveName;
    private final UUID cowUuid;
    private final UUID pigUuid;
    private final int cowId;
    private final int pigId;
    private final double cowX, cowY, cowZ;
    private final double pigX, pigY, pigZ;
    private final EntityFixtureMotion motion = new EntityFixtureMotion();

    /** Called on the integrated server thread after the fixture floor has been built. */
    EntitySmokeFixture(String saveName, WorldServer world, int centerX, int floorY, int centerZ) {
        this.saveName = saveName;
        cowX = centerX - 2.5;
        cowY = pigY = floorY + 1.0;
        cowZ = centerZ + 9.5;
        pigX = centerX + 2.5;
        pigZ = centerZ + 10.5;
        EntityCow cow = new EntityCow(world);
        EntityPig pig = new EntityPig(world);
        prepare(cow, cowX, cowY, cowZ, 270f);
        prepare(pig, pigX, pigY, pigZ, 0f);
        if (!world.spawnEntity(cow) || !world.spawnEntity(pig)) {
            throw new IllegalStateException("Fixture creature spawn was cancelled");
        }
        cowUuid = cow.getUniqueID();
        pigUuid = pig.getUniqueID();
        cowId = cow.getEntityId();
        pigId = pig.getEntityId();
    }

    private static void prepare(EntityLiving creature, double x, double y, double z, float yaw) {
        creature.enablePersistence();
        creature.setNoAI(true);
        creature.setNoGravity(true);
        creature.setEntityInvulnerable(true);
        creature.setSilent(true);
        creature.setLocationAndAngles(x, y, z, yaw, 0f);
        creature.renderYawOffset = creature.prevRenderYawOffset = yaw;
        creature.rotationYawHead = creature.prevRotationYawHead = yaw;
    }

    /** Called only on the integrated server thread, with at most one queued update. */
    boolean advance(IntegratedServer server, boolean restart) {
        if (!server.isServerRunning() || !saveName.equals(server.getFolderName())) {
            throw new IllegalStateException("Unexpected fixture server");
        }
        WorldServer world = server.getWorld(0);
        Entity cow = world.getEntityFromUuid(cowUuid);
        Entity pig = world.getEntityFromUuid(pigUuid);
        if (!(cow instanceof EntityCow) || cow.isDead || !(pig instanceof EntityPig) || pig.isDead) {
            throw new IllegalStateException("Fixture creature disappeared");
        }
        if (restart) motion.reset();
        motion.advance();
        cow.setPositionAndRotation(cowX + motion.offsetX(), cowY, cowZ, motion.yaw(), 0f);
        cow.setRotationYawHead(motion.yaw());
        cow.motionX = cow.motionY = cow.motionZ = 0;
        pig.setPositionAndRotation(pigX, pigY, pigZ, 0f, 0f);
        pig.motionX = pig.motionY = pig.motionZ = 0;
        return motion.isComplete();
    }

    /** Wait for server motion to reach the client before fixing the screenshot pose. */
    boolean isAtReferencePosition(World world) {
        return at(world.getEntityByID(cowId), cowUuid, cowX, cowY, cowZ)
                && at(world.getEntityByID(pigId), pigUuid, pigX, pigY, pigZ);
    }

    /** Holds only the owned client creatures after their authoritative motion has finished. */
    void holdReferencePose(IntegratedServer server, World world) {
        if (!world.isRemote || world.provider.getDimension() != 0
                || !saveName.equals(server.getFolderName())) {
            throw new IllegalStateException("Unexpected fixture client world");
        }
        Entity cow = world.getEntityByID(cowId);
        Entity pig = world.getEntityByID(pigId);
        if (!(cow instanceof EntityCow) || cow.isDead || !cowUuid.equals(cow.getUniqueID())
                || !(pig instanceof EntityPig) || pig.isDead || !pigUuid.equals(pig.getUniqueID())) {
            throw new IllegalStateException("Fixture client creature disappeared");
        }
        hold((EntityLiving) cow, cowX, cowY, cowZ, 270f);
        hold((EntityLiving) pig, pigX, pigY, pigZ, 0f);
    }

    private static void hold(EntityLiving creature, double x, double y, double z, float yaw) {
        creature.setPositionAndRotation(x, y, z, yaw, 0f);
        creature.prevPosX = creature.lastTickPosX = x;
        creature.prevPosY = creature.lastTickPosY = y;
        creature.prevPosZ = creature.lastTickPosZ = z;
        creature.prevRotationYaw = yaw;
        creature.prevRotationPitch = 0f;
        // EntityBodyHelper keeps turning after motion stops. Fix current and previous
        // angles so both renderers see the same pose at every partial tick.
        creature.renderYawOffset = creature.prevRenderYawOffset = yaw;
        creature.rotationYawHead = creature.prevRotationYawHead = yaw;
        creature.limbSwing = creature.limbSwingAmount = creature.prevLimbSwingAmount = 0f;
        creature.motionX = creature.motionY = creature.motionZ = 0;
    }

    boolean isAtReferencePose(World world) {
        return isAtReferencePosition(world)
                && fixedPose((EntityLiving) world.getEntityByID(cowId), 270f)
                && fixedPose((EntityLiving) world.getEntityByID(pigId), 0f);
    }

    private static boolean fixedPose(EntityLiving creature, float yaw) {
        return creature.rotationYaw == yaw && creature.prevRotationYaw == yaw
                && creature.rotationPitch == 0f && creature.prevRotationPitch == 0f
                && creature.renderYawOffset == yaw && creature.prevRenderYawOffset == yaw
                && creature.rotationYawHead == yaw && creature.prevRotationYawHead == yaw
                && creature.limbSwing == 0f && creature.limbSwingAmount == 0f
                && creature.prevLimbSwingAmount == 0f;
    }

    UUID cowUuid() { return cowUuid; }
    UUID pigUuid() { return pigUuid; }

    double clientCowX(World world) {
        Entity cow = world.getEntityByID(cowId);
        return cow != null && !cow.isDead && cowUuid.equals(cow.getUniqueID()) ? cow.posX : Double.NaN;
    }

    private static boolean at(Entity entity, UUID uuid, double x, double y, double z) {
        return entity != null && !entity.isDead && uuid.equals(entity.getUniqueID())
                && Math.abs(entity.posX - x) < 0.03
                && Math.abs(entity.posY - y) < 0.03
                && Math.abs(entity.posZ - z) < 0.03;
    }
}
