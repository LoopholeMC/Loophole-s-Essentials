package com.loophole.essentials.module.modules;

import com.loophole.essentials.mixin.AccessorKeyMapping;
import com.loophole.essentials.module.LoopholeListenerModule;
import com.loophole.essentials.module.PersistentSettingProvider;
import com.loophole.essentials.module.settings.RangeDoubleSetting;
import io.github.itzispyder.clickcrystals.events.EventHandler;
import io.github.itzispyder.clickcrystals.events.events.client.KeyPressEvent;
import io.github.itzispyder.clickcrystals.events.events.client.MouseScrollEvent;
import io.github.itzispyder.clickcrystals.events.events.networking.GameLeaveEvent;
import io.github.itzispyder.clickcrystals.events.events.networking.PacketSentEvent;
import io.github.itzispyder.clickcrystals.events.events.world.ClientTickStartEvent;
import io.github.itzispyder.clickcrystals.gui.screens.ModuleEditScreen;
import io.github.itzispyder.clickcrystals.modules.ModuleSetting;
import io.github.itzispyder.clickcrystals.modules.settings.SettingSection;
import io.github.itzispyder.clickcrystals.util.minecraft.EntityUtils;
import io.github.itzispyder.clickcrystals.util.minecraft.InvUtils;
import io.github.itzispyder.clickcrystals.util.minecraft.PlayerUtils;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public class AutoDrainModule extends LoopholeListenerModule implements PersistentSettingProvider {

    private static final long MIN_SWITCH_BACK_AFTER_USE_MS = 55L;

    private final SettingSection scGeneral = getGeneralSection();

    public final ModuleSetting<Double> playerRange = scGeneral.add(createDoubleSetting()
            .name("player-range")
            .description("Maximum distance a valid player must be within before Auto Drain can react to a targeted pickupable water source.")
            .min(0.0)
            .max(12.0)
            .def(6.0)
            .decimalPlaces(1)
            .build()
    );

    public final ModuleSetting<Integer> playerFov = scGeneral.add(createIntSetting()
            .name("player-fov")
            .description("Maximum angle in degrees a valid player can be off your crosshair direction for Auto Drain to trigger.")
            .min(1)
            .max(180)
            .def(50)
            .build()
    );

    public final RangeDoubleSetting useDelay = scGeneral.add(createRangeDoubleSetting()
            .name("use-delay")
            .description("Randomized delay before Auto Drain swaps to an empty bucket and queues the drain right-click on a valid targeted water source.")
            .def(0.010, 0.035)
            .min(0.000)
            .max(0.100)
            .decimalPlaces(3)
            .build()
    );

    public final ModuleSetting<Boolean> switchBack = scGeneral.add(createBoolSetting()
            .name("switch-back")
            .description("Switch back to your original hotbar slot after the queued bucket drain finishes.")
            .def(true)
            .build()
    );

    public final RangeDoubleSetting switchBackDelay = scGeneral.add(createRangeDoubleSetting()
            .name("switch-back-delay")
            .description("Randomized extra delay after the queued drain click before your original hotbar slot can be restored.")
            .def(0.010, 0.035)
            .min(0.000)
            .max(0.100)
            .decimalPlaces(3)
            .build()
    );

    public final ModuleSetting<Boolean> cancelSwitchBackOnManualSlotChange = scGeneral.add(createBoolSetting()
            .name("cancel-switch-back-on-manual-slot-change")
            .description("Stay on the slot you manually changed to instead of switching back if you move off the bucket before the delayed restore is ready.")
            .def(true)
            .build()
    );

    private final Set<BlockPos> placedWaterPositions = new HashSet<>();
    private DrainSession activeSession = null;
    private int activeSessionToken = 0;
    private boolean pendingChildSettingsSync = false;
    private boolean pendingSettingsScreenRefresh = false;

    public AutoDrainModule() {
        super("auto-drain", "Switches to a hotbar empty bucket to drain a real targeted water source near players in your FOV, then optionally switches back after short randomized delays while ignoring water you placed.");
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
    private void onMouseScroll(MouseScrollEvent e) {
        if (activeSession != null && mc.screen == null && e.isVertical() && e.getDeltaY() != 0.0) {
            activeSession.setManualSlotChanged(true);
        }
    }

    @EventHandler
    private void onKeyPress(KeyPressEvent e) {
        if (activeSession == null || mc.options == null || !e.getAction().isDown()) {
            return;
        }

        KeyEvent input = new KeyEvent(e.getKeycode(), e.getScancode(), 0);
        for (int slot = 0; slot < mc.options.keyHotbarSlots.length; slot++) {
            if (mc.options.keyHotbarSlots[slot].matches(input)) {
                activeSession.setManualSlotChanged(true);
                return;
            }
        }
    }

    @EventHandler
    private void onPacketSent(PacketSentEvent e) {
        if (!PlayerUtils.valid() || mc.player == null || mc.level == null) {
            return;
        }
        if (!(e.getPacket() instanceof ServerboundUseItemOnPacket packet)) {
            return;
        }

        ItemStack usedStack = mc.player.getItemInHand(packet.getHand());
        if (!usedStack.is(Items.WATER_BUCKET)) {
            return;
        }

        BlockHitResult hit = packet.getHitResult();
        BlockPos clickedPos = hit.getBlockPos().immutable();
        Direction clickedFace = hit.getDirection();
        system.scheduler.runDelayedTask(() -> mc.execute(() -> trackPlacedWater(clickedPos, clickedFace)), MIN_SWITCH_BACK_AFTER_USE_MS);
    }

    @EventHandler
    private void onTickStart(ClientTickStartEvent e) {
        prunePlacedWaterPositions();
        if (!canOperate()) {
            resetRuntimeState();
            return;
        }

        if (activeSession == null) {
            tryStartSession();
            return;
        }

        if (InvUtils.selected() != activeSession.bucketSlot()) {
            activeSession.setManualSlotChanged(true);
            if (!activeSession.drainAttempted()) {
                clearSession();
                return;
            }
        }

        if (!activeSession.drainAttempted() && !canContinuePreparingSession(activeSession)) {
            clearSession();
            return;
        }

        tryFinalizeSession(activeSession.token());
    }

    private void tryStartSession() {
        if (activeSession != null) {
            return;
        }

        BlockPos waterPos = findEligibleWaterTarget();
        if (waterPos == null) {
            return;
        }

        int bucketSlot = findBucketHotbarSlot();
        if (bucketSlot < 0) {
            return;
        }

        int originalSlot = InvUtils.selected();
        if (originalSlot != bucketSlot) {
            InvUtils.select(bucketSlot);
        }

        int sessionToken = ++activeSessionToken;
        activeSession = new DrainSession(sessionToken, originalSlot, bucketSlot, waterPos);
        scheduleDrainAttempt(sessionToken);
    }

    private boolean canOperate() {
        return isEnabled()
                && PlayerUtils.valid()
                && mc.player != null
                && mc.level != null
                && mc.options != null
                && mc.screen == null;
    }

    private BlockPos findEligibleWaterTarget() {
        BlockPos pos = findTargetedWaterPos();
        if (pos == null) {
            return null;
        }
        if (!isEligibleDrainWater(pos)) {
            return null;
        }

        return hasSupportingPlayerTarget() ? pos.immutable() : null;
    }

    private BlockPos findTargetedWaterPos() {
        if (mc.player == null || mc.level == null) {
            return null;
        }

        Vec3 eye = mc.player.getEyePosition();
        Vec3 direction = mc.player.getLookAngle().normalize();
        double maxDistance = Math.max(1.0, mc.player.blockInteractionRange());
        double maxVisibleDistance = getVisibleTraceDistance(eye, direction, maxDistance);

        BlockPos firstFluidPos = null;
        for (double distance = 0.0; distance <= maxVisibleDistance; distance += 0.05) {
            Vec3 point = eye.add(direction.scale(distance));
            BlockPos pos = BlockPos.containing(point);

            if (mc.level.getFluidState(pos).isEmpty()) {
                continue;
            }
            if (firstFluidPos == null) {
                firstFluidPos = pos.immutable();
            }
            if (isPickupableWaterSource(pos)) {
                return pos.immutable();
            }
        }

        return firstFluidPos != null && isPickupableWaterSource(firstFluidPos) ? firstFluidPos : null;
    }

    private double getVisibleTraceDistance(Vec3 eye, Vec3 direction, double maxDistance) {
        if (mc.level == null || mc.player == null) {
            return maxDistance;
        }

        Vec3 end = eye.add(direction.scale(maxDistance));
        BlockHitResult occlusionHit = mc.level.clip(new ClipContext(
                eye,
                end,
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                mc.player
        ));
        if (occlusionHit.getType() != HitResult.Type.BLOCK) {
            return maxDistance;
        }

        return Math.min(maxDistance, eye.distanceTo(occlusionHit.getLocation()));
    }

    private boolean isEligibleDrainWater(BlockPos pos) {
        return isPickupableWaterSource(pos)
                && !placedWaterPositions.contains(pos)
                && !isLikelyInfiniteWaterSource(pos);
    }

    private boolean isPickupableWaterSource(BlockPos pos) {
        if (mc.level == null) {
            return false;
        }

        BlockState state = mc.level.getBlockState(pos);
        return state.is(Blocks.WATER)
                && state.getFluidState().isSource()
                && state.getBlock() instanceof BucketPickup;
    }

    private boolean isLikelyInfiniteWaterSource(BlockPos pos) {
        int horizontalSources = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (isPickupableWaterSource(pos.relative(direction))) {
                horizontalSources++;
                if (horizontalSources >= 2) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasSupportingPlayerTarget() {
        if (mc.level == null || mc.player == null) {
            return false;
        }

        for (Player player : mc.level.players()) {
            if (isValidPlayer(player)
                    && mc.player.distanceTo(player) <= playerRange.getVal()
                    && isWithinFov(player)) {
                return true;
            }
        }
        return false;
    }

    private boolean isValidPlayer(Player player) {
        return player != null
                && player != mc.player
                && player.isAlive()
                && !player.isSpectator()
                && !EntityUtils.shouldCancelCcsAttack(player);
    }

    private boolean isWithinFov(Player player) {
        Vec3 look = mc.player.getViewVector(1.0F).normalize();
        Vec3 toTarget = player.getEyePosition().subtract(mc.player.getEyePosition()).normalize();
        double dot = Mth.clamp(look.dot(toTarget), -1.0, 1.0);
        double angle = Math.toDegrees(Math.acos(dot));
        return angle <= playerFov.getVal();
    }

    private int findBucketHotbarSlot() {
        for (int slot = 0; slot <= 8; slot++) {
            if (mc.player.getInventory().getItem(slot).is(Items.BUCKET)) {
                return slot;
            }
        }
        return -1;
    }

    private boolean canContinuePreparingSession(DrainSession session) {
        return session != null
                && canOperate()
                && !session.manualSlotChanged()
                && InvUtils.selected() == session.bucketSlot()
                && mc.player.getMainHandItem().is(Items.BUCKET)
                && session.waterPos().equals(findEligibleWaterTarget());
    }

    private long getUseDelayMs() {
        return Math.max(0L, Math.round(useDelay.getRandomizedValue() * 1000.0));
    }

    private long getSwitchBackDelayMs() {
        return Math.max(0L, Math.round(switchBackDelay.getRandomizedValue() * 1000.0));
    }

    private void scheduleDrainAttempt(int sessionToken) {
        long delayMs = getUseDelayMs();
        system.scheduler.runDelayedTask(() -> mc.execute(() -> tryDrain(sessionToken)), delayMs);
    }

    private void tryDrain(int sessionToken) {
        if (!isSessionValid(sessionToken)) {
            return;
        }
        if (!canContinuePreparingSession(activeSession)) {
            clearSession();
            return;
        }

        activeSession.setDrainAttempted(true);
        queueUseClick();

        if (switchBack.getVal()) {
            scheduleSwitchBackReady(sessionToken);
        }
        else {
            clearSession();
        }
    }

    private void scheduleSwitchBackReady(int sessionToken) {
        long delayMs = MIN_SWITCH_BACK_AFTER_USE_MS + getSwitchBackDelayMs();
        system.scheduler.runDelayedTask(() -> mc.execute(() -> {
            if (!isSessionValid(sessionToken)) {
                return;
            }
            activeSession.setSwitchBackReady(true);
            tryFinalizeSession(sessionToken);
        }), delayMs);
    }

    private void tryFinalizeSession(int sessionToken) {
        if (!isSessionValid(sessionToken) || !activeSession.drainAttempted()) {
            return;
        }
        if (!switchBack.getVal()) {
            clearSession();
            return;
        }
        if (!activeSession.switchBackReady()) {
            return;
        }
        if (activeSession.manualSlotChanged() && cancelSwitchBackOnManualSlotChange.getVal()) {
            clearSession();
            return;
        }
        if (activeSession.originalSlot() < 0 || activeSession.originalSlot() > 8) {
            clearSession();
            return;
        }

        InvUtils.select(activeSession.originalSlot());
        clearSession();
    }

    private boolean isSessionValid(int sessionToken) {
        return activeSession != null && activeSession.token() == sessionToken;
    }

    private void queueUseClick() {
        mc.execute(() -> {
            AccessorKeyMapping keyUse = (AccessorKeyMapping) mc.options.keyUse;
            keyUse.loopholeEssentials$setClickCount(keyUse.loopholeEssentials$getClickCount() + 1);
        });
    }

    private void trackPlacedWater(BlockPos clickedPos, Direction clickedFace) {
        if (mc.level == null) {
            return;
        }

        rememberPlacedWaterAt(clickedPos);
        rememberPlacedWaterAt(clickedPos.relative(clickedFace));
    }

    private void rememberPlacedWaterAt(BlockPos pos) {
        if (isPickupableWaterSource(pos)) {
            placedWaterPositions.add(pos.immutable());
        }
    }

    private void prunePlacedWaterPositions() {
        if (mc.level == null) {
            placedWaterPositions.clear();
            return;
        }

        Iterator<BlockPos> iterator = placedWaterPositions.iterator();
        while (iterator.hasNext()) {
            if (!isPickupableWaterSource(iterator.next())) {
                iterator.remove();
            }
        }
    }

    private void clearSession() {
        activeSession = null;
        activeSessionToken++;
    }

    private void resetRuntimeState() {
        placedWaterPositions.clear();
        clearSession();
    }

    private void configureChildSettings() {
        switchBack.setChangeAction(setting -> scheduleChildSettingsSync());
    }

    private void syncVisibleSettings() {
        List<ModuleSetting<?>> settings = scGeneral.getSettings();
        settings.clear();
        settings.add(playerRange);
        settings.add(playerFov);
        settings.add(useDelay);
        settings.add(switchBack);
        if (switchBack.getVal()) {
            settings.add(switchBackDelay);
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
                switchBackDelay,
                cancelSwitchBackOnManualSlotChange
        );
    }

    private static final class DrainSession {

        private final int token;
        private final int originalSlot;
        private final int bucketSlot;
        private final BlockPos waterPos;
        private boolean drainAttempted;
        private boolean switchBackReady;
        private boolean manualSlotChanged;

        private DrainSession(int token, int originalSlot, int bucketSlot, BlockPos waterPos) {
            this.token = token;
            this.originalSlot = originalSlot;
            this.bucketSlot = bucketSlot;
            this.waterPos = waterPos;
            this.drainAttempted = false;
            this.switchBackReady = false;
            this.manualSlotChanged = false;
        }

        public int token() {
            return token;
        }

        public int originalSlot() {
            return originalSlot;
        }

        public int bucketSlot() {
            return bucketSlot;
        }

        public BlockPos waterPos() {
            return waterPos;
        }

        public boolean drainAttempted() {
            return drainAttempted;
        }

        public void setDrainAttempted(boolean drainAttempted) {
            this.drainAttempted = drainAttempted;
        }

        public boolean switchBackReady() {
            return switchBackReady;
        }

        public void setSwitchBackReady(boolean switchBackReady) {
            this.switchBackReady = switchBackReady;
        }

        public boolean manualSlotChanged() {
            return manualSlotChanged;
        }

        public void setManualSlotChanged(boolean manualSlotChanged) {
            this.manualSlotChanged = manualSlotChanged;
        }
    }
}
