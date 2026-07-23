package com.loophole.essentials.gui.elements.browsingmode.module;

import com.loophole.essentials.module.settings.RangeDoubleSetting;
import io.github.itzispyder.clickcrystals.gui.GuiScreen;
import io.github.itzispyder.clickcrystals.gui.elements.browsingmode.module.SettingElement;
import io.github.itzispyder.clickcrystals.gui.misc.Shades;
import io.github.itzispyder.clickcrystals.util.MathUtils;
import io.github.itzispyder.clickcrystals.util.minecraft.render.RenderUtils;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public class RangeDoubleSettingElement extends SettingElement<RangeDoubleSetting> {

    private static final int CONTROL_HEIGHT = 40;
    private static final int TRACK_HEIGHT = 10;
    private static final int HANDLE_RADIUS = 4;
    private static final int HANDLE_HITBOX = 8;

    private ActiveHandle activeHandle;

    public RangeDoubleSettingElement(RangeDoubleSetting setting, int x, int y) {
        super(setting, x, y);
        this.activeHandle = ActiveHandle.NONE;
        this.height = CONTROL_HEIGHT;
        createResetButton();
    }

    @Override
    public void onRender(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        renderSettingDetails(context);
        setHeight(Math.max(height, CONTROL_HEIGHT));

        int sliderStart = getSliderStart();
        int sliderEnd = getSliderEnd();
        int sliderWidth = sliderEnd - sliderStart;
        int sliderY = y + height / 2;
        int labelY = sliderY - 10;

        if (sliderWidth <= 0) {
            return;
        }

        if (mc.screen instanceof GuiScreen screen && screen.selected == this && activeHandle != ActiveHandle.NONE) {
            double draggedValue = xToValue(mouseX, sliderStart, sliderEnd);
            if (activeHandle == ActiveHandle.LOWER) {
                setting.setLower(Math.min(draggedValue, setting.getUpper()));
            }
            else {
                setting.setUpper(Math.max(draggedValue, setting.getLower()));
            }
        }

        int lowerX = valueToX(setting.getLower(), sliderStart, sliderWidth);
        int upperX = valueToX(setting.getUpper(), sliderStart, sliderWidth);

        RenderUtils.drawText(context, setting.format(setting.getLower()), sliderStart, labelY, 0.55F, false);
        RenderUtils.drawRightText(context, setting.format(setting.getUpper()), sliderEnd, labelY, 0.55F, false);

        RenderUtils.fillRoundHoriLine(context, sliderStart, sliderY, sliderWidth, TRACK_HEIGHT, Shades.GRAY);

        int fillStart = Math.min(lowerX, upperX);
        int fillEnd = Math.max(lowerX, upperX);
        int fillWidth = Math.max(fillEnd - fillStart, HANDLE_RADIUS * 2);
        int fillDrawX = fillWidth == HANDLE_RADIUS * 2 && fillStart == fillEnd ? fillStart - HANDLE_RADIUS : fillStart;
        RenderUtils.fillRoundHoriLine(context, fillDrawX, sliderY, fillWidth, TRACK_HEIGHT, Shades.GENERIC);

        renderHandle(context, lowerX, sliderY, activeHandle == ActiveHandle.LOWER ? Shades.LIGHT_GRAY : Shades.GENERIC_LOW);
        renderHandle(context, upperX, sliderY, activeHandle == ActiveHandle.UPPER ? Shades.LIGHT_GRAY : Shades.GENERIC);
    }

    @Override
    public void mouseClicked(double mouseX, double mouseY, int button) {
        if (mc.screen instanceof GuiScreen screen && isHovered((int)mouseX, (int)mouseY)) {
            int sliderStart = getSliderStart();
            int sliderEnd = getSliderEnd();
            int sliderWidth = sliderEnd - sliderStart;
            int lowerX = valueToX(setting.getLower(), sliderStart, sliderWidth);
            int upperX = valueToX(setting.getUpper(), sliderStart, sliderWidth);

            activeHandle = pickHandle((int)mouseX, lowerX, upperX);
            screen.selected = this;
        }
        super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void mouseReleased(double mouseX, double mouseY, int button) {
        activeHandle = ActiveHandle.NONE;
        super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean isHovered(int mouseX, int mouseY) {
        int sliderStart = getSliderStart();
        int sliderEnd = getSliderEnd();
        int sliderTop = y + height / 2 - 4;
        int sliderBottom = y + height / 2 + 12;
        return rendering && mouseX > sliderStart && mouseX < sliderEnd && mouseY > sliderTop && mouseY < sliderBottom;
    }

    private void renderHandle(GuiGraphicsExtractor context, int centerX, int sliderY, int color) {
        int centerY = sliderY + TRACK_HEIGHT / 2;
        RenderUtils.fillCircle(context, centerX, centerY, HANDLE_RADIUS + 1, Shades.DARK_GRAY);
        RenderUtils.fillCircle(context, centerX, centerY, HANDLE_RADIUS, color);
    }

    private int valueToX(double value, int sliderStart, int sliderWidth) {
        double range = setting.getMax() - setting.getMin();
        if (range <= 0.0) {
            return sliderStart;
        }
        double ratio = (value - setting.getMin()) / range;
        return sliderStart + (int)Math.round(sliderWidth * ratio);
    }

    private double xToValue(int mouseX, int sliderStart, int sliderEnd) {
        int clamped = MathUtils.clamp(mouseX, sliderStart, sliderEnd);
        double ratio = (double)(clamped - sliderStart) / (double)Math.max(1, sliderEnd - sliderStart);
        double range = setting.getMax() - setting.getMin();
        return setting.getMin() + range * ratio;
    }

    private ActiveHandle pickHandle(int mouseX, int lowerX, int upperX) {
        int lowerDistance = Math.abs(mouseX - lowerX);
        int upperDistance = Math.abs(mouseX - upperX);

        if (lowerDistance <= HANDLE_HITBOX && upperDistance <= HANDLE_HITBOX) {
            if (lowerX == upperX) {
                return mouseX <= lowerX ? ActiveHandle.LOWER : ActiveHandle.UPPER;
            }
            return lowerDistance <= upperDistance ? ActiveHandle.LOWER : ActiveHandle.UPPER;
        }
        if (lowerDistance <= HANDLE_HITBOX) {
            return ActiveHandle.LOWER;
        }
        if (upperDistance <= HANDLE_HITBOX) {
            return ActiveHandle.UPPER;
        }
        if (lowerDistance == upperDistance) {
            return mouseX <= lowerX ? ActiveHandle.LOWER : ActiveHandle.UPPER;
        }
        return lowerDistance < upperDistance ? ActiveHandle.LOWER : ActiveHandle.UPPER;
    }

    private int getSliderStart() {
        return x + width / 4 * 3 - 5;
    }

    private int getSliderEnd() {
        return x + width - 5;
    }

    private enum ActiveHandle {
        NONE,
        LOWER,
        UPPER
    }
}
