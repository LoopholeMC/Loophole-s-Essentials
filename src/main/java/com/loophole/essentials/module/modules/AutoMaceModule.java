package com.loophole.essentials.module.modules;

import com.loophole.essentials.mixin.AccessorKeyMapping;
import com.loophole.essentials.mixin.AccessorMinecraft;
import com.loophole.essentials.module.LoopholeListenerModule;
import com.loophole.essentials.module.PersistentSettingProvider;
import com.loophole.essentials.module.settings.RangeDoubleSetting;
import io.github.itzispyder.clickcrystals.events.EventHandler;
import io.github.itzispyder.clickcrystals.events.EventPriority;
import io.github.itzispyder.clickcrystals.events.events.client.KeyPressEvent;
import io.github.itzispyder.clickcrystals.events.events.client.MouseClickEvent;
import io.github.itzispyder.clickcrystals.events.events.client.MouseScrollEvent;
import io.github.itzispyder.clickcrystals.events.events.networking.GameLeaveEvent;
import io.github.itzispyder.clickcrystals.events.events.world.ClientTickStartEvent;
import io.github.itzispyder.clickcrystals.events.events.world.RenderWorldEvent;
import io.github.itzispyder.clickcrystals.gui.ClickType;
import io.github.itzispyder.clickcrystals.gui.screens.ModuleEditScreen;
import io.github.itzispyder.clickcrystals.modules.ModuleSetting;
import io.github.itzispyder.clickcrystals.modules.settings.SettingSection;
import io.github.itzispyder.clickcrystals.util.minecraft.EntityUtils;
import io.github.itzispyder.clickcrystals.util.minecraft.InvUtils;
import io.github.itzispyder.clickcrystals.util.minecraft.PlayerUtils;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class AutoMaceModule extends LoopholeListenerModule implements PersistentSettingProvider {

    private static final double FALLING_CRIT_VELOCITY = -0.03;
    private static final long MIN_CLICK_GAP_MS = 25L;

    private final SettingSection scGeneral = getGeneralSection();
    private final SettingSection scSmash = createSettingSection("Smash Attack");
    private final SettingSection scStunSlam = createSettingSection("Stun Slam");
    private final SettingSection scBreach = createSettingSection("Breach Swap");
    private final SettingSection scShieldBreak = createSettingSection("Shield Break");
    private final SettingSection scSwitchBack = createSettingSection("Switch Back");

    public final ModuleSetting<AutomationMode> automationMode = scGeneral.add(createEnumSetting(AutomationMode.class)
            .name("automation-mode")
            .description("Automatic reacts on its own when conditions pass. Manual uses your left-click as the trigger and then handles the swap sequence for you.")
            .def(AutomationMode.Automatic)
            .build()
    );

    public final ModuleSetting<String> allowedHeldItems = scGeneral.add(createStringSetting()
            .name("allowed-held-items")
            .description("Comma-separated main-hand item matchers allowed to trigger Auto-Mace, for example #sword,#_axe,mace[density].")
            .def("#sword,#_axe")
            .build()
    );

    public final ModuleSetting<Double> smashVelocityThreshold = scGeneral.add(createDoubleSetting()
            .name("smash-velocity-threshold")
            .description("Minimum downward speed required for smash and stun-slam logic. A value of 0.70 means vel_y must be <= -0.70.")
            .def(0.70)
            .min(0.00)
            .max(3.00)
            .decimalPlaces(2)
            .build()
    );

    public final ModuleSetting<Boolean> requireGroundProximityForBreach = scGeneral.add(createBoolSetting()
            .name("require-ground-proximity-for-breach")
            .description("When not moving fast enough for a smash, require the nearest ground below you to be within one block before breach-swap logic can run.")
            .def(true)
            .build()
    );

    public final ModuleSetting<Boolean> smashEnabled = scSmash.add(createBoolSetting()
            .name("smash-enabled")
            .description("Enable density-mace smash attacks against non-blocking targets while fast-falling.")
            .def(true)
            .build()
    );

    public final ModuleSetting<String> smashMaceMatcher = scSmash.add(createStringSetting()
            .name("smash-mace")
            .description("Matcher for the mace used during non-blocking smash attacks, for example mace[density].")
            .def("mace[density]")
            .build()
    );

    public final RangeDoubleSetting smashSwitchBackDelay = scSmash.add(createRangeDoubleSetting()
            .name("smash-switch-back-delay")
            .description("Randomized delay before switching back after a smash attack finishes.")
            .def(0.000, 0.100)
            .min(0.000)
            .max(0.100)
            .decimalPlaces(3)
            .build()
    );

    public final ModuleSetting<Boolean> stunSlamEnabled = scStunSlam.add(createBoolSetting()
            .name("stun-slam-enabled")
            .description("Enable axe-first shield punishes during a fast-falling smash sequence.")
            .def(true)
            .build()
    );

    public final RangeDoubleSetting stunPrepDistanceWindow = scStunSlam.add(createRangeDoubleSetting()
            .name("stun-prep-distance-window")
            .description("Minimum and maximum distance where Auto-Mace can pre-switch to the axe while you are still outside real hit range.")
            .def(3.500, 6.000)
            .min(0.000)
            .max(6.000)
            .decimalPlaces(2)
            .build()
    );

    public final RangeDoubleSetting stunAxeFollowUpDelay = scStunSlam.add(createRangeDoubleSetting()
            .name("stun-follow-up-delay")
            .description("Randomized delay between the opening axe click and the follow-up density-mace click.")
            .def(0.000, 0.050)
            .min(0.000)
            .max(0.050)
            .decimalPlaces(3)
            .build()
    );

    public final ModuleSetting<String> stunMaceMatcher = scStunSlam.add(createStringSetting()
            .name("stun-mace")
            .description("Matcher for the mace used after the opening axe click in a stun slam sequence.")
            .def("mace[density]")
            .build()
    );

    public final RangeDoubleSetting stunSwitchBackDelay = scStunSlam.add(createRangeDoubleSetting()
            .name("stun-switch-back-delay")
            .description("Randomized delay before switching back after the stun slam follow-up finishes.")
            .def(0.000, 0.100)
            .min(0.000)
            .max(0.100)
            .decimalPlaces(3)
            .build()
    );

    public final ModuleSetting<Boolean> breachSwapEnabled = scBreach.add(createBoolSetting()
            .name("breach-swap-enabled")
            .description("Enable breach-mace swaps for falling crits and sprinting grounded knockback hits.")
            .def(true)
            .build()
    );

    public final ModuleSetting<Integer> breachSwapChance = scBreach.add(createIntSetting()
            .name("breach-swap-chance")
            .description("Percent chance for grounded sprinting knockback hits to breach-swap instead of using a normal click.")
            .def(50)
            .min(0)
            .max(100)
            .build()
    );

    public final RangeDoubleSetting breachSwitchBackDelay = scBreach.add(createRangeDoubleSetting()
            .name("breach-switch-back-delay")
            .description("Randomized delay before switching back after a breach swap finishes.")
            .def(0.000, 0.100)
            .min(0.000)
            .max(0.100)
            .decimalPlaces(3)
            .build()
    );

    public final ModuleSetting<Boolean> shieldBreakEnabled = scShieldBreak.add(createBoolSetting()
            .name("shield-break-enabled")
            .description("Enable axe-only shield breaks during non-smash breach logic.")
            .def(true)
            .build()
    );

    public final ModuleSetting<Boolean> shieldBreakSecondClick = scShieldBreak.add(createBoolSetting()
            .name("shield-break-second-click")
            .description("Queue the optional second axe click after the opening shield-break hit.")
            .def(true)
            .build()
    );

    public final RangeDoubleSetting shieldBreakSecondClickDelay = scShieldBreak.add(createRangeDoubleSetting()
            .name("shield-break-second-click-delay")
            .description("Randomized delay before the optional second axe click in the shield-break branch.")
            .def(0.000, 0.050)
            .min(0.000)
            .max(0.050)
            .decimalPlaces(3)
            .build()
    );

    public final RangeDoubleSetting shieldBreakSwitchBackDelay = scShieldBreak.add(createRangeDoubleSetting()
            .name("shield-break-switch-back-delay")
            .description("Randomized delay before switching back after a shield-break sequence finishes.")
            .def(0.000, 0.100)
            .min(0.000)
            .max(0.100)
            .decimalPlaces(3)
            .build()
    );

    public final ModuleSetting<Boolean> switchBack = scSwitchBack.add(createBoolSetting()
            .name("switch-back")
            .description("Return to the original hotbar slot after Auto-Mace finishes a swap sequence.")
            .def(true)
            .build()
    );

    public final ModuleSetting<Boolean> cancelSwitchBackOnManualSlotChange = scSwitchBack.add(createBoolSetting()
            .name("cancel-switch-back-on-manual-slot-change")
            .description("Cancel active Auto-Mace swap control if you manually change hotbar slots before the delayed restore happens.")
            .def(true)
            .build()
    );

    private PreparedStun preparedStun = null;
    private SwitchSequence activeSequence = null;
    private int sequenceToken = 0;
    private PendingTickClick pendingTickClick = null;
    private long lastQueuedClickAtMs = 0L;
    private boolean handlingTickStartRuntime = false;
    private boolean pendingChildSettingsSync = false;
    private boolean pendingSettingsScreenRefresh = false;

    public AutoMaceModule() {
        super("auto-mace", "Automates density and breach mace swap combos with optional manual left-click triggering.");
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

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onMouseClick(MouseClickEvent e) {
        if (e.getButton() != 0 || e.getAction() != ClickType.CLICK || !e.isScreenNull()) {
            return;
        }
        if (automationMode.getVal() != AutomationMode.Manual || activeSequence != null || preparedStun != null) {
            return;
        }
        if (!canOperate()) {
            return;
        }

        AttackIntent intent = createAttackIntent(true);
        if (intent == null) {
            return;
        }

        if (intent.consumeManualClick()) {
            e.cancel();
        }
        startIntent(intent);
    }

    @EventHandler
    private void onMouseScroll(MouseScrollEvent e) {
        if (shouldCancelOnManualSlotChange() && mc.screen == null && e.isVertical() && e.getDeltaY() != 0.0) {
            invalidateControlledState();
        }
    }

    @EventHandler
    private void onKeyPress(KeyPressEvent e) {
        if (!shouldCancelOnManualSlotChange() || mc.options == null || !e.getAction().isDown()) {
            return;
        }

        KeyEvent input = new KeyEvent(e.getKeycode(), e.getScancode(), 0);
        for (int slot = 0; slot < mc.options.keyHotbarSlots.length; slot++) {
            if (mc.options.keyHotbarSlots[slot].matches(input)) {
                invalidateControlledState();
                return;
            }
        }
    }

    @EventHandler
    private void onTickStart(ClientTickStartEvent e) {
        handlingTickStartRuntime = true;
        try {
            handleRuntime();
            processPendingTickClick();
        }
        finally {
            handlingTickStartRuntime = false;
        }
    }

    @EventHandler
    private void onRenderWorld(RenderWorldEvent e) {
        if (shouldCancelOnManualSlotChange() && canOperate()) {
            validateControlledSlot();
        }
    }

    private void handleRuntime() {
        if (!canOperate()) {
            resetRuntimeState();
            return;
        }
        if (shouldCancelOnManualSlotChange()) {
            validateControlledSlot();
        }
        if (activeSequence != null) {
            return;
        }
        if (automationMode.getVal() != AutomationMode.Automatic) {
            return;
        }
        if (preparedStun != null) {
            clearPreparedStun(true);
        }
        if (!passesMinimumClickGap()) {
            return;
        }

        AttackIntent intent = createAttackIntent(false);
        if (intent == null) {
            return;
        }
        startIntent(intent);
    }

    private AttackIntent createAttackIntent(boolean manualTrigger) {
        if (!passesBaseCombatState() || !isHoldingAllowedItem()) {
            return null;
        }

        Player directTarget = getDirectCrosshairTarget();
        if (isFastSmashState()) {
            Player smashTarget = directTarget;
            if (!isValidTarget(smashTarget)) {
                smashTarget = getExtendedCrosshairTarget(stunPrepDistanceWindow.getUpper());
            }
            if (!isValidTarget(smashTarget)) {
                return null;
            }

            double distance = mc.player.distanceTo(smashTarget);
            if (isTargetBlocking(smashTarget) && stunSlamEnabled.getVal()) {
                int axeSlot = findAxeHotbarSlot();
                int maceSlot = resolveHotbarSlotForMatcher(stunMaceMatcher.getVal());
                if (axeSlot < 0 || maceSlot < 0) {
                    return null;
                }

                int originalSlot = InvUtils.selected();
                if (distance <= getAttackRange() && smashTarget == directTarget) {
                    return createStunSlamIntent(smashTarget.getUUID(), originalSlot, axeSlot, maceSlot, manualTrigger);
                }
                if (!manualTrigger
                        && distance >= stunPrepDistanceWindow.getLower()
                        && distance <= stunPrepDistanceWindow.getUpper()
                        && directTarget == null) {
                    return createPreparedStunIntent(smashTarget.getUUID(), originalSlot, axeSlot);
                }
                return null;
            }

            if (!isTargetBlocking(smashTarget)
                    && smashEnabled.getVal()
                    && smashTarget == directTarget
                    && distance <= getAttackRange()) {
                int maceSlot = resolveHotbarSlotForMatcher(smashMaceMatcher.getVal());
                if (maceSlot < 0) {
                    return null;
                }
                return createSmashIntent(smashTarget.getUUID(), InvUtils.selected(), maceSlot, manualTrigger);
            }
            return null;
        }

        if ((!breachSwapEnabled.getVal() && !shieldBreakEnabled.getVal())
                || (requireGroundProximityForBreach.getVal() && !isGroundClose())) {
            return null;
        }
        if (!isValidTarget(directTarget) || mc.player.getAttackStrengthScale(1.0F) < 1.0F) {
            return null;
        }

        boolean targetBlocking = isTargetBlocking(directTarget);
        int originalSlot = InvUtils.selected();

        if (targetBlocking && shieldBreakEnabled.getVal()) {
            int axeSlot = findAxeHotbarSlot();
            if (axeSlot < 0) {
                return null;
            }
            return createShieldBreakIntent(directTarget.getUUID(), originalSlot, axeSlot, manualTrigger, shieldBreakSecondClick.getVal());
        }

        if (!breachSwapEnabled.getVal() || targetBlocking) {
            return null;
        }

        int breachMaceSlot = resolveHotbarSlotForMatcher("mace[breach]");
        if (breachMaceSlot < 0) {
            return null;
        }

        if (isFallingCritState()) {
            return createBreachSwapIntent(directTarget.getUUID(), originalSlot, breachMaceSlot, manualTrigger);
        }
        if (mc.player.onGround() && mc.player.isSprinting()) {
            if (passesChanceRoll(breachSwapChance.getVal())) {
                return createBreachSwapIntent(directTarget.getUUID(), originalSlot, breachMaceSlot, manualTrigger);
            }
            return manualTrigger ? createManualNormalHitIntent(directTarget.getUUID()) : createAutoNormalHitIntent(directTarget.getUUID());
        }
        return null;
    }

    private AttackIntent createSmashIntent(UUID targetId, int originalSlot, int maceSlot, boolean manualTrigger) {
        return new AttackIntent(
                AttackKind.SMASH,
                targetId,
                originalSlot,
                maceSlot,
                -1,
                manualTrigger,
                true,
                false,
                -1L,
                getDelayMs(smashSwitchBackDelay)
        );
    }

    private AttackIntent createStunSlamIntent(UUID targetId, int originalSlot, int axeSlot, int maceSlot, boolean manualTrigger) {
        return new AttackIntent(
                AttackKind.STUN_SLAM,
                targetId,
                originalSlot,
                axeSlot,
                maceSlot,
                manualTrigger,
                true,
                true,
                getDelayMs(stunAxeFollowUpDelay),
                getDelayMs(stunSwitchBackDelay)
        );
    }

    private AttackIntent createPreparedStunIntent(UUID targetId, int originalSlot, int axeSlot) {
        return new AttackIntent(AttackKind.PREPARED_STUN, targetId, originalSlot, axeSlot, -1, false, false, false, -1L, -1L);
    }

    private AttackIntent createBreachSwapIntent(UUID targetId, int originalSlot, int maceSlot, boolean manualTrigger) {
        return new AttackIntent(
                AttackKind.BREACH_SWAP,
                targetId,
                originalSlot,
                maceSlot,
                -1,
                manualTrigger,
                true,
                false,
                -1L,
                getDelayMs(breachSwitchBackDelay)
        );
    }

    private AttackIntent createShieldBreakIntent(UUID targetId, int originalSlot, int axeSlot, boolean manualTrigger, boolean secondClickEnabled) {
        long followUpDelay = secondClickEnabled ? getDelayMs(shieldBreakSecondClickDelay) : -1L;
        return new AttackIntent(
                AttackKind.SHIELD_BREAK,
                targetId,
                originalSlot,
                axeSlot,
                -1,
                manualTrigger,
                true,
                secondClickEnabled,
                followUpDelay,
                getDelayMs(shieldBreakSwitchBackDelay)
        );
    }

    private AttackIntent createAutoNormalHitIntent(UUID targetId) {
        return new AttackIntent(AttackKind.NORMAL_HIT, targetId, -1, -1, -1, false, true, false, -1L, -1L);
    }

    private AttackIntent createManualNormalHitIntent(UUID targetId) {
        return new AttackIntent(AttackKind.NORMAL_HIT, targetId, -1, -1, -1, false, false, false, -1L, -1L);
    }

    private long getDelayMs(RangeDoubleSetting setting) {
        return Math.max(0L, Math.round(setting.getRandomizedValue() * 1000.0));
    }

    private void startIntent(AttackIntent intent) {
        if (intent == null) {
            return;
        }

        switch (intent.kind()) {
            case PREPARED_STUN -> beginPreparedStun(intent);
            case NORMAL_HIT -> {
                if (intent.queueOpeningClick()
                        && canOperate()
                        && passesBaseCombatState()) {
                    queueAttackClick(intent.targetId(), PendingClickPhase.NORMAL_HIT, -1);
                }
            }
            case SMASH, BREACH_SWAP, STUN_SLAM, SHIELD_BREAK -> startSequence(intent);
        }
    }

    private void beginPreparedStun(AttackIntent intent) {
        if (intent.primarySlot() < 0 || preparedStun != null || activeSequence != null) {
            return;
        }

        InvUtils.select(intent.primarySlot());
        preparedStun = new PreparedStun(++sequenceToken, intent.targetId(), intent.originalSlot(), intent.primarySlot());
    }

    private boolean processPreparedStun() {
        if (preparedStun == null) {
            return false;
        }
        if (!canOperate() || !passesBaseCombatState() || !isFastSmashState()) {
            clearPreparedStun(true);
            return false;
        }

        Player target = findPlayerByUuid(preparedStun.targetId());
        if (!isValidTarget(target)) {
            clearPreparedStun(true);
            return false;
        }

        double distance = mc.player.distanceTo(target);
        if (distance > stunPrepDistanceWindow.getUpper() + 0.25) {
            clearPreparedStun(true);
            return false;
        }

        Player directTarget = getDirectCrosshairTarget();
        if (directTarget != target || distance > getAttackRange()) {
            return true;
        }

        int maceSlot = resolveHotbarSlotForMatcher(stunMaceMatcher.getVal());
        if (maceSlot < 0) {
            clearPreparedStun(true);
            return false;
        }

        PreparedStun prepared = preparedStun;
        preparedStun = null;
        startSequence(createStunSlamIntent(target.getUUID(), prepared.originalSlot(), prepared.axeSlot(), maceSlot, false));
        return true;
    }

    private void startSequence(AttackIntent intent) {
        if (activeSequence != null) {
            return;
        }

        SwitchSequence sequence = new SwitchSequence(
                ++sequenceToken,
                intent.kind(),
                intent.targetId(),
                intent.originalSlot(),
                intent.primarySlot(),
                intent.secondarySlot(),
                InvUtils.selected(),
                intent.followUpDelayMs(),
                intent.switchBackDelayMs(),
                intent.queueFollowUpClick()
        );
        activeSequence = sequence;

        if (intent.primarySlot() >= 0 && InvUtils.selected() != intent.primarySlot()) {
            InvUtils.select(intent.primarySlot());
        }
        sequence.setControlledSlot(InvUtils.selected());

        if (intent.queueOpeningClick()) {
            queueAttackClick(sequence.targetId(), PendingClickPhase.OPENING, sequence.token());
            return;
        }
        schedulePostOpeningSteps(sequence.token());
    }

    private void schedulePostOpeningSteps(int token) {
        if (!isSequenceValid(token) || activeSequence == null) {
            return;
        }
        if (activeSequence.followUpDelayMs() >= 0L) {
            scheduleMs(activeSequence.followUpDelayMs(), token, this::runFollowUp);
            return;
        }
        scheduleSwitchBack(token);
    }

    private void runFollowUp(int token) {
        if (!isSequenceValid(token) || activeSequence == null) {
            return;
        }
        if (!isSequenceAttackStillValid(activeSequence, true)) {
            abortActiveSequence();
            return;
        }
        if (shouldCancelOnManualSlotChange() && InvUtils.selected() != activeSequence.controlledSlot()) {
            invalidateControlledState();
            return;
        }

        if (activeSequence.secondarySlot() >= 0 && InvUtils.selected() != activeSequence.secondarySlot()) {
            InvUtils.select(activeSequence.secondarySlot());
            activeSequence.setControlledSlot(activeSequence.secondarySlot());
        }
        if (activeSequence.queueFollowUpClick()) {
            queueAttackClick(activeSequence.targetId(), PendingClickPhase.FOLLOW_UP, token);
            return;
        }
        scheduleSwitchBack(token);
    }

    private void scheduleSwitchBack(int token) {
        if (!isSequenceValid(token) || activeSequence == null) {
            return;
        }
        if (!switchBack.getVal()) {
            clearSequence();
            return;
        }

        scheduleMs(activeSequence.switchBackDelayMs(), token, this::finalizeSwitchBack);
    }

    private void finalizeSwitchBack(int token) {
        if (!isSequenceValid(token) || activeSequence == null) {
            return;
        }
        if (shouldCancelOnManualSlotChange() && InvUtils.selected() != activeSequence.controlledSlot()) {
            invalidateControlledState();
            return;
        }
        if (activeSequence.originalSlot() >= 0 && activeSequence.originalSlot() <= 8) {
            InvUtils.select(activeSequence.originalSlot());
        }
        clearSequence();
    }

    private void scheduleMs(long delayMs, int token, java.util.function.IntConsumer action) {
        long safeDelayMs = Math.max(0L, delayMs);
        system.scheduler.runDelayedTask(() -> mc.execute(() -> {
            if (isSequenceValid(token)) {
                action.accept(token);
            }
        }), safeDelayMs);
    }

    private boolean isSequenceValid(int token) {
        return activeSequence != null && activeSequence.token() == token;
    }

    private void processPendingTickClick() {
        if (pendingTickClick == null || !passesMinimumClickGap()) {
            return;
        }

        PendingTickClick pendingClick = pendingTickClick;
        if (!isPendingTickClickStillValid(pendingClick)) {
            clearPendingTickClick();
            handlePendingTickClickFailure(pendingClick);
            return;
        }

        Player target = resolvePinnedAttackTarget(pendingClick.targetId());
        if (target == null) {
            clearPendingTickClick();
            handlePendingTickClickFailure(pendingClick);
            return;
        }

        clearPendingTickClick();
        queueAttackClick(target);
        handlePendingTickClickSuccess(pendingClick);
    }

    private boolean isPendingTickClickStillValid(PendingTickClick pendingClick) {
        if (pendingClick == null || !canOperate() || !passesBaseCombatState()) {
            return false;
        }

        return switch (pendingClick.phase()) {
            case NORMAL_HIT -> true;
            case OPENING -> isSequenceValid(pendingClick.sequenceToken());
            case FOLLOW_UP -> isSequenceValid(pendingClick.sequenceToken())
                    && isSequenceAttackStillValid(activeSequence, true);
        };
    }

    private void handlePendingTickClickSuccess(PendingTickClick pendingClick) {
        if (pendingClick == null) {
            return;
        }

        switch (pendingClick.phase()) {
            case NORMAL_HIT -> {
            }
            case OPENING -> {
                if (isSequenceValid(pendingClick.sequenceToken())) {
                    schedulePostOpeningSteps(pendingClick.sequenceToken());
                }
            }
            case FOLLOW_UP -> {
                if (isSequenceValid(pendingClick.sequenceToken())) {
                    scheduleSwitchBack(pendingClick.sequenceToken());
                }
            }
        }
    }

    private void handlePendingTickClickFailure(PendingTickClick pendingClick) {
        if (pendingClick == null) {
            return;
        }

        switch (pendingClick.phase()) {
            case NORMAL_HIT -> {
            }
            case OPENING, FOLLOW_UP -> {
                if (isSequenceValid(pendingClick.sequenceToken())) {
                    abortActiveSequence();
                }
            }
        }
    }

    private void queueAttackClick(UUID targetId, PendingClickPhase phase, int sequenceToken) {
        pendingTickClick = new PendingTickClick(targetId, phase, sequenceToken);
    }

    private boolean isSequenceAttackStillValid(SwitchSequence sequence, boolean followUpClick) {
        if (sequence == null || !canOperate()) {
            return false;
        }
        if (mc.player == null || mc.player.isBlocking() || mc.player.isUsingItem() || mc.player.isFallFlying()) {
            return false;
        }
        if (resolvePinnedAttackTarget(sequence.targetId()) == null) {
            return false;
        }

        return switch (sequence.kind()) {
            case SMASH, STUN_SLAM -> isFastSmashState();
            case BREACH_SWAP -> isFallingCritState() || (mc.player.onGround() && mc.player.isSprinting() && mc.player.getAttackStrengthScale(1.0F) >= 1.0F);
            case SHIELD_BREAK -> mc.player.getAttackStrengthScale(1.0F) >= 1.0F;
            case PREPARED_STUN, NORMAL_HIT -> false;
        };
    }

    private boolean isDirectCrosshairTarget(Player target) {
        return target != null && getDirectCrosshairTarget() == target;
    }

    private void abortActiveSequence() {
        if (activeSequence == null) {
            return;
        }
        SwitchSequence sequence = activeSequence;
        clearSequence();
        if (switchBack.getVal()
                && InvUtils.selected() == sequence.controlledSlot()
                && sequence.originalSlot() >= 0
                && sequence.originalSlot() <= 8) {
            InvUtils.select(sequence.originalSlot());
        }
    }

    private void validateControlledSlot() {
        if (preparedStun != null && InvUtils.selected() != preparedStun.axeSlot()) {
            invalidateControlledState();
            return;
        }
        if (activeSequence != null && InvUtils.selected() != activeSequence.controlledSlot()) {
            invalidateControlledState();
        }
    }

    private void invalidateControlledState() {
        clearPendingTickClick();
        preparedStun = null;
        clearSequence();
    }

    private void clearSequence() {
        clearPendingTickClick();
        activeSequence = null;
        sequenceToken++;
    }

    private void clearPreparedStun(boolean restoreOriginalSlot) {
        if (preparedStun == null) {
            return;
        }

        PreparedStun prepared = preparedStun;
        preparedStun = null;
        sequenceToken++;

        if (restoreOriginalSlot
                && switchBack.getVal()
                && InvUtils.selected() == prepared.axeSlot()
                && prepared.originalSlot() >= 0
                && prepared.originalSlot() <= 8) {
            InvUtils.select(prepared.originalSlot());
        }
    }

    private void resetRuntimeState() {
        clearPendingTickClick();
        if (activeSequence != null
                && switchBack.getVal()
                && InvUtils.selected() == activeSequence.controlledSlot()
                && activeSequence.originalSlot() >= 0
                && activeSequence.originalSlot() <= 8) {
            InvUtils.select(activeSequence.originalSlot());
        }
        clearSequence();
        clearPreparedStun(true);
    }

    private boolean passesBaseCombatState() {
        return canOperate()
                && !mc.player.isBlocking()
                && !mc.player.isUsingItem()
                && !mc.player.isFallFlying();
    }

    private boolean canOperate() {
        return isEnabled()
                && PlayerUtils.valid()
                && mc.player != null
                && mc.level != null
                && mc.options != null
                && mc.screen == null;
    }

    private boolean isHoldingAllowedItem() {
        return matchesConfiguredHeldItem(mc.player.getMainHandItem(), allowedHeldItems.getVal());
    }

    private boolean matchesConfiguredHeldItem(ItemStack stack, String matcherList) {
        if (stack == null || stack.isEmpty() || matcherList == null || matcherList.isBlank()) {
            return false;
        }

        String descriptionId = stack.getItem().getDescriptionId().toLowerCase(Locale.ROOT);
        for (String rawToken : matcherList.split(",")) {
            MatcherSpec spec = MatcherSpec.parse(rawToken);
            if (spec.matches(stack, descriptionId)) {
                return true;
            }
        }
        return false;
    }

    private int resolveHotbarSlotForMatcher(String matcher) {
        if (matcher == null || matcher.isBlank()) {
            return InvUtils.selected();
        }
        if (matchesConfiguredHeldItem(mc.player.getMainHandItem(), matcher)) {
            return InvUtils.selected();
        }
        for (int slot = 0; slot <= 8; slot++) {
            if (matchesConfiguredHeldItem(mc.player.getInventory().getItem(slot), matcher)) {
                return slot;
            }
        }
        return -1;
    }

    private int findAxeHotbarSlot() {
        if (mc.player == null) {
            return -1;
        }
        for (int slot = 0; slot <= 8; slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String descriptionId = stack.getItem().getDescriptionId().toLowerCase(Locale.ROOT);
            if (descriptionId.contains("_axe") && !descriptionId.contains("_pickaxe")) {
                return slot;
            }
        }
        return -1;
    }

    private Player getDirectCrosshairTarget() {
        if (!(mc.hitResult instanceof EntityHitResult hit) || mc.hitResult.getType() != HitResult.Type.ENTITY) {
            return null;
        }
        return hit.getEntity() instanceof Player target ? target : null;
    }

    private Player getExtendedCrosshairTarget(double maxDistance) {
        if (mc.player == null || mc.level == null || maxDistance <= 0.0) {
            return null;
        }

        Vec3 eye = mc.player.getEyePosition(1.0F);
        Vec3 look = mc.player.getViewVector(1.0F).normalize();
        if (look.lengthSqr() <= 1.0E-6) {
            return null;
        }

        Vec3 rayEnd = eye.add(look.scale(maxDistance));
        Player bestTarget = null;
        double bestDistanceSq = Double.MAX_VALUE;

        for (Player player : mc.level.players()) {
            if (!isValidTarget(player)) {
                continue;
            }

            AABB box = player.getBoundingBox().inflate(0.10);
            Vec3 clipped = box.clip(eye, rayEnd).orElse(null);
            if (clipped == null) {
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

    private boolean isValidTarget(Player target) {
        return target != null
                && target != mc.player
                && target.isAlive()
                && !target.isSpectator()
                && !EntityUtils.shouldCancelCcsAttack(target);
    }

    private boolean isTargetBlocking(Player target) {
        if (target == null) {
            return false;
        }

        ItemStack active = target.getUseItem();
        if (!active.isEmpty() && !active.is(Items.SHIELD)) {
            return false;
        }
        if (target.isBlocking()) {
            return true;
        }
        if (!active.isEmpty() && active.is(Items.SHIELD)) {
            return true;
        }
        if (target.getUseItemRemainingTicks() > 0) {
            return target.getMainHandItem().is(Items.SHIELD) || target.getOffhandItem().is(Items.SHIELD);
        }
        if (!target.isUsingItem() || target.getUsedItemHand() == null) {
            return false;
        }
        ItemStack activeHandItem = target.getItemInHand(target.getUsedItemHand());
        return !activeHandItem.isEmpty() && activeHandItem.is(Items.SHIELD);
    }

    private boolean isFastSmashState() {
        return !mc.player.onGround()
                && !mc.player.isFallFlying()
                && mc.player.getDeltaMovement().y <= -Math.abs(smashVelocityThreshold.getVal());
    }

    private boolean isFallingCritState() {
        return !mc.player.onGround()
                && !mc.player.isFallFlying()
                && mc.player.fallDistance > 0.0F
                && mc.player.getDeltaMovement().y < FALLING_CRIT_VELOCITY;
    }

    private boolean isGroundClose() {
        return mc.player.onGround() || mc.player.fallDistance <= 1.0F;
    }

    private double getAttackRange() {
        return Math.max(0.0, mc.player.entityInteractionRange());
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

    private boolean passesChanceRoll(int chance) {
        if (chance <= 0) {
            return false;
        }
        if (chance >= 100) {
            return true;
        }
        return Math.random() * 100.0 < chance;
    }

    private Player resolvePinnedAttackTarget(UUID targetId) {
        Player target = findPlayerByUuid(targetId);
        return isPinnedAttackTargetStillValid(target) ? target : null;
    }

    private boolean isPinnedAttackTargetStillValid(Player target) {
        return target != null
                && isValidTarget(target)
                && isDirectCrosshairTarget(target);
    }

    private boolean passesMinimumClickGap() {
        return System.currentTimeMillis() - lastQueuedClickAtMs >= MIN_CLICK_GAP_MS;
    }

    private void queueAttackClick(Player resolvedTarget) {
        if (resolvedTarget != null) {
            ((AccessorMinecraft) mc).loopholeEssentials$setHitResult(
                    new EntityHitResult(resolvedTarget, resolvedTarget.getEyePosition(1.0F))
            );
        }
        KeyMapping.click(((AccessorKeyMapping) mc.options.keyAttack).loopholeEssentials$getBoundKey());
        lastQueuedClickAtMs = System.currentTimeMillis();
    }

    private void clearPendingTickClick() {
        pendingTickClick = null;
    }

    private boolean shouldCancelOnManualSlotChange() {
        return switchBack.getVal() && cancelSwitchBackOnManualSlotChange.getVal();
    }

    private void configureChildSettings() {
        smashEnabled.setChangeAction(setting -> scheduleChildSettingsSync());
        stunSlamEnabled.setChangeAction(setting -> scheduleChildSettingsSync());
        breachSwapEnabled.setChangeAction(setting -> scheduleChildSettingsSync());
        shieldBreakEnabled.setChangeAction(setting -> scheduleChildSettingsSync());
        shieldBreakSecondClick.setChangeAction(setting -> scheduleChildSettingsSync());
        switchBack.setChangeAction(setting -> scheduleChildSettingsSync());
    }

    private void syncVisibleSettings() {
        syncSmashSettings();
        syncStunSettings();
        syncBreachSettings();
        syncShieldBreakSettings();
        syncSwitchBackSettings();
    }

    private void syncSmashSettings() {
        List<ModuleSetting<?>> settings = scSmash.getSettings();
        settings.clear();
        settings.add(smashEnabled);
        if (smashEnabled.getVal()) {
            settings.add(smashMaceMatcher);
            settings.add(smashSwitchBackDelay);
        }
    }

    private void syncStunSettings() {
        List<ModuleSetting<?>> settings = scStunSlam.getSettings();
        settings.clear();
        settings.add(stunSlamEnabled);
        if (stunSlamEnabled.getVal()) {
            settings.add(stunPrepDistanceWindow);
            settings.add(stunAxeFollowUpDelay);
            settings.add(stunMaceMatcher);
            settings.add(stunSwitchBackDelay);
        }
    }

    private void syncBreachSettings() {
        List<ModuleSetting<?>> settings = scBreach.getSettings();
        settings.clear();
        settings.add(breachSwapEnabled);
        if (breachSwapEnabled.getVal()) {
            settings.add(breachSwapChance);
            settings.add(breachSwitchBackDelay);
        }
    }

    private void syncShieldBreakSettings() {
        List<ModuleSetting<?>> settings = scShieldBreak.getSettings();
        settings.clear();
        settings.add(shieldBreakEnabled);
        if (!shieldBreakEnabled.getVal()) {
            return;
        }
        settings.add(shieldBreakSecondClick);
        if (shieldBreakSecondClick.getVal()) {
            settings.add(shieldBreakSecondClickDelay);
        }
        settings.add(shieldBreakSwitchBackDelay);
    }

    private void syncSwitchBackSettings() {
        List<ModuleSetting<?>> settings = scSwitchBack.getSettings();
        settings.clear();
        settings.add(switchBack);
        if (switchBack.getVal()) {
            settings.add(cancelSwitchBackOnManualSlotChange);
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
                smashMaceMatcher,
                smashSwitchBackDelay,
                stunPrepDistanceWindow,
                stunAxeFollowUpDelay,
                stunMaceMatcher,
                stunSwitchBackDelay,
                breachSwapChance,
                breachSwitchBackDelay,
                shieldBreakSecondClick,
                shieldBreakSecondClickDelay,
                shieldBreakSwitchBackDelay,
                cancelSwitchBackOnManualSlotChange
        );
    }

    private enum AutomationMode {
        Automatic,
        Manual
    }

    private enum AttackKind {
        SMASH,
        STUN_SLAM,
        PREPARED_STUN,
        BREACH_SWAP,
        SHIELD_BREAK,
        NORMAL_HIT
    }

    private record PreparedStun(int token, UUID targetId, int originalSlot, int axeSlot) {
    }

    private record AttackIntent(AttackKind kind, UUID targetId, int originalSlot, int primarySlot, int secondarySlot,
                                boolean consumeManualClick, boolean queueOpeningClick, boolean queueFollowUpClick,
                                long followUpDelayMs, long switchBackDelayMs) {
    }

    private record PendingTickClick(UUID targetId, PendingClickPhase phase, int sequenceToken) {
    }

    private enum PendingClickPhase {
        NORMAL_HIT,
        OPENING,
        FOLLOW_UP
    }

    private static final class SwitchSequence {

        private final int token;
        private final AttackKind kind;
        private final UUID targetId;
        private final int originalSlot;
        private final int primarySlot;
        private final int secondarySlot;
        private int controlledSlot;
        private final long followUpDelayMs;
        private final long switchBackDelayMs;
        private final boolean queueFollowUpClick;

        private SwitchSequence(int token, AttackKind kind, UUID targetId, int originalSlot, int primarySlot, int secondarySlot,
                               int controlledSlot, long followUpDelayMs, long switchBackDelayMs, boolean queueFollowUpClick) {
            this.token = token;
            this.kind = kind;
            this.targetId = targetId;
            this.originalSlot = originalSlot;
            this.primarySlot = primarySlot;
            this.secondarySlot = secondarySlot;
            this.controlledSlot = controlledSlot;
            this.followUpDelayMs = followUpDelayMs;
            this.switchBackDelayMs = switchBackDelayMs;
            this.queueFollowUpClick = queueFollowUpClick;
        }

        public int token() {
            return token;
        }

        public AttackKind kind() {
            return kind;
        }

        public UUID targetId() {
            return targetId;
        }

        public int originalSlot() {
            return originalSlot;
        }

        public int primarySlot() {
            return primarySlot;
        }

        public int secondarySlot() {
            return secondarySlot;
        }

        public int controlledSlot() {
            return controlledSlot;
        }

        public void setControlledSlot(int controlledSlot) {
            this.controlledSlot = controlledSlot;
        }

        public long followUpDelayMs() {
            return followUpDelayMs;
        }

        public long switchBackDelayMs() {
            return switchBackDelayMs;
        }

        public boolean queueFollowUpClick() {
            return queueFollowUpClick;
        }
    }

    private static final class MatcherSpec {

        private final String itemFragment;
        private final String enchantFragment;

        private MatcherSpec(String itemFragment, String enchantFragment) {
            this.itemFragment = itemFragment;
            this.enchantFragment = enchantFragment;
        }

        public static MatcherSpec parse(String raw) {
            String cleaned = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            if (cleaned.startsWith("#")) {
                cleaned = cleaned.substring(1);
            }
            int open = cleaned.indexOf('[');
            int close = cleaned.lastIndexOf(']');
            if (open >= 0 && close > open) {
                String item = cleaned.substring(0, open).trim();
                String enchant = cleaned.substring(open + 1, close).trim();
                return new MatcherSpec(item, enchant);
            }
            return new MatcherSpec(cleaned, "");
        }

        public boolean matches(ItemStack stack, String descriptionId) {
            if (stack == null || stack.isEmpty()) {
                return false;
            }
            if (!itemFragment.isEmpty() && !descriptionId.contains(itemFragment)) {
                return false;
            }
            if (enchantFragment.isEmpty()) {
                return !itemFragment.isEmpty();
            }

            for (Holder<?> enchantment : stack.getEnchantments().keySet()) {
                if (enchantment.getRegisteredName().toLowerCase(Locale.ROOT).contains(enchantFragment)) {
                    return true;
                }
            }
            return false;
        }
    }
}
