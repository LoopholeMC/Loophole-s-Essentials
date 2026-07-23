package com.loophole.essentials.module.modules;

import com.loophole.essentials.mixin.AccessorKeyMapping;
import com.loophole.essentials.module.LoopholeListenerModule;
import com.loophole.essentials.module.settings.MouseButtonSetting;
import com.loophole.essentials.module.settings.RangeDoubleSetting;
import io.github.itzispyder.clickcrystals.events.EventHandler;
import io.github.itzispyder.clickcrystals.events.events.client.KeyPressEvent;
import io.github.itzispyder.clickcrystals.events.events.client.MouseClickEvent;
import io.github.itzispyder.clickcrystals.events.events.networking.GameLeaveEvent;
import io.github.itzispyder.clickcrystals.events.events.world.ClientTickStartEvent;
import io.github.itzispyder.clickcrystals.events.events.world.RenderWorldEvent;
import io.github.itzispyder.clickcrystals.events.listeners.UserInputListener;
import io.github.itzispyder.clickcrystals.gui.ClickType;
import io.github.itzispyder.clickcrystals.modules.ModuleSetting;
import io.github.itzispyder.clickcrystals.modules.settings.SettingSection;
import io.github.itzispyder.clickcrystals.util.minecraft.InvUtils;
import io.github.itzispyder.clickcrystals.util.minecraft.PlayerUtils;
import net.minecraft.client.KeyMapping;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.item.Items;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.lwjgl.glfw.GLFW;

public class AutoHitCrystalModule extends LoopholeListenerModule {

    private static final int OBSIDIAN_CONFIRM_TICKS = 10;
    private static final int CRYSTAL_ACTION_CONFIRM_TICKS = 10;

    private final SettingSection scGeneral = getGeneralSection();

    public final MouseButtonSetting activationButton = scGeneral.add(createMouseButtonSetting()
            .name("activation-button")
            .description("Key or mouse button that runs Auto Hit Crystal. The obsidian helper keeps checking while held until it places once, then waits for a real release before it can place obsidian again. Supports keyboard keys plus Right Click, Middle Click, Mouse 4, and Mouse 5.")
            .def(MouseButtonSetting.Button.NONE)
            .build()
    );

    public final RangeDoubleSetting obsidianDelay = scGeneral.add(createRangeDoubleSetting()
            .name("obsidian-delay")
            .description("Randomized delay used before the obsidian place action.")
            .def(0.000, 0.010)
            .min(0.000)
            .max(0.100)
            .decimalPlaces(3)
            .build()
    );

    public final RangeDoubleSetting crystalDelay = scGeneral.add(createRangeDoubleSetting()
            .name("crystal-delay")
            .description("Randomized delay used before each crystal place action while the activation bind stays held.")
            .def(0.000, 0.010)
            .min(0.000)
            .max(0.100)
            .decimalPlaces(3)
            .build()
    );

    public final ModuleSetting<Boolean> cw = scGeneral.add(createBoolSetting()
            .name("cw")
            .description("Attack a directly targeted end crystal while the activation bind stays held and no block is currently targeted.")
            .def(true)
            .build()
    );

    public final RangeDoubleSetting cwDelay = scGeneral.add(createRangeDoubleSetting()
            .name("cw-delay")
            .description("Randomized delay used before each optional CW crystal hit while the activation bind stays held.")
            .def(0.000, 0.010)
            .min(0.000)
            .max(0.100)
            .decimalPlaces(3)
            .build()
    );

    private boolean activationHeld = false;
    private boolean obsidianConsumedThisHold = false;
    private boolean obsidianLockedUntilRelease = false;
    private BlockPos pendingObsidianPlacementPos = null;
    private int pendingObsidianPlacementTicks = 0;
    private boolean obsidianTaskScheduled = false;
    private long obsidianTaskReadyAtMs = 0L;

    private ContinuousMode continuousMode = ContinuousMode.NONE;
    private boolean continuousTaskScheduled = false;
    private long continuousTaskReadyAtMs = 0L;
    private int continuousTaskToken = 0;
    private boolean suppressNextTickActionPass = false;
    private boolean actionQueuedThisPass = false;
    private PendingCrystalAction pendingCrystalAction = PendingCrystalAction.NONE;
    private BlockPos pendingCrystalPlacementPos = null;
    private int pendingCrystalActionTicks = 0;

    public AutoHitCrystalModule() {
        super("auto-hit-crystal", "Keeps checking for a non-crystal block while held until it places obsidian once, then continues crystal placement and optional CW crystal hits until release.");
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
        if (!activationButton.isMouseBinding() || !activationButton.matchesMouse(e.getButton())) {
            return;
        }

        if (e.getAction() == ClickType.CLICK) {
            handleActivationPressed();
            return;
        }
        if (e.getAction() == ClickType.RELEASE) {
            handleActivationReleased();
        }
    }

    @EventHandler
    private void onKeyPress(KeyPressEvent e) {
        if (!activationButton.isKeyboardBinding() || !activationButton.matchesKey(e.getKeycode())) {
            return;
        }

        if (e.getAction() == ClickType.CLICK) {
            handleActivationPressed();
            return;
        }
        if (e.getAction() == ClickType.RELEASE) {
            handleActivationReleased();
        }
    }

    @EventHandler
    private void onTickStart(ClientTickStartEvent e) {
        syncActivationState();
        if (!canOperate()) {
            resetRuntimeState();
            return;
        }

        resolvePendingObsidianPlacement();
        resolvePendingCrystalAction();

        if (!activationHeld) {
            cancelObsidianTask();
            stopContinuousLoop();
            return;
        }

        if (suppressNextTickActionPass) {
            suppressNextTickActionPass = false;
            return;
        }

        long nowMs = System.currentTimeMillis();
        if (runReadyActions(nowMs)) {
            return;
        }

        tryStartObsidianAction(nowMs);
        updateContinuousLoop(nowMs);
    }

    @EventHandler
    private void onRenderWorld(RenderWorldEvent e) {
        if (!activationHeld || !canOperate() || suppressNextTickActionPass) {
            return;
        }

        runReadyActions(System.currentTimeMillis());
    }

    private void tryStartObsidianAction(long nowMs) {
        if (obsidianConsumedThisHold || obsidianTaskScheduled || isPendingCrystalAction() || !shouldPlaceObsidianOnce()) {
            return;
        }

        long delayMs = getObsidianDelayMs();
        if (delayMs <= 0L) {
            tryPlaceObsidian();
            return;
        }

        obsidianTaskScheduled = true;
        obsidianTaskReadyAtMs = nowMs + delayMs;
    }

    private boolean tryPlaceObsidian() {
        obsidianTaskScheduled = false;
        if (!shouldPlaceObsidianOnce()) {
            return false;
        }

        int obsidianSlot = findHotbarSlot(Items.OBSIDIAN);
        if (obsidianSlot < 0) {
            return false;
        }

        if (InvUtils.selected() != obsidianSlot) {
            InvUtils.select(obsidianSlot);
        }
        if (!mc.player.getMainHandItem().is(Items.OBSIDIAN)) {
            return false;
        }

        armPendingObsidianPlacement();
        queueUseClick();
        return true;
    }

    private void updateContinuousLoop(long nowMs) {
        if (isPendingCrystalAction()) {
            if (continuousMode != ContinuousMode.NONE || continuousTaskScheduled) {
                stopContinuousLoop();
            }
            return;
        }

        ContinuousMode desiredMode = getDesiredContinuousMode();
        if (desiredMode != continuousMode) {
            continuousMode = desiredMode;
            continuousTaskScheduled = false;
            continuousTaskReadyAtMs = 0L;
        }
        if (desiredMode == ContinuousMode.NONE || continuousTaskScheduled) {
            return;
        }

        long delayMs = getDelayMsForMode(continuousMode);
        if (delayMs <= 0L) {
            runImmediateContinuousAction(continuousMode);
            return;
        }

        if (continuousMode == ContinuousMode.HIT_CRYSTAL) {
            scheduleContinuousHitAction(delayMs);
            return;
        }

        continuousTaskScheduled = true;
        continuousTaskReadyAtMs = nowMs + delayMs;
    }

    private void scheduleContinuousHitAction(long delayMs) {
        int taskToken = ++continuousTaskToken;
        continuousTaskScheduled = true;
        continuousTaskReadyAtMs = 0L;
        system.scheduler.runDelayedTask(() -> mc.execute(() -> tryRunScheduledContinuousHit(taskToken)), delayMs);
    }

    private void tryRunScheduledContinuousHit(int taskToken) {
        if (!continuousTaskScheduled
                || continuousTaskToken != taskToken
                || continuousMode != ContinuousMode.HIT_CRYSTAL) {
            return;
        }

        continuousTaskScheduled = false;
        if (!runImmediateContinuousAction(ContinuousMode.HIT_CRYSTAL)) {
            return;
        }

        if (shouldContinueMode(ContinuousMode.HIT_CRYSTAL)) {
            updateContinuousLoop(System.currentTimeMillis());
        }
    }

    private boolean runImmediateContinuousAction(ContinuousMode mode) {
        if (!shouldContinueMode(mode)) {
            return false;
        }

        return switch (mode) {
            case PLACE_CRYSTAL -> tryPlaceCrystal();
            case HIT_CRYSTAL -> tryHitCrystal();
            default -> false;
        };
    }

    private boolean tryPlaceCrystal() {
        BlockPos expectedCrystalPos = getExpectedCrystalPlacementPos();
        if (expectedCrystalPos == null || hasCrystalAt(expectedCrystalPos)) {
            return false;
        }

        int crystalSlot = findHotbarSlot(Items.END_CRYSTAL);
        if (crystalSlot < 0) {
            return false;
        }

        if (InvUtils.selected() != crystalSlot) {
            InvUtils.select(crystalSlot);
        }
        if (!mc.player.getMainHandItem().is(Items.END_CRYSTAL)) {
            return false;
        }

        armPendingCrystalPlacement(expectedCrystalPos);
        queueUseClick();
        return true;
    }

    private boolean tryHitCrystal() {
        EndCrystal targetedCrystal = getTargetedCrystalEntity();
        if (targetedCrystal == null) {
            return false;
        }

        int crystalSlot = findHotbarSlot(Items.END_CRYSTAL);
        if (crystalSlot < 0) {
            return false;
        }

        if (InvUtils.selected() != crystalSlot) {
            InvUtils.select(crystalSlot);
        }
        if (!mc.player.getMainHandItem().is(Items.END_CRYSTAL)) {
            return false;
        }

        queueAttackClick();
        return true;
    }

    private boolean shouldPlaceObsidianOnce() {
        return activationHeld
                && canOperate()
                && !isPendingObsidianPlacement()
                && !obsidianLockedUntilRelease
                && !isPendingCrystalAction()
                && !activationButton.isNone()
                && hasHotbarItem(Items.OBSIDIAN)
                && hasHotbarItem(Items.END_CRYSTAL)
                && isTargetingNonCrystalBaseBlock();
    }

    private ContinuousMode getDesiredContinuousMode() {
        if (shouldContinuouslyPlaceCrystal()) {
            return ContinuousMode.PLACE_CRYSTAL;
        }
        if (shouldContinuouslyHitCrystal()) {
            return ContinuousMode.HIT_CRYSTAL;
        }
        return ContinuousMode.NONE;
    }

    private boolean shouldContinueMode(ContinuousMode mode) {
        return switch (mode) {
            case PLACE_CRYSTAL -> shouldContinuouslyPlaceCrystal();
            case HIT_CRYSTAL -> shouldContinuouslyHitCrystal();
            case NONE -> false;
        };
    }

    private boolean shouldContinuouslyPlaceCrystal() {
        return activationHeld
                && canOperate()
                && !activationButton.isNone()
                && hasHotbarItem(Items.OBSIDIAN)
                && hasHotbarItem(Items.END_CRYSTAL)
                && isTargetingCrystalBaseBlock()
                && !hasCrystalAtTargetedBase()
                && !isTargetingCrystalEntity();
    }

    private boolean shouldContinuouslyHitCrystal() {
        return activationHeld
                && canOperate()
                && !activationButton.isNone()
                && cw.getVal()
                && hasHotbarItem(Items.OBSIDIAN)
                && hasHotbarItem(Items.END_CRYSTAL)
                && !isTargetingRealBlock()
                && isTargetingCrystalEntity();
    }

    private boolean canOperate() {
        return isEnabled()
                && PlayerUtils.valid()
                && mc.player != null
                && mc.level != null
                && mc.options != null
                && mc.screen == null;
    }

    private boolean isTargetingRealBlock() {
        if (!(mc.hitResult instanceof BlockHitResult hit) || mc.hitResult.getType() != HitResult.Type.BLOCK) {
            return false;
        }
        return !mc.level.getBlockState(hit.getBlockPos()).isAir();
    }

    private boolean isTargetingNonCrystalBaseBlock() {
        if (!(mc.hitResult instanceof BlockHitResult hit) || mc.hitResult.getType() != HitResult.Type.BLOCK) {
            return false;
        }

        BlockState state = mc.level.getBlockState(hit.getBlockPos());
        return !state.isAir()
                && !state.is(Blocks.OBSIDIAN)
                && !state.is(Blocks.BEDROCK)
                && !state.is(Blocks.RESPAWN_ANCHOR);
    }

    private boolean isTargetingCrystalBaseBlock() {
        if (!(mc.hitResult instanceof BlockHitResult hit) || mc.hitResult.getType() != HitResult.Type.BLOCK) {
            return false;
        }

        BlockState state = mc.level.getBlockState(hit.getBlockPos());
        return state.is(Blocks.OBSIDIAN) || state.is(Blocks.BEDROCK);
    }

    private boolean isTargetingCrystalEntity() {
        return mc.hitResult instanceof EntityHitResult hit
                && mc.hitResult.getType() == HitResult.Type.ENTITY
                && hit.getEntity() instanceof EndCrystal;
    }

    private EndCrystal getTargetedCrystalEntity() {
        if (!(mc.hitResult instanceof EntityHitResult hit) || mc.hitResult.getType() != HitResult.Type.ENTITY) {
            return null;
        }
        return hit.getEntity() instanceof EndCrystal crystal ? crystal : null;
    }

    private boolean hasHotbarItem(net.minecraft.world.item.Item item) {
        return findHotbarSlot(item) >= 0;
    }

    private int findHotbarSlot(net.minecraft.world.item.Item item) {
        for (int slot = 0; slot <= 8; slot++) {
            if (mc.player.getInventory().getItem(slot).is(item)) {
                return slot;
            }
        }
        return -1;
    }

    private long getObsidianDelayMs() {
        return Math.max(0L, Math.round(obsidianDelay.getRandomizedValue() * 1000.0));
    }

    private long getCrystalDelayMs() {
        return Math.max(0L, Math.round(crystalDelay.getRandomizedValue() * 1000.0));
    }

    private long getCwDelayMs() {
        return Math.max(0L, Math.round(cwDelay.getRandomizedValue() * 1000.0));
    }

    private long getDelayMsForMode(ContinuousMode mode) {
        return switch (mode) {
            case HIT_CRYSTAL -> getCwDelayMs();
            case PLACE_CRYSTAL -> getCrystalDelayMs();
            case NONE -> getObsidianDelayMs();
        };
    }

    private void queueUseClick() {
        actionQueuedThisPass = true;
        mc.execute(() -> {
            AccessorKeyMapping keyUse = (AccessorKeyMapping) mc.options.keyUse;
            KeyMapping.click(keyUse.loopholeEssentials$getBoundKey());
        });
    }

    private void queueAttackClick() {
        actionQueuedThisPass = true;
        mc.execute(() -> {
            AccessorKeyMapping keyAttack = (AccessorKeyMapping) mc.options.keyAttack;
            keyAttack.loopholeEssentials$setClickCount(keyAttack.loopholeEssentials$getClickCount() + 1);
        });
    }

    private void cancelObsidianTask() {
        obsidianTaskScheduled = false;
        obsidianTaskReadyAtMs = 0L;
    }

    private void armPendingObsidianPlacement() {
        pendingObsidianPlacementPos = getExpectedObsidianPlacementPos();
        pendingObsidianPlacementTicks = pendingObsidianPlacementPos == null ? 0 : OBSIDIAN_CONFIRM_TICKS;
    }

    private void resolvePendingObsidianPlacement() {
        if (!isPendingObsidianPlacement()) {
            return;
        }

        if (mc.level.getBlockState(pendingObsidianPlacementPos).is(Blocks.OBSIDIAN)) {
            obsidianConsumedThisHold = true;
            clearPendingObsidianPlacement();
            return;
        }
        if (shouldClearStalePendingObsidianPlacement()) {
            clearPendingObsidianPlacement();
            return;
        }

        pendingObsidianPlacementTicks--;
        if (pendingObsidianPlacementTicks <= 0) {
            clearPendingObsidianPlacement();
        }
    }

    private boolean isPendingObsidianPlacement() {
        return pendingObsidianPlacementPos != null && pendingObsidianPlacementTicks > 0;
    }

    private void clearPendingObsidianPlacement() {
        pendingObsidianPlacementPos = null;
        pendingObsidianPlacementTicks = 0;
    }

    private boolean shouldClearStalePendingObsidianPlacement() {
        BlockPos currentExpectedPos = getExpectedObsidianPlacementPos();
        if (currentExpectedPos == null || currentExpectedPos.equals(pendingObsidianPlacementPos)) {
            return false;
        }

        BlockState pendingState = mc.level.getBlockState(pendingObsidianPlacementPos);
        return !pendingState.isAir() || hasBlockingEntityAt(pendingObsidianPlacementPos);
    }

    private BlockPos getExpectedObsidianPlacementPos() {
        if (!(mc.hitResult instanceof BlockHitResult hit) || mc.hitResult.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        return hit.getBlockPos().relative(hit.getDirection()).immutable();
    }

    private void armPendingCrystalPlacement(BlockPos expectedCrystalPos) {
        pendingCrystalAction = PendingCrystalAction.PLACE;
        pendingCrystalPlacementPos = expectedCrystalPos;
        pendingCrystalActionTicks = CRYSTAL_ACTION_CONFIRM_TICKS;
        stopContinuousLoop();
        cancelObsidianTask();
    }

    private void resolvePendingCrystalAction() {
        if (!isPendingCrystalAction()) {
            return;
        }

        switch (pendingCrystalAction) {
            case PLACE -> {
                if (pendingCrystalPlacementPos != null && hasCrystalAt(pendingCrystalPlacementPos)) {
                    lockObsidianUntilRelease();
                    clearPendingCrystalAction();
                    return;
                }
            }
            case NONE -> {
                return;
            }
        }

        pendingCrystalActionTicks--;
        if (pendingCrystalActionTicks <= 0) {
            clearPendingCrystalAction();
        }
    }

    private boolean isPendingCrystalAction() {
        return pendingCrystalAction != PendingCrystalAction.NONE && pendingCrystalActionTicks > 0;
    }

    private void clearPendingCrystalAction() {
        pendingCrystalAction = PendingCrystalAction.NONE;
        pendingCrystalPlacementPos = null;
        pendingCrystalActionTicks = 0;
    }

    private BlockPos getExpectedCrystalPlacementPos() {
        if (!(mc.hitResult instanceof BlockHitResult hit) || mc.hitResult.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        return hit.getBlockPos().above().immutable();
    }

    private boolean hasCrystalAtTargetedBase() {
        BlockPos expectedCrystalPos = getExpectedCrystalPlacementPos();
        return expectedCrystalPos != null && hasCrystalAt(expectedCrystalPos);
    }

    private boolean hasCrystalAt(BlockPos blockPos) {
        return !mc.level.getEntitiesOfClass(EndCrystal.class, new AABB(blockPos)).isEmpty();
    }

    private boolean hasBlockingEntityAt(BlockPos blockPos) {
        return !mc.level.getEntities((net.minecraft.world.entity.Entity) null, new AABB(blockPos), entity ->
                entity != null
                        && entity.isAlive()
                        && !entity.isSpectator()
                        && entity.isPickable()).isEmpty();
    }

    private void lockObsidianUntilRelease() {
        obsidianLockedUntilRelease = true;
        cancelObsidianTask();
    }

    private void stopContinuousLoop() {
        continuousMode = ContinuousMode.NONE;
        continuousTaskScheduled = false;
        continuousTaskReadyAtMs = 0L;
        continuousTaskToken++;
    }

    private boolean runReadyActions(long nowMs) {
        if (obsidianTaskScheduled && nowMs >= obsidianTaskReadyAtMs) {
            if (tryPlaceObsidian()) {
                return true;
            }
        }

        if (!continuousTaskScheduled
                || continuousMode == ContinuousMode.HIT_CRYSTAL
                || nowMs < continuousTaskReadyAtMs) {
            return false;
        }

        ContinuousMode readyMode = continuousMode;
        continuousTaskScheduled = false;
        continuousTaskReadyAtMs = 0L;
        if (readyMode == ContinuousMode.NONE) {
            return false;
        }

        boolean queuedAction = runImmediateContinuousAction(readyMode);
        if (queuedAction && shouldContinueMode(readyMode)) {
            long delayMs = getDelayMsForMode(readyMode);
            if (delayMs > 0L) {
                continuousTaskScheduled = true;
                continuousTaskReadyAtMs = nowMs + delayMs;
            }
        }
        return queuedAction;
    }

    private void resetRuntimeState() {
        activationHeld = false;
        obsidianConsumedThisHold = false;
        obsidianLockedUntilRelease = false;
        clearPendingObsidianPlacement();
        clearPendingCrystalAction();
        suppressNextTickActionPass = false;
        actionQueuedThisPass = false;
        cancelObsidianTask();
        stopContinuousLoop();
    }

    private void handleActivationPressed() {
        if (activationHeld) {
            return;
        }
        activationHeld = true;
        if (canOperate()) {
            actionQueuedThisPass = false;
            long nowMs = System.currentTimeMillis();
            tryStartObsidianAction(nowMs);
            updateContinuousLoop(nowMs);
            if (actionQueuedThisPass) {
                suppressNextTickActionPass = true;
            }
        }
    }

    private void handleActivationReleased() {
        if (!activationHeld) {
            return;
        }
        activationHeld = false;
        obsidianConsumedThisHold = false;
        obsidianLockedUntilRelease = false;
        clearPendingObsidianPlacement();
        clearPendingCrystalAction();
        cancelObsidianTask();
        stopContinuousLoop();
    }

    private void syncActivationState() {
        boolean heldNow = isActivationHeldNow();
        if (heldNow == activationHeld) {
            return;
        }
        if (heldNow) {
            handleActivationPressed();
        }
        else {
            handleActivationReleased();
        }
    }

    private boolean isActivationHeldNow() {
        if (!isEnabled()
                || mc == null
                || mc.getWindow() == null
                || mc.screen != null
                || activationButton.isNone()) {
            return false;
        }

        if (activationButton.isKeyboardBinding()) {
            return UserInputListener.isKeyPressed(activationButton.getKey());
        }
        if (activationButton.isMouseBinding()) {
            return GLFW.glfwGetMouseButton(mc.getWindow().handle(), activationButton.getButton()) == GLFW.GLFW_PRESS;
        }
        return false;
    }

    private enum ContinuousMode {
        NONE,
        PLACE_CRYSTAL,
        HIT_CRYSTAL
    }

    private enum PendingCrystalAction {
        NONE,
        PLACE
    }
}
