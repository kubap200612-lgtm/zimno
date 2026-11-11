package pl.rynek.analityk.core;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Formatowanie i parsowanie cen (bez zależności od Minecrafta). */
public final class PriceFmt {
    private PriceFmt() {}

    private static final Pattern PRICE_LINE = Pattern.compile(
            "(?i)(?:koszt|cena|price)[^0-9]*([0-9][0-9 .,\\u00a0\\u202f]*(?:mld|[kmbt])?)");

    /** "1 234 567$", dla małych cen z częścią ułamkową: "12,5$". */
    public static String full(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "-";
        if (Math.abs(v) < 100 && Math.abs(v - Math.rint(v)) > 0.005) {
            return String.format(Locale.ROOT, "%.2f", v).replace('.', ',').replaceAll(",?0+$", "") + "$";
        }
        long r = Math.round(v);
        boolean neg = r < 0;
        String s = Long.toString(Math.abs(r));
        StringBuilder sb = new StringBuilder();
        int n = s.length();
        for (int i = 0; i < n; i++) {
            if (i > 0 && (n - i) % 3 == 0) sb.append(' ');
            sb.append(s.charAt(i));
        }
        return (neg ? "-" : "") + sb + "$";
    }

    /** Krótki zapis do osi wykresu: 950, 1,2k, 15k, 1,5m. */
    public static String compact(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "-";
        double a = Math.abs(v);
        String sign = v < 0 ? "-" : "";
        if (a >= 1e9) return sign + trim(a / 1e9) + "mld";
        if (a >= 1e6) return sign + trim(a / 1e6) + "m";
        if (a >= 1e4) return sign + Math.round(a / 1e3) + "k";
        if (a >= 1e3) return sign + trim(a / 1e3) + "k";
        if (a >= 100) return sign + Math.round(a);
        return sign + trim(a);
    }

    private static String trim(double x) {
        String s = String.format(Locale.ROOT, "%.1f", x);
        if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
        return s.replace('.', ',');
    }

    /** "+3,2%" z ułamka 0.032. */
    public static String pct(double fraction, boolean signed) {
        if (Double.isNaN(fraction) || Double.isInfinite(fraction)) return "-";
        String s = String.format(Locale.ROOT, "%.1f", fraction * 100).replace('.', ',');
        if (s.endsWith(",0")) s = s.substring(0, s.length() - 2);
        if (signed && fraction > 0) s = "+" + s;
        return s + "%";
    }

    /** Wyciąga cenę z linii opisu typu "Koszt: 1.5k$" / "Cena: 1 000$". Zwraca -1 gdy brak. */
    public static double fromLoreLine(String plain) {
        if (plain == null) return -1;
        Matcher m = PRICE_LINE.matcher(plain);
        if (!m.find()) return -1;
        return parse(m.group(1));
    }

    /**
     * Parsuje kwotę z sufiksem: "1.5k", "1 000", "1,000", "1.000.000", "2,5mld", "12.50".
     * Zwraca -1 gdy nie da się odczytać.
     */
    public static double parse(String raw) {
        if (raw == null) return -1;
        String s = raw.toLowerCase(Locale.ROOT)
                .replace("$", "").replace("zł", "")
                .replace(" ", "").replace(" ", "").replace(" ", "").trim();
        double mult = 1;
        if (s.endsWith("mld")) { mult = 1e9; s = s.substring(0, s.length() - 3); }
        else if (s.endsWith("k")) { mult = 1e3; s = s.substring(0, s.length() - 1); }
        else if (s.endsWith("m")) { mult = 1e6; s = s.substring(0, s.length() - 1); }
        else if (s.endsWith("b")) { mult = 1e9; s = s.substring(0, s.length() - 1); }
        else if (s.endsWith("t")) { mult = 1e12; s = s.substring(0, s.length() - 1); }
        s = s.replaceAll("[^0-9.,]", "");
        while (!s.isEmpty() && (s.endsWith(".") || s.endsWith(","))) s = s.substring(0, s.length() - 1);
        if (s.isEmpty()) return -1;
        boolean hasSuffix = mult != 1;
        int dots = count(s, '.'), commas = count(s, ',');
        String num;
        if (dots > 0 && commas > 0) {
            char dec = s.lastIndexOf('.') > s.lastIndexOf(',') ? '.' : ',';
            char thou = dec == '.' ? ',' : '.';
            num = s.replace(String.valueOf(thou), "").replace(dec, '.');
        } else if (dots + commas == 0) {
            num = s;
        } else {
            char sep = dots > 0 ? '.' : ',';
            int cnt = Math.max(dots, commas);
            int last = s.lastIndexOf(sep);
            int digitsAfter = s.length() - last - 1;
            if (cnt > 1 || (digitsAfter == 3 && !hasSuffix)) {
                num = s.replace(String.valueOf(sep), ""); // separator tysięcy
            } else {
                num = s.replace(sep, '.');
            }
        }
        try {
            double v = Double.parseDouble(num) * mult;
            return v > 0 && Double.isFinite(v) ? v : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }
}
