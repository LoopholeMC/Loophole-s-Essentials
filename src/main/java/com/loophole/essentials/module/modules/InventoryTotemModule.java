package com.loophole.essentials.module.modules;

import com.loophole.essentials.mixin.AccessorAbstractContainerScreen;
import com.loophole.essentials.module.LoopholeListenerModule;
import com.loophole.essentials.module.PersistentSettingProvider;
import com.loophole.essentials.module.settings.RangeDoubleSetting;
import io.github.itzispyder.clickcrystals.events.EventHandler;
import io.github.itzispyder.clickcrystals.events.events.client.KeyPressEvent;
import io.github.itzispyder.clickcrystals.events.events.client.RenderInventorySlotEvent;
import io.github.itzispyder.clickcrystals.events.events.networking.GameLeaveEvent;
import io.github.itzispyder.clickcrystals.events.events.world.ClientTickStartEvent;
import io.github.itzispyder.clickcrystals.gui.ClickType;
import io.github.itzispyder.clickcrystals.gui.screens.ModuleEditScreen;
import io.github.itzispyder.clickcrystals.mixins.AccessorHandledScreen;
import io.github.itzispyder.clickcrystals.modules.ModuleSetting;
import io.github.itzispyder.clickcrystals.modules.modules.misc.GuiCursor;
import io.github.itzispyder.clickcrystals.modules.settings.SettingSection;
import io.github.itzispyder.clickcrystals.util.minecraft.InteractionUtils;
import io.github.itzispyder.clickcrystals.util.minecraft.InvUtils;
import io.github.itzispyder.clickcrystals.util.minecraft.PlayerUtils;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.awt.Point;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class InventoryTotemModule extends LoopholeListenerModule implements PersistentSettingProvider {

    private static final long EQUIP_CONFIRMATION_WINDOW_MS = 500L;
    private static final int HOTBAR_START_SLOT = 0;
    private static final int HOTBAR_END_SLOT = 8;
    private static final int PLAYER_MAIN_INVENTORY_START_SLOT = 9;
    private static final int PLAYER_MAIN_INVENTORY_END_SLOT = 35;
    private static final int OFFHAND_SWAP_BUTTON = 40;

    private final SettingSection scGeneral = getGeneralSection();

    public final ModuleSetting<Boolean> smoothCursor = scGeneral.add(createBoolSetting()
            .name("smooth-cursor")
            .description("Smoothly drags the inventory cursor toward the chosen totem before the swap key action. Disable this to move to the target slot directly without waiting for cursor hover.")
            .def(true)
            .build()
    );

    public final RangeDoubleSetting cursorMoveDuration = scGeneral.add(createRangeDoubleSetting()
            .name("cursor-move-duration")
            .description("Randomized cursor-travel duration before the totem hover is reached when smooth-cursor is enabled.")
            .def(0.000, 0.050)
            .min(0.000)
            .max(0.100)
            .decimalPlaces(3)
            .build()
    );

    public final ModuleSetting<Boolean> randomizeHoverPoint = scGeneral.add(createBoolSetting()
            .name("randomize-hover-point")
            .description("Aim at a randomized point inside the chosen totem slot instead of always moving to the slot center.")
            .def(false)
            .build()
    );

    public final ModuleSetting<CursorCurveMode> cursorCurveMode = scGeneral.add(createEnumSetting(CursorCurveMode.class)
            .name("cursor-curve-mode")
            .description("Optional alternate cursor path to use during smooth movement instead of a plain straight line.")
            .def(CursorCurveMode.OFF)
            .build()
    );

    public final ModuleSetting<Integer> customCurveChance = scGeneral.add(createIntSetting()
            .name("custom-curve-chance")
            .description("Chance to apply the selected non-off cursor curve for a given smooth movement. Failed rolls fall back to a straight cursor path.")
            .def(100)
            .min(0)
            .max(100)
            .build()
    );

    public final RangeDoubleSetting swapDelay = scGeneral.add(createRangeDoubleSetting()
            .name("swap-delay")
            .description("Randomized delay after the target totem is hovered before the offhand or remembered hotbar-key swap is sent.")
            .def(0.000, 0.050)
            .min(0.000)
            .max(0.100)
            .decimalPlaces(3)
            .build()
    );

    private ActiveSession activeSession = null;
    private int activeSessionToken = 0;
    private int rememberedTotemHotbarSlot = -1;
    private boolean pendingChildSettingsSync = false;
    private boolean pendingSettingsScreenRefresh = false;

    public InventoryTotemModule() {
        super("inventory-totem", "Keeps a totem in the offhand and remembers a totem hotbar slot while the player inventory screen is open.");
        configureChildSettings();
        syncVisibleSettings();
    }

    @Override
    protected void onDisable() {
        super.onDisable();
        clearSession();
    }

    @EventHandler
    private void onGameLeave(GameLeaveEvent e) {
        clearSession();
    }

    @EventHandler
    private void onTickStart(ClientTickStartEvent e) {
        syncRememberedTotemHotbarSlot();

        if (activeSession != null && !isSessionStillRelevant(activeSession)) {
            clearSession();
        }

        if (activeSession == null) {
            tryStartSession();
        }
    }

    @EventHandler
    private void onKeyPress(KeyPressEvent e) {
        if (!isEnabled() || !canOperate() || mc.options == null) {
            return;
        }

        if (mc.screen == null
                && e.getAction() == ClickType.CLICK
                && hasRememberedTotemHotbarSlot()
                && inventoryKeyMatches(e)) {
            InvUtils.select(rememberedTotemHotbarSlot);
            return;
        }

        if (mc.screen != null || !e.getAction().isDown()) {
            return;
        }

        KeyEvent input = new KeyEvent(e.getKeycode(), e.getScancode(), 0);
        for (int slot = HOTBAR_START_SLOT; slot <= HOTBAR_END_SLOT; slot++) {
            if (mc.options.keyHotbarSlots[slot].matches(input) && hotbarSlotHasTotem(slot)) {
                rememberedTotemHotbarSlot = slot;
                return;
            }
        }
    }

    private void tryStartSession() {
        AbstractContainerScreen<?> screen = getActiveInventoryScreen();
        if (!canOperateInInventory() || screen == null) {
            return;
        }

        DesiredAction desiredAction = determineDesiredAction();
        if (desiredAction == null) {
            return;
        }

        TotemSource source = findBestTotemSource(screen, desiredAction.targetSwapButton());
        if (source == null) {
            return;
        }

        Point cursor = InteractionUtils.getCursor();
        Point targetPoint = selectTargetPoint(source);
        boolean requireHover = smoothCursor.getVal();
        boolean alreadyHovering = isSlotHovered(screen, source.slot());
        long moveDurationMs = requireHover && !alreadyHovering ? getMoveDurationMs() : 0L;
        CursorCurveMode appliedCurveMode = pickAppliedCurveMode();
        int curveDirection = ThreadLocalRandom.current().nextBoolean() ? 1 : -1;

        activeSession = new ActiveSession(
                ++activeSessionToken,
                source.slot().index,
                desiredAction.targetSwapButton(),
                requireHover,
                System.currentTimeMillis(),
                moveDurationMs,
                cursor.x,
                cursor.y,
                targetPoint.x,
                targetPoint.y,
                appliedCurveMode,
                curveDirection
        );

        if (!requireHover) {
            GuiCursor.setCursor(targetPoint.x, targetPoint.y);
            armSwapDelay(activeSession, System.currentTimeMillis());
        }
        else if (alreadyHovering) {
            activeSession.setHoverLocked(true);
            armSwapDelay(activeSession, System.currentTimeMillis());
        }
    }

    @EventHandler
    private void onRenderInventorySlot(RenderInventorySlotEvent e) {
        if (activeSession != null) {
            tickActiveSession(activeSession.token());
        }
    }

    private void tickActiveSession(int sessionToken) {
        if (!isSessionValid(sessionToken)) {
            return;
        }

        AbstractContainerScreen<?> screen = getActiveInventoryScreen();
        if (screen == null) {
            clearSession();
            return;
        }

        Slot sourceSlot = findMenuSlotByIndex(screen, activeSession.sourceSlotIndex());
        if (sourceSlot == null || !isEligibleTotemSourceSlot(sourceSlot)) {
            clearSession();
            return;
        }

        long now = System.currentTimeMillis();
        if (activeSession.swapSent()) {
            if (isTargetSatisfied(activeSession.targetSwapButton())) {
                finalizeConfirmedSwap(activeSession);
                clearSession();
                return;
            }
            if (now > activeSession.swapSentAtMs() + EQUIP_CONFIRMATION_WINDOW_MS) {
                clearSession();
            }
            return;
        }

        if (activeSession.requiresHover()) {
            if (!activeSession.hoverLocked()) {
                updateCursorPosition(activeSession, now);
            }
            if (!isSlotHovered(screen, sourceSlot)) {
                return;
            }
            activeSession.setHoverLocked(true);
        }

        if (!activeSession.delayArmed()) {
            armSwapDelay(activeSession, now);
            if (!activeSession.delayArmed()) {
                return;
            }
        }

        if (now < activeSession.readyAtMs()) {
            return;
        }

        tryExecuteSwap(sessionToken);
    }

    private DesiredAction determineDesiredAction() {
        if (!offhandHasTotem()) {
            return new DesiredAction(OFFHAND_SWAP_BUTTON);
        }
        if (hasRememberedTotemHotbarSlot() && !hotbarSlotHasTotem(rememberedTotemHotbarSlot)) {
            return new DesiredAction(rememberedTotemHotbarSlot);
        }
        return null;
    }

    private boolean inventoryKeyMatches(KeyPressEvent e) {
        KeyEvent input = new KeyEvent(e.getKeycode(), e.getScancode(), 0);
        return mc.options.keyInventory.matches(input);
    }

    private boolean canOperate() {
        return isEnabled()
                && PlayerUtils.valid()
                && mc.player != null
                && mc.level != null
                && mc.options != null;
    }

    private boolean canOperateInInventory() {
        return canOperate() && getActiveInventoryScreen() != null;
    }

    private AbstractContainerScreen<?> getActiveInventoryScreen() {
        if (mc.screen instanceof InventoryScreen inventoryScreen) {
            return inventoryScreen;
        }
        if (mc.screen instanceof CreativeModeInventoryScreen creativeScreen) {
            return creativeScreen;
        }
        return null;
    }

    private void syncRememberedTotemHotbarSlot() {
        if (!canOperate()) {
            return;
        }
        if (hasRememberedTotemHotbarSlot() && hotbarSlotHasTotem(rememberedTotemHotbarSlot)) {
            return;
        }
        int detectedSlot = findFirstHotbarTotemSlot();
        if (detectedSlot >= 0) {
            rememberedTotemHotbarSlot = detectedSlot;
        }
    }

    private int findFirstHotbarTotemSlot() {
        for (int slot = HOTBAR_START_SLOT; slot <= HOTBAR_END_SLOT; slot++) {
            if (hotbarSlotHasTotem(slot)) {
                return slot;
            }
        }
        return -1;
    }

    private boolean hotbarSlotHasTotem(int slot) {
        return slot >= HOTBAR_START_SLOT
                && slot <= HOTBAR_END_SLOT
                && mc.player != null
                && mc.player.getInventory().getItem(slot).is(Items.TOTEM_OF_UNDYING);
    }

    private boolean offhandHasTotem() {
        return mc.player != null && mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING);
    }

    private boolean hasRememberedTotemHotbarSlot() {
        return rememberedTotemHotbarSlot >= HOTBAR_START_SLOT && rememberedTotemHotbarSlot <= HOTBAR_END_SLOT;
    }

    private TotemSource findBestTotemSource(AbstractContainerScreen<?> screen, int targetSwapButton) {
        Point cursor = InteractionUtils.getCursor();
        TotemSource bestSource = null;
        double bestDistanceSq = Double.MAX_VALUE;

        for (Slot slot : screen.getMenu().slots) {
            if (!isEligibleTotemSourceSlot(slot)) {
                continue;
            }
            if (targetSwapButton != OFFHAND_SWAP_BUTTON && slot.index == targetSwapButton) {
                continue;
            }

            TotemSource candidate = createTotemSource(screen, slot);
            if (candidate == null) {
                continue;
            }

            double distanceSq = squaredDistanceToSlot(cursor.x, cursor.y, candidate.left(), candidate.top(), candidate.right(), candidate.bottom());
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                bestSource = candidate;
            }
        }

        return bestSource;
    }

    private TotemSource createTotemSource(AbstractContainerScreen<?> screen, Slot slot) {
        if (slot == null) {
            return null;
        }
        AccessorAbstractContainerScreen accessor = (AccessorAbstractContainerScreen) screen;
        int left = accessor.loopholeEssentials$getLeftPos() + slot.x;
        int top = accessor.loopholeEssentials$getTopPos() + slot.y;
        return new TotemSource(slot, left, top, left + 16, top + 16);
    }

    private boolean isEligibleTotemSourceSlot(Slot slot) {
        return slot != null
                && slot.hasItem()
                && slot.getItem().is(Items.TOTEM_OF_UNDYING)
                && slot.index >= HOTBAR_START_SLOT
                && slot.index <= PLAYER_MAIN_INVENTORY_END_SLOT;
    }

    private boolean isSessionStillRelevant(ActiveSession session) {
        AbstractContainerScreen<?> screen = getActiveInventoryScreen();
        if (!canOperateInInventory() || screen == null) {
            return false;
        }

        if (session.swapSent()) {
            return true;
        }

        Slot sourceSlot = findMenuSlotByIndex(screen, session.sourceSlotIndex());
        if (!isEligibleTotemSourceSlot(sourceSlot)) {
            return false;
        }
        return !isTargetSatisfied(session.targetSwapButton());
    }

    private Slot findMenuSlotByIndex(AbstractContainerScreen<?> screen, int slotIndex) {
        for (Slot slot : screen.getMenu().slots) {
            if (slot.index == slotIndex) {
                return slot;
            }
        }
        return null;
    }

    private boolean isSlotHovered(AbstractContainerScreen<?> screen, Slot slot) {
        Point cursor = InteractionUtils.getCursor();
        return ((AccessorHandledScreen) screen).isHovered(slot, cursor.x, cursor.y);
    }

    private Point selectTargetPoint(TotemSource source) {
        if (!randomizeHoverPoint.getVal()) {
            return new Point(source.centerX(), source.centerY());
        }

        int minX = source.left() + 2;
        int maxX = source.right() - 3;
        int minY = source.top() + 2;
        int maxY = source.bottom() - 3;
        int x = ThreadLocalRandom.current().nextInt(minX, maxX + 1);
        int y = ThreadLocalRandom.current().nextInt(minY, maxY + 1);
        return new Point(x, y);
    }

    private void armSwapDelay(ActiveSession session, long now) {
        long delayMs = getSwapDelayMs();
        session.setReadyAtMs(now + delayMs);
        session.setDelayArmed(true);
    }

    private long getMoveDurationMs() {
        return Math.max(0L, Math.round(cursorMoveDuration.getRandomizedValue() * 1000.0));
    }

    private long getSwapDelayMs() {
        return Math.max(0L, Math.round(swapDelay.getRandomizedValue() * 1000.0));
    }

    private CursorCurveMode pickAppliedCurveMode() {
        CursorCurveMode selectedMode = cursorCurveMode.getVal();
        if (selectedMode == CursorCurveMode.OFF) {
            return CursorCurveMode.OFF;
        }
        int roll = ThreadLocalRandom.current().nextInt(100) + 1;
        return roll <= customCurveChance.getVal() ? selectedMode : CursorCurveMode.OFF;
    }

    private void updateCursorPosition(ActiveSession session, long now) {
        double progress;
        if (session.moveDurationMs() <= 0L) {
            progress = 1.0;
        }
        else {
            progress = Math.min(1.0, Math.max(0.0, (double) (now - session.moveStartedAtMs()) / (double) session.moveDurationMs()));
        }

        double pathProgress = switch (session.curveMode()) {
            case EASE_IN_OUT, ARC -> easeInOut(progress);
            case OFF -> progress;
        };

        double x = lerp(session.startCursorX(), session.targetCursorX(), pathProgress);
        double y = lerp(session.startCursorY(), session.targetCursorY(), pathProgress);

        if (session.curveMode() == CursorCurveMode.ARC) {
            double dx = session.targetCursorX() - session.startCursorX();
            double dy = session.targetCursorY() - session.startCursorY();
            double distance = Math.hypot(dx, dy);
            if (distance > 0.0) {
                double nx = -dy / distance;
                double ny = dx / distance;
                double amplitude = Math.min(18.0, distance * 0.18) * Math.sin(progress * Math.PI) * session.curveDirection();
                x += nx * amplitude;
                y += ny * amplitude;
            }
        }

        GuiCursor.setCursor((int) Math.round(x), (int) Math.round(y));
    }

    private void tryExecuteSwap(int sessionToken) {
        if (!isSessionValid(sessionToken)) {
            return;
        }

        AbstractContainerScreen<?> screen = getActiveInventoryScreen();
        if (screen == null) {
            clearSession();
            return;
        }

        Slot sourceSlot = findMenuSlotByIndex(screen, activeSession.sourceSlotIndex());
        if (!isEligibleTotemSourceSlot(sourceSlot)) {
            clearSession();
            return;
        }

        if (activeSession.targetSwapButton() == OFFHAND_SWAP_BUTTON && offhandHasTotem()) {
            clearSession();
            return;
        }
        if (activeSession.targetSwapButton() != OFFHAND_SWAP_BUTTON && hotbarSlotHasTotem(activeSession.targetSwapButton())) {
            clearSession();
            return;
        }

        sendSwapPacket(screen, sourceSlot, activeSession.targetSwapButton());
        activeSession.setSwapSent(true);
        activeSession.setSwapSentAtMs(System.currentTimeMillis());
    }

    private void sendSwapPacket(AbstractContainerScreen<?> screen, Slot sourceSlot, int targetSwapButton) {
        ItemStack stack = sourceSlot.getItem();
        HashedStack hash = HashedStack.create(stack, component -> sourceSlot.index);
        ServerboundContainerClickPacket packet = new ServerboundContainerClickPacket(
                screen.getMenu().containerId,
                screen.getMenu().getStateId(),
                (short) sourceSlot.index,
                (byte) targetSwapButton,
                ContainerInput.SWAP,
                Int2ObjectMaps.singleton(sourceSlot.index, hash),
                hash
        );
        PlayerUtils.sendPacket(packet);
    }

    private boolean isSessionValid(int sessionToken) {
        return activeSession != null && activeSession.token() == sessionToken;
    }

    private boolean isTargetSatisfied(int targetSwapButton) {
        if (targetSwapButton == OFFHAND_SWAP_BUTTON) {
            return offhandHasTotem();
        }
        return hotbarSlotHasTotem(targetSwapButton);
    }

    private void finalizeConfirmedSwap(ActiveSession session) {
        if (session.targetSwapButton() == OFFHAND_SWAP_BUTTON
                && session.sourceSlotIndex() >= HOTBAR_START_SLOT
                && session.sourceSlotIndex() <= HOTBAR_END_SLOT) {
            rememberedTotemHotbarSlot = session.sourceSlotIndex();
        }
        else if (session.targetSwapButton() >= HOTBAR_START_SLOT
                && session.targetSwapButton() <= HOTBAR_END_SLOT) {
            rememberedTotemHotbarSlot = session.targetSwapButton();
        }
    }

    private void clearSession() {
        activeSession = null;
        activeSessionToken++;
    }

    private void configureChildSettings() {
        smoothCursor.setChangeAction(setting -> scheduleChildSettingsSync());
        cursorCurveMode.setChangeAction(setting -> scheduleChildSettingsSync());
    }

    private void syncVisibleSettings() {
        List<ModuleSetting<?>> settings = scGeneral.getSettings();
        settings.clear();
        settings.add(smoothCursor);
        settings.add(randomizeHoverPoint);
        if (smoothCursor.getVal()) {
            settings.add(cursorMoveDuration);
            settings.add(cursorCurveMode);
            if (cursorCurveMode.getVal() != CursorCurveMode.OFF) {
                settings.add(customCurveChance);
            }
        }
        settings.add(swapDelay);
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
                cursorMoveDuration,
                cursorCurveMode,
                customCurveChance
        );
    }

    private double lerp(double start, double end, double progress) {
        return start + (end - start) * progress;
    }

    private double easeInOut(double progress) {
        return progress < 0.5
                ? 4.0 * progress * progress * progress
                : 1.0 - Math.pow(-2.0 * progress + 2.0, 3.0) / 2.0;
    }

    private double squaredDistance(double x1, double y1, double x2, double y2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        return dx * dx + dy * dy;
    }

    private double squaredDistanceToSlot(double x, double y, int left, int top, int right, int bottom) {
        double clampedX = Math.max(left, Math.min(x, right));
        double clampedY = Math.max(top, Math.min(y, bottom));
        return squaredDistance(x, y, clampedX, clampedY);
    }

    private record DesiredAction(int targetSwapButton) {
    }

    private record TotemSource(Slot slot, int left, int top, int right, int bottom) {

        private int centerX() {
            return left + 8;
        }

        private int centerY() {
            return top + 8;
        }
    }

    private enum CursorCurveMode {
        OFF,
        EASE_IN_OUT,
        ARC
    }

    private static final class ActiveSession {

        private final int token;
        private final int sourceSlotIndex;
        private final int targetSwapButton;
        private final boolean requiresHover;
        private final long moveStartedAtMs;
        private final long moveDurationMs;
        private final int startCursorX;
        private final int startCursorY;
        private final int targetCursorX;
        private final int targetCursorY;
        private final CursorCurveMode curveMode;
        private final int curveDirection;
        private boolean hoverLocked;
        private boolean delayArmed;
        private long readyAtMs;
        private boolean swapSent;
        private long swapSentAtMs;

        private ActiveSession(int token,
                              int sourceSlotIndex,
                              int targetSwapButton,
                              boolean requiresHover,
                              long moveStartedAtMs,
                              long moveDurationMs,
                              int startCursorX,
                              int startCursorY,
                              int targetCursorX,
                              int targetCursorY,
                              CursorCurveMode curveMode,
                              int curveDirection) {
            this.token = token;
            this.sourceSlotIndex = sourceSlotIndex;
            this.targetSwapButton = targetSwapButton;
            this.requiresHover = requiresHover;
            this.moveStartedAtMs = moveStartedAtMs;
            this.moveDurationMs = moveDurationMs;
            this.startCursorX = startCursorX;
            this.startCursorY = startCursorY;
            this.targetCursorX = targetCursorX;
            this.targetCursorY = targetCursorY;
            this.curveMode = curveMode;
            this.curveDirection = curveDirection;
            this.hoverLocked = false;
            this.delayArmed = false;
            this.readyAtMs = 0L;
            this.swapSent = false;
            this.swapSentAtMs = 0L;
        }

        public int token() {
            return token;
        }

        public int sourceSlotIndex() {
            return sourceSlotIndex;
        }

        public int targetSwapButton() {
            return targetSwapButton;
        }

        public boolean requiresHover() {
            return requiresHover;
        }

        public long moveStartedAtMs() {
            return moveStartedAtMs;
        }

        public long moveDurationMs() {
            return moveDurationMs;
        }

        public int startCursorX() {
            return startCursorX;
        }

        public int startCursorY() {
            return startCursorY;
        }

        public int targetCursorX() {
            return targetCursorX;
        }

        public int targetCursorY() {
            return targetCursorY;
        }

        public CursorCurveMode curveMode() {
            return curveMode;
        }

        public int curveDirection() {
            return curveDirection;
        }

        public boolean hoverLocked() {
            return hoverLocked;
        }

        public void setHoverLocked(boolean hoverLocked) {
            this.hoverLocked = hoverLocked;
        }

        public boolean delayArmed() {
            return delayArmed;
        }

        public void setDelayArmed(boolean delayArmed) {
            this.delayArmed = delayArmed;
        }

        public long readyAtMs() {
            return readyAtMs;
        }

        public void setReadyAtMs(long readyAtMs) {
            this.readyAtMs = readyAtMs;
        }

        public boolean swapSent() {
            return swapSent;
        }

        public void setSwapSent(boolean swapSent) {
            this.swapSent = swapSent;
        }

        public long swapSentAtMs() {
            return swapSentAtMs;
        }

        public void setSwapSentAtMs(long swapSentAtMs) {
            this.swapSentAtMs = swapSentAtMs;
        }
    }
}
