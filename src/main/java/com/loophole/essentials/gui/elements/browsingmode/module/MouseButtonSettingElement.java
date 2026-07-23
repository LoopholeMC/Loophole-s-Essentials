package com.loophole.essentials.gui.elements.browsingmode.module;

import com.loophole.essentials.module.settings.MouseButtonSetting;
import io.github.itzispyder.clickcrystals.gui.ClickType;
import io.github.itzispyder.clickcrystals.gui.GuiScreen;
import io.github.itzispyder.clickcrystals.gui.elements.browsingmode.module.SettingElement;
import io.github.itzispyder.clickcrystals.gui.elements.common.Typeable;
import io.github.itzispyder.clickcrystals.gui.misc.callbacks.MouseClickCallback;
import io.github.itzispyder.clickcrystals.gui.misc.Shades;
import io.github.itzispyder.clickcrystals.util.minecraft.render.RenderUtils;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.lwjgl.glfw.GLFW;

import java.util.function.Function;

public class MouseButtonSettingElement extends SettingElement<MouseButtonSetting> implements Typeable {

    private static final int BUTTON_WIDTH = 44;
    private static final int BUTTON_HEIGHT = 12;
    private static final int[] BINDABLE_MOUSE_BUTTONS = {
            GLFW.GLFW_MOUSE_BUTTON_RIGHT,
            GLFW.GLFW_MOUSE_BUTTON_MIDDLE,
            GLFW.GLFW_MOUSE_BUTTON_4,
            GLFW.GLFW_MOUSE_BUTTON_5
    };
    private final MouseClickCallback captureListener;
    private GuiScreen registeredScreen;
    private boolean listening;

    public MouseButtonSettingElement(MouseButtonSetting setting, int x, int y) {
        super(setting, x, y);
        createResetButton();
        captureListener = (mouseX, mouseY, button, click) -> {
            if (!(mc.screen instanceof GuiScreen screen) || !listening || click != ClickType.CLICK) {
                return;
            }
            if (MouseButtonSetting.isBindableMouseButton(button)) {
                setting.setButton(button);
                stopListening(screen);
                return;
            }
            screen.selected = this;
        };
        registeredScreen = null;
        listening = false;
    }

    @Override
    public void onRender(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        renderSettingDetails(context);

        int drawX = getButtonX();
        int drawY = getButtonY();
        int fill = listening ? Shades.LIGHT_GRAY : Shades.GRAY;
        String label = listening ? "SET" : setting.getDisplayCode();
        String display = label.equals("NONE") ? "§7NONE" : "§7[§f" + label + "§7]";
        float scale = label.length() > 4 ? 0.42F : label.length() > 3 ? 0.50F : 0.60F;

        RenderUtils.fillRoundRect(context, drawX, drawY, BUTTON_WIDTH, BUTTON_HEIGHT, 3, fill);
        RenderUtils.drawCenteredText(context, display, drawX + BUTTON_WIDTH / 2, drawY + BUTTON_HEIGHT / 3, scale, false);
    }

    @Override
    public void mouseClicked(double mouseX, double mouseY, int button) {
        if (!(mc.screen instanceof GuiScreen screen) || !isHovered((int)mouseX, (int)mouseY)) {
            super.mouseClicked(mouseX, mouseY, button);
            return;
        }

        if (MouseButtonSetting.isBindableMouseButton(button) && button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            setting.setButton(button);
            stopListening(screen);
            return;
        }

        startListening(screen);
    }

    @Override
    public void onTick() {
        super.onTick();
        if (!(mc.screen instanceof GuiScreen screen)) {
            unregisterCaptureListener();
            listening = false;
            return;
        }

        if (registeredScreen != screen) {
            unregisterCaptureListener();
            screen.mouseClickListeners.add(captureListener);
            registeredScreen = screen;
        }

        if (listening) {
            screen.selected = this;
        }
    }

    @Override
    public boolean onKey(int key, int scanCode) {
        if (mc.screen instanceof GuiScreen screen && listening) {
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                setting.setVal(MouseButtonSetting.NONE);
                stopListening(screen);
                return true;
            }
            if (MouseButtonSetting.isBindableKey(key)) {
                setting.setKey(key);
                stopListening(screen);
                return true;
            }
        }
        return true;
    }

    @Override
    public void onInput(Function<String, String> factory) {

    }

    @Override
    public boolean isHovered(int mouseX, int mouseY) {
        int drawX = getButtonX();
        int drawY = getButtonY();
        return rendering && mouseX > drawX && mouseX < drawX + BUTTON_WIDTH && mouseY > drawY && mouseY < drawY + BUTTON_HEIGHT;
    }

    private int getButtonX() {
        return x + width - BUTTON_WIDTH - 18;
    }

    private int getButtonY() {
        return y + height / 2 - 2;
    }

    private void startListening(GuiScreen screen) {
        listening = true;
        screen.selected = this;
    }

    private void stopListening(GuiScreen screen) {
        listening = false;
        if (screen.selected == this) {
            screen.selected = null;
        }
    }

    private void unregisterCaptureListener() {
        if (registeredScreen != null) {
            registeredScreen.mouseClickListeners.remove(captureListener);
            registeredScreen = null;
        }
    }
}
