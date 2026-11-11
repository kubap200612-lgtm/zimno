package pl.rynek.analityk.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.Locale;

/** Pomocnicze operacje na tekstach (bez Minecrafta). */
public final class TextUtil {
    private TextUtil() {}

    /** Usuwa kody kolorów §x / &x oraz hex §x§r§r§g§g§b§b. */
    public static String strip(String s) {
        if (s == null) return "";
        return s.replaceAll("(?i)[§&]x([§&][0-9a-f]){6}", "")
                .replaceAll("(?i)§[0-9a-fk-orx]", "")
                .trim();
    }

    /** Małe litery, bez polskich znaków i kolorów — do porównań słów kluczowych. */
    public static String norm(String s) {
        String x = strip(s).toLowerCase(Locale.ROOT).replace('ł', 'l');
        x = Normalizer.normalize(x, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return x;
    }

    public static String sha(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    /** Pierwsza niepusta linia opisu (bez kolorów), np. "Regeneracji I". */
    public static String firstLoreLine(String lore) {
        if (lore == null || lore.isBlank()) return "";
        for (String part : lore.split("[;\\n]")) {
            String p = strip(part).replaceAll("^[»>\\-•\\s]+", "").trim();
            if (!p.isEmpty()) return p;
        }
        return "";
    }
}
