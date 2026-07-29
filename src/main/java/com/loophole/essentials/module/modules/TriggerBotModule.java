package com.loophole.essentials.module.modules;

import com.loophole.essentials.mixin.AccessorKeyMapping;
import com.loophole.essentials.module.LoopholeListenerModule;
import com.loophole.essentials.module.PersistentSettingProvider;
import com.loophole.essentials.module.settings.RangeDoubleSetting;
import io.github.itzispyder.clickcrystals.events.EventHandler;
import io.github.itzispyder.clickcrystals.events.events.client.EntityDamageEvent;
import io.github.itzispyder.clickcrystals.events.events.networking.GameLeaveEvent;
import io.github.itzispyder.clickcrystals.events.events.world.ClientTickStartEvent;
import io.github.itzispyder.clickcrystals.events.events.world.RenderWorldEvent;
import io.github.itzispyder.clickcrystals.events.listeners.UserInputListener;
import io.github.itzispyder.clickcrystals.gui.screens.ModuleEditScreen;
import io.github.itzispyder.clickcrystals.modules.Module;
import io.github.itzispyder.clickcrystals.modules.ModuleSetting;
import io.github.itzispyder.clickcrystals.modules.settings.SettingSection;
import io.github.itzispyder.clickcrystals.util.minecraft.EntityUtils;
import io.github.itzispyder.clickcrystals.util.minecraft.PlayerUtils;
import net.minecraft.client.KeyMapping;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class TriggerBotModule extends LoopholeListenerModule implements PersistentSettingProvider {

    private static final long MIN_CLICK_GAP_MS = 25L;
    private static final long SINGLE_FIRE_FAILSAFE_MS = 400L;
    private static final long KNOCKBACK_MISS_WINDOW_MS = 175L;
    private static final double FALLING_VELOCITY_THRESHOLD = -0.03;
    private static final double ASCENDING_VELOCITY_THRESHOLD = 0.03;
    private static final double COBWEB_PLAYER_TRACK_RANGE = 4.75;
    private static final double COBWEB_TRACK_EPSILON_SQ = 1.0E-4;

    private final SettingSection scGeneral = getGeneralSection();
    private final SettingSection scUppercut = createSettingSection("Uppercut");
    private final SettingSection scSprintReset = createSettingSection("Sprint Reset");
    private final SettingSection scAttackDelay = createSettingSection("Attack Delay");
    private final SettingSection scHumanization = createSettingSection("Humanization");

    public final ModuleSetting<String> allowedHeldItems = scGeneral.add(createStringSetting()
            .name("allowed-held-items")
            .description("Comma-separated main-hand item-name fragments that Trigger-Bot accepts, for example #sword,#_axe,mace.")
            .def("#sword,#_axe,mace")
            .build()
    );

    public final RangeDoubleSetting attackProgress = scGeneral.add(createRangeDoubleSetting()
            .name("attack-progress")
            .description("Randomized attack-strength threshold that must be met before Trigger-Bot queues a real hit.")
            .def(0.900, 0.900)
            .min(0.000)
            .max(1.000)
            .decimalPlaces(3)
            .build()
    );

    public final ModuleSetting<Boolean> requireSprinting = scGeneral.add(createBoolSetting()
            .name("require-sprinting")
            .description("Require sprinting for grounded and uppercut attacks. Critical falling attacks always ignore this gate, and water/lava grounded hits do too.")
            .def(true)
            .build()
    );

    public final ModuleSetting<Boolean> uppercutEnabled = scUppercut.add(createBoolSetting()
            .name("uppercut-enabled")
            .description("Allow Trigger-Bot to attack while airborne and not yet falling.")
            .def(true)
            .build()
    );

    public final ModuleSetting<Boolean> requireAscending = scUppercut.add(createBoolSetting()
            .name("require-ascending")
            .description("Require upward vertical motion for uppercut attacks instead of allowing any non-falling airborne state.")
            .def(true)
            .build()
    );

    public final RangeDoubleSetting uppercutDistanceWindow = scUppercut.add(createRangeDoubleSetting()
            .name("uppercut-distance-window")
            .description("Minimum and maximum player distance allowed for uppercut attacks.")
            .def(1.500, 3.000)
            .min(0.000)
            .max(6.000)
            .decimalPlaces(2)
            .build()
    );

    public final ModuleSetting<SprintResetMode> sprintResetMode = scSprintReset.add(createEnumSetting(SprintResetMode.class)
            .name("sprint-reset-mode")
            .description("Choose whether Trigger-Bot performs a W-Tap or S-Tap after qualifying grounded hits.")
            .def(SprintResetMode.Off)
            .build()
    );

    public final ModuleSetting<Boolean> opponentAngleCheck = scSprintReset.add(createBoolSetting()
            .name("opponent-angle-check")
            .description("Only allow W-Tap or S-Tap activation while the opponent is facing you within the configured angle threshold.")
            .def(false)
            .build()
    );

    public final ModuleSetting<Double> opponentAngleThreshold = scSprintReset.add(createDoubleSetting()
            .name("opponent-angle-threshold")
            .description("Maximum allowed opponent facing angle in degrees for W-Tap or S-Tap activation. Default 90 means <= 90 passes.")
            .def(90.0)
            .min(0.0)
            .max(180.0)
            .decimalPlaces(1)
            .build()
    );

    public final RangeDoubleSetting tapDuration = scSprintReset.add(createRangeDoubleSetting()
            .name("tap-duration")
            .description("Randomized tap duration in milliseconds used after qualifying grounded hits or optional humanized misses.")
            .def(40.0, 55.0)
            .min(0.0)
            .max(250.0)
            .decimalPlaces(0)
            .build()
    );

    public final RangeDoubleSetting tapDistanceWindow = scSprintReset.add(createRangeDoubleSetting()
            .name("tap-distance-window")
            .description("Minimum and maximum target distance required to start or keep a sprint reset tap active.")
            .def(1.000, 6.000)
            .min(0.000)
            .max(6.000)
            .decimalPlaces(2)
            .build()
    );

    public final ModuleSetting<Boolean> waitForShieldBreakFollowUpClick = scSprintReset.add(createBoolSetting()
            .name("wait-for-shield-break-follow-up-click")
            .description("When Shield-Breaker takes over a grounded Trigger-Bot hit, wait for Shield-Breaker's shield-stun follow-up click before starting the configured W-Tap or S-Tap.")
            .def(true)
            .build()
    );

    public final ModuleSetting<Boolean> attackDelayEnabled = scAttackDelay.add(createBoolSetting()
            .name("attack-delay-enabled")
            .description("Add a randomized delay before real successful attacks. Disable this to bypass the delay system entirely.")
            .def(false)
            .build()
    );

    public final RangeDoubleSetting attackDelay = scAttackDelay.add(createRangeDoubleSetting()
            .name("attack-delay")
            .description("Randomized delay in milliseconds applied only to real successful attacks.")
            .def(5.0, 15.0)
            .min(0.0)
            .max(75.0)
            .decimalPlaces(0)
            .build()
    );

    public final ModuleSetting<Boolean> humanizationEnabled = scHumanization.add(createBoolSetting()
            .name("humanization-enabled")
            .description("Enable optional intentional miss actions that keep validating their own trigger rules before clicking.")
            .def(false)
            .build()
    );

    public final ModuleSetting<Boolean> knockbackMisses = scHumanization.add(createBoolSetting()
            .name("knockback-misses")
            .description("Optionally queue one intentional miss after a qualifying player hit while the attacker stays in FOV during the knockback window.")
            .def(true)
            .build()
    );

    public final ModuleSetting<Integer> knockbackMissChance = scHumanization.add(createIntSetting()
            .name("knockback-miss-chance")
            .description("Percent chance for each qualifying knockback miss opportunity.")
            .def(50)
            .min(0)
            .max(100)
            .build()
    );

    public final RangeDoubleSetting knockbackMissDistanceWindow = scHumanization.add(createRangeDoubleSetting()
            .name("knockback-miss-distance-window")
            .description("Minimum and maximum player distance allowed for knockback misses.")
            .def(0.000, 6.000)
            .min(0.000)
            .max(6.000)
            .decimalPlaces(2)
            .build()
    );

    public final ModuleSetting<Boolean> knockbackMissReduceBackToBackChance = scHumanization.add(createBoolSetting()
            .name("knockback-miss-reduce-back-to-back-chance")
            .description("Halve the knockback miss chance for each consecutive player hit you take without Trigger-Bot queuing a real hit back.")
            .def(false)
            .build()
    );

    public final ModuleSetting<Double> knockbackMissFov = scHumanization.add(createDoubleSetting()
            .name("knockback-miss-fov")
            .description("Maximum angular FOV in degrees required for knockback misses.")
            .def(90.0)
            .min(1.0)
            .max(180.0)
            .decimalPlaces(1)
            .build()
    );

    public final ModuleSetting<Boolean> outOfRangeMisses = scHumanization.add(createBoolSetting()
            .name("out-of-range-misses")
            .description("Optionally miss-click when the crosshair is on a player just outside attack range.")
            .def(true)
            .build()
    );

    public final ModuleSetting<Integer> outOfRangeMissChance = scHumanization.add(createIntSetting()
            .name("out-of-range-miss-chance")
            .description("Percent chance applied each time a tracked player enters the configured out-of-range distance window.")
            .def(50)
            .min(0)
            .max(100)
            .build()
    );

    public final RangeDoubleSetting outOfRangeWindow = scHumanization.add(createRangeDoubleSetting()
            .name("out-of-range-distance-window")
            .description("Minimum and maximum player distance allowed for out-of-range misses.")
            .def(3.500, 4.000)
            .min(3.000)
            .max(6.000)
            .decimalPlaces(2)
            .build()
    );

    public final ModuleSetting<Boolean> outOfRangeMissTap = scHumanization.add(createBoolSetting()
            .name("out-of-range-miss-tap")
            .description("Allow Trigger-Bot to start the configured sprint reset tap after a qualifying out-of-range miss.")
            .def(false)
            .build()
    );

    public final ModuleSetting<Boolean> cobwebMisses = scHumanization.add(createBoolSetting()
            .name("cobweb-misses")
            .description("Optionally miss-click when a cobweb is under the crosshair and a valid player is directly behind it.")
            .def(true)
            .build()
    );

    public final ModuleSetting<Integer> cobwebMissChance = scHumanization.add(createIntSetting()
            .name("cobweb-miss-chance")
            .description("Percent chance for each qualifying cobweb miss opportunity.")
            .def(50)
            .min(0)
            .max(100)
            .build()
    );

    public final ModuleSetting<Boolean> cobwebMissTap = scHumanization.add(createBoolSetting()
            .name("cobweb-miss-tap")
            .description("Allow Trigger-Bot to start the configured sprint reset tap after a qualifying cobweb miss.")
            .def(false)
            .build()
    );

    private PendingAction pendingAction = null;
    private int pendingActionToken = 0;
    private TapSession activeTap = null;
    private QueuedKnockbackMiss queuedKnockbackMiss = null;
    private OutOfRangeWindowEntry outOfRangeWindowEntry = null;
    private boolean singleFireLocked = false;
    private long singleFireLockStartedAtMs = 0L;
    private long lastQueuedClickAtMs = 0L;
    private int consecutiveIncomingHitsWithoutRetaliation = 0;
    private boolean pendingChildSettingsSync = false;
    private boolean pendingSettingsScreenRefresh = false;

    public TriggerBotModule() {
        super("trigger-bot", "Frame-validated trigger bot that only attacks when a real player hit is still about to connect, prioritizing no air swings over raw CPS.");
        configureChildSettings();
        syncVisibleSettings();
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
        if (!shouldQueueKnockbackMiss(e)) {
            return;
        }

        Player attacker = getAttackingPlayer(e.getSource());
        if (attacker == null) {
            return;
        }
        if (!passesChanceRoll(getEffectiveKnockbackMissChance())) {
            consecutiveIncomingHitsWithoutRetaliation++;
            return;
        }

        consecutiveIncomingHitsWithoutRetaliation++;
        queuedKnockbackMiss = new QueuedKnockbackMiss(attacker.getUUID(), System.currentTimeMillis() + KNOCKBACK_MISS_WINDOW_MS);
    }

    @EventHandler
    private void onTickStart(ClientTickStartEvent e) {
        long nowMs = System.currentTimeMillis();
        if (!canOperate()) {
            resetRuntimeState();
            return;
        }

        updateRuntimeState(nowMs);
        tryCreateCandidate(nowMs);
    }

    @EventHandler
    private void onRenderWorld(RenderWorldEvent e) {
        long nowMs = System.currentTimeMillis();
        if (!canOperate()) {
            resetRuntimeState();
            return;
        }

        updateRuntimeState(nowMs);
    }

    private void updateRuntimeState(long nowMs) {
        updateSingleFireLock(nowMs);
        updateTapState(nowMs);
        expireQueuedKnockbackMiss(nowMs);
        updateOutOfRangeWindowEntry();

        if (pendingAction != null) {
            processPendingAction(nowMs);
        }
    }

    private void tryCreateCandidate(long nowMs) {
        if (pendingAction != null || !passesMinimumClickGap(nowMs)) {
            return;
        }

        PendingAction next = createRealAttackCandidate(nowMs);
        if (next == null) {
            next = createHumanizationCandidate(nowMs);
        }
        if (next == null) {
            return;
        }

        pendingAction = next;
        if (next.kind() == PendingActionKind.REAL_ATTACK && next.readyAtMs() <= nowMs) {
            finalizePendingAction(next.token());
            return;
        }
        processPendingAction(nowMs);
    }

    private PendingAction createRealAttackCandidate(long nowMs) {
        if (singleFireLocked || !passesBaseCombatState() || !isHoldingAllowedItem()) {
            return null;
        }

        AttackContext context = findCurrentAttackContext();
        if (context == null) {
            return null;
        }

        float selectedAttackProgress = pickAttackProgressThreshold();
        if (mc.player.getAttackStrengthScale(1.0F) < selectedAttackProgress) {
            return null;
        }

        long readyAtMs = nowMs;
        if (attackDelayEnabled.getVal()) {
            readyAtMs += getAttackDelayMs();
        }

        return PendingAction.real(
                ++pendingActionToken,
                context.target().getUUID(),
                context.branch(),
                selectedAttackProgress,
                context.distance(),
                readyAtMs
        );
    }

    private PendingAction createHumanizationCandidate(long nowMs) {
        if (!humanizationEnabled.getVal() || !passesBaseCombatState() || !isHoldingAllowedItem()) {
            return null;
        }

        PendingAction knockback = createKnockbackMissCandidate(nowMs);
        if (knockback != null) {
            return knockback;
        }

        PendingAction outOfRange = createOutOfRangeMissCandidate(nowMs);
        if (outOfRange != null) {
            return outOfRange;
        }

        return createCobwebMissCandidate(nowMs);
    }

    private PendingAction createKnockbackMissCandidate(long nowMs) {
        if (!knockbackMisses.getVal() || queuedKnockbackMiss == null) {
            return null;
        }

        Player attacker = findPlayerByUuid(queuedKnockbackMiss.targetId());
        if (!isKnockbackMissTargetEligible(attacker)) {
            queuedKnockbackMiss = null;
            return null;
        }

        float selectedAttackProgress = pickAttackProgressThreshold();
        queuedKnockbackMiss = null;
        return PendingAction.miss(
                ++pendingActionToken,
                HumanizationMissType.KNOCKBACK,
                attacker.getUUID(),
                selectedAttackProgress,
                nowMs,
                false,
                TapValidationMode.ENTITY_CROSSHAIR
        );
    }

    private PendingAction createOutOfRangeMissCandidate(long nowMs) {
        if (!outOfRangeMisses.getVal()) {
            return null;
        }

        Player target = getOutOfRangeTrackedPlayer();
        if (!isOutOfRangeMissTargetEligible(target)) {
            return null;
        }
        if (outOfRangeWindowEntry == null
                || !target.getUUID().equals(outOfRangeWindowEntry.targetId())
                || !outOfRangeWindowEntry.shouldClick()
                || outOfRangeWindowEntry.consumed()) {
            return null;
        }

        float selectedAttackProgress = pickAttackProgressThreshold();
        outOfRangeWindowEntry = outOfRangeWindowEntry.consume();
        return PendingAction.miss(
                ++pendingActionToken,
                HumanizationMissType.OUT_OF_RANGE,
                target.getUUID(),
                selectedAttackProgress,
                nowMs,
                outOfRangeMissTap.getVal(),
                TapValidationMode.OUT_OF_RANGE_PLAYER_TRACK
        );
    }

    private PendingAction createCobwebMissCandidate(long nowMs) {
        if (!cobwebMisses.getVal()) {
            return null;
        }

        CobwebTrackedPlayer tracked = getCobwebTrackedPlayer();
        if (tracked == null || !isCobwebTargetWithinAttackRange(tracked) || isCurrentValidAttackTargetFor(tracked.target())) {
            return null;
        }
        if (!passesChanceRoll(cobwebMissChance.getVal())) {
            return null;
        }

        float selectedAttackProgress = pickAttackProgressThreshold();
        return PendingAction.miss(
                ++pendingActionToken,
                HumanizationMissType.COBWEB,
                tracked.target().getUUID(),
                selectedAttackProgress,
                nowMs,
                cobwebMissTap.getVal(),
                TapValidationMode.COBWEB_PLAYER_TRACK
        );
    }

    private void processPendingAction(long nowMs) {
        if (pendingAction == null) {
            return;
        }
        if (pendingAction.executionQueued()) {
            return;
        }
        if (!isPendingActionStillValid(pendingAction)) {
            clearPendingAction();
            return;
        }
        if (!isPendingActionReady(pendingAction, nowMs)) {
            return;
        }

        pendingAction.setExecutionQueued(true);
        int token = pendingAction.token();
        mc.execute(() -> finalizePendingAction(token));
    }

    private void finalizePendingAction(int token) {
        if (pendingAction == null || pendingAction.token() != token) {
            return;
        }
        if (!isPendingActionStillValid(pendingAction)) {
            clearPendingAction();
            return;
        }

        PendingAction action = pendingAction;
        long nowMs = System.currentTimeMillis();
        PendingTap groundedTap = action.kind() == PendingActionKind.REAL_ATTACK && action.branch() == AttackBranch.GROUND
                ? createPendingTap(action.targetId(), TapValidationMode.ENTITY_CROSSHAIR)
                : null;

        if (action.kind() == PendingActionKind.REAL_ATTACK) {
            Player target = findPlayerByUuid(action.targetId());
            boolean waitingForShieldBreakFollowUp = groundedTap != null && waitForShieldBreakFollowUpClick.getVal();
            if (!tryQueueShieldBreakAttack(target, waitingForShieldBreakFollowUp ? groundedTap : null)) {
                queueAttackClick();
                startDeferredTap(groundedTap);
            }
            else if (!waitingForShieldBreakFollowUp) {
                startDeferredTap(groundedTap);
            }
            lastQueuedClickAtMs = nowMs;
            singleFireLocked = true;
            singleFireLockStartedAtMs = nowMs;
            consecutiveIncomingHitsWithoutRetaliation = 0;
        }
        else {
            queueAttackClick();
            lastQueuedClickAtMs = nowMs;
            if (action.startTapAfterClick()) {
                tryStartTap(action.targetId(), action.tapValidationMode());
            }
        }

        clearPendingAction();
    }

    private boolean isPendingActionStillValid(PendingAction action) {
        if (action == null || !canOperate() || !passesBaseCombatState() || !isHoldingAllowedItem()) {
            return false;
        }

        Player target = findPlayerByUuid(action.targetId());
        if (!isValidOpponent(target)) {
            return false;
        }

        return switch (action.kind()) {
            case REAL_ATTACK -> isRealAttackStillValid(action, target);
            case HUMANIZATION_MISS -> isHumanizationMissStillValid(action, target);
        };
    }

    private boolean isRealAttackStillValid(PendingAction action, Player target) {
        if (!isDirectCrosshairTarget(target)) {
            return false;
        }

        AttackContext context = findCurrentAttackContext();
        if (context == null || context.target() != target) {
            return false;
        }
        if (context.branch() != action.branch()) {
            return false;
        }

        return mc.player.getAttackStrengthScale(1.0F) >= action.selectedAttackProgress();
    }

    private boolean isHumanizationMissStillValid(PendingAction action, Player target) {
        return switch (action.missType()) {
            case KNOCKBACK -> isKnockbackMissTargetEligible(target);
            case OUT_OF_RANGE -> {
                Player trackedTarget = getOutOfRangeTrackedPlayer();
                yield trackedTarget != null
                        && trackedTarget.getUUID().equals(action.targetId())
                        && isOutOfRangeMissTargetEligible(trackedTarget);
            }
            case COBWEB -> {
                if (isCurrentValidAttackTargetFor(target)) {
                    yield false;
                }
                CobwebTrackedPlayer tracked = getCobwebTrackedPlayer();
                yield tracked != null
                        && tracked.target().getUUID().equals(action.targetId())
                        && isCobwebTargetWithinAttackRange(tracked);
            }
        };
    }

    private boolean shouldQueueKnockbackMiss(EntityDamageEvent e) {
        if (!isEnabled()
                || !humanizationEnabled.getVal()
                || !knockbackMisses.getVal()
                || e == null
                || !e.isSelf()
                || !canOperate()
                || !passesBaseCombatState()
                || !isHoldingAllowedItem()) {
            return false;
        }

        Player attacker = getAttackingPlayer(e.getSource());
        return attacker != null
                && isKnockbackMissTargetEligible(attacker)
                && !isCurrentValidAttackTargetFor(attacker);
    }

    private Player getAttackingPlayer(DamageSource source) {
        if (source == null) {
            return null;
        }

        Entity attacker = source.getEntity();
        return attacker instanceof Player player && isValidOpponent(player) ? player : null;
    }

    private boolean passesBaseCombatState() {
        return canOperate()
                && !mc.player.isBlocking()
                && !mc.player.isUsingItem();
    }

    private boolean canOperate() {
        return isEnabled()
                && PlayerUtils.valid()
                && mc.player != null
                && mc.level != null
                && mc.options != null
                && mc.screen == null;
    }

    private Player getCrosshairPlayerTarget() {
        if (!(mc.hitResult instanceof EntityHitResult hit) || mc.hitResult.getType() != HitResult.Type.ENTITY) {
            return null;
        }
        return hit.getEntity() instanceof Player player ? player : null;
    }

    private boolean isDirectCrosshairTarget(Player target) {
        return target != null && getCrosshairPlayerTarget() == target;
    }

    private AttackContext findCurrentAttackContext() {
        if (!passesBaseCombatState() || !isHoldingAllowedItem()) {
            return null;
        }

        Player target = getCrosshairPlayerTarget();
        if (!isValidOpponent(target)) {
            return null;
        }

        double distance = mc.player.distanceTo(target);
        AttackBranch branch = resolveAttackBranch(distance);
        return branch == AttackBranch.NONE ? null : new AttackContext(target, distance, branch);
    }

    private Player getOutOfRangeTrackedPlayer() {
        if (mc.player == null || mc.level == null) {
            return null;
        }

        Vec3 eye = mc.player.getEyePosition(1.0F);
        Vec3 look = mc.player.getViewVector(1.0F).normalize();
        if (look.lengthSqr() <= 1.0E-6) {
            return null;
        }

        double maxDistance = Math.max(outOfRangeWindow.getUpper(), getAttackRange());
        Vec3 rayEnd = eye.add(look.scale(maxDistance));
        Player bestTarget = null;
        double bestDistanceSq = Double.MAX_VALUE;

        for (Player player : mc.level.players()) {
            if (!isValidOpponent(player)) {
                continue;
            }

            AABB box = player.getBoundingBox().inflate(0.10);
            Vec3 clipped = box.clip(eye, rayEnd).orElse(null);
            if (clipped == null) {
                continue;
            }

            double distance = mc.player.distanceTo(player);
            if (distance <= getAttackRange()
                    || distance < outOfRangeWindow.getLower()
                    || distance > outOfRangeWindow.getUpper()) {
                continue;
            }

            double clippedDistanceSq = eye.distanceToSqr(clipped);
            if (clippedDistanceSq >= bestDistanceSq) {
                continue;
            }

            bestTarget = player;
            bestDistanceSq = clippedDistanceSq;
        }

        return bestTarget;
    }

    private void updateOutOfRangeWindowEntry() {
        if (!humanizationEnabled.getVal()
                || !outOfRangeMisses.getVal()
                || !passesBaseCombatState()
                || !isHoldingAllowedItem()) {
            outOfRangeWindowEntry = null;
            return;
        }

        Player target = getOutOfRangeTrackedPlayer();
        if (!isOutOfRangeMissTargetEligible(target)) {
            outOfRangeWindowEntry = null;
            return;
        }

        UUID targetId = target.getUUID();
        if (outOfRangeWindowEntry == null || !targetId.equals(outOfRangeWindowEntry.targetId())) {
            outOfRangeWindowEntry = new OutOfRangeWindowEntry(targetId, passesChanceRoll(outOfRangeMissChance.getVal()), false);
        }
    }

    private boolean isOutOfRangeMissTargetEligible(Player target) {
        if (!isValidOpponent(target)
                || isCurrentValidAttackTargetFor(target)
                || !passesOutOfRangeGroundRequirement()
                || !passesOutOfRangeSprintRequirement()) {
            return false;
        }

        double distance = mc.player.distanceTo(target);
        return distance > getAttackRange()
                && distance >= outOfRangeWindow.getLower()
                && distance <= outOfRangeWindow.getUpper();
    }

    private boolean passesOutOfRangeGroundRequirement() {
        return mc.player != null && mc.player.onGround();
    }

    private boolean passesOutOfRangeSprintRequirement() {
        return !requireSprinting.getVal() || passesGroundSprintingRequirement();
    }

    private boolean isCurrentValidAttackTargetFor(Player target) {
        if (!passesBaseCombatState() || !isHoldingAllowedItem() || singleFireLocked || !isValidOpponent(target)) {
            return false;
        }

        AttackContext context = findCurrentAttackContext();
        if (context == null || context.target() != target) {
            return false;
        }

        return mc.player.getAttackStrengthScale(1.0F) >= attackProgress.getLower();
    }

    private boolean isHoldingAllowedItem() {
        if (mc.player == null) {
            return false;
        }

        String descriptionId = mc.player.getMainHandItem().getItem().getDescriptionId().toLowerCase(Locale.ROOT);
        if (descriptionId.isBlank()) {
            return false;
        }

        for (String token : getAllowedHeldItemTokens()) {
            if (!token.isEmpty() && descriptionId.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private List<String> getAllowedHeldItemTokens() {
        String raw = allowedHeldItems.getVal();
        if (raw == null || raw.isBlank()) {
            return List.of();
        }

        List<String> tokens = new ArrayList<>();
        for (String token : raw.toLowerCase(Locale.ROOT).split(",")) {
            String normalized = token.trim();
            if (normalized.startsWith("#")) {
                normalized = normalized.substring(1);
            }
            if (!normalized.isEmpty()) {
                tokens.add(normalized);
            }
        }
        return tokens;
    }

    private AttackBranch resolveAttackBranch(double distance) {
        if (mc.player == null) {
            return AttackBranch.NONE;
        }

        if (isGroundedAttackState()) {
            if (!passesGroundSprintingRequirement()) {
                return AttackBranch.NONE;
            }
            return AttackBranch.GROUND;
        }

        if (isCriticalFallingState()) {
            return AttackBranch.CRITICAL;
        }

        if (!uppercutEnabled.getVal()) {
            return AttackBranch.NONE;
        }
        if (requireAscending.getVal() && mc.player.getDeltaMovement().y <= ASCENDING_VELOCITY_THRESHOLD) {
            return AttackBranch.NONE;
        }
        if (distance < uppercutDistanceWindow.getLower() || distance > uppercutDistanceWindow.getUpper()) {
            return AttackBranch.NONE;
        }
        if (requireSprinting.getVal() && !mc.player.isSprinting()) {
            return AttackBranch.NONE;
        }
        return AttackBranch.UPPERCUT;
    }

    private boolean isGroundedAttackState() {
        return mc.player.onGround()
                || mc.player.isInWater()
                || mc.player.isInLava()
                || isInCobweb();
    }

    private boolean passesGroundSprintingRequirement() {
        if (!requireSprinting.getVal()) {
            return true;
        }
        if (mc.player.isInWater() || mc.player.isInLava()) {
            return true;
        }
        return mc.player.isSprinting();
    }

    private boolean isCriticalFallingState() {
        return !isGroundedAttackState()
                && !mc.player.onGround()
                && mc.player.getDeltaMovement().y < FALLING_VELOCITY_THRESHOLD
                && mc.player.fallDistance > 0.0F;
    }

    private PendingTap createPendingTap(UUID targetId, TapValidationMode validationMode) {
        if (sprintResetMode.getVal() == SprintResetMode.Off || targetId == null || validationMode == null) {
            return null;
        }

        long durationMs = Math.max(0L, Math.round(tapDuration.getRandomizedValue()));
        if (durationMs <= 0L) {
            return null;
        }

        return new PendingTap(targetId, validationMode, durationMs);
    }

    public void startDeferredTap(PendingTap pendingTap) {
        if (pendingTap == null) {
            return;
        }

        tryStartTap(pendingTap.targetId(), pendingTap.validationMode(), pendingTap.durationMs());
    }

    private void tryStartTap(UUID targetId, TapValidationMode validationMode) {
        PendingTap pendingTap = createPendingTap(targetId, validationMode);
        if (pendingTap == null) {
            return;
        }

        tryStartTap(pendingTap.targetId(), pendingTap.validationMode(), pendingTap.durationMs());
    }

    private void tryStartTap(UUID targetId, TapValidationMode validationMode, long durationMs) {
        if (sprintResetMode.getVal() == SprintResetMode.Off
                || targetId == null
                || !canStartTapNow(targetId, validationMode)) {
            return;
        }

        if (durationMs <= 0L) {
            return;
        }

        activeTap = new TapSession(targetId, validationMode, System.currentTimeMillis() + durationMs);
        applyTapState(activeTap);
    }

    private boolean canStartTapNow(UUID targetId, TapValidationMode validationMode) {
        if (!canOperate() || mc.player == null || activeTap != null || sprintResetMode.getVal() == SprintResetMode.Off) {
            return false;
        }
        if (mc.options.keyJump.isDown() || !mc.player.onGround() || mc.player.isInWater() || mc.player.isInLava() || isInCobweb()) {
            return false;
        }

        Player target = findPlayerByUuid(targetId);
        if (!isValidOpponent(target)) {
            return false;
        }

        double distance = mc.player.distanceTo(target);
        if (distance < tapDistanceWindow.getLower() || distance > tapDistanceWindow.getUpper()) {
            return false;
        }
        if (!passesOpponentAngleCheck(target)) {
            return false;
        }

        return switch (validationMode) {
            case ENTITY_CROSSHAIR -> isDirectCrosshairTarget(target);
            case OUT_OF_RANGE_PLAYER_TRACK -> {
                Player trackedTarget = getOutOfRangeTrackedPlayer();
                yield trackedTarget != null && trackedTarget.getUUID().equals(targetId);
            }
            case COBWEB_PLAYER_TRACK -> {
                CobwebTrackedPlayer tracked = getCobwebTrackedPlayer();
                yield tracked != null
                        && tracked.target().getUUID().equals(targetId)
                        && isCobwebTargetWithinAttackRange(tracked);
            }
        };
    }

    private boolean passesOpponentAngleCheck(Player target) {
        return !opponentAngleCheck.getVal()
                || getOpponentFacingAngle(target) <= opponentAngleThreshold.getVal();
    }

    private double getOpponentFacingAngle(Player target) {
        if (target == null || mc.player == null) {
            return 0.0;
        }

        Vec3 targetLook = target.getViewVector(1.0F);
        Vec3 targetEye = target.getEyePosition();
        Vec3 observerEye = mc.player.getEyePosition();
        Vec3 horizontalLook = new Vec3(targetLook.x, 0.0, targetLook.z);
        Vec3 horizontalToObserver = new Vec3(
                observerEye.x - targetEye.x,
                0.0,
                observerEye.z - targetEye.z
        );
        if (horizontalLook.lengthSqr() <= 1.0E-6 || horizontalToObserver.lengthSqr() <= 1.0E-6) {
            return 0.0;
        }

        double dot = horizontalLook.normalize().dot(horizontalToObserver.normalize());
        return Math.toDegrees(Math.acos(Mth.clamp(dot, -1.0, 1.0)));
    }

    private void updateTapState(long nowMs) {
        if (activeTap == null) {
            return;
        }
        if (!shouldContinueTap(activeTap, nowMs)) {
            stopTap();
            return;
        }

        applyTapState(activeTap);
    }

    private boolean shouldContinueTap(TapSession tap, long nowMs) {
        if (tap == null || sprintResetMode.getVal() == SprintResetMode.Off || nowMs >= tap.endsAtMs()) {
            return false;
        }
        if (!canOperate() || mc.player == null || mc.options == null) {
            return false;
        }
        if (mc.options.keyJump.isDown() || !mc.player.onGround() || mc.player.isInWater() || mc.player.isInLava() || isInCobweb()) {
            return false;
        }

        return isValidOpponent(findPlayerByUuid(tap.targetId()));
    }

    private void applyTapState(TapSession tap) {
        if (tap == null || mc.options == null) {
            return;
        }

        switch (sprintResetMode.getVal()) {
            case WTap -> {
                mc.options.keyUp.setDown(false);
                mc.options.keyDown.setDown(isPhysicalMovementKeyPressed(mc.options.keyDown));
            }
            case STap -> {
                mc.options.keyUp.setDown(false);
                mc.options.keyDown.setDown(true);
            }
            case Off -> restoreMovementKeys();
        }
    }

    private void stopTap() {
        if (activeTap == null) {
            return;
        }

        activeTap = null;
        restoreMovementKeys();
    }

    private void restoreMovementKeys() {
        if (mc.options == null) {
            return;
        }

        mc.options.keyUp.setDown(isPhysicalMovementKeyPressed(mc.options.keyUp));
        mc.options.keyDown.setDown(isPhysicalMovementKeyPressed(mc.options.keyDown));
    }

    private boolean isPhysicalMovementKeyPressed(KeyMapping keyMapping) {
        if (keyMapping == null) {
            return false;
        }

        return UserInputListener.isKeyPressed(((AccessorKeyMapping) keyMapping).loopholeEssentials$getBoundKey().getValue());
    }

    private void updateSingleFireLock(long nowMs) {
        if (!singleFireLocked || mc.player == null) {
            return;
        }
        if (mc.player.getAttackStrengthScale(0.0F) <= 0.20F || nowMs - singleFireLockStartedAtMs >= SINGLE_FIRE_FAILSAFE_MS) {
            singleFireLocked = false;
        }
    }

    private boolean passesMinimumClickGap(long nowMs) {
        return nowMs - lastQueuedClickAtMs >= MIN_CLICK_GAP_MS;
    }

    private long getAttackDelayMs() {
        return Math.max(0L, Math.round(attackDelay.getRandomizedValue()));
    }

    private void queueAttackClick() {
        KeyMapping.click(((AccessorKeyMapping) mc.options.keyAttack).loopholeEssentials$getBoundKey());
    }

    private boolean tryQueueShieldBreakAttack(Player target, PendingTap deferredTap) {
        ShieldBreakerModule shieldBreaker = Module.get(ShieldBreakerModule.class);
        return shieldBreaker != null && shieldBreaker.tryStartFromTriggerBot(target, deferredTap);
    }

    private float pickAttackProgressThreshold() {
        return (float) attackProgress.getRandomizedValue();
    }

    private boolean isPendingActionReady(PendingAction action, long nowMs) {
        if (action == null || nowMs < action.readyAtMs()) {
            return false;
        }

        return switch (action.kind()) {
            case REAL_ATTACK -> true;
            case HUMANIZATION_MISS -> mc.player.getAttackStrengthScale(1.0F) >= action.selectedAttackProgress();
        };
    }

    private double getAttackRange() {
        return Math.max(0.0, mc.player.entityInteractionRange());
    }

    private double getAngleToPlayer(Player target) {
        if (target == null || mc.player == null) {
            return 180.0;
        }

        Vec3 look = mc.player.getViewVector(1.0F).normalize();
        Vec3 eye = mc.player.getEyePosition(1.0F);
        Vec3 toTarget = target.getEyePosition().subtract(eye);
        if (toTarget.lengthSqr() <= 1.0E-6 || look.lengthSqr() <= 1.0E-6) {
            return 180.0;
        }

        double dot = Mth.clamp(look.dot(toTarget.normalize()), -1.0, 1.0);
        return Math.toDegrees(Math.acos(dot));
    }

    private void expireQueuedKnockbackMiss(long nowMs) {
        if (queuedKnockbackMiss != null && nowMs > queuedKnockbackMiss.expiresAtMs()) {
            queuedKnockbackMiss = null;
        }
    }

    private double getEffectiveKnockbackMissChance() {
        double chance = knockbackMissChance.getVal();
        if (!knockbackMissReduceBackToBackChance.getVal() || consecutiveIncomingHitsWithoutRetaliation <= 0) {
            return chance;
        }

        return chance / Math.pow(2.0, consecutiveIncomingHitsWithoutRetaliation);
    }

    private boolean isValidOpponent(Player player) {
        return player != null
                && player != mc.player
                && player.isAlive()
                && !player.isSpectator()
                && !EntityUtils.shouldCancelCcsAttack(player);
    }

    private Player findPlayerByUuid(UUID uuid) {
        if (uuid == null || mc.level == null) {
            return null;
        }

        for (Player player : mc.level.players()) {
            if (player != null && uuid.equals(player.getUUID())) {
                return player;
            }
        }
        return null;
    }

    private CobwebTrackedPlayer getCobwebTrackedPlayer() {
        if (!(mc.hitResult instanceof BlockHitResult blockHit)
                || mc.hitResult.getType() != HitResult.Type.BLOCK
                || !mc.level.getBlockState(blockHit.getBlockPos()).is(Blocks.COBWEB)) {
            return null;
        }

        Vec3 eye = mc.player.getEyePosition(1.0F);
        Vec3 look = mc.player.getViewVector(1.0F).normalize();
        if (look.lengthSqr() <= 1.0E-6) {
            return null;
        }

        double blockDistanceSq = eye.distanceToSqr(blockHit.getLocation());
        double maxDistance = Math.max(getAttackRange(), COBWEB_PLAYER_TRACK_RANGE);
        Vec3 rayEnd = eye.add(look.scale(maxDistance));

        Player bestTarget = null;
        Vec3 bestHitPos = null;
        double bestDistanceSq = Double.MAX_VALUE;

        for (Player player : mc.level.players()) {
            if (!isValidOpponent(player)) {
                continue;
            }

            AABB box = player.getBoundingBox().inflate(0.10);
            Vec3 clipped = box.clip(eye, rayEnd).orElse(null);
            if (clipped == null) {
                continue;
            }

            double clippedDistanceSq = eye.distanceToSqr(clipped);
            if (clippedDistanceSq <= blockDistanceSq + COBWEB_TRACK_EPSILON_SQ || clippedDistanceSq >= bestDistanceSq) {
                continue;
            }

            bestTarget = player;
            bestHitPos = clipped;
            bestDistanceSq = clippedDistanceSq;
        }

        return bestTarget == null ? null : new CobwebTrackedPlayer(bestTarget, bestHitPos);
    }

    private boolean isCobwebTargetWithinAttackRange(CobwebTrackedPlayer tracked) {
        if (tracked == null || tracked.hitPos() == null || mc.player == null) {
            return false;
        }

        double attackRange = getAttackRange();
        return mc.player.getEyePosition(1.0F).distanceToSqr(tracked.hitPos()) <= attackRange * attackRange;
    }

    private boolean isInCobweb() {
        if (mc.player == null || mc.level == null) {
            return false;
        }

        AABB box = mc.player.getBoundingBox().deflate(1.0E-4);
        int minX = Mth.floor(box.minX);
        int maxX = Mth.floor(box.maxX);
        int minY = Mth.floor(box.minY);
        int maxY = Mth.floor(box.maxY);
        int minZ = Mth.floor(box.minZ);
        int maxZ = Mth.floor(box.maxZ);

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    cursor.set(x, y, z);
                    if (mc.level.getBlockState(cursor).is(Blocks.COBWEB)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isKnockbackMissTargetEligible(Player target) {
        return isValidOpponent(target)
                && getAngleToPlayer(target) <= knockbackMissFov.getVal()
                && isWithinWindow(mc.player.distanceTo(target), knockbackMissDistanceWindow);
    }

    private boolean isWithinWindow(double distance, RangeDoubleSetting window) {
        return window != null
                && distance >= window.getLower()
                && distance <= window.getUpper();
    }

    private void clearPendingAction() {
        pendingAction = null;
    }

    private void resetRuntimeState() {
        clearPendingAction();
        queuedKnockbackMiss = null;
        outOfRangeWindowEntry = null;
        singleFireLocked = false;
        singleFireLockStartedAtMs = 0L;
        consecutiveIncomingHitsWithoutRetaliation = 0;
        stopTap();
    }

    private boolean passesChanceRoll(double chance) {
        if (chance <= 0) {
            return false;
        }
        if (chance >= 100) {
            return true;
        }
        return Math.random() * 100.0 < chance;
    }

    private void configureChildSettings() {
        uppercutEnabled.setChangeAction(setting -> scheduleChildSettingsSync());
        sprintResetMode.setChangeAction(setting -> scheduleChildSettingsSync());
        opponentAngleCheck.setChangeAction(setting -> scheduleChildSettingsSync());
        attackDelayEnabled.setChangeAction(setting -> scheduleChildSettingsSync());
        humanizationEnabled.setChangeAction(setting -> scheduleChildSettingsSync());
        knockbackMisses.setChangeAction(setting -> scheduleChildSettingsSync());
        outOfRangeMisses.setChangeAction(setting -> scheduleChildSettingsSync());
        cobwebMisses.setChangeAction(setting -> scheduleChildSettingsSync());
    }

    private void syncVisibleSettings() {
        syncGeneralSettings();
        syncUppercutSettings();
        syncSprintResetSettings();
        syncAttackDelaySettings();
        syncHumanizationSettings();
    }

    private void syncGeneralSettings() {
        List<ModuleSetting<?>> settings = scGeneral.getSettings();
        settings.clear();
        settings.add(allowedHeldItems);
        settings.add(attackProgress);
        settings.add(requireSprinting);
    }

    private void syncUppercutSettings() {
        List<ModuleSetting<?>> settings = scUppercut.getSettings();
        settings.clear();
        settings.add(uppercutEnabled);
        if (uppercutEnabled.getVal()) {
            settings.add(requireAscending);
            settings.add(uppercutDistanceWindow);
        }
    }

    private void syncSprintResetSettings() {
        List<ModuleSetting<?>> settings = scSprintReset.getSettings();
        settings.clear();
        settings.add(sprintResetMode);
        if (sprintResetMode.getVal() != SprintResetMode.Off) {
            settings.add(opponentAngleCheck);
            if (opponentAngleCheck.getVal()) {
                settings.add(opponentAngleThreshold);
            }
            settings.add(waitForShieldBreakFollowUpClick);
            settings.add(tapDuration);
            settings.add(tapDistanceWindow);
        }
    }

    private void syncAttackDelaySettings() {
        List<ModuleSetting<?>> settings = scAttackDelay.getSettings();
        settings.clear();
        settings.add(attackDelayEnabled);
        if (attackDelayEnabled.getVal()) {
            settings.add(attackDelay);
        }
    }

    private void syncHumanizationSettings() {
        List<ModuleSetting<?>> settings = scHumanization.getSettings();
        settings.clear();
        settings.add(humanizationEnabled);
        if (!humanizationEnabled.getVal()) {
            return;
        }

        settings.add(knockbackMisses);
        if (knockbackMisses.getVal()) {
            settings.add(knockbackMissChance);
            settings.add(knockbackMissDistanceWindow);
            settings.add(knockbackMissReduceBackToBackChance);
            settings.add(knockbackMissFov);
        }

        settings.add(outOfRangeMisses);
        if (outOfRangeMisses.getVal()) {
            settings.add(outOfRangeMissChance);
            settings.add(outOfRangeWindow);
            settings.add(outOfRangeMissTap);
        }

        settings.add(cobwebMisses);
        if (cobwebMisses.getVal()) {
            settings.add(cobwebMissChance);
            settings.add(cobwebMissTap);
        }
    }

    private void refreshSettingsScreen() {
        if (pendingSettingsScreenRefresh) {
            return;
        }

        pendingSettingsScreenRefresh = true;
        system.scheduler.runDelayedTask(() -> mc.execute(() -> {
            pendingSettingsScreenRefresh = false;
            if (mc.screen instanceof ModuleEditScreen screen && screen.getModule() == this) {
                mc.setScreen(new ModuleEditScreen(this));
            }
        }), 1L);
    }

    private void scheduleChildSettingsSync() {
        if (pendingChildSettingsSync) {
            return;
        }

        pendingChildSettingsSync = true;
        system.scheduler.runDelayedTask(() -> mc.execute(() -> {
            pendingChildSettingsSync = false;
            syncVisibleSettings();
            refreshSettingsScreen();
        }), 1L);
    }

    @Override
    public Collection<ModuleSetting<?>> getPersistentSettings() {
        return List.of(
                requireAscending,
                uppercutDistanceWindow,
                opponentAngleCheck,
                opponentAngleThreshold,
                tapDuration,
                tapDistanceWindow,
                waitForShieldBreakFollowUpClick,
                attackDelay,
                knockbackMisses,
                knockbackMissChance,
                knockbackMissDistanceWindow,
                knockbackMissReduceBackToBackChance,
                knockbackMissFov,
                outOfRangeMisses,
                outOfRangeMissChance,
                outOfRangeWindow,
                outOfRangeMissTap,
                cobwebMisses,
                cobwebMissChance,
                cobwebMissTap
        );
    }

    public record PendingTap(UUID targetId, TapValidationMode validationMode, long durationMs) {
    }

    private record TapSession(UUID targetId, TapValidationMode validationMode, long endsAtMs) {
    }

    private record QueuedKnockbackMiss(UUID targetId, long expiresAtMs) {
    }

    private record OutOfRangeWindowEntry(UUID targetId, boolean shouldClick, boolean consumed) {

        private OutOfRangeWindowEntry consume() {
            return new OutOfRangeWindowEntry(targetId, shouldClick, true);
        }
    }

    private record CobwebTrackedPlayer(Player target, Vec3 hitPos) {
    }

    private record AttackContext(Player target, double distance, AttackBranch branch) {
    }

    private static final class PendingAction {

        private final int token;
        private final PendingActionKind kind;
        private final UUID targetId;
        private final AttackBranch branch;
        private final HumanizationMissType missType;
        private final float selectedAttackProgress;
        private final double baselineDistance;
        private final long readyAtMs;
        private final boolean startTapAfterClick;
        private final TapValidationMode tapValidationMode;
        private boolean executionQueued;

        private PendingAction(int token, PendingActionKind kind, UUID targetId, AttackBranch branch, HumanizationMissType missType,
                              float selectedAttackProgress, double baselineDistance, long readyAtMs,
                              boolean startTapAfterClick, TapValidationMode tapValidationMode) {
            this.token = token;
            this.kind = kind;
            this.targetId = targetId;
            this.branch = branch;
            this.missType = missType;
            this.selectedAttackProgress = selectedAttackProgress;
            this.baselineDistance = baselineDistance;
            this.readyAtMs = readyAtMs;
            this.startTapAfterClick = startTapAfterClick;
            this.tapValidationMode = tapValidationMode;
            this.executionQueued = false;
        }

        public static PendingAction real(int token, UUID targetId, AttackBranch branch, float selectedAttackProgress,
                                         double baselineDistance, long readyAtMs) {
            return new PendingAction(
                    token,
                    PendingActionKind.REAL_ATTACK,
                    targetId,
                    branch,
                    HumanizationMissType.KNOCKBACK,
                    selectedAttackProgress,
                    baselineDistance,
                    readyAtMs,
                    false,
                    TapValidationMode.ENTITY_CROSSHAIR
            );
        }

        public static PendingAction miss(int token, HumanizationMissType missType, UUID targetId, float selectedAttackProgress, long readyAtMs,
                                         boolean startTapAfterClick, TapValidationMode tapValidationMode) {
            return new PendingAction(
                    token,
                    PendingActionKind.HUMANIZATION_MISS,
                    targetId,
                    AttackBranch.NONE,
                    missType,
                    selectedAttackProgress,
                    0.0,
                    readyAtMs,
                    startTapAfterClick,
                    tapValidationMode
            );
        }

        public int token() {
            return token;
        }

        public PendingActionKind kind() {
            return kind;
        }

        public UUID targetId() {
            return targetId;
        }

        public AttackBranch branch() {
            return branch;
        }

        public HumanizationMissType missType() {
            return missType;
        }

        public float selectedAttackProgress() {
            return selectedAttackProgress;
        }

        public double baselineDistance() {
            return baselineDistance;
        }

        public long readyAtMs() {
            return readyAtMs;
        }

        public boolean startTapAfterClick() {
            return startTapAfterClick;
        }

        public TapValidationMode tapValidationMode() {
            return tapValidationMode;
        }

        public boolean executionQueued() {
            return executionQueued;
        }

        public void setExecutionQueued(boolean executionQueued) {
            this.executionQueued = executionQueued;
        }
    }

    private enum AttackBranch {
        NONE,
        GROUND,
        CRITICAL,
        UPPERCUT
    }

    private enum PendingActionKind {
        REAL_ATTACK,
        HUMANIZATION_MISS
    }

    private enum HumanizationMissType {
        KNOCKBACK,
        OUT_OF_RANGE,
        COBWEB
    }

    private enum TapValidationMode {
        ENTITY_CROSSHAIR,
        OUT_OF_RANGE_PLAYER_TRACK,
        COBWEB_PLAYER_TRACK
    }

    public enum SprintResetMode {
        Off,
        WTap,
        STap
    }
}
