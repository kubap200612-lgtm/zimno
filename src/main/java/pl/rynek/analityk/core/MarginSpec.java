package pl.rynek.analityk.core;

import java.util.Locale;

/** Marża: automatyczna, procentowa albo kwotowa (za sztukę). */
public final class MarginSpec {
    public enum Mode { AUTO, PCT, ABS }

    public final Mode mode;
    /** dla PCT ułamek (0.1 = 10%), dla ABS kwota za sztukę */
    public final double value;

    private MarginSpec(Mode mode, double value) {
        this.mode = mode;
        this.value = value;
    }

    public static final MarginSpec AUTO = new MarginSpec(Mode.AUTO, 0);

    /** "auto" / "" → AUTO, "10%" → 10%, "250" lub "1.5k" → 250$ za sztukę. Błędne → null. */
    public static MarginSpec parse(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty() || s.equals("auto") || s.equals("a")) return AUTO;
        if (s.endsWith("%")) {
            String n = s.substring(0, s.length() - 1).trim().replace(',', '.');
            try {
                double p = Double.parseDouble(n);
                if (p < 0 || p >= 95) return null;
                return new MarginSpec(Mode.PCT, p / 100.0);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        double v = PriceFmt.parse(s);
        if (v <= 0) {
            if (s.equals("0")) return new MarginSpec(Mode.ABS, 0);
            return null;
        }
        return new MarginSpec(Mode.ABS, v);
    }

    /** Wybiera marżę przedmiotu, a gdy pusta/błędna — domyślną. */
    public static MarginSpec resolve(String item, String global) {
        if (item != null && !item.isBlank()) {
            MarginSpec m = parse(item);
            if (m != null) return m;
        }
        MarginSpec g = parse(global);
        return g == null ? AUTO : g;
    }

    public String label() {
        switch (mode) {
            case PCT: return PriceFmt.pct(value, false);
            case ABS: return PriceFmt.full(value) + "/szt";
            default: return "auto";
        }
    }
}
