package com.loophole.essentials.module.modules;

import com.loophole.essentials.module.LoopholeListenerModule;
import com.loophole.essentials.module.PotionMatcher;
import com.loophole.essentials.module.settings.RangeDoubleSetting;
import io.github.itzispyder.clickcrystals.events.EventHandler;
import io.github.itzispyder.clickcrystals.events.events.networking.GameLeaveEvent;
import io.github.itzispyder.clickcrystals.events.events.world.ClientTickStartEvent;
import io.github.itzispyder.clickcrystals.mixins.AccessorHandledScreen;
import io.github.itzispyder.clickcrystals.modules.ModuleSetting;
import io.github.itzispyder.clickcrystals.modules.settings.SettingSection;
import io.github.itzispyder.clickcrystals.util.minecraft.InteractionUtils;
import io.github.itzispyder.clickcrystals.util.minecraft.PlayerUtils;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.awt.Point;

public class PotRefillModule extends LoopholeListenerModule {

    private static final int OFFHAND_SWAP_BUTTON = 40;
    private static final int PLAYER_MAIN_INVENTORY_START_SLOT = 9;
    private static final int PLAYER_MAIN_INVENTORY_END_SLOT = 35;

    private final SettingSection scGeneral = getGeneralSection();

    public final ModuleSetting<String> allowedPotions = scGeneral.add(createStringSetting()
            .name("allowed-potions")
            .description("Comma-separated splash potion names or effect names to move, for example healing,strength or instant_health,strength. Matching ignores vanilla long or strong potion variants so one entry works across all levels and durations.")
            .def("instant_health")
            .build()
    );

    public final RangeDoubleSetting refillDelay = scGeneral.add(createRangeDoubleSetting()
            .name("refill-delay")
            .description("Randomized delay before moving the hovered matching splash potion into the first empty hotbar slot or offhand while the inventory screen is open.")
            .def(0.000, 0.050)
            .min(0.000)
            .max(0.050)
            .decimalPlaces(3)
            .build()
    );

    private ActiveSession activeSession = null;
    private int activeSessionToken = 0;

    public PotRefillModule() {
        super("pot-refill", "Refills hovered matching splash potions into empty hotbar slots or offhand.");
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
        if (activeSession != null && !isSessionStillRelevant(activeSession)) {
            clearSession();
        }

        if (activeSession == null) {
            tryStartSession();
        }
    }

    private void tryStartSession() {
        if (!canOperate()) {
            return;
        }

        AbstractContainerScreen<?> screen = getActiveInventoryScreen();
        if (screen == null) {
            return;
        }

        Slot hoveredSlot = findHoveredMatchingPotionSlot(screen);
        if (hoveredSlot == null) {
            return;
        }

        int targetSwapButton = findTargetSwapButton();
        if (targetSwapButton < 0) {
            return;
        }

        int sessionToken = ++activeSessionToken;
        activeSession = new ActiveSession(sessionToken, hoveredSlot.index, targetSwapButton);
        scheduleRefill(sessionToken);
    }

    private boolean canOperate() {
        return isEnabled()
                && PlayerUtils.valid()
                && mc.player != null
                && mc.level != null
                && mc.options != null;
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

    private Slot findHoveredMatchingPotionSlot(AbstractContainerScreen<?> screen) {
        AccessorHandledScreen handledScreen = (AccessorHandledScreen) screen;
        Point cursor = InteractionUtils.getCursor();

        for (Slot slot : screen.getMenu().slots) {
            if (!handledScreen.isHovered(slot, cursor.x, cursor.y)) {
                continue;
            }
            if (!isEligibleHoveredSlot(slot) || !matchesConfiguredPotion(slot.getItem())) {
                continue;
            }
            return slot;
        }
        return null;
    }

    private boolean isEligibleHoveredSlot(Slot slot) {
        return slot != null
                && slot.index >= PLAYER_MAIN_INVENTORY_START_SLOT
                && slot.index <= PLAYER_MAIN_INVENTORY_END_SLOT
                && slot.hasItem();
    }

    private boolean matchesConfiguredPotion(ItemStack stack) {
        return PotionMatcher.matchesConfiguredSplashPotion(stack, allowedPotions.getVal());
    }

    private int findTargetSwapButton() {
        for (int slot = 0; slot <= 8; slot++) {
            if (mc.player.getInventory().getItem(slot).isEmpty()) {
                return slot;
            }
        }

        if (mc.player.getOffhandItem().isEmpty()) {
            return OFFHAND_SWAP_BUTTON;
        }
        return -1;
    }

    private long getRefillDelayMs() {
        return Math.max(0L, Math.round(refillDelay.getRandomizedValue() * 1000.0));
    }

    private void scheduleRefill(int sessionToken) {
        long delayMs = getRefillDelayMs();
        system.scheduler.runDelayedTask(() -> mc.execute(() -> tryExecuteRefill(sessionToken)), delayMs);
    }

    private void tryExecuteRefill(int sessionToken) {
        if (!isSessionValid(sessionToken) || !canOperate()) {
            clearSession();
            return;
        }

        AbstractContainerScreen<?> screen = getActiveInventoryScreen();
        if (screen == null) {
            clearSession();
            return;
        }

        Slot sourceSlot = findMenuSlotByIndex(screen, activeSession.sourceSlotIndex());
        if (sourceSlot == null
                || !isEligibleHoveredSlot(sourceSlot)
                || !matchesConfiguredPotion(sourceSlot.getItem())
                || !isTargetStillEmpty(activeSession.targetSwapButton())) {
            clearSession();
            return;
        }

        sendSwapPacket(screen, sourceSlot, activeSession.targetSwapButton());
        clearSession();
    }

    private Slot findMenuSlotByIndex(AbstractContainerScreen<?> screen, int sourceSlotIndex) {
        for (Slot slot : screen.getMenu().slots) {
            if (slot.index == sourceSlotIndex) {
                return slot;
            }
        }
        return null;
    }

    private boolean isTargetStillEmpty(int targetSwapButton) {
        if (targetSwapButton == OFFHAND_SWAP_BUTTON) {
            return mc.player.getOffhandItem().isEmpty();
        }
        if (targetSwapButton < 0 || targetSwapButton > 8) {
            return false;
        }
        return mc.player.getInventory().getItem(targetSwapButton).isEmpty();
    }

    private boolean isSessionStillRelevant(ActiveSession session) {
        AbstractContainerScreen<?> screen = getActiveInventoryScreen();
        if (!canOperate() || screen == null) {
            return false;
        }

        Slot sourceSlot = findMenuSlotByIndex(screen, session.sourceSlotIndex());
        return sourceSlot != null
                && isEligibleHoveredSlot(sourceSlot)
                && matchesConfiguredPotion(sourceSlot.getItem())
                && isTargetStillEmpty(session.targetSwapButton());
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

    private void clearSession() {
        activeSession = null;
        activeSessionToken++;
    }

    private void resetRuntimeState() {
        clearSession();
    }

    private record ActiveSession(int token, int sourceSlotIndex, int targetSwapButton) {
    }
}
