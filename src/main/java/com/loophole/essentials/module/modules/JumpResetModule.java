package com.loophole.essentials.module.modules;

import com.loophole.essentials.module.LoopholeListenerModule;
import com.loophole.essentials.module.settings.RangeDoubleSetting;
import io.github.itzispyder.clickcrystals.events.EventHandler;
import io.github.itzispyder.clickcrystals.events.events.client.EntityDamageEvent;
import io.github.itzispyder.clickcrystals.events.events.networking.GameLeaveEvent;
import io.github.itzispyder.clickcrystals.util.minecraft.EntityUtils;
import io.github.itzispyder.clickcrystals.util.minecraft.InteractionUtils;
import io.github.itzispyder.clickcrystals.util.minecraft.PlayerUtils;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

public class JumpResetModule extends LoopholeListenerModule {

    private static final long HIT_STREAK_RESET_MS = 2500L;

    public final RangeDoubleSetting playerDistanceWindow = getGeneralSection().add(createRangeDoubleSetting()
            .name("player-distance-window")
            .description("Shared minimum and maximum player distance required for Jump Reset to react on first, second, and third-plus hits.")
            .def(2.0, 5.0)
            .min(0.0)
            .max(10.0)
            .decimalPlaces(2)
            .build()
    );

    public final io.github.itzispyder.clickcrystals.modules.ModuleSetting<Integer> firstHitChance = getGeneralSection().add(createIntSetting()
            .name("first-hit-chance")
            .description("Percent chance to queue a jump reset on the first qualifying hit in a streak.")
            .min(0)
            .max(100)
            .def(50)
            .build()
    );

    public final io.github.itzispyder.clickcrystals.modules.ModuleSetting<Integer> secondHitChance = getGeneralSection().add(createIntSetting()
            .name("second-hit-chance")
            .description("Percent chance to queue a jump reset on the second qualifying hit in a streak.")
            .min(0)
            .max(100)
            .def(75)
            .build()
    );

    public final io.github.itzispyder.clickcrystals.modules.ModuleSetting<Integer> thirdHitPlusChance = getGeneralSection().add(createIntSetting()
            .name("third-hit-plus-chance")
            .description("Percent chance to queue a jump reset on the third qualifying hit or later in a streak.")
            .min(0)
            .max(100)
            .def(100)
            .build()
    );

    private long lastPlayerHitAt = 0L;
    private int consecutiveHitCount = 0;
    private int lastDamagerId = Integer.MIN_VALUE;

    public JumpResetModule() {
        super("jump-reset", "Queues a normal jump key press after qualifying player hits using separate first, second, and third-plus chances plus one shared distance window.");
    }

    @Override
    protected void onDisable() {
        super.onDisable();
        resetRuntimeState();
    }

    @EventHandler
    private void onGameLeave(GameLeaveEvent e) {
        resetRuntimeState();
    }

    @EventHandler
    private void onEntityDamage(EntityDamageEvent e) {
        if (!isEligibleSelfDamageEvent(e)) {
            return;
        }

        Player attacker = getAttackingPlayer(e.getSource());
        if (attacker == null) {
            return;
        }

        registerHit(attacker);
        if (!shouldAttemptJumpReset(attacker)) {
            return;
        }
        if (!passesChanceRoll(getChanceForCurrentHit())) {
            return;
        }

        queueJumpClick();
    }

    private boolean isEligibleSelfDamageEvent(EntityDamageEvent e) {
        return isEnabled()
                && e != null
                && e.isSelf()
                && canOperate();
    }

    private boolean canOperate() {
        return PlayerUtils.valid()
                && mc.player != null
                && mc.level != null
                && mc.options != null
                && mc.screen == null;
    }

    private Player getAttackingPlayer(DamageSource source) {
        if (source == null) {
            return null;
        }

        Entity attacker = source.getEntity();
        if (attacker instanceof Player player && isValidOpponent(player)) {
            return player;
        }
        return null;
    }

    private boolean isValidOpponent(Player player) {
        return player != null
                && player != mc.player
                && player.isAlive()
                && !player.isSpectator()
                && !EntityUtils.shouldCancelCcsAttack(player);
    }

    private void registerHit(Player attacker) {
        long now = System.currentTimeMillis();
        if (now - lastPlayerHitAt > HIT_STREAK_RESET_MS || lastDamagerId != attacker.getId()) {
            consecutiveHitCount = 1;
        }
        else {
            consecutiveHitCount++;
        }

        lastPlayerHitAt = now;
        lastDamagerId = attacker.getId();
    }

    private boolean shouldAttemptJumpReset(Player attacker) {
        return !mc.player.isOnFire()
                && mc.player.onGround()
                && isWithinDistanceWindow(mc.player.distanceTo(attacker));
    }

    private boolean isWithinDistanceWindow(double distance) {
        return distance >= playerDistanceWindow.getLower()
                && distance <= playerDistanceWindow.getUpper();
    }

    private int getChanceForCurrentHit() {
        if (consecutiveHitCount <= 1) {
            return firstHitChance.getVal();
        }
        if (consecutiveHitCount == 2) {
            return secondHitChance.getVal();
        }
        return thirdHitPlusChance.getVal();
    }

    private boolean passesChanceRoll(int chance) {
        if (chance <= 0) {
            return false;
        }
        if (chance >= 100) {
            return true;
        }
        return Math.random() * 100.0 < chance;
    }

    private void queueJumpClick() {
        InteractionUtils.inputJump();
    }

    private void resetRuntimeState() {
        lastPlayerHitAt = 0L;
        consecutiveHitCount = 0;
        lastDamagerId = Integer.MIN_VALUE;
    }
}
