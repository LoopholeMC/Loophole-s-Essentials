package com.loophole.essentials.module.modules;

import com.loophole.essentials.mixin.AccessorKeyMapping;
import com.loophole.essentials.module.LoopholeListenerModule;
import com.loophole.essentials.module.PersistentSettingProvider;
import io.github.itzispyder.clickcrystals.events.EventHandler;
import io.github.itzispyder.clickcrystals.events.events.networking.GameLeaveEvent;
import io.github.itzispyder.clickcrystals.events.events.world.RenderWorldEvent;
import io.github.itzispyder.clickcrystals.gui.screens.ModuleEditScreen;
import io.github.itzispyder.clickcrystals.modules.ModuleSetting;
import io.github.itzispyder.clickcrystals.modules.settings.SettingSection;
import io.github.itzispyder.clickcrystals.util.MathUtils;
import io.github.itzispyder.clickcrystals.util.minecraft.EntityUtils;
import io.github.itzispyder.clickcrystals.util.minecraft.PlayerUtils;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class AimAssistModule extends LoopholeListenerModule implements PersistentSettingProvider {

    private static final double FRAME_DELTA_CAP_SECONDS = 0.050;
    private static final double MIN_VECTOR_LENGTH_SQ = 1.0E-6;
    private static final double MIN_STOP_THRESHOLD = 1.0E-4;
    private static final double HITBOX_STOP_THRESHOLD_MIN = 0.020;
    private static final double HITBOX_STOP_THRESHOLD_MAX = 0.350;

    private final SettingSection scGeneral = getGeneralSection();
    private final SettingSection scTargeting = createSettingSection("Targeting");
    private final SettingSection scItemFilter = createSettingSection("Item Filter");
    private final SettingSection scAimPoint = createSettingSection("Aim Point");
    private final SettingSection scAxis = createSettingSection("Axis");
    private final SettingSection scRotation = createSettingSection("Rotation");
    private final SettingSection scPrediction = createSettingSection("Prediction");
    private final SettingSection scStickiness = createSettingSection("Stickiness");
    private final SettingSection scAntiAimlock = createSettingSection("Anti-Aimlock");

    public final ModuleSetting<Boolean> targetPlayers = scGeneral.add(createBoolSetting()
            .name("target-players")
            .description("Allow Aim Assist to evaluate player targets.")
            .def(true)
            .build()
    );

    public final ModuleSetting<Boolean> targetMobs = scGeneral.add(createBoolSetting()
            .name("target-mobs")
            .description("Allow Aim Assist to evaluate non-player mob targets.")
            .def(false)
            .build()
    );

    public final ModuleSetting<Boolean> ignoreInvisible = scGeneral.add(createBoolSetting()
            .name("ignore-invisible")
            .description("Ignore invisible targets instead of assisting toward them.")
            .def(true)
            .build()
    );

    public final ModuleSetting<Boolean> throughWalls = scGeneral.add(createBoolSetting()
            .name("through-walls")
            .description("Allow assistance through walls. Disable this to require line of sight.")
            .def(false)
            .build()
    );

    public final ModuleSetting<Boolean> requireClick = scGeneral.add(createBoolSetting()
            .name("require-click")
            .description("Only assist while the normal attack input is physically held or has a pending click.")
            .def(true)
            .build()
    );

    public final ModuleSetting<Boolean> stopWhileBreakingBlocks = scGeneral.add(createBoolSetting()
            .name("stop-while-breaking-blocks")
            .description("Suspend Aim Assist while you are actively mining so breaking blocks keeps full manual control.")
            .def(true)
            .build()
    );

    public final ModuleSetting<Double> range = scGeneral.add(createDoubleSetting()
            .name("range")
            .description("Maximum target distance in blocks that Aim Assist can consider.")
            .def(4.5)
            .min(1.0)
            .max(6.0)
            .decimalPlaces(2)
            .build()
    );

    public final ModuleSetting<Double> fov = scGeneral.add(createDoubleSetting()
            .name("fov")
            .description("Maximum view angle that Aim Assist can search inside.")
            .def(75.0)
            .min(1.0)
            .max(180.0)
            .decimalPlaces(1)
            .build()
    );

    public final ModuleSetting<TargetSelectionMode> targetSelectionMode = scTargeting.add(createEnumSetting(TargetSelectionMode.class)
            .name("target-selection-mode")
            .description("How Aim Assist chooses between multiple valid targets.")
            .def(TargetSelectionMode.ClosestToCrosshair)
            .build()
    );

    public final ModuleSetting<Boolean> useItemFilter = scItemFilter.add(createBoolSetting()
            .name("use-item-filter")
            .description("Gate Aim Assist with held-item description fragments instead of allowing every main-hand item.")
            .def(false)
            .build()
    );

    public final ModuleSetting<String> allowedHeldItems = scItemFilter.add(createStringSetting()
            .name("allowed-held-items")
            .description("Comma-separated main-hand item-name fragments such as sword,_axe,mace.")
            .def("sword,_axe,mace")
            .build()
    );

    public final ModuleSetting<Boolean> blacklistMode = scItemFilter.add(createBoolSetting()
            .name("blacklist-mode")
            .description("Invert the held-item filter so listed fragments disable Aim Assist and every other item is allowed.")
            .def(false)
            .build()
    );

    public final ModuleSetting<AimPointMode> aimPointMode = scAimPoint.add(createEnumSetting(AimPointMode.class)
            .name("aim-point-mode")
            .description("Primary target point inside the hitbox that Aim Assist pulls toward.")
            .def(AimPointMode.Neck)
            .build()
    );

    public final ModuleSetting<Boolean> dynamicAimPoint = scAimPoint.add(createBoolSetting()
            .name("dynamic-aim-point")
            .description("Subtly shift the aim point inside the target's hitbox based on movement and distance.")
            .def(true)
            .build()
    );

    public final ModuleSetting<Boolean> randomizeAimPoint = scAimPoint.add(createBoolSetting()
            .name("randomize-aim-point")
            .description("Apply gentle, smooth aim-point jitter inside the target's hitbox so tracking never feels perfectly robotic.")
            .def(false)
            .build()
    );

    public final ModuleSetting<Double> randomizationRange = scAimPoint.add(createDoubleSetting()
            .name("randomization-range")
            .description("Maximum aim-point jitter range inside the hitbox as a percentage.")
            .def(2.0)
            .min(0.0)
            .max(10.0)
            .decimalPlaces(1)
            .build()
    );

    public final ModuleSetting<Double> customOffsetX = scAimPoint.add(createDoubleSetting()
            .name("custom-offset-x")
            .description("Additional right-left target-space offset applied to the selected aim point.")
            .def(0.0)
            .min(-0.5)
            .max(0.5)
            .decimalPlaces(3)
            .build()
    );

    public final ModuleSetting<Double> customOffsetY = scAimPoint.add(createDoubleSetting()
            .name("custom-offset-y")
            .description("Additional vertical offset applied to the selected aim point.")
            .def(0.0)
            .min(-0.5)
            .max(0.5)
            .decimalPlaces(3)
            .build()
    );

    public final ModuleSetting<Double> customOffsetZ = scAimPoint.add(createDoubleSetting()
            .name("custom-offset-z")
            .description("Additional forward-back target-space offset applied to the selected aim point.")
            .def(0.0)
            .min(-0.5)
            .max(0.5)
            .decimalPlaces(3)
            .build()
    );

    public final ModuleSetting<AxisMode> axisMode = scAxis.add(createEnumSetting(AxisMode.class)
            .name("axis-mode")
            .description("Choose whether Aim Assist can modify yaw, pitch, or both.")
            .def(AxisMode.HorizontalAndVertical)
            .build()
    );

    public final ModuleSetting<Double> strength = scAxis.add(createDoubleSetting()
            .name("strength")
            .description("Shared assist strength used on any axis that is not using its own separate strength override.")
            .def(0.13)
            .min(0.01)
            .max(1.0)
            .decimalPlaces(2)
            .build()
    );

    public final ModuleSetting<Boolean> separateHorizontalStrength = scAxis.add(createBoolSetting()
            .name("separate-horizontal-strength")
            .description("Use an independent horizontal yaw strength instead of the shared strength value.")
            .def(true)
            .build()
    );

    public final ModuleSetting<Double> horizontalStrength = scAxis.add(createDoubleSetting()
            .name("horizontal-strength")
            .description("Yaw assist strength. Higher values turn faster but still remain interpolated and soft.")
            .def(0.14)
            .min(0.01)
            .max(1.0)
            .decimalPlaces(2)
            .build()
    );

    public final ModuleSetting<Boolean> separateVerticalStrength = scAxis.add(createBoolSetting()
            .name("separate-vertical-strength")
            .description("Use an independent vertical pitch strength instead of the shared strength value.")
            .def(true)
            .build()
    );

    public final ModuleSetting<Double> verticalStrength = scAxis.add(createDoubleSetting()
            .name("vertical-strength")
            .description("Pitch assist strength. Higher values pull up and down more quickly.")
            .def(0.12)
            .min(0.01)
            .max(1.0)
            .decimalPlaces(2)
            .build()
    );

    public final ModuleSetting<Integer> smooth = scAxis.add(createIntSetting()
            .name("smooth")
            .description("Shared smoothing used on any axis that is not using its own separate smooth override.")
            .def(9)
            .min(1)
            .max(20)
            .build()
    );

    public final ModuleSetting<Boolean> separateHorizontalSmooth = scAxis.add(createBoolSetting()
            .name("separate-horizontal-smooth")
            .description("Use an independent yaw smoothing value instead of the shared smooth value.")
            .def(true)
            .build()
    );

    public final ModuleSetting<Integer> horizontalSmooth = scAxis.add(createIntSetting()
            .name("horizontal-smooth")
            .description("Yaw smoothing. Higher values make horizontal help softer and more delayed.")
            .def(8)
            .min(1)
            .max(20)
            .build()
    );

    public final ModuleSetting<Boolean> separateVerticalSmooth = scAxis.add(createBoolSetting()
            .name("separate-vertical-smooth")
            .description("Use an independent pitch smoothing value instead of the shared smooth value.")
            .def(true)
            .build()
    );

    public final ModuleSetting<Integer> verticalSmooth = scAxis.add(createIntSetting()
            .name("vertical-smooth")
            .description("Pitch smoothing. Higher values make vertical help softer and more delayed.")
            .def(10)
            .min(1)
            .max(20)
            .build()
    );

    public final ModuleSetting<AccelerationCurve> accelerationCurve = scRotation.add(createEnumSetting(AccelerationCurve.class)
            .name("acceleration-curve")
            .description("Shape the rotation ramp so larger misses can accelerate differently from small misses.")
            .def(AccelerationCurve.Dynamic)
            .build()
    );

    public final ModuleSetting<Integer> randomization = scRotation.add(createIntSetting()
            .name("randomization")
            .description("Amount of smooth micro-variance applied to offsets and speed so tracking does not feel perfectly machine-like.")
            .def(20)
            .min(0)
            .max(100)
            .build()
    );

    public final ModuleSetting<Boolean> variableSpeed = scRotation.add(createBoolSetting()
            .name("variable-speed")
            .description("Continuously vary assist speed a little instead of moving with a perfectly consistent cadence.")
            .def(true)
            .build()
    );

    public final ModuleSetting<Boolean> humanizeRotations = scRotation.add(createBoolSetting()
            .name("humanize-rotations")
            .description("Apply smooth micro-biases so the module behaves more like subtle controller aim assistance than precise lock-on tracking.")
            .def(true)
            .build()
    );

    public final ModuleSetting<Boolean> predictionEnabled = scPrediction.add(createBoolSetting()
            .name("prediction-enabled")
            .description("Lead moving targets slightly instead of aiming only at their current interpolated position.")
            .def(true)
            .build()
    );

    public final ModuleSetting<Integer> predictionAmount = scPrediction.add(createIntSetting()
            .name("prediction-amount")
            .description("How strongly movement prediction is applied.")
            .def(35)
            .min(0)
            .max(100)
            .build()
    );

    public final ModuleSetting<Boolean> horizontalPrediction = scPrediction.add(createBoolSetting()
            .name("horizontal-prediction")
            .description("Apply prediction on the horizontal plane.")
            .def(true)
            .build()
    );

    public final ModuleSetting<Boolean> verticalPrediction = scPrediction.add(createBoolSetting()
            .name("vertical-prediction")
            .description("Apply prediction on the vertical axis.")
            .def(true)
            .build()
    );

    public final ModuleSetting<Boolean> pingCompensation = scPrediction.add(createBoolSetting()
            .name("ping-compensation")
            .description("Blend a small portion of target latency into the prediction amount for more natural leading on players.")
            .def(true)
            .build()
    );

    public final ModuleSetting<Boolean> targetStickinessEnabled = scStickiness.add(createBoolSetting()
            .name("target-stickiness-enabled")
            .description("Temporarily prefer the current target so Aim Assist does not bounce between nearby entities too easily.")
            .def(true)
            .build()
    );

    public final ModuleSetting<Integer> stickTime = scStickiness.add(createIntSetting()
            .name("stick-time")
            .description("How long to prefer the current target before freely switching again.")
            .def(250)
            .min(0)
            .max(1000)
            .build()
    );

    public final ModuleSetting<Integer> switchThreshold = scStickiness.add(createIntSetting()
            .name("switch-threshold")
            .description("Required percent score improvement before Aim Assist abandons a sticky target early.")
            .def(20)
            .min(0)
            .max(100)
            .build()
    );

    public final ModuleSetting<StopMode> stopMode = scAntiAimlock.add(createEnumSetting(StopMode.class)
            .name("stop-mode")
            .description("Choose how the anti-aimlock stop zone is measured.")
            .def(StopMode.Angle)
            .build()
    );

    public final ModuleSetting<StopPointMode> stopPointMode = scAntiAimlock.add(createEnumSetting(StopPointMode.class)
            .name("stop-point-mode")
            .description("Select the point inside the target hitbox that causes Aim Assist to back off when reached.")
            .def(StopPointMode.Neck)
            .build()
    );

    public final ModuleSetting<Double> customStopPointPercentage = scAntiAimlock.add(createDoubleSetting()
            .name("custom-stop-point-percentage")
            .description("Custom vertical stop point as a hitbox percentage where 0.10 is feet and 0.92 is head.")
            .def(0.78)
            .min(0.0)
            .max(1.0)
            .decimalPlaces(2)
            .build()
    );

    public final ModuleSetting<Double> stopRadius = scAntiAimlock.add(createDoubleSetting()
            .name("stop-radius")
            .description("Angular stop radius for angle or hitbox-percentage anti-aimlock modes.")
            .def(1.0)
            .min(0.1)
            .max(5.0)
            .decimalPlaces(2)
            .build()
    );

    public final ModuleSetting<Integer> screenRadius = scAntiAimlock.add(createIntSetting()
            .name("screen-radius")
            .description("Screen-space stop radius in pixels for the screen-radius anti-aimlock mode.")
            .def(8)
            .min(1)
            .max(25)
            .build()
    );

    public final ModuleSetting<Boolean> dynamicRadius = scAntiAimlock.add(createBoolSetting()
            .name("dynamic-radius")
            .description("Scale the stop zone with distance so close and far targets do not use an identical deadzone.")
            .def(true)
            .build()
    );

    public final ModuleSetting<Integer> distanceScaling = scAntiAimlock.add(createIntSetting()
            .name("distance-scaling")
            .description("How strongly target distance widens the stop zone when dynamic-radius is enabled.")
            .def(35)
            .min(0)
            .max(100)
            .build()
    );

    public final ModuleSetting<Boolean> fadeNearTarget = scAntiAimlock.add(createBoolSetting()
            .name("fade-near-target")
            .description("Gradually fade assistance toward zero inside the stop zone instead of cutting off immediately.")
            .def(true)
            .build()
    );

    public final ModuleSetting<Integer> fadeStrength = scAntiAimlock.add(createIntSetting()
            .name("fade-strength")
            .description("How aggressively assistance fades out once the crosshair enters the stop zone.")
            .def(80)
            .min(0)
            .max(100)
            .build()
    );

    private long lastAssistTimeNs = -1L;
    private UUID currentTargetId = null;
    private long currentTargetAcquiredAtMs = 0L;
    private double currentTargetSeed = ThreadLocalRandom.current().nextDouble(0.0, Math.PI * 2.0);
    private boolean pendingChildSettingsSync = false;
    private boolean pendingSettingsScreenRefresh = false;

    public AimAssistModule() {
        super("aim-assist", "Softly guides your aim toward nearby entities like controller aim assist without snapping, locking, or perfectly tracking.");
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
    private void onRenderWorld(RenderWorldEvent e) {
        if (!canAssist()) {
            resetRuntimeState();
            return;
        }

        long nowNs = System.nanoTime();
        long nowMs = System.currentTimeMillis();
        double deltaSeconds = getFrameDeltaSeconds(nowNs);
        lastAssistTimeNs = nowNs;
        if (deltaSeconds <= 0.0) {
            return;
        }

        float tickDelta = e.getTickCounter().getGameTimeDeltaPartialTick(true);
        AimCandidate target = selectTarget(tickDelta, nowMs);
        if (target == null) {
            return;
        }

        applyAimAssist(target, deltaSeconds, nowMs);
    }

    private boolean canAssist() {
        return isEnabled()
                && PlayerUtils.valid()
                && mc.player != null
                && mc.level != null
                && mc.options != null
                && mc.screen == null
                && !isBreakingBlock()
                && passesItemFilter()
                && passesClickRequirement();
    }

    private boolean isBreakingBlock() {
        return stopWhileBreakingBlocks.getVal()
                && mc.gameMode != null
                && mc.gameMode.isDestroying();
    }

    private boolean passesClickRequirement() {
        if (!requireClick.getVal()) {
            return true;
        }
        AccessorKeyMapping keyAttack = (AccessorKeyMapping) mc.options.keyAttack;
        return mc.options.keyAttack.isDown() || keyAttack.loopholeEssentials$getClickCount() > 0;
    }

    private boolean passesItemFilter() {
        if (!useItemFilter.getVal()) {
            return true;
        }

        ItemStack stack = mc.player.getMainHandItem();
        if (stack == null || stack.isEmpty()) {
            return blacklistMode.getVal();
        }

        boolean listed = matchesHeldItemFilter(stack);
        return blacklistMode.getVal() ? !listed : listed;
    }

    private boolean matchesHeldItemFilter(ItemStack stack) {
        String descriptionId = stack.getItem().getDescriptionId().toLowerCase(Locale.ROOT);
        for (String token : getAllowedHeldItemTokens()) {
            if (token.isEmpty()) {
                continue;
            }
            if (descriptionId.contains(token)) {
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
            if (!normalized.isEmpty()) {
                tokens.add(normalized);
            }
        }
        return tokens;
    }

    private double getFrameDeltaSeconds(long nowNs) {
        if (lastAssistTimeNs < 0L) {
            return 0.0;
        }
        return Math.min((nowNs - lastAssistTimeNs) / 1_000_000_000.0, FRAME_DELTA_CAP_SECONDS);
    }

    private AimCandidate selectTarget(float tickDelta, long nowMs) {
        List<AimCandidate> candidates = collectCandidates(tickDelta, nowMs);
        if (candidates.isEmpty()) {
            clearTargetState();
            return null;
        }

        AimCandidate best = candidates.stream()
                .min((a, b) -> Double.compare(a.selectionScore(), b.selectionScore()))
                .orElse(null);
        if (best == null) {
            clearTargetState();
            return null;
        }

        if (!targetStickinessEnabled.getVal()) {
            updateCurrentTarget(best, nowMs);
            return best;
        }

        AimCandidate stickyCandidate = findCandidate(candidates, currentTargetId);
        if (stickyCandidate == null) {
            updateCurrentTarget(best, nowMs);
            return best;
        }
        if (best.target().getUUID().equals(currentTargetId)) {
            return stickyCandidate;
        }

        long stickyUntil = currentTargetAcquiredAtMs + stickTime.getVal();
        if (nowMs < stickyUntil) {
            double improvementRatio = getImprovementRatio(stickyCandidate.selectionScore(), best.selectionScore());
            if (improvementRatio < switchThreshold.getVal() / 100.0) {
                return stickyCandidate;
            }
        }

        updateCurrentTarget(best, nowMs);
        return best;
    }

    private List<AimCandidate> collectCandidates(float tickDelta, long nowMs) {
        List<AimCandidate> candidates = new ArrayList<>();
        double maxRange = range.getVal();

        if (targetPlayers.getVal()) {
            for (Player player : mc.level.players()) {
                AimCandidate candidate = createCandidate(player, tickDelta, nowMs);
                if (candidate != null) {
                    candidates.add(candidate);
                }
            }
        }

        if (targetMobs.getVal()) {
            AABB searchBox = mc.player.getBoundingBox().inflate(maxRange + 1.0);
            for (Entity entity : mc.level.getEntities((Entity) null, searchBox, ent -> ent instanceof Mob)) {
                if (entity instanceof LivingEntity living) {
                    AimCandidate candidate = createCandidate(living, tickDelta, nowMs);
                    if (candidate != null) {
                        candidates.add(candidate);
                    }
                }
            }
        }

        return candidates;
    }

    private AimCandidate createCandidate(LivingEntity target, float tickDelta, long nowMs) {
        if (!isValidTarget(target)) {
            return null;
        }

        Vec3 eyes = mc.player.getEyePosition(tickDelta);
        Vec3 aimPoint = getTargetPoint(target, tickDelta, nowMs, false);
        Vec3 stopPoint = getTargetPoint(target, tickDelta, nowMs, true);
        Vec3 toAim = aimPoint.subtract(eyes);
        Vec3 toStop = stopPoint.subtract(eyes);
        if (toAim.lengthSqr() <= MIN_VECTOR_LENGTH_SQ || toStop.lengthSqr() <= MIN_VECTOR_LENGTH_SQ) {
            return null;
        }

        double distance = mc.player.distanceTo(target);
        float[] aimRotation = MathUtils.toPolar(toAim.x, toAim.y, toAim.z);
        float[] stopRotation = MathUtils.toPolar(toStop.x, toStop.y, toStop.z);
        double currentYaw = mc.player.getYRot();
        double currentPitch = mc.player.getXRot();

        double yawDiff = isHorizontalActive() ? Mth.wrapDegrees((float) (aimRotation[1] - currentYaw)) : 0.0;
        double pitchDiff = isVerticalActive() ? Mth.wrapDegrees((float) (aimRotation[0] - currentPitch)) : 0.0;
        double stopYawDiff = isHorizontalActive() ? Mth.wrapDegrees((float) (stopRotation[1] - currentYaw)) : 0.0;
        double stopPitchDiff = isVerticalActive() ? Mth.wrapDegrees((float) (stopRotation[0] - currentPitch)) : 0.0;

        double angularDistance = getAngularDistance(yawDiff, pitchDiff);
        if (angularDistance > fov.getVal()) {
            return null;
        }

        double screenDistance = getScreenDistance(yawDiff, pitchDiff);
        double stopAngleMetric = getAngularDistance(stopYawDiff, stopPitchDiff);
        double stopScreenMetric = getScreenDistance(stopYawDiff, stopPitchDiff);
        double stopHitboxMetric = getHitboxStopMetric(target, stopPoint, eyes);
        double selectionScore = getSelectionScore(target, angularDistance, screenDistance, distance);

        return new AimCandidate(
                target,
                aimPoint,
                stopPoint,
                distance,
                yawDiff,
                pitchDiff,
                angularDistance,
                stopAngleMetric,
                stopScreenMetric,
                stopHitboxMetric,
                selectionScore
        );
    }

    private boolean isValidTarget(LivingEntity target) {
        if (target == null
                || target == mc.player
                || !target.isAlive()
                || target.isSpectator()) {
            return false;
        }
        if (ignoreInvisible.getVal() && target.isInvisible()) {
            return false;
        }
        if (!throughWalls.getVal() && !mc.player.hasLineOfSight(target)) {
            return false;
        }

        double maxRange = range.getVal();
        if (mc.player.distanceToSqr(target) > maxRange * maxRange) {
            return false;
        }

        if (target instanceof Player player) {
            return PlayerUtils.playerValid(player) && !EntityUtils.shouldCancelCcsAttack(player);
        }
        return target instanceof Mob;
    }

    private Vec3 getTargetPoint(LivingEntity target, float tickDelta, long nowMs, boolean stopPoint) {
        Vec3 base = MathUtils.lerpEntityPosVec(target, tickDelta);
        Vec3 predicted = applyPrediction(target, base);
        double hitboxPercent = stopPoint
                ? resolveStopPointPercentage()
                : resolveAimPointPercentage(target, predicted, nowMs);
        hitboxPercent = Mth.clamp(hitboxPercent, 0.02, 0.98);

        Vec3 point = predicted.add(0.0, target.getBbHeight() * hitboxPercent, 0.0);
        if (stopPoint) {
            return point;
        }

        Vec3 forward = Vec3.directionFromRotation(0.0F, target.getYRot()).normalize();
        Vec3 right = new Vec3(-forward.z, 0.0, forward.x).normalize();
        point = point.add(right.scale(customOffsetX.getVal()))
                .add(0.0, customOffsetY.getVal(), 0.0)
                .add(forward.scale(customOffsetZ.getVal()));

        if (randomizeAimPoint.getVal()) {
            double rangeFactor = randomizationRange.getVal() / 100.0;
            double widthFactor = target.getBbWidth() * 0.45 * rangeFactor;
            double heightFactor = target.getBbHeight() * rangeFactor;
            double seed = getTargetSeed(target);
            double jitterX = Math.sin(nowMs * 0.0065 + seed * 0.83) * widthFactor;
            double jitterY = Math.cos(nowMs * 0.0051 + seed * 1.17) * heightFactor;
            double jitterZ = Math.sin(nowMs * 0.0073 + seed * 1.61) * widthFactor;
            point = point.add(right.scale(jitterX))
                    .add(0.0, jitterY, 0.0)
                    .add(forward.scale(jitterZ));
        }

        return point;
    }

    private Vec3 applyPrediction(LivingEntity target, Vec3 base) {
        if (!predictionEnabled.getVal()) {
            return base;
        }

        double amountFactor = predictionAmount.getVal() / 100.0;
        if (amountFactor <= 0.0) {
            return base;
        }

        double predictedTicks = amountFactor * 1.50;
        if (pingCompensation.getVal()) {
            predictedTicks += amountFactor * Mth.clamp(getTargetLatencyMs(target) / 100.0, 0.0, 3.0) * 0.25;
        }

        Vec3 delta = target.getDeltaMovement().scale(predictedTicks);
        if (!horizontalPrediction.getVal()) {
            delta = new Vec3(0.0, delta.y, 0.0);
        }
        if (!verticalPrediction.getVal()) {
            delta = new Vec3(delta.x, 0.0, delta.z);
        }
        return base.add(delta);
    }

    private double resolveAimPointPercentage(LivingEntity target, Vec3 predictedBase, long nowMs) {
        double percent = aimPointMode.getVal().fraction;
        if (dynamicAimPoint.getVal()) {
            double distanceRatio = Mth.clamp(mc.player.distanceTo(target) / Math.max(range.getVal(), 0.1), 0.0, 1.0);
            double verticalMotion = Mth.clamp(target.getDeltaMovement().y * 0.30, -0.08, 0.08);
            double heightDifference = Mth.clamp((predictedBase.y - mc.player.getY()) * 0.02, -0.05, 0.05);
            percent += (0.5 - distanceRatio) * 0.04;
            percent += verticalMotion;
            percent += heightDifference;
        }
        if (humanizeRotations.getVal()) {
            double randomFactor = randomization.getVal() / 100.0;
            double seed = getTargetSeed(target);
            percent += Math.sin(nowMs * 0.0038 + seed * 0.57) * 0.015 * randomFactor;
        }
        return percent;
    }

    private double resolveStopPointPercentage() {
        return switch (stopPointMode.getVal()) {
            case Feet -> StopPointMode.Feet.fraction;
            case Legs -> StopPointMode.Legs.fraction;
            case Chest -> StopPointMode.Chest.fraction;
            case Neck -> StopPointMode.Neck.fraction;
            case Head -> StopPointMode.Head.fraction;
            case CustomPercentage -> customStopPointPercentage.getVal();
        };
    }

    private double getSelectionScore(LivingEntity target, double angularDistance, double screenDistance, double distance) {
        double health = target.getHealth() + target.getAbsorptionAmount();
        double distanceRatio = Mth.clamp(distance / Math.max(range.getVal(), 0.1), 0.0, 1.0);
        double angleRatio = Mth.clamp(angularDistance / Math.max(fov.getVal(), 1.0), 0.0, 1.0);

        return switch (targetSelectionMode.getVal()) {
            case ClosestToCrosshair -> screenDistance;
            case ClosestDistance -> distance;
            case LowestHealth -> health + angularDistance * 0.01 + distance * 0.001;
            case HighestHealth -> (200.0 - health) + angularDistance * 0.01 + distance * 0.001;
            case SmallestAngle -> angularDistance;
            case SmartPriority -> angleRatio * 0.70 + distanceRatio * 0.30;
        };
    }

    private double getScreenDistance(double yawDiff, double pitchDiff) {
        int halfWidth = Math.max(1, mc.getWindow().getGuiScaledWidth() / 2);
        int halfHeight = Math.max(1, mc.getWindow().getGuiScaledHeight() / 2);
        double yawPixels = Math.abs(yawDiff) / Math.max(fov.getVal(), 1.0) * halfWidth;
        double pitchPixels = Math.abs(pitchDiff) / Math.max(fov.getVal(), 1.0) * halfHeight;
        return Math.hypot(yawPixels, pitchPixels);
    }

    private double getHitboxStopMetric(LivingEntity target, Vec3 stopPoint, Vec3 eyes) {
        Vec3 look = mc.player.getViewVector(1.0F).normalize();
        Vec3 toStop = stopPoint.subtract(eyes);
        double alongRay = Mth.clamp(toStop.dot(look), 0.0, Math.max(toStop.length(), 0.001));
        Vec3 nearestPoint = eyes.add(look.scale(alongRay));

        AABB box = target.getBoundingBox();
        Vec3 clampedPoint = new Vec3(
                Mth.clamp(nearestPoint.x, box.minX, box.maxX),
                Mth.clamp(nearestPoint.y, box.minY, box.maxY),
                Mth.clamp(nearestPoint.z, box.minZ, box.maxZ)
        );

        double boxWidth = Math.max(box.getXsize(), 1.0E-4);
        double boxHeight = Math.max(box.getYsize(), 1.0E-4);
        double boxDepth = Math.max(box.getZsize(), 1.0E-4);

        double pointX = (clampedPoint.x - box.minX) / boxWidth;
        double pointY = (clampedPoint.y - box.minY) / boxHeight;
        double pointZ = (clampedPoint.z - box.minZ) / boxDepth;
        double stopX = (stopPoint.x - box.minX) / boxWidth;
        double stopY = (stopPoint.y - box.minY) / boxHeight;
        double stopZ = (stopPoint.z - box.minZ) / boxDepth;

        return Math.sqrt(
                Mth.square(pointX - stopX)
                        + Mth.square(pointY - stopY)
                        + Mth.square(pointZ - stopZ)
        );
    }

    private void applyAimAssist(AimCandidate target, double deltaSeconds, long nowMs) {
        double fadeMultiplier = getStopFadeMultiplier(target);
        if (fadeMultiplier <= 0.0) {
            return;
        }

        double randomFactor = randomization.getVal() / 100.0;
        double yawBias = 0.0;
        double pitchBias = 0.0;
        if (humanizeRotations.getVal()) {
            yawBias = Math.sin(nowMs * 0.0047 + currentTargetSeed * 0.91) * 0.25 * randomFactor;
            pitchBias = Math.cos(nowMs * 0.0039 + currentTargetSeed * 1.37) * 0.18 * randomFactor;
        }

        double adjustedYawDiff = isHorizontalActive() ? target.yawDiff() + yawBias : 0.0;
        double adjustedPitchDiff = isVerticalActive() ? target.pitchDiff() + pitchBias : 0.0;
        double yawStep = isHorizontalActive()
                ? computeAxisStep(adjustedYawDiff, getResolvedHorizontalStrength(), getResolvedHorizontalSmooth(), fadeMultiplier, deltaSeconds, nowMs, 0.0)
                : 0.0;
        double pitchStep = isVerticalActive()
                ? computeAxisStep(adjustedPitchDiff, getResolvedVerticalStrength(), getResolvedVerticalSmooth(), fadeMultiplier, deltaSeconds, nowMs, 1.7)
                : 0.0;

        if (Math.abs(yawStep) <= MIN_STOP_THRESHOLD && Math.abs(pitchStep) <= MIN_STOP_THRESHOLD) {
            return;
        }

        float nextYaw = (float) (mc.player.getYRot() + yawStep);
        float nextPitch = Mth.clamp((float) (mc.player.getXRot() + pitchStep), -90.0F, 90.0F);
        mc.player.setYRot(nextYaw);
        mc.player.setXRot(nextPitch);
    }

    private double computeAxisStep(double diff, double strengthValue, int smoothValue, double fadeMultiplier, double deltaSeconds, long nowMs, double seedOffset) {
        double absDiff = Math.abs(diff);
        if (absDiff <= MIN_STOP_THRESHOLD) {
            return 0.0;
        }

        double errorRatio = Mth.clamp(absDiff / Math.max(fov.getVal(), 1.0), 0.0, 1.0);
        double frameScale = Mth.clamp(deltaSeconds / (1.0 / 60.0), 0.10, 3.00);
        double curveMultiplier = getCurveMultiplier(errorRatio);
        double variableMultiplier = 1.0;
        double randomFactor = randomization.getVal() / 100.0;
        if (variableSpeed.getVal()) {
            variableMultiplier += Math.sin(nowMs * 0.0055 + currentTargetSeed + seedOffset) * 0.12;
        }
        if (humanizeRotations.getVal()) {
            variableMultiplier += Math.cos(nowMs * 0.0043 + currentTargetSeed * 1.19 + seedOffset) * 0.06 * randomFactor;
        }
        variableMultiplier = Mth.clamp(variableMultiplier, 0.65, 1.35);

        double step = diff * strengthValue * frameScale * curveMultiplier * fadeMultiplier * variableMultiplier / Math.max(smoothValue, 1);
        double maxFraction = Mth.clamp(0.28 + strengthValue * 0.10, 0.18, 0.40);
        double maxStep = absDiff * maxFraction;
        return Mth.clamp(step, -maxStep, maxStep);
    }

    private double getCurveMultiplier(double errorRatio) {
        return switch (accelerationCurve.getVal()) {
            case Linear -> 0.55 + errorRatio * 0.45;
            case Dynamic -> 0.45 + Math.sqrt(errorRatio) * 0.70;
            case Exponential -> 0.30 + errorRatio * errorRatio * 1.10;
        };
    }

    private double getStopFadeMultiplier(AimCandidate target) {
        double threshold = getStopThreshold(target.distance());
        double metric = switch (stopMode.getVal()) {
            case Angle -> target.stopAngleMetric();
            case ScreenRadius -> target.stopScreenMetric();
            case HitboxPercentage -> target.stopHitboxMetric();
        };

        if (metric > threshold) {
            return 1.0;
        }
        if (!fadeNearTarget.getVal()) {
            return 0.0;
        }

        double ratio = Mth.clamp(metric / Math.max(threshold, MIN_STOP_THRESHOLD), 0.0, 1.0);
        double fadeExponent = 1.0 + (fadeStrength.getVal() / 100.0) * 3.0;
        return Math.pow(ratio, fadeExponent);
    }

    private double getStopThreshold(double distanceToTarget) {
        double distanceRatio = Mth.clamp(distanceToTarget / Math.max(range.getVal(), 0.1), 0.0, 1.0);
        double scale = 1.0;
        if (dynamicRadius.getVal()) {
            scale += distanceRatio * (distanceScaling.getVal() / 100.0);
        }

        return switch (stopMode.getVal()) {
            case Angle -> stopRadius.getVal() * scale;
            case ScreenRadius -> screenRadius.getVal() * scale;
            case HitboxPercentage -> Mth.clamp(
                    (HITBOX_STOP_THRESHOLD_MIN + (stopRadius.getVal() / 5.0) * 0.25) * scale,
                    HITBOX_STOP_THRESHOLD_MIN,
                    HITBOX_STOP_THRESHOLD_MAX
            );
        };
    }

    private double getResolvedHorizontalStrength() {
        return separateHorizontalStrength.getVal() ? horizontalStrength.getVal() : strength.getVal();
    }

    private double getResolvedVerticalStrength() {
        return separateVerticalStrength.getVal() ? verticalStrength.getVal() : strength.getVal();
    }

    private int getResolvedHorizontalSmooth() {
        return separateHorizontalSmooth.getVal() ? horizontalSmooth.getVal() : smooth.getVal();
    }

    private int getResolvedVerticalSmooth() {
        return separateVerticalSmooth.getVal() ? verticalSmooth.getVal() : smooth.getVal();
    }

    private boolean isHorizontalActive() {
        return axisMode.getVal() != AxisMode.VerticalOnly;
    }

    private boolean isVerticalActive() {
        return axisMode.getVal() != AxisMode.HorizontalOnly;
    }

    private double getAngularDistance(double yawDiff, double pitchDiff) {
        if (isHorizontalActive() && isVerticalActive()) {
            return Math.hypot(yawDiff, pitchDiff);
        }
        return isHorizontalActive() ? Math.abs(yawDiff) : Math.abs(pitchDiff);
    }

    private double getImprovementRatio(double currentScore, double challengerScore) {
        double baseline = Math.max(Math.abs(currentScore), 1.0E-6);
        return Math.max(0.0, (currentScore - challengerScore) / baseline);
    }

    private AimCandidate findCandidate(List<AimCandidate> candidates, UUID uuid) {
        if (uuid == null) {
            return null;
        }
        for (AimCandidate candidate : candidates) {
            if (candidate.target().getUUID().equals(uuid)) {
                return candidate;
            }
        }
        return null;
    }

    private void updateCurrentTarget(AimCandidate candidate, long nowMs) {
        UUID nextId = candidate.target().getUUID();
        if (!nextId.equals(currentTargetId)) {
            currentTargetId = nextId;
            currentTargetAcquiredAtMs = nowMs;
            currentTargetSeed = ThreadLocalRandom.current().nextDouble(0.0, Math.PI * 2.0);
        }
    }

    private double getTargetSeed(LivingEntity target) {
        if (target != null && target.getUUID().equals(currentTargetId)) {
            return currentTargetSeed;
        }
        return (target.getUUID().hashCode() & 0xFFFF) * 0.001;
    }

    private int getTargetLatencyMs(LivingEntity target) {
        if (!(target instanceof Player player) || mc.player == null || mc.player.connection == null) {
            return 0;
        }

        PlayerInfo info = mc.player.connection.getPlayerInfo(player.getGameProfile().id());
        return info == null ? 0 : Math.max(info.getLatency(), 0);
    }

    private void clearTargetState() {
        currentTargetId = null;
        currentTargetAcquiredAtMs = 0L;
        currentTargetSeed = ThreadLocalRandom.current().nextDouble(0.0, Math.PI * 2.0);
    }

    private void resetRuntimeState() {
        lastAssistTimeNs = -1L;
        clearTargetState();
    }

    private void configureChildSettings() {
        useItemFilter.setChangeAction(setting -> scheduleChildSettingsSync());
        randomizeAimPoint.setChangeAction(setting -> scheduleChildSettingsSync());
        separateHorizontalStrength.setChangeAction(setting -> scheduleChildSettingsSync());
        separateVerticalStrength.setChangeAction(setting -> scheduleChildSettingsSync());
        separateHorizontalSmooth.setChangeAction(setting -> scheduleChildSettingsSync());
        separateVerticalSmooth.setChangeAction(setting -> scheduleChildSettingsSync());
        predictionEnabled.setChangeAction(setting -> scheduleChildSettingsSync());
        targetStickinessEnabled.setChangeAction(setting -> scheduleChildSettingsSync());
        stopMode.setChangeAction(setting -> scheduleChildSettingsSync());
        stopPointMode.setChangeAction(setting -> scheduleChildSettingsSync());
        dynamicRadius.setChangeAction(setting -> scheduleChildSettingsSync());
        fadeNearTarget.setChangeAction(setting -> scheduleChildSettingsSync());
    }

    private void syncVisibleSettings() {
        syncGeneralSettings();
        syncTargetingSettings();
        syncItemFilterSettings();
        syncAimPointSettings();
        syncAxisSettings();
        syncRotationSettings();
        syncPredictionSettings();
        syncStickinessSettings();
        syncAntiAimlockSettings();
    }

    private void syncGeneralSettings() {
        List<ModuleSetting<?>> settings = scGeneral.getSettings();
        settings.clear();
        settings.add(targetPlayers);
        settings.add(targetMobs);
        settings.add(ignoreInvisible);
        settings.add(throughWalls);
        settings.add(requireClick);
        settings.add(stopWhileBreakingBlocks);
        settings.add(range);
        settings.add(fov);
    }

    private void syncTargetingSettings() {
        List<ModuleSetting<?>> settings = scTargeting.getSettings();
        settings.clear();
        settings.add(targetSelectionMode);
    }

    private void syncItemFilterSettings() {
        List<ModuleSetting<?>> settings = scItemFilter.getSettings();
        settings.clear();
        settings.add(useItemFilter);
        if (useItemFilter.getVal()) {
            settings.add(allowedHeldItems);
            settings.add(blacklistMode);
        }
    }

    private void syncAimPointSettings() {
        List<ModuleSetting<?>> settings = scAimPoint.getSettings();
        settings.clear();
        settings.add(aimPointMode);
        settings.add(dynamicAimPoint);
        settings.add(randomizeAimPoint);
        if (randomizeAimPoint.getVal()) {
            settings.add(randomizationRange);
        }
        settings.add(customOffsetX);
        settings.add(customOffsetY);
        settings.add(customOffsetZ);
    }

    private void syncAxisSettings() {
        List<ModuleSetting<?>> settings = scAxis.getSettings();
        settings.clear();
        settings.add(axisMode);
        settings.add(strength);
        settings.add(separateHorizontalStrength);
        if (separateHorizontalStrength.getVal()) {
            settings.add(horizontalStrength);
        }
        settings.add(separateVerticalStrength);
        if (separateVerticalStrength.getVal()) {
            settings.add(verticalStrength);
        }
        settings.add(smooth);
        settings.add(separateHorizontalSmooth);
        if (separateHorizontalSmooth.getVal()) {
            settings.add(horizontalSmooth);
        }
        settings.add(separateVerticalSmooth);
        if (separateVerticalSmooth.getVal()) {
            settings.add(verticalSmooth);
        }
    }

    private void syncRotationSettings() {
        List<ModuleSetting<?>> settings = scRotation.getSettings();
        settings.clear();
        settings.add(accelerationCurve);
        settings.add(randomization);
        settings.add(variableSpeed);
        settings.add(humanizeRotations);
    }

    private void syncPredictionSettings() {
        List<ModuleSetting<?>> settings = scPrediction.getSettings();
        settings.clear();
        settings.add(predictionEnabled);
        if (predictionEnabled.getVal()) {
            settings.add(predictionAmount);
            settings.add(horizontalPrediction);
            settings.add(verticalPrediction);
            settings.add(pingCompensation);
        }
    }

    private void syncStickinessSettings() {
        List<ModuleSetting<?>> settings = scStickiness.getSettings();
        settings.clear();
        settings.add(targetStickinessEnabled);
        if (targetStickinessEnabled.getVal()) {
            settings.add(stickTime);
            settings.add(switchThreshold);
        }
    }

    private void syncAntiAimlockSettings() {
        List<ModuleSetting<?>> settings = scAntiAimlock.getSettings();
        settings.clear();
        settings.add(stopMode);
        settings.add(stopPointMode);
        if (stopPointMode.getVal() == StopPointMode.CustomPercentage) {
            settings.add(customStopPointPercentage);
        }
        if (stopMode.getVal() == StopMode.ScreenRadius) {
            settings.add(screenRadius);
        }
        else {
            settings.add(stopRadius);
        }
        settings.add(dynamicRadius);
        if (dynamicRadius.getVal()) {
            settings.add(distanceScaling);
        }
        settings.add(fadeNearTarget);
        if (fadeNearTarget.getVal()) {
            settings.add(fadeStrength);
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
                allowedHeldItems,
                blacklistMode,
                randomizationRange,
                horizontalStrength,
                verticalStrength,
                horizontalSmooth,
                verticalSmooth,
                predictionAmount,
                horizontalPrediction,
                verticalPrediction,
                pingCompensation,
                stickTime,
                switchThreshold,
                customStopPointPercentage,
                stopRadius,
                screenRadius,
                distanceScaling,
                fadeStrength
        );
    }

    private record AimCandidate(
            LivingEntity target,
            Vec3 aimPoint,
            Vec3 stopPoint,
            double distance,
            double yawDiff,
            double pitchDiff,
            double angularDistance,
            double stopAngleMetric,
            double stopScreenMetric,
            double stopHitboxMetric,
            double selectionScore
    ) {
    }

    public enum TargetSelectionMode {
        ClosestToCrosshair,
        ClosestDistance,
        LowestHealth,
        HighestHealth,
        SmallestAngle,
        SmartPriority
    }

    public enum AimPointMode {
        Feet(0.10),
        Legs(0.35),
        Chest(0.60),
        Neck(0.78),
        Head(0.92);

        private final double fraction;

        AimPointMode(double fraction) {
            this.fraction = fraction;
        }
    }

    public enum AxisMode {
        HorizontalAndVertical,
        HorizontalOnly,
        VerticalOnly
    }

    public enum AccelerationCurve {
        Linear,
        Dynamic,
        Exponential
    }

    public enum StopMode {
        Angle,
        ScreenRadius,
        HitboxPercentage
    }

    public enum StopPointMode {
        Feet(0.10),
        Legs(0.35),
        Chest(0.60),
        Neck(0.78),
        Head(0.92),
        CustomPercentage(0.78);

        private final double fraction;

        StopPointMode(double fraction) {
            this.fraction = fraction;
        }
    }
}
