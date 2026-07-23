package com.loophole.essentials.module.modules;

import com.loophole.essentials.mixin.AccessorKeyMapping;
import com.loophole.essentials.module.LoopholeListenerModule;
import com.loophole.essentials.module.settings.RangeDoubleSetting;
import io.github.itzispyder.clickcrystals.events.EventHandler;
import io.github.itzispyder.clickcrystals.events.events.networking.GameLeaveEvent;
import io.github.itzispyder.clickcrystals.events.events.world.ClientTickStartEvent;
import io.github.itzispyder.clickcrystals.modules.settings.SettingSection;
import io.github.itzispyder.clickcrystals.util.minecraft.InvUtils;
import io.github.itzispyder.clickcrystals.util.minecraft.PlayerUtils;
import net.minecraft.client.KeyMapping;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class AutoClutchModule extends LoopholeListenerModule {

    private static final double MIN_TRIGGER_FALL_SPEED = -0.7D;
    private static final long SESSION_TIMEOUT_MS = 1500L;

    private final SettingSection scGeneral = getGeneralSection();

    public final RangeDoubleSetting pickupDelay = scGeneral.add(createRangeDoubleSetting()
            .name("pickup-delay")
            .description("Randomized delay after the clutch place click before Auto Clutch is allowed to reclaim the placed water once contact and fall-damage reset are confirmed.")
            .def(0.045, 0.050)
            .min(0.000)
            .max(0.100)
            .decimalPlaces(3)
            .build()
    );

    private ActiveSession activeSession = null;
    private int activeSessionToken = 0;

    public AutoClutchModule() {
        super("auto-clutch", "Places clutch water while you are falling fast onto the top face of a block with a held water bucket, then reclaims that water only after confirmed contact and fall-damage cancel timing.");
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
        if (!canOperate()) {
            resetRuntimeState();
            return;
        }

        if (activeSession == null) {
            tryStartSession();
            return;
        }

        if (!isSessionValid(activeSession.token()) || isSessionExpired(activeSession)) {
            clearSession();
            return;
        }
        if (InvUtils.selected() != activeSession.slot()) {
            clearSession();
            return;
        }

        updateTouchConfirmation(activeSession);
        if (shouldPickupPlacedWater(activeSession)) {
            queueUseClick();
            clearSession();
            return;
        }

        if (shouldAbortSession(activeSession)) {
            clearSession();
        }
    }

    private void tryStartSession() {
        BlockHitResult hit = getPlacementHit();
        if (hit == null || !shouldPlaceClutchWater(hit)) {
            return;
        }

        int sessionToken = ++activeSessionToken;
        activeSession = new ActiveSession(
                sessionToken,
                InvUtils.selected(),
                hit.getBlockPos().relative(hit.getDirection()).immutable(),
                System.currentTimeMillis() + SESSION_TIMEOUT_MS
        );
        queueUseClick();
        schedulePickupReady(sessionToken);
    }

    private boolean canOperate() {
        return isEnabled()
                && PlayerUtils.valid()
                && mc.player != null
                && mc.level != null
                && mc.options != null
                && mc.screen == null;
    }

    private BlockHitResult getPlacementHit() {
        if (!(mc.hitResult instanceof BlockHitResult hit) || mc.hitResult.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        return hit;
    }

    private boolean shouldPlaceClutchWater(BlockHitResult hit) {
        if (mc.player == null || mc.level == null) {
            return false;
        }
        if (mc.player.onGround()
                || mc.player.isInWater()
                || mc.player.getDeltaMovement().y > MIN_TRIGGER_FALL_SPEED
                || !mc.player.getMainHandItem().is(Items.WATER_BUCKET)) {
            return false;
        }
        if (hit.getDirection() != Direction.UP || mc.level.getBlockState(hit.getBlockPos()).isAir()) {
            return false;
        }

        BlockPos waterPos = hit.getBlockPos().relative(hit.getDirection());
        return !isWaterAt(waterPos) && findTargetedWaterPos(false) == null;
    }

    private long getPickupDelayMs() {
        return Math.max(0L, Math.round(pickupDelay.getRandomizedValue() * 1000.0));
    }

    private void schedulePickupReady(int sessionToken) {
        long delayMs = getPickupDelayMs();
        system.scheduler.runDelayedTask(() -> mc.execute(() -> {
            if (!isSessionValid(sessionToken)) {
                return;
            }
            activeSession.setPickupReady(true);
        }), delayMs);
    }

    private boolean shouldPickupPlacedWater(ActiveSession session) {
        if (mc.player == null) {
            return false;
        }
        if (!session.pickupReady()
                || !session.touchedWater()
                || !mc.player.getMainHandItem().is(Items.BUCKET)
                || !isWaterSourceAt(session.waterPos())) {
            return false;
        }

        BlockPos targetedWaterPos = findTargetedWaterPos(true);
        return targetedWaterPos != null
                && targetedWaterPos.equals(session.waterPos())
                && hasFallDamageBeenNeutralized();
    }

    private boolean shouldAbortSession(ActiveSession session) {
        if (mc.player == null) {
            return true;
        }
        if (!mc.player.getMainHandItem().is(Items.WATER_BUCKET) && !mc.player.getMainHandItem().is(Items.BUCKET)) {
            return true;
        }
        return session.pickupReady()
                && !isWaterSourceAt(session.waterPos())
                && !mc.player.isInWater()
                && !session.touchedWater();
    }

    private void updateTouchConfirmation(ActiveSession session) {
        if (session.touchedWater() || mc.player == null || !mc.player.isInWater() || !isWaterSourceAt(session.waterPos())) {
            return;
        }

        AABB playerBox = mc.player.getBoundingBox().inflate(-1.0E-4);
        AABB waterBox = new AABB(session.waterPos());
        if (playerBox.intersects(waterBox)) {
            session.setTouchedWater(true);
        }
    }

    private boolean hasFallDamageBeenNeutralized() {
        return mc.player != null
                && (mc.player.isInWater()
                || mc.player.onGround()
                || mc.player.fallDistance <= 0.0F);
    }

    private BlockPos findTargetedWaterPos(boolean requireSource) {
        if (mc.player == null || mc.level == null) {
            return null;
        }

        Vec3 eye = mc.player.getEyePosition();
        Vec3 direction = mc.player.getLookAngle().normalize();
        double maxDistance = Math.max(1.0, mc.player.blockInteractionRange());

        for (double distance = 0.0; distance <= maxDistance; distance += 0.05) {
            BlockPos pos = BlockPos.containing(eye.add(direction.scale(distance)));
            if (!isWaterAt(pos)) {
                continue;
            }
            if (!requireSource || isWaterSourceAt(pos)) {
                return pos.immutable();
            }
        }
        return null;
    }

    private boolean isWaterAt(BlockPos pos) {
        return mc.level != null && mc.level.getFluidState(pos).is(FluidTags.WATER);
    }

    private boolean isWaterSourceAt(BlockPos pos) {
        return mc.level != null
                && mc.level.getFluidState(pos).is(FluidTags.WATER)
                && mc.level.getFluidState(pos).isSource();
    }

    private void queueUseClick() {
        mc.execute(() -> {
            AccessorKeyMapping keyUse = (AccessorKeyMapping) mc.options.keyUse;
            KeyMapping.click(keyUse.loopholeEssentials$getBoundKey());
        });
    }

    private boolean isSessionExpired(ActiveSession session) {
        return System.currentTimeMillis() > session.expiresAtMs();
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

    private static final class ActiveSession {

        private final int token;
        private final int slot;
        private final BlockPos waterPos;
        private final long expiresAtMs;
        private boolean pickupReady;
        private boolean touchedWater;

        private ActiveSession(int token, int slot, BlockPos waterPos, long expiresAtMs) {
            this.token = token;
            this.slot = slot;
            this.waterPos = waterPos;
            this.expiresAtMs = expiresAtMs;
            this.pickupReady = false;
            this.touchedWater = false;
        }

        public int token() {
            return token;
        }

        public int slot() {
            return slot;
        }

        public BlockPos waterPos() {
            return waterPos;
        }

        public long expiresAtMs() {
            return expiresAtMs;
        }

        public boolean pickupReady() {
            return pickupReady;
        }

        public void setPickupReady(boolean pickupReady) {
            this.pickupReady = pickupReady;
        }

        public boolean touchedWater() {
            return touchedWater;
        }

        public void setTouchedWater(boolean touchedWater) {
            this.touchedWater = touchedWater;
        }
    }
}
