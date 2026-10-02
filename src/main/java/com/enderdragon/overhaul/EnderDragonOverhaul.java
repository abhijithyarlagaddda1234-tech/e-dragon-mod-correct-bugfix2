package com.enderdragon.overhaul;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.PowerParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.projectile.hurtingprojectile.DragonFireball;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class EnderDragonOverhaul implements ModInitializer {
    private static final float MAX_HP = 600.0F;
    private static final double RADIUS = 128.0D;
    private static final AABB DRAGON_SEARCH = new AABB(-192, -64, -192, 192, 320, 192);
    private static final Map<UUID, State> STATES = new HashMap<>();
    private static final double MAX_DIVE_ARENA_RADIUS = 110.0D;

    @Override
    public void onInitialize() {
        ServerTickEvents.END_LEVEL_TICK.register(EnderDragonOverhaul::tickLevel);
    }

    private static void tickLevel(ServerLevel level) {
        if (level.dimension() != Level.END) return;

        List<EnderDragon> dragons = level.getEntitiesOfClass(EnderDragon.class, DRAGON_SEARCH);
        if (dragons.isEmpty()) {
            STATES.clear();
            return;
        }
        for (EnderDragon dragon : dragons) tickDragon(level, dragon);
    }

    private static void tickDragon(ServerLevel level, EnderDragon dragon) {
        State s = STATES.computeIfAbsent(dragon.getUUID(), id -> new State());

        if (!s.initialized) initializeDragon(dragon, s);
        else ensureMaxHealth(dragon);

        s.tick++;
        s.phase = phase(dragon.getHealth());
        s.attackCooldown = dec(s.attackCooldown);
        s.diveCooldown = dec(s.diveCooldown);
        s.laserCooldown = dec(s.laserCooldown);
        s.orbCooldown = dec(s.orbCooldown);
        s.shockwaveCooldown = dec(s.shockwaveCooldown);

        tickDive(level, dragon, s);
        tickLaser(level, dragon, s);
        tickOrbs(level, s);
        tickBreath(level, dragon, s);
        tickChargedShockwave(level, dragon, s);
        updateCrystalCounter(level, dragon);

        if (dragon.getHealth() <= MAX_HP * 0.10F) {
            finalPhase(level, dragon, s);
            ambience(level, dragon, s);
            return;
        }

        if (!busy(s) && s.attackCooldown == 0) {
            ServerPlayer target = nearest(level, dragon);
            if (target != null) chooseAttack(level, dragon, target, s);
        }

        ambience(level, dragon, s);
    }

    private static int dec(int v) { return v > 0 ? v - 1 : 0; }

    private static void initializeDragon(EnderDragon dragon, State s) {
        var attribute = dragon.getAttribute(Attributes.MAX_HEALTH);
        if (attribute != null) attribute.setBaseValue(MAX_HP);
        dragon.setHealth(MAX_HP); // fixes 200/600 on first load
        s.initialized = true;
        s.attackCooldown = 50;
    }

    private static void ensureMaxHealth(EnderDragon dragon) {
        var attribute = dragon.getAttribute(Attributes.MAX_HEALTH);
        if (attribute != null && attribute.getBaseValue() != MAX_HP) attribute.setBaseValue(MAX_HP);
        if (dragon.getHealth() > MAX_HP) dragon.setHealth(MAX_HP);
    }

    private static void chooseAttack(ServerLevel level, EnderDragon dragon, ServerPlayer target, State s) {
        // Cycling through attacks avoids Starvation: the next available move gets its turn.
        // The laser gets a reserved opportunity in phase 2+ even after a triple-dive.
        for (int attempt = 0; attempt < 5; attempt++) {
            int choice = s.nextAttack++ % 5;
            if (choice == 0) {
                startBreath(level, dragon, target, s);
                return;
            }
            if (choice == 1 && s.diveCooldown == 0) {
                startDive(level, dragon, target, s);
                return;
            }
            if (choice == 2 && s.phase >= 2 && s.laserCooldown == 0) {
                startLaser(level, dragon, target, s);
                return;
            }
            if (choice == 3 && s.phase >= 2 && s.orbCooldown == 0) {
                startOrbs(level, dragon, target, s);
                return;
            }
            if (choice == 4 && s.phase >= 3 && s.shockwaveCooldown == 0) {
                startShockwave(dragon, s);
                return;
            }
        }
        startBreath(level, dragon, target, s);
    }

    private static void startDive(ServerLevel level, EnderDragon dragon,
                                  ServerPlayer target, State s) {
        s.target = target.getUUID();
        s.divesRemaining = s.phase >= 2 ? 3 : 1;
        s.diveCooldown = cooldown(s.phase, 200);
        s.attackCooldown = cooldown(s.phase, s.phase >= 2 ? 185 : 145);
        s.diveActive = true;
        startDiveWindup(level, dragon, target, s);
    }

    private static void startDiveWindup(ServerLevel level, EnderDragon dragon,
                                        ServerPlayer target, State s) {
        s.diveStage = 0; // 0=turn/windup, 1=committed dive, 2=recover/climb.
        s.diveTimer = 18;
        s.diveTarget = target.position().add(
                target.getDeltaMovement().scale(5.0D)).add(0.0D, 1.0D, 0.0D);
        warning(level, s.diveTarget);
        dragon.playSound(SoundEvents.ENDER_DRAGON_GROWL, 3.0F, 0.70F);
    }

    private static void tickDive(ServerLevel level, EnderDragon dragon, State s) {
        if (!s.diveActive) return;

        if (s.diveStage == 2) {
            // An actual upward recovery makes triple-dive feel like three attacks.
            s.diveTimer--;
            Vec3 climb = new Vec3(0.0D, 0.78D, 0.0D);
            dragon.setDeltaMovement(dragon.getDeltaMovement().scale(0.60D).add(climb.scale(0.40D)));
            if (s.diveTimer <= 0) {
                ServerPlayer next = nearest(level, dragon);
                if (next == null) { s.diveActive = false; return; }
                s.target = next.getUUID();
                startDiveWindup(level, dragon, next, s);
            }
            return;
        }

        if (s.diveTarget == null) { s.diveActive = false; return; }
        s.diveTimer--;
        ServerPlayer target = player(level, s.target);
        if (target == null) { s.diveActive = false; return; }

        if (s.diveStage == 0) {
            // Gradually turn before moving. Never snap the body toward the target.
            Vec3 liveAim = target.position().add(0.0D, 1.0D, 0.0D);
            faceToward(dragon, liveAim, 7.5F, 5.0F);
            dragon.setDeltaMovement(dragon.getDeltaMovement().scale(0.70D));
            if (s.tick % 3 == 0) {
                level.sendParticles(ParticleTypes.ELECTRIC_SPARK,
                        dragon.getX(), dragon.getY() + 1.0D, dragon.getZ(),
                        3, 1.3D, 0.5D, 1.3D, 0.02D);
            }
            if (s.diveTimer <= 0) {
                s.diveTarget = target.position().add(
                        target.getDeltaMovement().scale(4.0D)).add(0, 0.7D, 0);
                s.diveStage = 1;
                s.diveTimer = 76;
            }
            return;
        }

        Vec3 to = s.diveTarget.subtract(dragon.position());
        double distance = to.length();

        // Turn at a limited angular speed AND steer using the resulting heading.
        // Velocity lerping gives inertia, avoiding the old 'grabbed and thrown' feel.
        faceToward(dragon, s.diveTarget, 10.5F, 7.0F);
        double angle = Math.toRadians(dragon.getYRot());
        double speed = 0.94D + 0.08D * s.phase;
        double dy = clamp(to.y * 0.050D, -0.65D, 0.25D);
        Vec3 desired = new Vec3(-Math.sin(angle) * speed, dy, Math.cos(angle) * speed);
        dragon.setDeltaMovement(dragon.getDeltaMovement().scale(0.66D).add(desired.scale(0.34D)));

        if (s.tick % 4 == 0) {
            level.sendParticles(PowerParticleOption.create(ParticleTypes.DRAGON_BREATH, 1.0F),
                    dragon.getX(), dragon.getY(), dragon.getZ(),
                    2, 1.0D, 0.4D, 1.0D, 0.012D);
        }

        boolean nearTarget = distance <= 5.5D;
        boolean timedOut = s.diveTimer <= 0;
        if (!nearTarget && !timedOut) return;

        // Avoid cheap off-target blasts when vanilla AI prevents an intercept.
        if (nearTarget && inArena(dragon.position())) {
            boolean last = s.divesRemaining == 1;
            shockwave(level, dragon.position(), last ? 10.0D : 8.0D,
                    8.0F + s.phase * 1.5F + (last ? 3.0F : 0.0F));
        }
        s.divesRemaining--;
        if (s.divesRemaining > 0) {
            s.diveStage = 2;
            s.diveTimer = 24;
        } else {
            s.diveActive = false;
            s.diveTarget = null;
        }
    }

    private static boolean inArena(Vec3 location) {
        return location.x * location.x + location.z * location.z
                <= MAX_DIVE_ARENA_RADIUS * MAX_DIVE_ARENA_RADIUS;
    }

    private static double clamp(double value, double low, double high) {
        return Math.max(low, Math.min(high, value));
    }

    private static void faceToward(EnderDragon dragon, Vec3 point,
                                   float maxYawStep, float maxPitchStep) {
        Vec3 to = point.subtract(dragon.position());
        if (to.lengthSqr() < 0.001D) return;
        float desiredYaw = (float) Math.toDegrees(Math.atan2(-to.x, to.z));
        float yawDelta = (float) Math.IEEEremainder(desiredYaw - dragon.getYRot(), 360.0D);
        dragon.setYRot(dragon.getYRot() + (float) clamp(yawDelta, -maxYawStep, maxYawStep));
        double flat = Math.hypot(to.x, to.z);
        float desiredPitch = (float) Math.toDegrees(Math.atan2(-to.y, flat));
        float pitchDelta = (float) Math.IEEEremainder(desiredPitch - dragon.getXRot(), 360.0D);
        dragon.setXRot(dragon.getXRot() + (float) clamp(pitchDelta, -maxPitchStep, maxPitchStep));
    }

    private static void startLaser(ServerLevel level, EnderDragon dragon, ServerPlayer target, State s) {
        s.target = target.getUUID();
        s.laserTarget = target.position().add(0.0D, 1.0D, 0.0D);
        s.laserCharge = 48;     // 2.4 second warning.
        s.laserActive = 0;
        s.laserCooldown = cooldown(s.phase, 210);
        s.attackCooldown = cooldown(s.phase, 150);
        warning(level, s.laserTarget);
        dragon.playSound(SoundEvents.BEACON_ACTIVATE, 4.0F, 0.45F);
    }

    private static void tickLaser(ServerLevel level, EnderDragon dragon, State s) {
        if (s.laserCharge > 0) {
            s.laserCharge--;
            dragon.setDeltaMovement(dragon.getDeltaMovement().scale(0.72D));
            ServerPlayer target = player(level, s.target);
            if (target != null && s.laserCharge > 12) {
                s.laserTarget = target.position().add(0.0D, 1.0D, 0.0D);
            }
            if (s.laserTarget != null) {
                faceToward(dragon, s.laserTarget, 5.0F, 4.0F);
                Vec3 origin = dragon.position().add(0.0D, 1.5D, 0.0D);
                Vec3 beam = s.laserTarget.subtract(origin);
                if (beam.lengthSqr() > 0.001D && s.tick % 3 == 0) {
                    renderBeam(level, origin, beam.normalize(), Math.min(90, beam.length()),
                            ParticleTypes.ELECTRIC_SPARK, 5.0D);
                }
            }
            if (s.laserCharge == 0) {
                if (s.laserTarget == null) return;
                Vec3 from = dragon.position().add(0.0D, 1.5D, 0.0D);
                Vec3 path = s.laserTarget.subtract(from);
                if (path.lengthSqr() < 0.001D) return;
                s.laserDirection = path.normalize();
                s.laserActive = 44;
                dragon.playSound(SoundEvents.BEACON_POWER_SELECT, 5.0F, 0.35F);
            }
            return;
        }

        if (s.laserActive <= 0 || s.laserDirection == null) return;
        s.laserActive--;
        dragon.setDeltaMovement(dragon.getDeltaMovement().scale(0.66D));

        Vec3 origin = dragon.position().add(0.0D, 1.5D, 0.0D);
        Vec3 end = origin.add(s.laserDirection.scale(95.0D));
        if (s.tick % 2 == 0) {
            renderBeam(level, origin, s.laserDirection, 95.0D, ParticleTypes.END_ROD, 3.0D);
        }

        // The exact same origin and endpoint are used for visuals and hit checks.
        // No instant undodgeable damage: the direction was locked before firing.
        if (s.tick % 5 == 0) {
            for (ServerPlayer p : participants(level)) {
                if (distancePointSegment(p.position().add(0, 1, 0), origin, end) < 3.0D) {
                    p.hurt(level.damageSources().dragonBreath(), 10.0F + s.phase * 2.0F);
                    Vec3 push = p.position().subtract(origin);
                    if (push.lengthSqr() > 0.001D) {
                        push = push.normalize().scale(0.55D);
                        p.push(push.x, 0.22D, push.z);
                    }
                }
            }
        }
        if (s.laserActive == 0) {
            s.laserDirection = null;
            s.laserTarget = null;
        }
    }

    private static void renderBeam(ServerLevel level, Vec3 origin, Vec3 direction,
                                   double length, ParticleOptions particle, double spacing) {
        for (double d = 2.0D; d <= length; d += spacing) {
            Vec3 p = origin.add(direction.scale(d));
            level.sendParticles(particle, p.x, p.y, p.z, 1, 0.08, 0.08, 0.08, 0.0);
        }
    }

    private static void startOrbs(ServerLevel level, EnderDragon dragon,
                                  ServerPlayer target, State s) {
        s.orbCooldown = cooldown(s.phase, 205);
        s.attackCooldown = cooldown(s.phase, 145);
        int count = Math.min(5, 2 + s.phase);
        for (int i = 0; i < count; i++) {
            double angle = 2.0D * Math.PI * i / count;
            Vec3 spawn = dragon.position().add(Math.cos(angle) * 4.0D,
                    1.0D + (i % 2), Math.sin(angle) * 4.0D);
            Orb orb = new Orb(spawn, target.getUUID(), 140);
            orb.windup = 24 + i * 3;
            s.orbs.add(orb);
            level.sendParticles(ParticleTypes.END_ROD, spawn.x, spawn.y, spawn.z,
                    8, 0.35D, 0.35D, 0.35D, 0.015D);
        }
        dragon.playSound(SoundEvents.RESPAWN_ANCHOR_CHARGE, 2.5F, 1.35F);
    }

    private static void tickOrbs(ServerLevel level, State s) {
        Iterator<Orb> it = s.orbs.iterator();
        while (it.hasNext()) {
            Orb orb = it.next();
            orb.life--;
            ServerPlayer target = player(level, orb.target);
            if (target == null || orb.life <= 0) { it.remove(); continue; }

            if (orb.windup > 0) {
                orb.windup--;
                // Visible charging ball near the dragon before it starts tracking.
                if (orb.windup % 3 == 0) {
                    level.sendParticles(ParticleTypes.END_ROD,
                            orb.position.x, orb.position.y, orb.position.z,
                            4, 0.38D, 0.38D, 0.38D, 0.005D);
                }
                continue;
            }

            Vec3 targetPoint = target.position().add(0.0D, 1.0D, 0.0D);
            Vec3 to = targetPoint.subtract(orb.position);
            if (to.lengthSqr() > 0.001D) {
                Vec3 direction = to.normalize();
                double speed = 0.35D + s.phase * 0.025D;
                orb.position = orb.position.add(direction.scale(speed));
            }

            if (s.tick % 2 == 0) {
                level.sendParticles(ParticleTypes.END_ROD,
                        orb.position.x, orb.position.y, orb.position.z,
                        2, 0.12D, 0.12D, 0.12D, 0.01D);
            }
            if (orb.position.distanceTo(targetPoint) <= 1.4D) {
                target.hurt(level.damageSources().dragonBreath(), 5.0F + s.phase);
                level.sendParticles(ParticleTypes.EXPLOSION,
                        orb.position.x, orb.position.y, orb.position.z,
                        2, 0.3D, 0.3D, 0.3D, 0.0D);
                it.remove();
            }
        }
    }

    private static void startBreath(ServerLevel level, EnderDragon dragon,
                                    ServerPlayer target, State s) {
        s.target = target.getUUID();
        s.breathBursts = Math.min(5, 2 + s.phase);
        s.breathTimer = 22; // Warning/charge before the first actual projectile.
        s.attackCooldown = cooldown(s.phase, 145);
        warning(level, target.position());
        dragon.playSound(SoundEvents.ENDER_DRAGON_GROWL, 3.0F, 0.75F);
    }

    private static void tickBreath(ServerLevel level, EnderDragon dragon, State s) {
        if (s.breathBursts <= 0) return;
        if (s.breathTimer > 0) { s.breathTimer--; return; }
        ServerPlayer target = player(level, s.target);
        if (target == null) { s.breathBursts = 0; return; }

        // Real vanilla DragonFireball entity, aimed at a live player instead of
        // particles/damage appearing on the player's current coordinates.
        Vec3 spawn = dragon.position().add(0.0D, 2.5D, 0.0D);
        Vec3 predicted = target.position().add(0.0D, 1.0D, 0.0D).add(
                target.getDeltaMovement().scale(6.0D));
        Vec3 aim = predicted.subtract(spawn);
        if (aim.lengthSqr() > 0.01D) {
            DragonFireball projectile = new DragonFireball(level, dragon, aim.normalize());
            Vec3 nose = spawn.add(aim.normalize().scale(3.2D));
            projectile.setPos(nose.x, nose.y, nose.z);
            level.addFreshEntity(projectile);
            level.sendParticles(PowerParticleOption.create(ParticleTypes.DRAGON_BREATH, 1.0F),
                    nose.x, nose.y, nose.z, 7, 0.6D, 0.6D, 0.6D, 0.02D);
            dragon.playSound(SoundEvents.ENDER_DRAGON_GROWL, 2.8F, 0.8F);
        }
        s.breathBursts--;
        s.breathTimer = 11;
    }

    private static void startShockwave(EnderDragon dragon, State s) {
        s.shockwaveCharge = 28;
        s.shockwaveCooldown = cooldown(s.phase, 220);
        s.attackCooldown = cooldown(s.phase, 170);
        dragon.playSound(SoundEvents.RESPAWN_ANCHOR_CHARGE, 3.0F, 0.7F);
    }

    private static void tickChargedShockwave(ServerLevel level, EnderDragon dragon, State s) {
        if (s.shockwaveCharge <= 0) return;
        s.shockwaveCharge--;
        if (s.tick % 3 == 0)
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, dragon.getX(), dragon.getY(), dragon.getZ(),
                    5, 3, 1, 3, 0.02);
        if (s.shockwaveCharge == 0)
            shockwave(level, dragon.position(), 14.0D, 9.0F + s.phase * 2.0F);
    }

    private static boolean busy(State s) {
        return s.diveActive || s.laserCharge > 0 || s.laserActive > 0
                || s.breathBursts > 0 || s.shockwaveCharge > 0;
    }

    private static void finalPhase(ServerLevel level, EnderDragon dragon, State s) {
        if (!s.finalAnnounced) {
            s.finalAnnounced = true;
            s.finalTimer = 0;
            for (ServerPlayer p : participants(level))
                p.sendOverlayMessage(Component.literal("The Ender Dragon enters its final phase."));
            level.playSound(null, dragon.blockPosition(), SoundEvents.ENDER_DRAGON_GROWL,
                    SoundSource.HOSTILE, 5.0F, 0.55F);
        }

        s.finalTimer++;
        Vec3 hover = new Vec3(0.5D, 82.0D, 0.5D);
        double centerDistance = Math.hypot(dragon.getX() - 0.5D, dragon.getZ() - 0.5D);

        if (centerDistance > 18.0D) {
            if (dragon.getHealth() < 60.0F) dragon.setHealth(60.0F);
            Vec3 to = hover.subtract(dragon.position());
            if (to.lengthSqr() > 0.001D) {
                dragon.setDeltaMovement(to.normalize().scale(1.15D));
                // Velocity is applied with setDeltaMovement above.
            }
            if (s.finalTimer > 600) {
                s.finalTimer = 0;
                dragon.teleportTo(level, 0.5D, 82.0D, 0.5D, java.util.Set.of(),
                        dragon.getYRot(), dragon.getXRot(), false);
            }
        } else {
            if (s.tick % 8 == 0)
                level.sendParticles(PowerParticleOption.create(ParticleTypes.DRAGON_BREATH, 1.0F), dragon.getX(), dragon.getY(), dragon.getZ(),
                        10, 2, 1, 2, 0.03);
            if (s.tick % 50 == 0) shockwave(level, dragon.position(), 10, 7.0F);
        }
    }

    private static void updateCrystalCounter(ServerLevel level, EnderDragon dragon) {
        State s = STATES.get(dragon.getUUID());
        if (s == null || s.tick % 10 != 0) return;
        int count = level.getEntitiesOfClass(EndCrystal.class,
                new AABB(-128, 0, -128, 128, 256, 128)).size();
        if (count != s.lastCrystalCount) {
            s.lastCrystalCount = count;
            for (ServerPlayer p : participants(level))
                p.sendOverlayMessage(Component.literal("End Crystals Remaining: " + count));
        }
    }

    private static void shockwave(ServerLevel level, Vec3 center, double radius, float damage) {
        level.sendParticles(ParticleTypes.EXPLOSION, center.x, center.y + 0.2, center.z,
                8, radius / 3, 0.2, radius / 3, 0.02);
        level.playSound(null, center.x, center.y, center.z, SoundEvents.GENERIC_EXPLODE,
                SoundSource.HOSTILE, 3.0F, 0.7F);

        for (ServerPlayer p : level.getEntitiesOfClass(ServerPlayer.class, new AABB(center, center).inflate(radius))) {
            double d = Math.max(0.1D, p.position().distanceTo(center));
            double scale = 1.0D - Math.min(1.0D, d / radius);
            p.hurt(level.damageSources().dragonBreath(), damage * (float)Math.max(0.35D, scale));
            Vec3 push = p.position().subtract(center);
            if (push.lengthSqr() > 0.001D) {
                push = push.normalize().scale(0.8D * Math.max(0.35D, scale));
                p.push(push.x, 0.3D + 0.4D * scale, push.z);
            }
        }
    }

    private static void warning(ServerLevel level, Vec3 p) {
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, p.x, p.y + 0.2, p.z,
                12, 1.4, 0.2, 1.4, 0.02);
        level.playSound(null, p.x, p.y, p.z, SoundEvents.ENDER_DRAGON_GROWL,
                SoundSource.HOSTILE, 2.5F, 0.8F);
    }

    private static void ambience(ServerLevel level, EnderDragon dragon, State s) {
        if (s.tick % 12 != 0) return;
        level.sendParticles(ParticleTypes.PORTAL, dragon.getX(), dragon.getY(), dragon.getZ(),
                s.phase >= 3 ? 2 : 1, 3, 2, 3, 0.015);
    }

    private static int phase(float hp) {
        if (hp > 450) return 1;
        if (hp > 300) return 2;
        if (hp > 150) return 3;
        return 4;
    }

    private static int cooldown(int phase, int base) {
        return Math.max(30, base - (phase - 1) * 14);
    }

    private static List<ServerPlayer> participants(ServerLevel level) {
        return level.getEntitiesOfClass(ServerPlayer.class,
                new AABB(-RADIUS, 0, -RADIUS, RADIUS, 256, RADIUS), p -> !p.isSpectator());
    }

    private static ServerPlayer nearest(ServerLevel level, Entity entity) {
        return participants(level).stream()
                .min(Comparator.comparingDouble(entity::distanceToSqr)).orElse(null);
    }

    private static ServerPlayer player(ServerLevel level, UUID id) {
        if (id == null) return null;
        for (ServerPlayer p : level.players()) if (id.equals(p.getUUID())) return p;
        return null;
    }

    private static double distancePointSegment(Vec3 point, Vec3 start, Vec3 end) {
        Vec3 segment = end.subtract(start);
        double lengthSquared = segment.lengthSqr();
        if (lengthSquared <= 0.000001D) return point.distanceTo(start);
        double t = point.subtract(start).dot(segment) / lengthSquared;
        t = Math.max(0, Math.min(1, t));
        return point.distanceTo(start.add(segment.scale(t)));
    }

    private static final class Orb {
        Vec3 position;
        final UUID target;
        int life;
        int windup;
        Orb(Vec3 position, UUID target, int life) {
            this.position = position;
            this.target = target;
            this.life = life;
        }
    }

    private static final class State {
        boolean initialized;
        boolean finalAnnounced;
        boolean diveActive;
        int tick, phase, nextAttack;
        int attackCooldown, diveCooldown, laserCooldown, orbCooldown, shockwaveCooldown;
        int divesRemaining, diveDelay, diveTimer;
        int diveStage;
        int laserCharge, laserActive;
        int breathBursts, breathTimer, shockwaveCharge, finalTimer;
        int lastCrystalCount = -1;
        UUID target;
        Vec3 diveTarget, laserTarget, laserDirection;
        final List<Orb> orbs = new ArrayList<>();
    }
}
