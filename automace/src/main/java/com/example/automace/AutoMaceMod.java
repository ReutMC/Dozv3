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
    private static final double  SEARCH_RANGE  = 8.0;
    private static final float   AIM_CONE      = 40f;   // target must be inside this view cone
    private static final float   ATTACK_CONE   = 9f;    // allowed aim error to attack
    private static final boolean AIM_ASSIST    = true;  // false = never rotates the camera
    private static final boolean ALLOW_ELYTRA  = true;
    private static final boolean SWAP_BACK     = true;

    private final Random rng = new Random();
    private KeyBinding toggleKey;
    private boolean enabled = false;
    private long tickCount = 0;

    // per-fall state (re-randomised at the start of every fall)
    private boolean airborne, attackedThisFall;
    private double minFall, reachLimit;
    private float cooldownNeed, aimSpeed;
    private int hesitate;
    private int swapWait = -1, attackWait = -1;
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

        // swap back to the previous slot (with a human-like delay)
        if (returnTimer > 0 && --returnTimer == 0) {
            if (prevSlot >= 0 && prevSlot < 9) p.getInventory().setSelectedSlot(prevSlot);
            prevSlot = -1;
            returnTimer = -1;
        }

        if (!enabled || mc.currentScreen != null || p.isSpectator()) return;

        boolean grounded = p.isOnGround() || p.isTouchingWater() || p.isInLava()
                || p.hasVehicle() || p.getAbilities().flying || p.isClimbing();
        if (grounded) { endFall(); return; }

        if (!airborne) startFall();

        boolean gliding = p.isGliding();
        if (gliding && !ALLOW_ELYTRA) return;

        double fall = p.fallDistance;

        // fallDistance resets to 0 after an attack -> new fall
        if (attackedThisFall && fall < 0.5) startFall();
        if (attackedThisFall) { decayAim(); return; }

        boolean diving = p.getVelocity().y < (gliding ? -0.5 : -0.15);
        if (!diving || fall < minFall - 1.0) { decayAim(); return; }

        target = pickTarget(mc, p, target);
        if (target == null) { swapWait = attackWait = -1; decayAim(); return; }

        if (++offsetAge > 20 + rng.nextInt(25)) rollOffset();
        updateWander();
        Vec3d aim = aimPoint(target);
        double reach = reachDist(p, target);
        float err = coneAngle(p, aim);

        // ---- 1) pick and swap to the best mace ----
        int cur = p.getInventory().getSelectedSlot();
        int best = bestMaceSlot(p, target, Math.max(fall, 3.0));
        if (best == -1) return;

        boolean needSwap = cur != best;
        if (needSwap && p.getInventory().getStack(cur).isOf(Items.MACE)) {
            double sc = score(p.getInventory().getStack(cur), Math.max(fall, 3.0), target);
            double sb = score(p.getInventory().getStack(best), Math.max(fall, 3.0), target);
            needSwap = sb > sc * 1.08;   // only swap if it is clearly better
        }
        if (needSwap && reach < SEARCH_RANGE) {
            if (swapWait < 0) swapWait = gaussTicks(3.5, 1.2, 1, 8);
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

        // ---- 2) aiming ----
        if (AIM_ASSIST) aimStep(mc, p, aim); else decayAim();

        // ---- 3) attack conditions ----
        boolean holdingMace = p.getInventory().getStack(p.getInventory().getSelectedSlot()).isOf(Items.MACE);
        boolean onTarget = AIM_ASSIST ? err <= ATTACK_CONE : mc.targetedEntity == target;

        boolean ready = holdingMace
                && fall >= minFall
                && reach <= reachLimit
                && onTarget
                && p.getAttackCooldownProgress(0.5f) >= cooldownNeed
                && tickCount - lastSwapTick >= swapExtra;

        if (!ready) { attackWait = -1; return; }

        if (attackWait < 0) { attackWait = gaussTicks(3.8, 1.4, 2, 9) + hesitate; hesitate = 0; }
        if (attackWait > 0) { attackWait--; return; }

        mc.interactionManager.attackEntity(p, target);
        p.swingHand(Hand.MAIN_HAND);
        attackedThisFall = true;
        attackWait = -1;
        if (SWAP_BACK && prevSlot != -1) returnTimer = 4 + rng.nextInt(9);
    }

    // ================= fall management =================
    private void startFall() {
        airborne = true;
        attackedThisFall = false;
        minFall      = 2.6 + rng.nextDouble() * 1.4;    // 2.6 to 4.0 blocks
        reachLimit   = 2.6 + rng.nextDouble() * 0.3;
        cooldownNeed = 0.9f + rng.nextFloat() * 0.1f;
        aimSpeed     = 0.16f + rng.nextFloat() * 0.14f;
        hesitate     = rng.nextInt(100) < 6 ? 5 + rng.nextInt(8) : 0;
        swapWait = attackWait = -1;
        rollOffset();
    }

    private void endFall() {
        airborne = false;
        attackedThisFall = false;
        target = null;
        swapWait = attackWait = -1;
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
    private PlayerEntity pickTarget(MinecraftClient mc, ClientPlayerEntity p, PlayerEntity current) {
        if (current != null && current.isAlive() && p.canSee(current)
                && p.distanceTo(current) < SEARCH_RANGE + 2
                && coneAngle(p, current.getBoundingBox().getCenter()) < AIM_CONE * 1.3f) {
            return current; // stay loyal to the previous target
        }
        PlayerEntity best = null;
        float bestAng = AIM_CONE;
        for (PlayerEntity e : mc.world.getPlayers()) {
            if (e == p || !e.isAlive() || e.isSpectator()) continue;
            if (p.distanceTo(e) > SEARCH_RANGE || !p.canSee(e)) continue;
            float a = coneAngle(p, e.getBoundingBox().getCenter());
            if (a < bestAng) { bestAng = a; best = e; }
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

    private double reachDist(ClientPlayerEntity p, PlayerEntity t) {
        Vec3d eye = p.getEyePos();
        Box b = t.getBoundingBox();
        Vec3d c = new Vec3d(
                MathHelper.clamp(eye.x, b.minX, b.maxX),
                MathHelper.clamp(eye.y, b.minY, b.maxY),
                MathHelper.clamp(eye.z, b.minZ, b.maxZ));
        return eye.distanceTo(c);
    }

    // ================= rotation =================
    private float yawTo(ClientPlayerEntity p, Vec3d pt) {
        Vec3d e = p.getEyePos();
        return (float) (MathHelper.atan2(pt.z - e.z, pt.x - e.x) * 180.0 / Math.PI) - 90f;
    }

    private float pitchTo(ClientPlayerEntity p, Vec3d pt) {
        Vec3d e = p.getEyePos();
        return (float) -(MathHelper.atan2(pt.y - e.y, Math.hypot(pt.x - e.x, pt.z - e.z)) * 180.0 / Math.PI);
    }

    private float coneAngle(ClientPlayerEntity p, Vec3d pt) {
        Vec3d look = p.getRotationVec(1.0f);
        Vec3d dir = pt.subtract(p.getEyePos()).normalize();
        return (float) Math.toDegrees(Math.acos(MathHelper.clamp(look.dotProduct(dir), -1.0, 1.0)));
    }

    private void aimStep(MinecraftClient mc, ClientPlayerEntity p, Vec3d pt) {
        float dYaw = MathHelper.wrapDegrees(yawTo(p, pt) - p.getYaw());
        float dPitch = pitchTo(p, pt) - p.getPitch();

        // close enough: stop moving, like a real mouse
        if (Math.abs(dYaw) < 0.4f && Math.abs(dPitch) < 0.4f) { decayAim(); return; }

        float wantYaw = dYaw * aimSpeed;
        float wantPitch = dPitch * aimSpeed * 0.75f;      // pitch is slower than yaw

        // inertia + noise => soft start, acceleration, slight overshoot
        yawVel   = yawVel   * 0.55f + wantYaw   * 0.45f + (float) rng.nextGaussian() * 0.12f;
        pitchVel = pitchVel * 0.55f + wantPitch * 0.45f + (float) rng.nextGaussian() * 0.09f;
        yawVel   = MathHelper.clamp(yawVel,   -12f, 12f);
        pitchVel = MathHelper.clamp(pitchVel, -9f,  9f);

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
