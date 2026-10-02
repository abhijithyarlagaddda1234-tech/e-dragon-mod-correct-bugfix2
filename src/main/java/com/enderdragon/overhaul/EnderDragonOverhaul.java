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
        tickBreath(level, s);
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
        for (int attempt = 0; attempt < 6; attempt++) {
            int choice = s.nextAttack++ % 5;
            if (choice == 0) { startBreath(dragon, target, s); return; }
            if (choice == 1 && s.diveCooldown == 0) { startDive(level, dragon, target, s); return; }
            if (choice == 2 && s.phase >= 2 && s.laserCooldown == 0) { startLaser(level, dragon, target, s); return; }
            if (choice == 3 && s.phase >= 2 && s.orbCooldown == 0) { startOrbs(dragon, target, s); return; }
            if (choice == 4 && s.phase >= 3 && s.shockwaveCooldown == 0) { startShockwave(dragon, s); return; }
        }
        startBreath(dragon, target, s);
    }

    private static void startDive(ServerLevel level, EnderDragon dragon, ServerPlayer target, State s) {
        s.target = target.getUUID();
        s.divesRemaining = s.phase >= 2 ? 3 : 1;
        s.diveCooldown = cooldown(s.phase, 190);
        s.attackCooldown = cooldown(s.phase, s.phase >= 2 ? 210 : 150);
        beginDive(level, dragon, target, s);
    }

    private static void beginDive(ServerLevel level, EnderDragon dragon, ServerPlayer target, State s) {
        s.diveActive = true;
        s.diveDelay = 0;
        s.diveTimer = 42;
        s.diveTarget = target.position().add(0, 0.5, 0);
        warning(level, s.diveTarget);
        dragon.playSound(SoundEvents.ENDER_DRAGON_GROWL, 3.0F, 0.65F);
    }

    private static void tickDive(ServerLevel level, EnderDragon dragon, State s) {
        if (!s.diveActive) return;

        if (s.diveDelay > 0) {
            s.diveDelay--;
            if (s.diveDelay == 0) {
                ServerPlayer next = nearest(level, dragon);
                if (next == null) { s.diveActive = false; return; }
                s.target = next.getUUID();
                beginDive(level, dragon, next, s);
            }
            return;
        }

        if (s.diveTarget == null) { s.diveActive = false; return; }
        s.diveTimer--;

        Vec3 to = s.diveTarget.subtract(dragon.position());
        double distance = to.length();
        if (distance > 0.001D) {
            double speed = 1.15D + s.phase * 0.12D;
            dragon.setDeltaMovement(to.normalize().scale(speed));
            // Velocity is applied with setDeltaMovement above.
        }

        if (s.tick % 2 == 0) {
            level.sendParticles(PowerParticleOption.create(ParticleTypes.DRAGON_BREATH, 1.0F), dragon.getX(), dragon.getY(), dragon.getZ(),
                    2, 1.2, 0.6, 1.2, 0.01);
        }

        if (distance <= 4.5D || s.diveTimer <= 0) {
            boolean last = s.divesRemaining == 1;
            shockwave(level, dragon.position(), last ? 11.0D : 8.5D,
                    9.0F + s.phase * 2.0F + (last ? 3.0F : 0.0F));
            s.divesRemaining--;
            s.diveTarget = null;
            if (s.divesRemaining > 0) s.diveDelay = 14;
            else s.diveActive = false;
        }
    }

    private static void startLaser(ServerLevel level, EnderDragon dragon, ServerPlayer target, State s) {
        s.target = target.getUUID();
        s.laserTarget = target.position().add(0, 1.0, 0);
        s.laserCharge = 36;
        s.laserActive = 0;
        s.laserCooldown = cooldown(s.phase, 240);
        s.attackCooldown = cooldown(s.phase, 210);
        warning(level, s.laserTarget);
        dragon.playSound(SoundEvents.BEACON_ACTIVATE, 4.0F, 0.45F);
    }

    private static void tickLaser(ServerLevel level, EnderDragon dragon, State s) {
        if (s.laserCharge > 0) {
            s.laserCharge--;
            dragon.setDeltaMovement(dragon.getDeltaMovement().scale(0.25D));
            Vec3 origin = dragon.position().add(0, 1.5, 0);
            if (s.laserTarget != null && s.tick % 3 == 0) {
                Vec3 preview = s.laserTarget.subtract(origin);
                if (preview.lengthSqr() > 0.001D)
                    renderBeam(level, origin, preview.normalize(), 60, ParticleTypes.ELECTRIC_SPARK, 8);
            }
            if (s.laserCharge == 0 && s.laserTarget != null) {
                Vec3 dir = s.laserTarget.subtract(origin);
                if (dir.lengthSqr() > 0.001D) {
                    s.laserDirection = dir.normalize();
                    s.laserActive = 32;
                    dragon.playSound(SoundEvents.BEACON_POWER_SELECT, 5.0F, 0.35F);
                }
            }
            return;
        }

        if (s.laserActive <= 0 || s.laserDirection == null) return;
        s.laserActive--;
        dragon.setDeltaMovement(dragon.getDeltaMovement().scale(0.15D));

        Vec3 origin = dragon.position().add(0, 1.5, 0);
        Vec3 end = origin.add(s.laserDirection.scale(64));

        if (s.tick % 2 == 0)
            renderBeam(level, origin, s.laserDirection, 64, ParticleTypes.END_ROD, 3);

        if (s.tick % 5 == 0) {
            for (ServerPlayer p : participants(level)) {
                if (distancePointSegment(p.position().add(0, 1, 0), origin, end) <= 2.4D) {
                    p.hurt(level.damageSources().dragonBreath(), 11.0F + s.phase * 2.0F);
                    Vec3 push = p.position().subtract(origin);
                    if (push.lengthSqr() > 0.001D) {
                        push = push.normalize().scale(0.7D);
                        p.push(push.x, 0.25D, push.z);
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

    private static void startOrbs(EnderDragon dragon, ServerPlayer target, State s) {
        s.orbCooldown = cooldown(s.phase, 190);
        s.attackCooldown = cooldown(s.phase, 120);
        int count = Math.min(6, 2 + s.phase);
        for (int i = 0; i < count; i++) {
            double a = Math.PI * 2.0D * i / count;
            Vec3 offset = new Vec3(Math.cos(a) * 3, 1 + (i % 2), Math.sin(a) * 3);
            s.orbs.add(new Orb(dragon.position().add(offset), target.getUUID(), 100));
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

            Vec3 targetPoint = target.position().add(0, 1, 0);
            Vec3 to = targetPoint.subtract(orb.position);
            if (to.lengthSqr() > 0.001D)
                orb.position = orb.position.add(to.normalize().scale(0.28D + s.phase * 0.035D));

            level.sendParticles(ParticleTypes.PORTAL, orb.position.x, orb.position.y, orb.position.z,
                    2, 0.12, 0.12, 0.12, 0.01);

            if (orb.position.distanceTo(targetPoint) <= 1.6D) {
                target.hurt(level.damageSources().dragonBreath(), 5.0F + s.phase);
                level.sendParticles(ParticleTypes.EXPLOSION, orb.position.x, orb.position.y, orb.position.z,
                        2, 0.3, 0.3, 0.3, 0.0);
                it.remove();
            }
        }
    }

    private static void startBreath(EnderDragon dragon, ServerPlayer target, State s) {
        s.target = target.getUUID();
        s.breathBursts = Math.min(7, 3 + s.phase);
        s.breathTimer = 1;
        s.attackCooldown = cooldown(s.phase, 105);
        dragon.playSound(SoundEvents.ENDER_DRAGON_GROWL, 3.0F, 0.75F);
    }

    private static void tickBreath(ServerLevel level, State s) {
        if (s.breathBursts <= 0) return;
        if (s.breathTimer > 0) { s.breathTimer--; return; }

        ServerPlayer target = player(level, s.target);
        if (target == null) { s.breathBursts = 0; return; }
        Vec3 point = target.position().add(0, 0.5, 0);
        level.sendParticles(PowerParticleOption.create(ParticleTypes.DRAGON_BREATH, 1.0F), point.x, point.y, point.z,
                8, 1.2, 0.5, 1.2, 0.02);

        for (ServerPlayer p : level.getEntitiesOfClass(ServerPlayer.class, new AABB(point, point).inflate(3.5D)))
            p.hurt(level.damageSources().dragonBreath(), 4.0F + s.phase);

        s.breathBursts--;
        s.breathTimer = 8;
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
        int laserCharge, laserActive;
        int breathBursts, breathTimer, shockwaveCharge, finalTimer;
        int lastCrystalCount = -1;
        UUID target;
        Vec3 diveTarget, laserTarget, laserDirection;
        final List<Orb> orbs = new ArrayList<>();
    }
}
