package com.loophole.essentials.module.modules;

import com.loophole.essentials.mixin.AccessorKeyMapping;
import com.loophole.essentials.module.LoopholeListenerModule;
import com.loophole.essentials.module.PersistentSettingProvider;
import com.loophole.essentials.module.settings.MouseButtonSetting;
import com.loophole.essentials.module.settings.RangeDoubleSetting;
import io.github.itzispyder.clickcrystals.events.EventHandler;
import io.github.itzispyder.clickcrystals.events.events.client.KeyPressEvent;
import io.github.itzispyder.clickcrystals.events.events.client.MouseClickEvent;
import io.github.itzispyder.clickcrystals.events.events.client.MouseScrollEvent;
import io.github.itzispyder.clickcrystals.events.events.networking.GameLeaveEvent;
import io.github.itzispyder.clickcrystals.events.events.world.ClientTickStartEvent;
import io.github.itzispyder.clickcrystals.events.listeners.UserInputListener;
import io.github.itzispyder.clickcrystals.gui.ClickType;
import io.github.itzispyder.clickcrystals.gui.screens.ModuleEditScreen;
import io.github.itzispyder.clickcrystals.modules.ModuleSetting;
import io.github.itzispyder.clickcrystals.modules.settings.SettingSection;
import io.github.itzispyder.clickcrystals.util.minecraft.InvUtils;
import io.github.itzispyder.clickcrystals.util.minecraft.PlayerUtils;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.Collection;
import java.util.List;

public class KeyBucketModule extends LoopholeListenerModule implements PersistentSettingProvider {

    private static final long PICKUP_CONFIRM_TIMEOUT_MS = 1000L;

    private final SettingSection scGeneral = getGeneralSection();

    public final MouseButtonSetting lavaActivationButton = scGeneral.add(createMouseButtonSetting()
            .name("lava-activation-button")
            .description("Key or mouse button that runs the lava bucket action once per press. Supports keyboard keys plus Right Click, Middle Click, Mouse 4, and Mouse 5.")
            .def(GLFW.GLFW_KEY_X)
            .build()
    );

    public final MouseButtonSetting waterActivationButton = scGeneral.add(createMouseButtonSetting()
            .name("water-activation-button")
            .description("Key or mouse button that runs the water bucket action once per press. Supports keyboard keys plus Right Click, Middle Click, Mouse 4, and Mouse 5.")
            .def(GLFW.GLFW_KEY_Z)
            .build()
    );

    public final ModuleSetting<Boolean> switchBack = scGeneral.add(createBoolSetting()
            .name("switch-back")
            .description("Switch back to the hotbar slot you were on before a remembered lava or water placement after a later bucket pickup confirms.")
            .def(true)
            .build()
    );

    public final RangeDoubleSetting switchBackDelay = scGeneral.add(createRangeDoubleSetting()
            .name("switch-back-delay")
            .description("Randomized delay after a bucket pickup confirms before the remembered original slot can be restored.")
            .def(0.000, 0.050)
            .min(0.000)
            .max(0.100)
            .decimalPlaces(3)
            .build()
    );

    public final ModuleSetting<Boolean> cancelSwitchBackOnManualSlotChange = scGeneral.add(createBoolSetting()
            .name("cancel-switch-back-on-manual-slot-change")
            .description("Stay on the slot you manually changed to instead of restoring the remembered slot before the delayed switch-back becomes ready.")
            .def(true)
            .build()
    );

    private PickupSequence activeSequence = null;
    private int activeSequenceToken = 0;
    private int rememberedLavaOriginalSlot = -1;
    private int rememberedWaterOriginalSlot = -1;
    private boolean lavaActivationHeld = false;
    private boolean waterActivationHeld = false;
    private boolean pendingChildSettingsSync = false;
    private boolean pendingSettingsScreenRefresh = false;

    public KeyBucketModule() {
        super("key-bucket", "Uses separate configurable lava and water binds to place the matching hotbar bucket or pick up a real targeted water or lava source with an empty bucket, then optionally switches back after a confirmed pickup.");
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
        if (handleMouseBindingEvent(ActionType.LAVA, e)) {
            return;
        }
        handleMouseBindingEvent(ActionType.WATER, e);
    }

    @EventHandler
    private void onMouseScroll(MouseScrollEvent e) {
        if (activeSequence != null && mc.screen == null && e.isVertical() && e.getDeltaY() != 0.0) {
            activeSequence.setManualSlotChanged(true);
        }
    }

    @EventHandler
    private void onKeyPress(KeyPressEvent e) {
        if (handleKeyBindingEvent(ActionType.LAVA, e)) {
            return;
        }
        if (handleKeyBindingEvent(ActionType.WATER, e)) {
            return;
        }

        if (activeSequence == null || mc.options == null || !e.getAction().isDown()) {
            return;
        }

        KeyEvent input = new KeyEvent(e.getKeycode(), e.getScancode(), 0);
        for (int slot = 0; slot < mc.options.keyHotbarSlots.length; slot++) {
            if (mc.options.keyHotbarSlots[slot].matches(input)) {
                activeSequence.setManualSlotChanged(true);
                return;
            }
        }
    }

    @EventHandler
    private void onTickStart(ClientTickStartEvent e) {
        syncActivationState();
        if (activeSequence == null) {
            return;
        }
        if (!canOperate()) {
            resetRuntimeState();
            return;
        }

        if (InvUtils.selected() != activeSequence.bucketSlot()) {
            activeSequence.setManualSlotChanged(true);
        }

        if (!activeSequence.pickupConfirmed()) {
            if (didBucketFill(activeSequence.bucketSlot(), activeSequence.expectedFilledBucket())) {
                activeSequence.setPickupConfirmed(true);
                handleConfirmedPickup(activeSequence.token());
                return;
            }
            if (System.currentTimeMillis() > activeSequence.confirmationDeadlineMs()) {
                clearSequence();
            }
            return;
        }

        tryFinalizeSequence(activeSequence.token());
    }

    private boolean handleMouseBindingEvent(ActionType actionType, MouseClickEvent e) {
        MouseButtonSetting binding = getBinding(actionType);
        if (!binding.isMouseBinding() || !binding.matchesMouse(e.getButton())) {
            return false;
        }

        if (e.getAction() == ClickType.CLICK) {
            handleActivationPressed(actionType);
            return true;
        }
        if (e.getAction() == ClickType.RELEASE) {
            handleActivationReleased(actionType);
            return true;
        }
        return false;
    }

    private boolean handleKeyBindingEvent(ActionType actionType, KeyPressEvent e) {
        MouseButtonSetting binding = getBinding(actionType);
        if (!binding.isKeyboardBinding() || !binding.matchesKey(e.getKeycode())) {
            return false;
        }

        if (e.getAction() == ClickType.CLICK) {
            handleActivationPressed(actionType);
            return true;
        }
        if (e.getAction() == ClickType.RELEASE) {
            handleActivationReleased(actionType);
            return true;
        }
        return false;
    }

    private void handleActivationPressed(ActionType actionType) {
        if (isActivationHeld(actionType)) {
            return;
        }

        setActivationHeld(actionType, true);
        tryHandleAction(actionType);
    }

    private void handleActivationReleased(ActionType actionType) {
        if (!isActivationHeld(actionType)) {
            return;
        }

        setActivationHeld(actionType, false);
    }

    private void syncActivationState() {
        syncActivationState(ActionType.LAVA);
        syncActivationState(ActionType.WATER);
    }

    private void syncActivationState(ActionType actionType) {
        boolean heldNow = isActivationHeldNow(actionType);
        if (heldNow == isActivationHeld(actionType)) {
            return;
        }

        if (heldNow) {
            handleActivationPressed(actionType);
        }
        else {
            handleActivationReleased(actionType);
        }
    }

    private boolean isActivationHeldNow(ActionType actionType) {
        MouseButtonSetting binding = getBinding(actionType);
        if (!isEnabled()
                || mc == null
                || mc.getWindow() == null
                || mc.screen != null
                || binding.isNone()) {
            return false;
        }

        if (binding.isKeyboardBinding()) {
            return UserInputListener.isKeyPressed(binding.getKey());
        }
        if (binding.isMouseBinding()) {
            return GLFW.glfwGetMouseButton(mc.getWindow().handle(), binding.getButton()) == GLFW.GLFW_PRESS;
        }
        return false;
    }

    private void tryHandleAction(ActionType actionType) {
        if (!canOperate() || getBinding(actionType).isNone() || activeSequence != null) {
            return;
        }

        FluidTarget target = findTargetedPickupableFluid();
        if (target != null) {
            int bucketSlot = findHotbarSlot(Items.BUCKET);
            if (bucketSlot >= 0) {
                startPickupSequence(actionType, bucketSlot, target.filledBucket());
                return;
            }

            useFluidBucket(actionType, false);
            return;
        }

        if (!isTargetingBlock()) {
            return;
        }

        useFluidBucket(actionType, true);
    }

    private void useFluidBucket(ActionType actionType, boolean rememberOriginalSlot) {
        int fluidBucketSlot = findHotbarSlot(actionType.bucketItem());
        if (fluidBucketSlot < 0) {
            return;
        }

        int originalSlot = InvUtils.selected();
        if (originalSlot != fluidBucketSlot) {
            InvUtils.select(fluidBucketSlot);
        }
        if (rememberOriginalSlot) {
            rememberOriginalSlot(actionType, originalSlot);
        }

        queueUseClick();
    }

    private void startPickupSequence(ActionType actionType, int bucketSlot, Item expectedFilledBucket) {
        int plannedSwitchBackSlot = shouldPlanSwitchBack(actionType) ? getRememberedOriginalSlot(actionType) : -1;
        if (InvUtils.selected() != bucketSlot) {
            InvUtils.select(bucketSlot);
        }

        int sequenceToken = ++activeSequenceToken;
        activeSequence = new PickupSequence(
                sequenceToken,
                actionType,
                bucketSlot,
                expectedFilledBucket,
                plannedSwitchBackSlot,
                System.currentTimeMillis() + PICKUP_CONFIRM_TIMEOUT_MS
        );
        queueUseClick();
    }

    private void handleConfirmedPickup(int sequenceToken) {
        if (!isSequenceValid(sequenceToken)) {
            return;
        }

        clearRememberedOriginalSlot(activeSequence.actionType());
        if (activeSequence.switchBackSlot() < 0) {
            clearSequence();
            return;
        }

        scheduleSwitchBackReady(sequenceToken);
    }

    private void scheduleSwitchBackReady(int sequenceToken) {
        long delayMs = getSwitchBackDelayMs();
        system.scheduler.runDelayedTask(() -> mc.execute(() -> {
            if (!isSequenceValid(sequenceToken)) {
                return;
            }
            activeSequence.setSwitchBackReady(true);
            tryFinalizeSequence(sequenceToken);
        }), delayMs);
    }

    private void tryFinalizeSequence(int sequenceToken) {
        if (!isSequenceValid(sequenceToken) || !activeSequence.pickupConfirmed()) {
            return;
        }
        if (activeSequence.switchBackSlot() < 0) {
            clearSequence();
            return;
        }
        if (!activeSequence.switchBackReady()) {
            return;
        }
        if (activeSequence.manualSlotChanged() && cancelSwitchBackOnManualSlotChange.getVal()) {
            clearSequence();
            return;
        }
        if (activeSequence.switchBackSlot() < 0 || activeSequence.switchBackSlot() > 8) {
            clearSequence();
            return;
        }

        InvUtils.select(activeSequence.switchBackSlot());
        clearSequence();
    }

    private boolean canOperate() {
        return isEnabled()
                && PlayerUtils.valid()
                && mc.player != null
                && mc.level != null
                && mc.options != null
                && mc.screen == null;
    }

    private boolean isTargetingBlock() {
        return mc.hitResult instanceof BlockHitResult && mc.hitResult.getType() == HitResult.Type.BLOCK;
    }

    private FluidTarget findTargetedPickupableFluid() {
        if (mc.player == null || mc.level == null) {
            return null;
        }

        Vec3 eye = mc.player.getEyePosition();
        Vec3 direction = mc.player.getLookAngle().normalize();
        double maxDistance = Math.max(1.0, mc.player.blockInteractionRange());
        Vec3 end = eye.add(direction.scale(maxDistance));
        BlockHitResult fluidHit = mc.level.clip(new ClipContext(
                eye,
                end,
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.SOURCE_ONLY,
                mc.player
        ));
        if (fluidHit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        return createFluidTarget(fluidHit.getBlockPos());
    }

    private FluidTarget createFluidTarget(BlockPos pos) {
        if (mc.level == null) {
            return null;
        }

        BlockState state = mc.level.getBlockState(pos);
        if (!state.getFluidState().isSource() || !(state.getBlock() instanceof BucketPickup)) {
            return null;
        }
        if (state.is(net.minecraft.world.level.block.Blocks.WATER)) {
            return new FluidTarget(pos.immutable(), Items.WATER_BUCKET);
        }
        if (state.is(net.minecraft.world.level.block.Blocks.LAVA)) {
            return new FluidTarget(pos.immutable(), Items.LAVA_BUCKET);
        }
        return null;
    }

    private int findHotbarSlot(Item item) {
        for (int slot = 0; slot <= 8; slot++) {
            if (mc.player.getInventory().getItem(slot).is(item)) {
                return slot;
            }
        }
        return -1;
    }

    private boolean didBucketFill(int bucketSlot, Item expectedFilledBucket) {
        return bucketSlot >= 0
                && bucketSlot <= 8
                && mc.player != null
                && mc.player.getInventory().getItem(bucketSlot).is(expectedFilledBucket);
    }

    private long getSwitchBackDelayMs() {
        return Math.max(0L, Math.round(switchBackDelay.getRandomizedValue() * 1000.0));
    }

    private boolean shouldPlanSwitchBack(ActionType actionType) {
        return switchBack.getVal() && getRememberedOriginalSlot(actionType) >= 0;
    }

    private int getRememberedOriginalSlot(ActionType actionType) {
        return actionType == ActionType.LAVA ? rememberedLavaOriginalSlot : rememberedWaterOriginalSlot;
    }

    private void rememberOriginalSlot(ActionType actionType, int originalSlot) {
        if (actionType == ActionType.LAVA) {
            rememberedLavaOriginalSlot = originalSlot;
            return;
        }
        rememberedWaterOriginalSlot = originalSlot;
    }

    private void clearRememberedOriginalSlot(ActionType actionType) {
        if (actionType == ActionType.LAVA) {
            rememberedLavaOriginalSlot = -1;
            return;
        }
        rememberedWaterOriginalSlot = -1;
    }

    private void queueUseClick() {
        mc.execute(() -> {
            AccessorKeyMapping keyUse = (AccessorKeyMapping) mc.options.keyUse;
            KeyMapping.click(keyUse.loopholeEssentials$getBoundKey());
        });
    }

    private boolean isSequenceValid(int sequenceToken) {
        return activeSequence != null && activeSequence.token() == sequenceToken;
    }

    private void clearSequence() {
        activeSequence = null;
        activeSequenceToken++;
    }

    private void resetRuntimeState() {
        rememberedLavaOriginalSlot = -1;
        rememberedWaterOriginalSlot = -1;
        lavaActivationHeld = false;
        waterActivationHeld = false;
        clearSequence();
    }

    private MouseButtonSetting getBinding(ActionType actionType) {
        return actionType == ActionType.LAVA ? lavaActivationButton : waterActivationButton;
    }

    private boolean isActivationHeld(ActionType actionType) {
        return actionType == ActionType.LAVA ? lavaActivationHeld : waterActivationHeld;
    }

    private void setActivationHeld(ActionType actionType, boolean held) {
        if (actionType == ActionType.LAVA) {
            lavaActivationHeld = held;
            return;
        }
        waterActivationHeld = held;
    }

    private void configureChildSettings() {
        switchBack.setChangeAction(setting -> scheduleChildSettingsSync());
    }

    private void syncVisibleSettings() {
        List<ModuleSetting<?>> settings = scGeneral.getSettings();
        settings.clear();
        settings.add(lavaActivationButton);
        settings.add(waterActivationButton);
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

    private enum ActionType {
        LAVA(Items.LAVA_BUCKET),
        WATER(Items.WATER_BUCKET);

        private final Item bucketItem;

        ActionType(Item bucketItem) {
            this.bucketItem = bucketItem;
        }

        public Item bucketItem() {
            return bucketItem;
        }
    }

    private static final class FluidTarget {

        private final Item filledBucket;

        private FluidTarget(BlockPos pos, Item filledBucket) {
            this.filledBucket = filledBucket;
        }

        public Item filledBucket() {
            return filledBucket;
        }
    }

    private static final class PickupSequence {

        private final int token;
        private final ActionType actionType;
        private final int bucketSlot;
        private final Item expectedFilledBucket;
        private final int switchBackSlot;
        private final long confirmationDeadlineMs;
        private boolean pickupConfirmed;
        private boolean switchBackReady;
        private boolean manualSlotChanged;

        private PickupSequence(int token, ActionType actionType, int bucketSlot, Item expectedFilledBucket, int switchBackSlot, long confirmationDeadlineMs) {
            this.token = token;
            this.actionType = actionType;
            this.bucketSlot = bucketSlot;
            this.expectedFilledBucket = expectedFilledBucket;
            this.switchBackSlot = switchBackSlot;
            this.confirmationDeadlineMs = confirmationDeadlineMs;
            this.pickupConfirmed = false;
            this.switchBackReady = false;
            this.manualSlotChanged = false;
        }

        public int token() {
            return token;
        }

        public ActionType actionType() {
            return actionType;
        }

        public int bucketSlot() {
            return bucketSlot;
        }

        public Item expectedFilledBucket() {
            return expectedFilledBucket;
        }

        public int switchBackSlot() {
            return switchBackSlot;
        }

        public long confirmationDeadlineMs() {
            return confirmationDeadlineMs;
        }

        public boolean pickupConfirmed() {
            return pickupConfirmed;
        }

        public void setPickupConfirmed(boolean pickupConfirmed) {
            this.pickupConfirmed = pickupConfirmed;
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
