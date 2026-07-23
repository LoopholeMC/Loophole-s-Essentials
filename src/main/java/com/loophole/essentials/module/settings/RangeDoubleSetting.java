package com.loophole.essentials.module.settings;

import com.loophole.essentials.gui.elements.browsingmode.module.RangeDoubleSettingElement;
import io.github.itzispyder.clickcrystals.modules.ModuleSetting;
import io.github.itzispyder.clickcrystals.modules.settings.SettingBuilder;
import io.github.itzispyder.clickcrystals.util.MathUtils;
import io.github.itzispyder.clickcrystals.util.misc.Randomizer;

import java.util.Locale;

public class RangeDoubleSetting extends ModuleSetting<String> {

    private static final String SEPARATOR = ",";
    private static final Randomizer RANDOMIZER = new Randomizer();

    private double min;
    private double max;
    private int decimalPlaces;

    public RangeDoubleSetting(String name, String description, double defLower, double defUpper, double valLower, double valUpper, double min, double max, int decimalPlaces) {
        super(name, description, "", "");
        this.min = Math.min(min, max);
        this.max = Math.max(min, max);
        this.decimalPlaces = Math.max(0, decimalPlaces);
        this.def = encode(defLower, defUpper);
        this.val = encode(valLower, valUpper);
    }

    @Override
    public RangeDoubleSettingElement toGuiElement(int x, int y) {
        return new RangeDoubleSettingElement(this, x, y);
    }

    @Override
    public void setDef(String def) {
        RangeValues values = parseOrDefault(def, getDefLower(), getDefUpper());
        this.def = encode(values.lower(), values.upper());
    }

    public void setDef(double lower, double upper) {
        this.def = encode(lower, upper);
    }

    @Override
    public void setVal(Object val) {
        if (val instanceof String encoded) {
            RangeValues values = parseOrDefault(encoded, getLower(), getUpper());
            super.setVal(encode(values.lower(), values.upper()));
            return;
        }
        super.setVal(this.val);
    }

    public void setVal(double lower, double upper) {
        super.setVal(encode(lower, upper));
    }

    public void setLower(double lower) {
        setVal(lower, getUpper());
    }

    public void setUpper(double upper) {
        setVal(getLower(), upper);
    }

    public double getLower() {
        return parseOrDefault(val, getDefLower(), getDefUpper()).lower();
    }

    public double getUpper() {
        return parseOrDefault(val, getDefLower(), getDefUpper()).upper();
    }

    public double getDefLower() {
        return parseOrDefault(def, min, min).lower();
    }

    public double getDefUpper() {
        return parseOrDefault(def, min, min).upper();
    }

    public double getMin() {
        return min;
    }

    public void setMin(double min) {
        this.min = Math.min(min, max);
        setDef(getDefLower(), getDefUpper());
        setVal(getLower(), getUpper());
    }

    public double getMax() {
        return max;
    }

    public void setMax(double max) {
        this.max = Math.max(min, max);
        setDef(getDefLower(), getDefUpper());
        setVal(getLower(), getUpper());
    }

    public int getDecimalPlaces() {
        return decimalPlaces;
    }

    public void setDecimalPlaces(int decimalPlaces) {
        this.decimalPlaces = Math.max(0, decimalPlaces);
        setDef(getDefLower(), getDefUpper());
        setVal(getLower(), getUpper());
    }

    public double getRandomizedValue() {
        return round(RANDOMIZER.getRandomDouble(getLower(), getUpper()));
    }

    public String format(double value) {
        String formatted = String.format(Locale.US, "%." + decimalPlaces + "f", round(value));
        if (decimalPlaces <= 0) {
            return formatted;
        }
        formatted = formatted.replaceAll("0+$", "");
        if (formatted.endsWith(".")) {
            formatted += "0";
        }
        return formatted;
    }

    private String encode(double lower, double upper) {
        RangeValues values = normalize(lower, upper);
        return format(values.lower()) + SEPARATOR + format(values.upper());
    }

    private RangeValues parseOrDefault(String encoded, double fallbackLower, double fallbackUpper) {
        if (encoded == null || encoded.isBlank()) {
            return normalize(fallbackLower, fallbackUpper);
        }

        String[] parts = encoded.split(SEPARATOR, 2);
        if (parts.length < 2) {
            return normalize(fallbackLower, fallbackUpper);
        }

        try {
            return normalize(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]));
        }
        catch (NumberFormatException ignored) {
            return normalize(fallbackLower, fallbackUpper);
        }
    }

    private RangeValues normalize(double lower, double upper) {
        double clampedLower = MathUtils.clamp(lower, min, max);
        double clampedUpper = MathUtils.clamp(upper, min, max);
        if (clampedLower > clampedUpper) {
            double swap = clampedLower;
            clampedLower = clampedUpper;
            clampedUpper = swap;
        }
        return new RangeValues(round(clampedLower), round(clampedUpper));
    }

    private double round(double value) {
        double pow = Math.pow(10, decimalPlaces);
        return decimalPlaces <= 0 ? Math.floor(value) : Math.floor(value * pow) / pow;
    }

    private record RangeValues(double lower, double upper) {
    }

    public static Builder create() {
        return new Builder();
    }

    public static class Builder extends SettingBuilder<String, Builder, RangeDoubleSetting> {

        private double min = 0.0;
        private double max = 1.0;
        private int decimalPlaces = 3;
        private double defLower = 0.0;
        private double defUpper = 0.0;
        private Double valLower;
        private Double valUpper;

        public Builder min(double min) {
            this.min = Math.min(min, max);
            return this;
        }

        public Builder max(double max) {
            this.max = Math.max(min, max);
            return this;
        }

        public Builder decimalPlaces(int decimalPlaces) {
            this.decimalPlaces = decimalPlaces;
            return this;
        }

        public Builder def(double lower, double upper) {
            this.defLower = lower;
            this.defUpper = upper;
            return this;
        }

        public Builder val(double lower, double upper) {
            this.valLower = lower;
            this.valUpper = upper;
            return this;
        }

        @Override
        protected RangeDoubleSetting buildSetting() {
            double valueLower = getOrDef(valLower, defLower);
            double valueUpper = getOrDef(valUpper, defUpper);
            return new RangeDoubleSetting(name, description, defLower, defUpper, valueLower, valueUpper, min, max, decimalPlaces);
        }
    }
}
