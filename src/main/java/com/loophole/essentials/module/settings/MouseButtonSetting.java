package com.loophole.essentials.module.settings;

import com.loophole.essentials.gui.elements.browsingmode.module.MouseButtonSettingElement;
import io.github.itzispyder.clickcrystals.modules.ModuleSetting;
import io.github.itzispyder.clickcrystals.modules.keybinds.Keybind;
import io.github.itzispyder.clickcrystals.modules.settings.SettingBuilder;
import io.github.itzispyder.clickcrystals.util.misc.ManualMap;
import org.lwjgl.glfw.GLFW;

import java.util.Map;

public class MouseButtonSetting extends ModuleSetting<Integer> {

    public static final int NONE = -1;
    private static final int MOUSE_STORAGE_OFFSET = 1000;
    private static final Map<Integer, String> BUTTON_NAMES = ManualMap.fromItems(
            GLFW.GLFW_MOUSE_BUTTON_RIGHT, "RIGHT CLICK",
            GLFW.GLFW_MOUSE_BUTTON_MIDDLE, "MIDDLE CLICK",
            GLFW.GLFW_MOUSE_BUTTON_4, "MOUSE 4",
            GLFW.GLFW_MOUSE_BUTTON_5, "MOUSE 5"
    );
    private static final Map<Integer, String> BUTTON_CODES = ManualMap.fromItems(
            GLFW.GLFW_MOUSE_BUTTON_RIGHT, "RMB",
            GLFW.GLFW_MOUSE_BUTTON_MIDDLE, "MMB",
            GLFW.GLFW_MOUSE_BUTTON_4, "M4",
            GLFW.GLFW_MOUSE_BUTTON_5, "M5"
    );
    private static final Map<Integer, String> KEY_CODES = ManualMap.fromItems(
            GLFW.GLFW_KEY_ESCAPE, "ESC",
            GLFW.GLFW_KEY_BACKSPACE, "BKSP",
            GLFW.GLFW_KEY_TAB, "TAB",
            GLFW.GLFW_KEY_CAPS_LOCK, "CAPS",
            GLFW.GLFW_KEY_SPACE, "SPACE",
            GLFW.GLFW_KEY_ENTER, "ENTER",
            GLFW.GLFW_KEY_INSERT, "INS",
            GLFW.GLFW_KEY_DELETE, "DEL",
            GLFW.GLFW_KEY_HOME, "HOME",
            GLFW.GLFW_KEY_END, "END",
            GLFW.GLFW_KEY_PAGE_UP, "PGUP",
            GLFW.GLFW_KEY_PAGE_DOWN, "PGDN"
    );

    public MouseButtonSetting(String name, String description, int def, int val) {
        super(name, description, normalizeStoredInput(def), normalizeStoredInput(val));
    }

    @Override
    public MouseButtonSettingElement toGuiElement(int x, int y) {
        return new MouseButtonSettingElement(this, x, y);
    }

    @Override
    public void setDef(Integer def) {
        super.setDef(normalizeStoredInput(def));
    }

    @Override
    public void setVal(Object val) {
        if (val instanceof Button button) {
            super.setVal(encodeMouseButton(button.getCode()));
            return;
        }
        if (val instanceof Number number) {
            super.setVal(normalizeStoredInput(number.intValue()));
            return;
        }
        super.setVal(this.val);
    }

    public int getInputCode() {
        return normalizeStoredInput(getVal());
    }

    public boolean isNone() {
        return getInputCode() == NONE;
    }

    public boolean isMouseBinding() {
        return isStoredMouseInput(getInputCode());
    }

    public boolean isKeyboardBinding() {
        int input = getInputCode();
        return input != NONE && !isStoredMouseInput(input);
    }

    public int getButton() {
        return isMouseBinding() ? decodeMouseButton(getInputCode()) : NONE;
    }

    public void setButton(int button) {
        setVal(encodeMouseButton(button));
    }

    public int getKey() {
        return isKeyboardBinding() ? getInputCode() : NONE;
    }

    public void setKey(int key) {
        setVal(key);
    }

    public int getDefaultButton() {
        int def = normalizeStoredInput(getDef());
        return isStoredMouseInput(def) ? decodeMouseButton(def) : NONE;
    }

    public boolean matches(int button) {
        return matchesMouse(button);
    }

    public boolean matchesMouse(int button) {
        return isMouseBinding() && getButton() == normalizeMouseButton(button);
    }

    public boolean matchesKey(int key) {
        return isKeyboardBinding() && getKey() == key;
    }

    public String getDisplayName() {
        int input = getInputCode();
        if (isStoredMouseInput(input)) {
            return getButtonName(decodeMouseButton(input));
        }
        return getKeyName(input);
    }

    public String getDisplayCode() {
        int input = getInputCode();
        if (input == NONE) {
            return "NONE";
        }
        if (isStoredMouseInput(input)) {
            return BUTTON_CODES.getOrDefault(decodeMouseButton(input), "NONE");
        }
        return getKeyCode(input);
    }

    public static boolean isBindable(int button) {
        return isBindableMouseButton(button);
    }

    public static boolean isBindableMouseButton(int button) {
        return button == NONE || BUTTON_NAMES.containsKey(button);
    }

    public static boolean isBindableKey(int key) {
        return key == NONE || isPlausibleKeyboardKey(key);
    }

    public static String getButtonName(int button) {
        return BUTTON_NAMES.getOrDefault(normalizeMouseButton(button), "NONE");
    }

    public static String getKeyName(int key) {
        if (key == NONE) {
            return "NONE";
        }

        String name = tryGetExtendedKeyName(key);
        if (name != null) {
            return name.replace('_', ' ').toUpperCase();
        }
        return getFallbackKeyName(key);
    }

    public static String getKeyCode(int key) {
        if (key == NONE) {
            return "NONE";
        }

        String code = KEY_CODES.get(key);
        if (code != null) {
            return code;
        }

        code = Keybind.EXTRAS.get(key);
        if (code != null) {
            return code;
        }

        String name = tryGetGlfwKeyName(key);
        if (name != null && !name.isBlank()) {
            return name.toUpperCase();
        }
        if (key >= GLFW.GLFW_KEY_F1 && key <= GLFW.GLFW_KEY_F25) {
            return "F" + (key - GLFW.GLFW_KEY_F1 + 1);
        }
        return getFallbackKeyCode(key);
    }

    private static int normalizeStoredInput(Integer input) {
        if (input == null) {
            return NONE;
        }

        int value = input;
        if (value == NONE) {
            return NONE;
        }
        if (isStoredMouseInput(value)) {
            int decoded = decodeMouseButton(value);
            return isBindableMouseButton(decoded) ? encodeMouseButton(decoded) : NONE;
        }
        if (BUTTON_NAMES.containsKey(value)) {
            return encodeMouseButton(value);
        }
        return isPlausibleKeyboardKey(value) ? value : NONE;
    }

    private static boolean isPlausibleKeyboardKey(int key) {
        return key >= GLFW.GLFW_KEY_SPACE && key <= GLFW.GLFW_KEY_MENU;
    }

    private static String tryGetExtendedKeyName(int key) {
        try {
            return Keybind.getExtendedKeyName(key, GLFW.glfwGetKeyScancode(key));
        }
        catch (IllegalStateException ignored) {
            return null;
        }
    }

    private static String tryGetGlfwKeyName(int key) {
        try {
            return GLFW.glfwGetKeyName(key, GLFW.glfwGetKeyScancode(key));
        }
        catch (IllegalStateException ignored) {
            return null;
        }
    }

    private static String getFallbackKeyName(int key) {
        if (key >= GLFW.GLFW_KEY_A && key <= GLFW.GLFW_KEY_Z) {
            return Character.toString((char) ('A' + (key - GLFW.GLFW_KEY_A)));
        }
        if (key >= GLFW.GLFW_KEY_0 && key <= GLFW.GLFW_KEY_9) {
            return Character.toString((char) ('0' + (key - GLFW.GLFW_KEY_0)));
        }
        if (key >= GLFW.GLFW_KEY_F1 && key <= GLFW.GLFW_KEY_F25) {
            return "F" + (key - GLFW.GLFW_KEY_F1 + 1);
        }

        String extendedName = Keybind.EXTENDED_NAMES.get(key);
        if (extendedName != null) {
            return extendedName.replace('_', ' ').toUpperCase();
        }

        return KEY_CODES.getOrDefault(key, "KEY " + key);
    }

    private static String getFallbackKeyCode(int key) {
        if (key >= GLFW.GLFW_KEY_A && key <= GLFW.GLFW_KEY_Z) {
            return Character.toString((char) ('A' + (key - GLFW.GLFW_KEY_A)));
        }
        if (key >= GLFW.GLFW_KEY_0 && key <= GLFW.GLFW_KEY_9) {
            return Character.toString((char) ('0' + (key - GLFW.GLFW_KEY_0)));
        }
        if (key >= GLFW.GLFW_KEY_F1 && key <= GLFW.GLFW_KEY_F25) {
            return "F" + (key - GLFW.GLFW_KEY_F1 + 1);
        }
        return KEY_CODES.getOrDefault(key, Keybind.EXTRAS.getOrDefault(key, "KEY" + key));
    }

    private static int normalizeMouseButton(int button) {
        return isBindableMouseButton(button) ? button : NONE;
    }

    private static boolean isStoredMouseInput(int input) {
        return input <= -MOUSE_STORAGE_OFFSET;
    }

    private static int encodeMouseButton(int button) {
        int normalized = normalizeMouseButton(button);
        return normalized == NONE ? NONE : -(MOUSE_STORAGE_OFFSET + normalized);
    }

    private static int decodeMouseButton(int encoded) {
        if (!isStoredMouseInput(encoded)) {
            return NONE;
        }
        return -(encoded + MOUSE_STORAGE_OFFSET);
    }

    public static Builder create() {
        return new Builder();
    }

    public enum Button {
        NONE(MouseButtonSetting.NONE, "NONE"),
        RIGHT_CLICK(GLFW.GLFW_MOUSE_BUTTON_RIGHT, "RIGHT CLICK"),
        MIDDLE_CLICK(GLFW.GLFW_MOUSE_BUTTON_MIDDLE, "MIDDLE CLICK"),
        MOUSE_4(GLFW.GLFW_MOUSE_BUTTON_4, "MOUSE 4"),
        MOUSE_5(GLFW.GLFW_MOUSE_BUTTON_5, "MOUSE 5");

        private final int code;
        private final String displayName;

        Button(int code, String displayName) {
            this.code = code;
            this.displayName = displayName;
        }

        public int getCode() {
            return code;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    public static class Builder extends SettingBuilder<Integer, Builder, MouseButtonSetting> {

        public Builder def(Button button) {
            this.def = button.getCode();
            return this;
        }

        public Builder val(Button button) {
            this.val = button.getCode();
            return this;
        }

        @Override
        protected MouseButtonSetting buildSetting() {
            int defaultButton = normalizeStoredInput(getOrDef(def, NONE));
            int valueButton = normalizeStoredInput(getOrDef(val, defaultButton));
            return new MouseButtonSetting(name, description, defaultButton, valueButton);
        }
    }
}
