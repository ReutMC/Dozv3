package com.example.automace;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.Random;

public class AutoMaceMod implements ClientModInitializer {

    // ---------------- settings ----------------
    private static final double  TRACK_RANGE        = 14.0;  // how far we look for a target while falling
    private static final double  AIM_RANGE          = 9.0;   // camera assist only starts inside this distance
    private static final float   AIM_CONE           = 75f;   // targets farther than reach must be inside this view cone
    private static final boolean AIM_ASSIST        = true;  // false = never rotates the camera
    private static final boolean ALLOW_ELYTRA      = true;
    private static final boolean SWAP_BACK         = true;  // return to the old slot AFTER landing
    private static final boolean REQUIRE_SMASH_FALL = true; // false = hit any airborne target that is in reach
    private static final float   MIN_CHARGE        = 0.55f; // lowest attack charge we accept when the window is closing

    private final Random rng = new Random();
    private KeyBinding toggleKey;
    private boolean enabled = false;
    private long tickCount = 0;

    // per-fall state (re-randomised at the start of every fall)
    private boolean airborne, attackedThisFall;
    private double minFall, reachLimit;
    private float cooldownNeed, aimSpeed;
    private int hesitate;
    private int swapWait = -1, attackWait = -1, windowTicks, missTicks;
    private long lastSwapTick = -100;
    private int swapExtra = 2;
    private int prevSlot = -1, returnTimer = -1;

    private PlayerEntity target;
    private double offX, offY, offZ, wanderX, wanderY, wanderZ;
    private int offsetAge;
    private float yawVel, pitchVel, yawRes, pitchRes;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.automace.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R,
                KeyBinding.Category.create(Identifier.of("automace", "main"))));
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    // ================= main loop =================
    private void tick(MinecraftClient mc) {
        while (toggleKey.wasPressed()) {
            enabled = !enabled;
            endFall();
            if (mc.player != null)
                mc.player.sendMessage(Text.literal("AutoMace: " + (enabled ? "ON" : "OFF")), true);
        }

        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null || mc.interactionManager == null) return;
        tickCount++;

        // swap back to the previous slot after landing (with a human-like delay)
        if (returnTimer > 0 && --returnTimer == 0) {
            if (prevSlot >= 0 && prevSlot < 9) p.getInventory().setSelectedSlot(prevSlot);
            prevSlot = -1;
            returnTimer = -1;
        }

        if (!enabled || mc.currentScreen != null || p.isSpectator()) return;

        boolean grounded = p.isOnGround() || p.isTouchingWater() || p.isInLava()
                || p.hasVehicle() || p.getAbilities().flying || p.isClimbing();
        if (grounded) {
            if (airborne && SWAP_BACK && prevSlot != -1 && returnTimer < 0) {
                returnTimer = 12 + rng.nextInt(24);
            }
            endFall();
            return;
        }

        if (!airborne) startFall();

        boolean gliding = p.isGliding();
        if (gliding && !ALLOW_ELYTRA) return;

        double fall = p.fallDistance;
        Vec3d vel = p.getVelocity();

        // after a hit the server resets fall distance (the client does not know) ->
        // wait for a new fall, e.g. when we get launched upwards again
        if (attackedThisFall) {
            if (vel.y > 0.1) startFall(); else { decayAim(); return; }
        }

        boolean falling = vel.y < (gliding ? -0.4 : -0.1);
        if (!falling) { decayAim(); windowTicks = 0; return; }

        target = pickTarget(mc, p, target);
        if (target == null) { swapWait = attackWait = -1; windowTicks = 0; decayAim(); return; }

        if (++offsetAge > 20 + rng.nextInt(25)) rollOffset();
        updateWander();

        Box box = target.getBoundingBox();
        Vec3d eye = p.getEyePos();
        double reachNow  = reachFrom(eye, box);
        double reachNext = reachFrom(new Vec3d(eye.x + vel.x, eye.y + vel.y, eye.z + vel.z), box);
        boolean inReach = reachNow <= reachLimit;

        // ---- 1) pick the best mace and swap EARLY ----
        // (switching items resets the attack cooldown and the mace cooldown is long,
        //  so the swap has to happen at the start of the dive, not at the last moment)
        int cur = p.getInventory().getSelectedSlot();
        double estFall = Math.max(fall, 3.0);
        int best = bestMaceSlot(p, target, estFall);
        if (best == -1) return;

        boolean needSwap = cur != best;
        if (needSwap && p.getInventory().getStack(cur).isOf(Items.MACE)) {
            double sc = score(p.getInventory().getStack(cur), estFall, target);
            double sb = score(p.getInventory().getStack(best), estFall, target);
            needSwap = sb > sc * 1.08;   // only swap if it is clearly better
        }
        if (needSwap) {
            if (swapWait < 0) swapWait = gaussTicks(3.0, 1.2, 1, 7);
            if (swapWait > 0) {
                swapWait--;
            } else {
                if (prevSlot == -1) prevSlot = cur;
                p.getInventory().setSelectedSlot(best);
                lastSwapTick = tickCount;
                swapExtra = 2 + rng.nextInt(3);
                swapWait = -1;
            }
        }

        // ---- 2) aiming (only when the target is close) ----
        Vec3d aim = aimPoint(target);
        if (AIM_ASSIST && reachNow < AIM_RANGE) aimStep(mc, p, aim, reachNow); else decayAim();

        // ---- 3) attack: if the target is in reach and under the crosshair, hit it ----
        boolean holdingMace = p.getInventory().getStack(p.getInventory().getSelectedSlot()).isOf(Items.MACE);
        boolean smashOk = !REQUIRE_SMASH_FALL || fall >= minFall;

        if (inReach) windowTicks++; else windowTicks = 0;
        boolean rayHit = lookHits(p, box, reachLimit + 0.15);

        // the longer the target stays in reach, the less charge we insist on
        float need = Math.max(MIN_CHARGE, cooldownNeed - 0.07f * windowTicks);

        boolean ready = holdingMace
                && smashOk
                && inReach
                && rayHit
                && p.getAttackCooldownProgress(0.5f) >= need
                && tickCount - lastSwapTick >= swapExtra;

        if (!ready) {
            // do not reset the reaction timer on a single flicker
            if (++missTicks >= 3) attackWait = -1;
            return;
        }
        missTicks = 0;

        // short human reaction (0 to 3 ticks), the window while falling is only 2-3 ticks long
        if (attackWait < 0) { attackWait = gaussTicks(1.2, 0.9, 0, 3) + hesitate; hesitate = 0; }
        // about to leave reach next tick -> last chance, click now
        if (reachNext > reachLimit + 0.15) attackWait = 0;
        if (attackWait > 0) { attackWait--; return; }

        mc.interactionManager.attackEntity(p, target);
        p.swingHand(Hand.MAIN_HAND);
        attackedThisFall = true;
        attackWait = -1;
    }

    // ================= fall management =================
    private void startFall() {
        airborne = true;
        attackedThisFall = false;
        returnTimer = -1;                                 // keep the mace in hand while we keep jumping
        minFall      = 1.7 + rng.nextDouble() * 0.8;      // smash needs > 1.5 blocks
        reachLimit   = 2.8 + rng.nextDouble() * 0.2;      // 2.8 to 3.0 blocks
        cooldownNeed = 0.85f + rng.nextFloat() * 0.15f;
        aimSpeed     = 0.22f + rng.nextFloat() * 0.16f;
        hesitate     = rng.nextInt(100) < 5 ? 2 + rng.nextInt(2) : 0;
        swapWait = attackWait = -1;
        windowTicks = missTicks = 0;
        rollOffset();
    }

    private void endFall() {
        airborne = false;
        attackedThisFall = false;
        target = null;
        swapWait = attackWait = -1;
        windowTicks = missTicks = 0;
        yawVel = pitchVel = 0;
    }

    // ================= mace selection =================
    private int bestMaceSlot(ClientPlayerEntity p, LivingEntity t, double fall) {
        int best = -1;
        double bestScore = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack s = p.getInventory().getStack(i);
            if (!s.isOf(Items.MACE)) continue;
            double sc = score(s, fall, t);
            if (sc > bestScore) { bestScore = sc; best = i; }
        }
        return best;
    }

    /** Estimated damage after the target's armor, plus small tie-breakers. */
    private double score(ItemStack s, double fall, LivingEntity t) {
        int remaining = s.getMaxDamage() - s.getDamage();
        if (remaining <= 1) return -1;

        int density = level(s, Enchantments.DENSITY);
        int breach  = level(s, Enchantments.BREACH);
        int fire    = level(s, Enchantments.FIRE_ASPECT);
        int wind    = level(s, Enchantments.WIND_BURST);

        double smash = fall <= 3 ? 4 * fall : fall <= 8 ? 12 + 2 * (fall - 3) : 22 + (fall - 8);
        double raw = 6 + smash + 0.5 * density * fall;

        double armor = t.getArmor() * (1.0 - 0.15 * breach);
        double tough = t.getAttributeValue(EntityAttributes.ARMOR_TOUGHNESS);
        double eff = MathHelper.clamp(armor - raw / (2.0 + tough / 4.0), armor / 5.0, 20.0);
        double dmg = raw * (1.0 - eff / 25.0);

        return dmg + 0.4 * fire + 0.3 * wind + 0.0005 * remaining;
    }

    private int level(ItemStack s, RegistryKey<Enchantment> key) {
        var ench = s.getEnchantments();
        for (RegistryEntry<Enchantment> e : ench.getEnchantments()) {
            if (e.matchesKey(key)) return ench.getLevel(e);
        }
        return 0;
    }

    // ================= targeting =================
    /** Players inside reach win; otherwise the closest one near the view direction. */
    private PlayerEntity pickTarget(MinecraftClient mc, ClientPlayerEntity p, PlayerEntity current) {
        PlayerEntity best = null;
        double bestScore = Double.MAX_VALUE;
        for (PlayerEntity e : mc.world.getPlayers()) {
            if (e == p || !e.isAlive() || e.isSpectator()) continue;
            double d = reachFrom(p.getEyePos(), e.getBoundingBox());
            if (d > TRACK_RANGE || !p.canSee(e)) continue;
            float ang = coneAngle(p, e.getBoundingBox().getCenter());
            boolean close = d <= reachLimit + 0.5;
            if (ang > AIM_CONE && !close) continue;
            double s = d + ang * 0.05;
            if (e == current) s -= 1.0;   // stay loyal to the previous target
            if (close) s -= 3.0;          // somebody we can actually hit has priority
            if (s < bestScore) { bestScore = s; best = e; }
        }
        return best;
    }

    private void rollOffset() {
        offX = (rng.nextDouble() - 0.5) * 0.4;
        offY = 0.35 + rng.nextDouble() * 0.4;
        offZ = (rng.nextDouble() - 0.5) * 0.4;
        offsetAge = 0;
    }

    private void updateWander() {
        wanderX = wanderX * 0.9 + rng.nextGaussian() * 0.012;
        wanderY = wanderY * 0.9 + rng.nextGaussian() * 0.012;
        wanderZ = wanderZ * 0.9 + rng.nextGaussian() * 0.012;
    }

    private Vec3d aimPoint(PlayerEntity t) {
        Box b = t.getBoundingBox();
        double w = b.maxX - b.minX, h = b.maxY - b.minY;
        return new Vec3d(
                b.getCenter().x + offX * w + wanderX,
                b.minY + offY * h + wanderY,
                b.getCenter().z + offZ * w + wanderZ);
    }

    /** Distance from an eye position to the closest point of a hitbox. */
    private double reachFrom(Vec3d eye, Box b) {
        Vec3d c = new Vec3d(
                MathHelper.clamp(eye.x, b.minX, b.maxX),
                MathHelper.clamp(eye.y, b.minY, b.maxY),
                MathHelper.clamp(eye.z, b.minZ, b.maxZ));
        return eye.distanceTo(c);
    }

    /** True when the crosshair ray hits the (slightly padded) hitbox within the given length. */
    private boolean lookHits(ClientPlayerEntity p, Box b, double reach) {
        Vec3d o = p.getEyePos();
        Vec3d d = p.getRotationVec(1.0f);
        double m = 0.12;
        double[] oo = {o.x, o.y, o.z};
        double[] dd = {d.x, d.y, d.z};
        double[] lo = {b.minX - m, b.minY - m, b.minZ - m};
        double[] hi = {b.maxX + m, b.maxY + m, b.maxZ + m};
        double tmin = 0.0, tmax = reach;
        for (int i = 0; i < 3; i++) {
            if (Math.abs(dd[i]) < 1.0e-9) {
                if (oo[i] < lo[i] || oo[i] > hi[i]) return false;
            } else {
                double t1 = (lo[i] - oo[i]) / dd[i];
                double t2 = (hi[i] - oo[i]) / dd[i];
                if (t1 > t2) { double t = t1; t1 = t2; t2 = t; }
                tmin = Math.max(tmin, t1);
                tmax = Math.min(tmax, t2);
                if (tmin > tmax) return false;
            }
        }
        return true;
    }

    // ================= rotation =================
    private float yawFrom(Vec3d e, Vec3d pt) {
        return (float) (MathHelper.atan2(pt.z - e.z, pt.x - e.x) * 180.0 / Math.PI) - 90f;
    }

    private float pitchFrom(Vec3d e, Vec3d pt) {
        return (float) -(MathHelper.atan2(pt.y - e.y, Math.hypot(pt.x - e.x, pt.z - e.z)) * 180.0 / Math.PI);
    }

    private float coneAngle(ClientPlayerEntity p, Vec3d pt) {
        Vec3d look = p.getRotationVec(1.0f);
        Vec3d dir = pt.subtract(p.getEyePos()).normalize();
        return (float) Math.toDegrees(Math.acos(MathHelper.clamp(look.dotProduct(dir), -1.0, 1.0)));
    }

    private void aimStep(MinecraftClient mc, ClientPlayerEntity p, Vec3d pt, double reachNow) {
        // aim from where we will be next tick, because we are falling fast
        Vec3d v = p.getVelocity();
        Vec3d e0 = p.getEyePos();
        Vec3d eye = new Vec3d(e0.x + v.x, e0.y + v.y, e0.z + v.z);

        float dYaw = MathHelper.wrapDegrees(yawFrom(eye, pt) - p.getYaw());
        float dPitch = pitchFrom(eye, pt) - p.getPitch();

        // close enough: stop moving, like a real mouse
        if (Math.abs(dYaw) < 0.4f && Math.abs(dPitch) < 0.4f) { decayAim(); return; }

        // the closer the target, the faster the hand moves (1.0 to 2.0)
        float urgency = reachNow < 6.0 ? 1.0f + (float) ((6.0 - reachNow) / 6.0) : 1.0f;
        float wantYaw = dYaw * aimSpeed * urgency;
        float wantPitch = dPitch * aimSpeed * 0.85f * urgency;   // pitch is a bit slower than yaw

        // inertia + noise => soft start, acceleration, slight overshoot
        yawVel   = yawVel   * 0.45f + wantYaw   * 0.55f + (float) rng.nextGaussian() * 0.12f;
        pitchVel = pitchVel * 0.45f + wantPitch * 0.55f + (float) rng.nextGaussian() * 0.09f;
        yawVel   = MathHelper.clamp(yawVel,   -22f, 22f);
        pitchVel = MathHelper.clamp(pitchVel, -18f, 18f);

        // quantise to the mouse step (GCD) and carry the remainder
        double f = mc.options.getMouseSensitivity().getValue() * 0.6 + 0.2;
        double gcd = f * f * f * 8.0 * 0.15;

        float ty = yawVel + yawRes;
        float qy = (float) (Math.round(ty / gcd) * gcd);
        yawRes = ty - qy;

        float tp = pitchVel + pitchRes;
        float qp = (float) (Math.round(tp / gcd) * gcd);
        pitchRes = tp - qp;

        p.setYaw(p.getYaw() + qy);
        p.setPitch(MathHelper.clamp(p.getPitch() + qp, -90f, 90f));
    }

    private void decayAim() {
        yawVel *= 0.5f;
        pitchVel *= 0.5f;
    }

    // ================= helpers =================
    private int gaussTicks(double mean, double sd, int min, int max) {
        int v = (int) Math.round(mean + rng.nextGaussian() * sd);
        return Math.max(min, Math.min(max, v));
    }
}
