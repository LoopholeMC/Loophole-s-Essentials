package com.loophole.essentials.module.modules;

import com.loophole.essentials.mixin.AccessorKeyMapping;
import com.loophole.essentials.module.LoopholeListenerModule;
import com.loophole.essentials.module.PersistentSettingProvider;
import com.loophole.essentials.module.settings.RangeDoubleSetting;
import io.github.itzispyder.clickcrystals.events.EventHandler;
import io.github.itzispyder.clickcrystals.events.events.client.KeyPressEvent;
import io.github.itzispyder.clickcrystals.events.events.client.MouseClickEvent;
import io.github.itzispyder.clickcrystals.events.events.client.MouseScrollEvent;
import io.github.itzispyder.clickcrystals.events.events.networking.GameLeaveEvent;
import io.github.itzispyder.clickcrystals.events.events.world.ClientTickStartEvent;
import io.github.itzispyder.clickcrystals.gui.ClickType;
import io.github.itzispyder.clickcrystals.gui.screens.ModuleEditScreen;
import io.github.itzispyder.clickcrystals.modules.Module;
import io.github.itzispyder.clickcrystals.modules.ModuleSetting;
import io.github.itzispyder.clickcrystals.modules.settings.SettingSection;
import io.github.itzispyder.clickcrystals.util.minecraft.EntityUtils;
import io.github.itzispyder.clickcrystals.util.minecraft.InvUtils;
import io.github.itzispyder.clickcrystals.util.minecraft.PlayerUtils;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class ShieldBreakerModule extends LoopholeListenerModule implements PersistentSettingProvider {

    private static final double DEFAULT_SHIELD_ANGLE = 90.0;
    private static final double TELEPORT_DISTANCE_SQ = 25.0;
    private static final double TELEPORT_VERTICAL_DELTA = 3.0;
    private static final int TELEPORT_GRACE_TICKS = 3;

    private final SettingSection scGeneral = getGeneralSection();
    private final SettingSection scTiming = createSettingSection("Timing");
    private final SettingSection scPrediction = createSettingSection("Prediction");

    public final ModuleSetting<String> allowedHeldItems = scGeneral.add(createStringSetting()
            .name("allowed-held-items")
            .description("Comma-separated main-hand item-name fragments that can start Shield-Breaker, for example sword,_axe.")
            .def("sword,_axe")
            .build()
    );

    public final ModuleSetting<Integer> chance = scGeneral.add(createIntSetting()
            .name("chance")
            .description("Percent chance for Shield-Breaker to start after all normal shield-break conditions pass.")
            .min(0)
            .max(100)
            .def(100)
            .build()
    );

    public final ModuleSetting<Boolean> shieldStun = scTiming.add(createBoolSetting()
            .name("shield-stun")
            .description("Queue one extra left-click after swapping to the axe so the shield gets stunned through Minecraft's normal attack click path.")
            .def(true)
            .build()
    );

    public final RangeDoubleSetting shieldStunDelay = scTiming.add(createRangeDoubleSetting()
            .name("shield-stun-delay")
            .description("Randomized delay before Shield-Breaker queues its extra shield-stun click.")
            .def(0.000, 0.005)
            .min(0.000)
            .max(0.050)
            .decimalPlaces(3)
            .build()
    );

    public final ModuleSetting<Boolean> switchBack = scTiming.add(createBoolSetting()
            .name("switch-back")
            .description("Switch back to your original hotbar slot after the shield-break sequence finishes.")
            .def(true)
            .build()
    );

    public final RangeDoubleSetting switchBackDelay = scTiming.add(createRangeDoubleSetting()
            .name("switch-back-delay")
            .description("Randomized delay before switching back after the shield-break sequence finishes.")
            .def(0.030, 0.050)
            .min(0.000)
            .max(0.100)
            .decimalPlaces(3)
            .build()
    );

    public final ModuleSetting<Boolean> cancelSwitchBackOnManualSlotChange = scTiming.add(createBoolSetting()
            .name("cancel-switch-back-on-manual-slot-change")
            .description("Cancel switch-back if you manually change hotbar slots before the delayed restore happens.")
            .def(true)
            .build()
    );

    public final ModuleSetting<Double> shieldAngleThreshold = scPrediction.add(createDoubleSetting()
            .name("shield-angle-threshold")
            .description("Maximum allowed facing angle in degrees for Shield-Breaker's shield-facing check. Default 90 means <= 90 passes.")
            .def(DEFAULT_SHIELD_ANGLE)
            .min(0.0)
            .max(180.0)
            .decimalPlaces(1)
            .build()
    );

    public final ModuleSetting<Integer> shieldAnglePrediction = scPrediction.add(createIntSetting()
            .name("shield-angle-prediction")
            .description("How many ticks ahead to predict the target's shield-facing angle.")
            .def(1)
            .min(0)
            .max(4)
            .build()
    );

    public final ModuleSetting<Integer> shieldBlockingPrediction = scPrediction.add(createIntSetting()
            .name("shield-blocking-prediction")
            .description("How many ticks of recent strict shield use to still treat as blocking.")
            .def(2)
            .min(0)
            .max(6)
            .build()
    );

    private final Map<UUID, ShieldSample> shieldSamples = new HashMap<>();
    private int tickCounter = 0;
    private SwitchSequence activeSequence = null;
    private int activeSequenceToken = 0;
    private boolean pendingChildSettingsSync = false;
    private boolean pendingSettingsScreenRefresh = false;

    public ShieldBreakerModule() {
        super("shield-breaker", "Swaps to a hotbar axe against blocking players in front of their shield, queues an optional shield-stun click, then switches back unless you manually take over the slot.");
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
    private void onMouseClick(MouseClickEvent e) {
        if (e.isCancelled() || e.getButton() != 0 || e.getAction() != ClickType.CLICK || !e.isScreenNull()) {
            return;
        }

        tryStartFromMouseClick();
    }

    @EventHandler
    private void onMouseScroll(MouseScrollEvent e) {
        if (shouldCancelOnManualSlotChange() && mc.screen == null && e.isVertical() && e.getDeltaY() != 0.0) {
            invalidateSequence();
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
                invalidateSequence();
                return;
            }
        }
    }

    @EventHandler
    private void onTickStart(ClientTickStartEvent e) {
        tickCounter++;
        updateShieldSamples();

        if (activeSequence == null) {
            return;
        }
        if (!canOperate()) {
            resetRuntimeState();
            return;
        }
        if (shouldCancelOnManualSlotChange() && InvUtils.selected() != activeSequence.axeSlot()) {
            invalidateSequence();
        }
    }

    private void tryStartFromMouseClick() {
        ShieldBreakContext context = getShieldBreakContext(getCurrentTarget());
        if (context == null) {
            return;
        }

        startSequence(context, false, null);
    }

    public boolean tryStartFromTriggerBot(Player target, TriggerBotModule.PendingTap deferredTap) {
        ShieldBreakContext context = getShieldBreakContext(target);
        if (context == null) {
            return false;
        }

        return startSequence(context, true, deferredTap);
    }

    private boolean startSequence(ShieldBreakContext context, boolean queueOpeningClick, TriggerBotModule.PendingTap deferredTap) {
        if (activeSequence != null) {
            return false;
        }

        InvUtils.select(context.axeSlot());

        int sequenceToken = ++activeSequenceToken;
        activeSequence = new SwitchSequence(sequenceToken, context.originalSlot(), context.axeSlot(), deferredTap);
        if (queueOpeningClick) {
            queueAttackClick();
        }

        if (shieldStun.getVal()) {
            double delaySeconds = shieldStunDelay.getRandomizedValue();
            scheduleSeconds(delaySeconds, sequenceToken, () -> executeShieldStunFollowUp(sequenceToken));
            return true;
        }

        notifyTriggerBotTap(activeSequence.deferredTap());
        scheduleSwitchBack(sequenceToken);
        return true;
    }

    private void executeShieldStunFollowUp(int sequenceToken) {
        if (!isSequenceValid(sequenceToken)) {
            return;
        }
        if (shouldCancelOnManualSlotChange() && InvUtils.selected() != activeSequence.axeSlot()) {
            invalidateSequence();
            return;
        }

        queueAttackClick();
        notifyTriggerBotTap(activeSequence.deferredTap());
        scheduleSwitchBack(sequenceToken);
    }

    private void scheduleSwitchBack(int sequenceToken) {
        if (!isSequenceValid(sequenceToken)) {
            return;
        }
        if (!switchBack.getVal()) {
            clearSequence();
            return;
        }

        double delaySeconds = switchBackDelay.getRandomizedValue();
        scheduleSeconds(delaySeconds, sequenceToken, () -> finalizeSwitchBack(sequenceToken));
    }

    private void finalizeSwitchBack(int sequenceToken) {
        if (!isSequenceValid(sequenceToken)) {
            return;
        }
        if (shouldCancelOnManualSlotChange() && InvUtils.selected() != activeSequence.axeSlot()) {
            invalidateSequence();
            return;
        }
        if (activeSequence.originalSlot() < 0 || activeSequence.originalSlot() > 8) {
            invalidateSequence();
            return;
        }

        InvUtils.select(activeSequence.originalSlot());
        clearSequence();
    }

    private ShieldBreakContext getShieldBreakContext(Player target) {
        if (!canOperate()
                || activeSequence != null
                || isStrictlyBlocking(mc.player)
                || isEating()) {
            return null;
        }
        if (!isHoldingAllowedItem()) {
            return null;
        }
        if (!isValidTarget(target) || !willLikelyHitShield(target)) {
            return null;
        }

        int axeSlot = findAxeHotbarSlot();
        if (axeSlot < 0) {
            return null;
        }

        int originalSlot = InvUtils.selected();
        if (originalSlot == axeSlot) {
            return null;
        }
        if (!passesChanceRoll(chance.getVal())) {
            return null;
        }

        return new ShieldBreakContext(target, originalSlot, axeSlot);
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

    private boolean canOperate() {
        return isEnabled()
                && PlayerUtils.valid()
                && mc.player != null
                && mc.level != null
                && mc.options != null
                && mc.screen == null;
    }

    private Player getCurrentTarget() {
        if (!(mc.hitResult instanceof EntityHitResult hit) || !(hit.getEntity() instanceof Player target)) {
            return null;
        }
        return target;
    }

    private boolean isValidTarget(Player target) {
        return target != null
                && target != mc.player
                && target.isAlive()
                && !target.isSpectator()
                && !EntityUtils.shouldCancelCcsAttack(target)
                && !isTeleportDragging(target);
    }

    private boolean isHoldingAllowedItem() {
        ItemStack stack = mc.player.getMainHandItem();
        if (stack.isEmpty()) {
            return false;
        }

        String descriptionId = stack.getItem().getDescriptionId().toLowerCase(Locale.ROOT);
        for (String token : allowedHeldItems.getVal().toLowerCase(Locale.ROOT).replace(" ", "").split(",")) {
            if (!token.isEmpty() && descriptionId.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private boolean isEating() {
        if (mc.player == null || !mc.player.isUsingItem()) {
            return false;
        }

        ItemUseAnimation useAnimation = mc.player.getUseItem().getUseAnimation();
        return useAnimation == ItemUseAnimation.EAT || useAnimation == ItemUseAnimation.DRINK;
    }

    private int findAxeHotbarSlot() {
        for (int slot = 0; slot <= 8; slot++) {
            if (isAxe(mc.player.getInventory().getItem(slot))) {
                return slot;
            }
        }
        return -1;
    }

    private boolean isAxe(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }

        String descriptionId = stack.getItem().getDescriptionId().toLowerCase(Locale.ROOT);
        return descriptionId.contains("_axe") && !descriptionId.contains("_pickaxe");
    }

    private boolean willLikelyHitShield(Player target) {
        ShieldSample sample = shieldSamples.get(target.getUUID());
        if (!passesBlockingConditional(target, sample)) {
            return false;
        }
        return passesAngleConditional(target, sample);
    }

    private boolean passesBlockingConditional(Player target, ShieldSample sample) {
        return isStrictlyBlocking(target) || isShieldBlockingPredicted(sample);
    }

    private boolean passesAngleConditional(Player target, ShieldSample sample) {
        return getPredictedFacingAngle(target, sample) <= shieldAngleThreshold.getVal();
    }

    private boolean isStrictlyBlocking(Player target) {
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
        if (target.getUseItemRemainingTicks() > 0
                && (target.getMainHandItem().is(Items.SHIELD) || target.getOffhandItem().is(Items.SHIELD))) {
            return true;
        }
        if (!target.isUsingItem()) {
            return false;
        }
        if (target.getUsedItemHand() != null) {
            ItemStack activeHandItem = target.getItemInHand(target.getUsedItemHand());
            return !activeHandItem.isEmpty() && activeHandItem.is(Items.SHIELD);
        }
        return false;
    }

    private boolean isShieldBlockingPredicted(ShieldSample sample) {
        int predictionTicks = shieldBlockingPrediction.getVal();
        return predictionTicks > 0
                && sample != null
                && sample.lastBlockingTick >= 0
                && tickCounter - sample.lastBlockingTick <= predictionTicks;
    }

    private double getPredictedFacingAngle(Player target, ShieldSample sample) {
        int ticksAhead = shieldAnglePrediction.getVal();
        Vec3 targetEye = target.getEyePosition().add(target.getDeltaMovement().scale(ticksAhead));
        Vec3 observerEye = mc.player.getEyePosition().add(mc.player.getDeltaMovement().scale(ticksAhead));
        Vec3 look = getPredictedLookVector(target, sample);
        return getFacingAngleToObserver(look, targetEye, observerEye);
    }

    private Vec3 getPredictedLookVector(Player target, ShieldSample sample) {
        if (sample == null || !sample.initialized) {
            return target.getViewVector(1.0F).normalize();
        }

        int ticksAhead = shieldAnglePrediction.getVal();
        float yawDelta = sample.hasPrevious ? Mth.wrapDegrees(sample.yaw - sample.previousYaw) : 0.0F;
        float pitchDelta = sample.hasPrevious ? sample.pitch - sample.previousPitch : 0.0F;
        float predictedYaw = sample.yaw + Mth.clamp(yawDelta * ticksAhead, -45.0F, 45.0F);
        float predictedPitch = Mth.clamp(sample.pitch + pitchDelta * ticksAhead, -90.0F, 90.0F);
        return Vec3.directionFromRotation(predictedPitch, predictedYaw).normalize();
    }

    private double getFacingAngleToObserver(Vec3 targetLook, Vec3 targetEye, Vec3 observerEye) {
        if (targetLook == null || targetEye == null || observerEye == null) {
            return 0.0;
        }

        Vec3 horizontalLook = new Vec3(targetLook.x, 0.0, targetLook.z);
        Vec3 horizontalToObserver = new Vec3(observerEye.x - targetEye.x, 0.0, observerEye.z - targetEye.z);
        if (horizontalLook.lengthSqr() <= 1.0E-6 || horizontalToObserver.lengthSqr() <= 1.0E-6) {
            return 0.0;
        }

        double dot = horizontalLook.normalize().dot(horizontalToObserver.normalize());
        dot = Mth.clamp(dot, -1.0, 1.0);
        return Math.toDegrees(Math.acos(dot));
    }

    private void updateShieldSamples() {
        if (mc.level == null) {
            shieldSamples.clear();
            return;
        }

        for (Player player : mc.level.players()) {
            if (player == null || player == mc.player) {
                continue;
            }

            shieldSamples.computeIfAbsent(player.getUUID(), id -> new ShieldSample()).update(player, tickCounter);
        }

        Iterator<Map.Entry<UUID, ShieldSample>> iterator = shieldSamples.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, ShieldSample> entry = iterator.next();
            if (tickCounter - entry.getValue().lastSeenTick > 10) {
                iterator.remove();
            }
        }
    }

    private boolean isTeleportDragging(Player player) {
        ShieldSample sample = shieldSamples.get(player.getUUID());
        return sample != null && sample.recentTeleportTicks > 0;
    }

    private void queueAttackClick() {
        AccessorKeyMapping keyAttack = (AccessorKeyMapping) mc.options.keyAttack;
        keyAttack.loopholeEssentials$setClickCount(keyAttack.loopholeEssentials$getClickCount() + 1);
    }

    private void notifyTriggerBotTap(TriggerBotModule.PendingTap deferredTap) {
        if (deferredTap == null) {
            return;
        }

        Module.acceptFor(TriggerBotModule.class, module -> module.startDeferredTap(deferredTap));
    }

    private void scheduleSeconds(double seconds, int sequenceToken, Runnable action) {
        long delayMs = Math.max(0L, Math.round(seconds * 1000.0));
        system.scheduler.runDelayedTask(() -> mc.execute(() -> {
            if (isSequenceValid(sequenceToken)) {
                action.run();
            }
        }), delayMs);
    }

    private boolean isSequenceValid(int sequenceToken) {
        return activeSequence != null && activeSequence.token() == sequenceToken;
    }

    private void invalidateSequence() {
        clearSequence();
    }

    private void clearSequence() {
        activeSequence = null;
        activeSequenceToken++;
    }

    private void resetRuntimeState() {
        if (activeSequence != null
                && switchBack.getVal()
                && InvUtils.selected() == activeSequence.axeSlot()
                && activeSequence.originalSlot() >= 0
                && activeSequence.originalSlot() <= 8) {
            InvUtils.select(activeSequence.originalSlot());
        }

        shieldSamples.clear();
        tickCounter = 0;
        clearSequence();
    }

    private boolean shouldCancelOnManualSlotChange() {
        return switchBack.getVal() && cancelSwitchBackOnManualSlotChange.getVal();
    }

    private void configureChildSettings() {
        shieldStun.setChangeAction(setting -> scheduleChildSettingsSync());
        switchBack.setChangeAction(setting -> scheduleChildSettingsSync());
    }

    private void syncVisibleSettings() {
        List<ModuleSetting<?>> timingSettings = scTiming.getSettings();
        timingSettings.clear();
        timingSettings.add(shieldStun);
        if (shieldStun.getVal()) {
            timingSettings.add(shieldStunDelay);
        }
        timingSettings.add(switchBack);
        if (switchBack.getVal()) {
            timingSettings.add(switchBackDelay);
            timingSettings.add(cancelSwitchBackOnManualSlotChange);
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
                shieldStunDelay,
                switchBackDelay,
                cancelSwitchBackOnManualSlotChange
        );
    }

    private record ShieldBreakContext(Player target, int originalSlot, int axeSlot) {
    }

    private record SwitchSequence(int token, int originalSlot, int axeSlot, TriggerBotModule.PendingTap deferredTap) {
    }

    private final class ShieldSample {

        private float previousYaw;
        private float previousPitch;
        private float yaw;
        private float pitch;
        private boolean initialized;
        private boolean hasPrevious;
        private int lastSeenTick;
        private int lastBlockingTick = -1;
        private Vec3 lastPos;
        private int recentTeleportTicks = 0;

        private void update(Player player, int currentTick) {
            Vec3 currentPos = player.position();
            if (initialized) {
                previousYaw = yaw;
                previousPitch = pitch;
                hasPrevious = true;

                Vec3 delta = currentPos.subtract(lastPos);
                if (delta.lengthSqr() >= TELEPORT_DISTANCE_SQ || Math.abs(delta.y) >= TELEPORT_VERTICAL_DELTA) {
                    recentTeleportTicks = TELEPORT_GRACE_TICKS;
                }
                else if (recentTeleportTicks > 0) {
                    recentTeleportTicks--;
                }
            }
            else {
                recentTeleportTicks = 0;
            }

            yaw = player.getYRot();
            pitch = player.getXRot();
            lastPos = currentPos;
            if (isStrictlyBlocking(player)) {
                lastBlockingTick = currentTick;
            }
            initialized = true;
            lastSeenTick = currentTick;
        }
    }
}
