package com.loophole.essentials.module.modules;

import com.loophole.essentials.mixin.AccessorKeyMapping;
import com.loophole.essentials.module.LoopholeListenerModule;
import com.loophole.essentials.module.PersistentSettingProvider;
import com.loophole.essentials.module.settings.RangeDoubleSetting;
import io.github.itzispyder.clickcrystals.events.EventHandler;
import io.github.itzispyder.clickcrystals.events.events.client.MouseClickEvent;
import io.github.itzispyder.clickcrystals.events.events.networking.GameLeaveEvent;
import io.github.itzispyder.clickcrystals.events.events.world.BlockPlaceEvent;
import io.github.itzispyder.clickcrystals.events.events.world.ClientTickStartEvent;
import io.github.itzispyder.clickcrystals.gui.ClickType;
import io.github.itzispyder.clickcrystals.gui.screens.ModuleEditScreen;
import io.github.itzispyder.clickcrystals.modules.ModuleSetting;
import io.github.itzispyder.clickcrystals.modules.settings.SettingSection;
import io.github.itzispyder.clickcrystals.util.minecraft.InvUtils;
import io.github.itzispyder.clickcrystals.util.minecraft.PlayerUtils;
import net.minecraft.client.KeyMapping;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.util.Collection;
import java.util.List;

public class AnchorMacroModule extends LoopholeListenerModule implements PersistentSettingProvider {

    private static final long STAGE_TARGET_TIMEOUT_MS = 750L;
    private static final long STAGE_CONFIRM_TIMEOUT_MS = 1_000L;
    private static final long DETONATION_SETTLE_TIMEOUT_MS = 300L;

    private final SettingSection scGeneral = getGeneralSection();
    private final SettingSection scPlacement = createSettingSection("Placement");
    private final SettingSection scHelpers = createSettingSection("Helpers");

    public final ModuleSetting<Boolean> safeAnchorMode = scGeneral.add(createBoolSetting()
            .name("safe-anchor-mode")
            .description("Only charge freshly placed anchors with glowstone and stop there instead of automatically switching to a totem detonation.")
            .def(true)
            .build()
    );

    public final RangeDoubleSetting safeChargeDelay = scPlacement.add(createRangeDoubleSetting()
            .name("safe-charge-delay")
            .description("Randomized delay after placing an anchor before Anchor-Macro swaps to glowstone and queues the safe-mode charge click.")
            .def(0.000, 0.025)
            .min(0.000)
            .max(0.250)
            .decimalPlaces(3)
            .build()
    );

    public final RangeDoubleSetting unsafeChargeDelay = scPlacement.add(createRangeDoubleSetting()
            .name("unsafe-charge-delay")
            .description("Randomized delay after placing an anchor before Anchor-Macro swaps to glowstone and queues the unsafe-mode charge click.")
            .def(0.000, 0.025)
            .min(0.000)
            .max(0.250)
            .decimalPlaces(3)
            .build()
    );

    public final RangeDoubleSetting unsafeDetonationDelay = scPlacement.add(createRangeDoubleSetting()
            .name("unsafe-detonation-delay")
            .description("Randomized delay that starts as soon as the unsafe glowstone charge click is queued; detonation still waits for the anchor to become visibly charged before firing.")
            .def(0.000, 0.025)
            .min(0.000)
            .max(0.250)
            .decimalPlaces(3)
            .build()
    );

    public final RangeDoubleSetting groundGlowstoneSwapDelay = scHelpers.add(createRangeDoubleSetting()
            .name("ground-glowstone-swap-delay")
            .description("Randomized delay before switching to your totem slot after glowstone is visibly placed on the ground instead of into an anchor.")
            .def(0.000, 0.025)
            .min(0.000)
            .max(0.250)
            .decimalPlaces(3)
            .build()
    );

    public final RangeDoubleSetting anchorTargetSwapDelay = scHelpers.add(createRangeDoubleSetting()
            .name("anchor-target-swap-delay")
            .description("Randomized delay before helper swaps to glowstone on uncharged anchors or to your totem slot on charged anchors after a right-click target use.")
            .def(0.000, 0.025)
            .min(0.000)
            .max(0.250)
            .decimalPlaces(3)
            .build()
    );

    private AnchorSequence activeSequence = null;
    private int activeSequenceToken = 0;
    private int pendingGroundRecoveryToken = 0;
    private int pendingTargetSwapToken = 0;
    private int rememberedTotemSlot = -1;
    private boolean pendingChildSettingsSync = false;
    private boolean pendingSettingsScreenRefresh = false;

    public AnchorMacroModule() {
        super("anchor-macro", "Charges freshly placed respawn anchors with glowstone, optionally chains an unsafe totem detonation after charge confirmation, and recovers back to totem when glowstone would otherwise stay out.");
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
    private void onTickStart(ClientTickStartEvent e) {
        if (mc.player != null) {
            updateRememberedTotemSlot();
        }

        if (!canOperate()) {
            resetRuntimeState();
            return;
        }

        processActiveSequence();
    }

    @EventHandler
    private void onBlockPlace(BlockPlaceEvent e) {
        if (!canOperate()) {
            return;
        }

        if (e.getState().is(Blocks.RESPAWN_ANCHOR)) {
            tryStartAnchorSequence(e.getPos().immutable());
            return;
        }

        if (e.getState().is(Blocks.GLOWSTONE)) {
            handleGroundGlowstonePlacement();
        }
    }

    @EventHandler
    private void onMouseClick(MouseClickEvent e) {
        if (e.getButton() != 1
                || e.getAction() != ClickType.CLICK
                || !e.isScreenNull()
                || !canOperate()) {
            return;
        }

        if (!(mc.hitResult instanceof BlockHitResult hit)) {
            return;
        }

        BlockPos pos = hit.getBlockPos().immutable();
        BlockState state = mc.level.getBlockState(pos);
        if (!state.is(Blocks.RESPAWN_ANCHOR) || !hasAnchorAndGlowstoneHotbarAccess()) {
            return;
        }

        if (activeSequence != null) {
            handleActiveSequenceAnchorClick(e, pos, state);
            return;
        }

        if (isAnchorUncharged(pos) && !safeAnchorMode.getVal()) {
            if (isHoldingGlowstone()) {
                tryStartUnsafeDetonationFollowup(pos);
                return;
            }

            e.setCancelled(true);
            tryStartAnchorSequence(pos, SequenceMode.Unsafe, getDelayMs(anchorTargetSwapDelay));
            return;
        }

        HelperSwapType swapType = null;
        if (isChargedAnchor(state)) {
            if (!isHoldingPreferredTotem()) {
                swapType = HelperSwapType.Totem;
            }
        }
        else if (!isHoldingGlowstone()) {
            swapType = HelperSwapType.Glowstone;
        }

        if (swapType == null) {
            return;
        }

        e.setCancelled(true);
        double delaySeconds = anchorTargetSwapDelay.getRandomizedValue();
        if (delaySeconds <= 0.0) {
            performImmediateTargetSwap(new HelperSwapRequest(pos, swapType));
            return;
        }

        scheduleTargetSwap(delaySeconds, new HelperSwapRequest(pos, swapType));
    }

    private void tryStartAnchorSequence(BlockPos anchorPos) {
        if (activeSequence != null || findGlowstoneSlot() < 0) {
            return;
        }

        SequenceMode mode = safeAnchorMode.getVal() ? SequenceMode.Safe : SequenceMode.Unsafe;
        tryStartAnchorSequence(
                anchorPos,
                mode,
                getDelayMs(mode == SequenceMode.Safe ? safeChargeDelay : unsafeChargeDelay)
        );
    }

    private void tryStartAnchorSequence(BlockPos anchorPos, SequenceMode mode, long initialDelayMs) {
        if (activeSequence != null || findGlowstoneSlot() < 0) {
            return;
        }

        long startedAtMs = System.currentTimeMillis();
        activeSequence = new AnchorSequence(
                ++activeSequenceToken,
                anchorPos,
                mode,
                SequenceStage.WaitChargeUse,
                startedAtMs + Math.max(0L, initialDelayMs),
                startedAtMs,
                false
        );
    }

    private void tryStartUnsafeDetonationFollowup(BlockPos anchorPos) {
        if (activeSequence != null || getPreferredTotemSlot() < 0) {
            return;
        }

        long startedAtMs = System.currentTimeMillis();
        activeSequence = new AnchorSequence(
                ++activeSequenceToken,
                anchorPos,
                SequenceMode.Unsafe,
                SequenceStage.WaitDetonationUse,
                startedAtMs + getDelayMs(unsafeDetonationDelay),
                startedAtMs,
                false
        );
    }

    private void processActiveSequence() {
        if (activeSequence == null) {
            return;
        }

        if (!isAnchorStillPresent(activeSequence.anchorPos())) {
            clearActiveSequence();
            return;
        }

        switch (activeSequence.stage()) {
            case WaitChargeUse -> processChargeUseStage();
            case WaitChargeConfirm -> processChargeConfirmStage();
            case WaitDetonationUse -> processDetonationUseStage();
            case WaitDetonationConfirm -> processDetonationConfirmStage();
        }
    }

    private void processChargeUseStage() {
        if (!isStageDelayElapsed()) {
            return;
        }
        if (!isAnchorUncharged(activeSequence.anchorPos())) {
            clearActiveSequence();
            return;
        }

        int glowstoneSlot = findGlowstoneSlot();
        if (glowstoneSlot < 0) {
            clearActiveSequence();
            return;
        }
        if (activeSequence.chargeSlotForced() && InvUtils.selected() != glowstoneSlot) {
            clearActiveSequence();
            return;
        }
        if (InvUtils.selected() != glowstoneSlot) {
            InvUtils.select(glowstoneSlot);
            if (InvUtils.selected() == glowstoneSlot && !activeSequence.chargeSlotForced()) {
                updateChargeSlotForced(true);
            }
        }
        if (InvUtils.selected() != glowstoneSlot || !isHoldingGlowstone()) {
            if (isStageTimedOut(STAGE_TARGET_TIMEOUT_MS)) {
                clearActiveSequence();
            }
            return;
        }
        if (!isTargetingAnchor(activeSequence.anchorPos(), false)) {
            if (isStageTimedOut(STAGE_TARGET_TIMEOUT_MS)) {
                clearActiveSequence();
            }
            return;
        }

        long actionAtMs = System.currentTimeMillis();
        queueUseClick();

        if (activeSequence.mode() == SequenceMode.Unsafe) {
            moveToStage(
                    SequenceStage.WaitDetonationUse,
                    actionAtMs + getDelayMs(unsafeDetonationDelay),
                    actionAtMs
            );
            return;
        }

        moveToStage(SequenceStage.WaitChargeConfirm, actionAtMs, actionAtMs);
    }

    private void processChargeConfirmStage() {
        if (isChargedAnchor(activeSequence.anchorPos())) {
            clearActiveSequence();
            return;
        }

        if (isStageTimedOut(STAGE_CONFIRM_TIMEOUT_MS)) {
            clearActiveSequence();
        }
    }

    private void processDetonationUseStage() {
        if (!isStageDelayElapsed()) {
            return;
        }
        if (!isChargedAnchor(activeSequence.anchorPos())) {
            if (isStageTimedOut(STAGE_CONFIRM_TIMEOUT_MS)) {
                clearActiveSequence();
            }
            return;
        }

        int totemSlot = getPreferredTotemSlot();
        if (totemSlot < 0) {
            clearActiveSequence();
            return;
        }
        if (!isTargetingAnchor(activeSequence.anchorPos(), true)) {
            if (isStageTimedOut(STAGE_TARGET_TIMEOUT_MS)) {
                clearActiveSequence();
            }
            return;
        }
        if (!tryQueueAnchorUseFromSlot(totemSlot, HeldItemExpectation.PreferredTotem)) {
            if (isStageTimedOut(STAGE_TARGET_TIMEOUT_MS)) {
                clearActiveSequence();
            }
            return;
        }

        moveToStage(SequenceStage.WaitDetonationConfirm, System.currentTimeMillis(), System.currentTimeMillis());
    }

    private void processDetonationConfirmStage() {
        if (!isChargedAnchor(activeSequence.anchorPos())) {
            clearActiveSequence();
            return;
        }
        if (isStageTimedOut(DETONATION_SETTLE_TIMEOUT_MS)) {
            clearActiveSequence();
        }
    }

    private void handleGroundGlowstonePlacement() {
        if (isHoldingPreferredTotem()) {
            return;
        }

        clearActiveSequence();
        int totemSlot = getPreferredTotemSlot();
        if (totemSlot < 0) {
            return;
        }

        int requestToken = ++pendingGroundRecoveryToken;
        long delayMs = getDelayMs(groundGlowstoneSwapDelay);
        system.scheduler.runDelayedTask(() -> mc.execute(() -> {
            if (!isGroundRecoveryValid(requestToken) || isHoldingPreferredTotem()) {
                return;
            }

            int latestTotemSlot = getPreferredTotemSlot();
            if (latestTotemSlot >= 0) {
                InvUtils.select(latestTotemSlot);
            }
        }), delayMs);
    }

    private void scheduleTargetSwap(double delaySeconds, HelperSwapRequest request) {
        int requestToken = ++pendingTargetSwapToken;
        long delayMs = Math.max(0L, Math.round(delaySeconds * 1000.0));
        system.scheduler.runDelayedTask(() -> mc.execute(() -> {
            if (!isTargetSwapValid(requestToken) || activeSequence != null) {
                return;
            }
            performImmediateTargetSwap(request);
        }), delayMs);
    }

    private void performImmediateTargetSwap(HelperSwapRequest request) {
        if (activeSequence != null
                || !isTargetingAnchor(request.anchorPos(), request.type() == HelperSwapType.Totem)) {
            return;
        }

        if (request.type() == HelperSwapType.Glowstone) {
            int glowstoneSlot = findGlowstoneSlot();
            if (glowstoneSlot >= 0 && isAnchorUncharged(request.anchorPos())) {
                tryQueueAnchorUseFromSlot(glowstoneSlot, HeldItemExpectation.Glowstone);
            }
            return;
        }

        int totemSlot = getPreferredTotemSlot();
        if (totemSlot >= 0 && isChargedAnchor(request.anchorPos())) {
            tryQueueAnchorUseFromSlot(totemSlot, HeldItemExpectation.PreferredTotem);
        }
    }

    private void handleActiveSequenceAnchorClick(MouseClickEvent e, BlockPos pos, BlockState state) {
        if (activeSequence == null
                || !pos.equals(activeSequence.anchorPos())
                || !isChargedAnchor(state)
                || isHoldingPreferredTotem()) {
            return;
        }

        int totemSlot = getPreferredTotemSlot();
        if (totemSlot < 0) {
            return;
        }

        e.setCancelled(true);
        tryQueueAnchorUseFromSlot(totemSlot, HeldItemExpectation.PreferredTotem);
    }

    private boolean canOperate() {
        return isEnabled()
                && PlayerUtils.valid()
                && mc.player != null
                && mc.level != null
                && mc.options != null
                && mc.screen == null;
    }

    private boolean hasAnchorAndGlowstoneHotbarAccess() {
        return findAnchorSlot() >= 0 && findGlowstoneSlot() >= 0;
    }

    private int findAnchorSlot() {
        for (int slot = 0; slot <= 8; slot++) {
            if (mc.player.getInventory().getItem(slot).is(Items.RESPAWN_ANCHOR)) {
                return slot;
            }
        }
        return -1;
    }

    private int findGlowstoneSlot() {
        for (int slot = 0; slot <= 8; slot++) {
            if (mc.player.getInventory().getItem(slot).is(Items.GLOWSTONE)) {
                return slot;
            }
        }
        return -1;
    }

    private int findTotemSlot() {
        for (int slot = 0; slot <= 8; slot++) {
            if (mc.player.getInventory().getItem(slot).is(Items.TOTEM_OF_UNDYING)) {
                return slot;
            }
        }
        return -1;
    }

    private void updateRememberedTotemSlot() {
        int totemSlot = findTotemSlot();
        if (totemSlot >= 0) {
            rememberedTotemSlot = totemSlot;
        }
    }

    private int getPreferredTotemSlot() {
        int liveTotemSlot = findTotemSlot();
        if (liveTotemSlot >= 0) {
            return liveTotemSlot;
        }
        return rememberedTotemSlot >= 0 && rememberedTotemSlot <= 8 ? rememberedTotemSlot : -1;
    }

    private boolean isHoldingGlowstone() {
        return mc.player != null && mc.player.getMainHandItem().is(Items.GLOWSTONE);
    }

    private boolean isHoldingPreferredTotem() {
        int preferredSlot = getPreferredTotemSlot();
        return preferredSlot >= 0 && InvUtils.selected() == preferredSlot;
    }

    private boolean tryQueueAnchorUseFromSlot(int slot, HeldItemExpectation expectation) {
        if (slot < 0 || slot > 8) {
            return false;
        }

        if (InvUtils.selected() != slot) {
            InvUtils.select(slot);
        }
        if (InvUtils.selected() != slot || !isHoldingExpectedItem(expectation)) {
            return false;
        }

        queueUseClick();
        return true;
    }

    private boolean isHoldingExpectedItem(HeldItemExpectation expectation) {
        return switch (expectation) {
            case Glowstone -> isHoldingGlowstone();
            case PreferredTotem -> isHoldingPreferredTotem();
        };
    }

    private boolean isTargetingAnchor(BlockPos anchorPos, boolean mustBeCharged) {
        if (!(mc.hitResult instanceof BlockHitResult hit)) {
            return false;
        }
        if (!hit.getBlockPos().equals(anchorPos)) {
            return false;
        }

        return mustBeCharged ? isChargedAnchor(anchorPos) : isAnchorUncharged(anchorPos);
    }

    private boolean isAnchorStillPresent(BlockPos pos) {
        return mc.level != null && mc.level.getBlockState(pos).is(Blocks.RESPAWN_ANCHOR);
    }

    private boolean isAnchorUncharged(BlockPos pos) {
        if (mc.level == null) {
            return false;
        }

        BlockState state = mc.level.getBlockState(pos);
        return state.is(Blocks.RESPAWN_ANCHOR) && state.getValue(RespawnAnchorBlock.CHARGE) == 0;
    }

    private boolean isChargedAnchor(BlockPos pos) {
        if (mc.level == null) {
            return false;
        }

        return isChargedAnchor(mc.level.getBlockState(pos));
    }

    private boolean isChargedAnchor(BlockState state) {
        return state.is(Blocks.RESPAWN_ANCHOR) && state.getValue(RespawnAnchorBlock.CHARGE) > 0;
    }

    private void queueUseClick() {
        mc.execute(() -> KeyMapping.click(((AccessorKeyMapping) mc.options.keyUse).loopholeEssentials$getBoundKey()));
    }

    private boolean isStageDelayElapsed() {
        return activeSequence != null && System.currentTimeMillis() >= activeSequence.readyAtMs();
    }

    private boolean isStageTimedOut(long timeoutMs) {
        return activeSequence != null && System.currentTimeMillis() - activeSequence.stageStartedAtMs() >= timeoutMs;
    }

    private long getDelayMs(RangeDoubleSetting setting) {
        return Math.max(0L, Math.round(setting.getRandomizedValue() * 1000.0));
    }

    private void moveToStage(SequenceStage stage, long readyAtMs, long startedAtMs) {
        if (activeSequence == null) {
            return;
        }

        activeSequence = new AnchorSequence(
                activeSequence.token(),
                activeSequence.anchorPos(),
                activeSequence.mode(),
                stage,
                readyAtMs,
                startedAtMs,
                activeSequence.chargeSlotForced()
        );
    }

    private void updateChargeSlotForced(boolean chargeSlotForced) {
        if (activeSequence == null) {
            return;
        }

        activeSequence = new AnchorSequence(
                activeSequence.token(),
                activeSequence.anchorPos(),
                activeSequence.mode(),
                activeSequence.stage(),
                activeSequence.readyAtMs(),
                activeSequence.stageStartedAtMs(),
                chargeSlotForced
        );
    }

    private void clearActiveSequence() {
        activeSequence = null;
        activeSequenceToken++;
    }

    private boolean isGroundRecoveryValid(int requestToken) {
        return canOperate() && requestToken == pendingGroundRecoveryToken;
    }

    private boolean isTargetSwapValid(int requestToken) {
        return canOperate() && requestToken == pendingTargetSwapToken;
    }

    private void resetRuntimeState() {
        clearActiveSequence();
        pendingGroundRecoveryToken++;
        pendingTargetSwapToken++;
    }

    private void configureChildSettings() {
        safeAnchorMode.setChangeAction(setting -> scheduleChildSettingsSync());
    }

    private void syncVisibleSettings() {
        List<ModuleSetting<?>> placementSettings = scPlacement.getSettings();
        placementSettings.clear();
        if (safeAnchorMode.getVal()) {
            placementSettings.add(safeChargeDelay);
        }
        else {
            placementSettings.add(unsafeChargeDelay);
            placementSettings.add(unsafeDetonationDelay);
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
                safeChargeDelay,
                unsafeChargeDelay,
                unsafeDetonationDelay,
                groundGlowstoneSwapDelay,
                anchorTargetSwapDelay
        );
    }

    private record AnchorSequence(
            int token,
            BlockPos anchorPos,
            SequenceMode mode,
            SequenceStage stage,
            long readyAtMs,
            long stageStartedAtMs,
            boolean chargeSlotForced
    ) {
    }

    private record HelperSwapRequest(BlockPos anchorPos, HelperSwapType type) {
    }

    private enum SequenceMode {
        Safe,
        Unsafe
    }

    private enum SequenceStage {
        WaitChargeUse,
        WaitChargeConfirm,
        WaitDetonationUse,
        WaitDetonationConfirm
    }

    private enum HelperSwapType {
        Glowstone,
        Totem
    }

    private enum HeldItemExpectation {
        Glowstone,
        PreferredTotem
    }
}
