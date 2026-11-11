package pl.rynek.analityk.market;

import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import pl.rynek.analityk.bk.BkBridge;
import pl.rynek.analityk.core.TextUtil;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Odczyt pojedynczej oferty z rynku (cena, ilość, czy moja, do których przedmiotów BK pasuje). */
public final class Listings {
    private Listings() {}

    public static final class Listing {
        public final double total;
        public final int count;
        public final double unit;
        public final boolean own;
        public final String seller;
        public final List<BkBridge.BkItem> items;

        Listing(double total, int count, boolean own, String seller, List<BkBridge.BkItem> items) {
            this.total = total;
            this.count = count;
            this.unit = total / count;
            this.own = own;
            this.seller = seller;
            this.items = items;
        }
    }

    private static final java.util.regex.Pattern DIGIT = java.util.regex.Pattern.compile("\\d");
    private static final int CACHE_MAX = 3000;
    private static final Map<String, List<BkBridge.BkItem>> MATCH_CACHE = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<BkBridge.BkItem>> e) {
            return size() > CACHE_MAX;
        }
    };
    private static List<BkBridge.BkItem> cacheOwner = null;

    /** Odczytuje ofertę; null gdy to nie oferta (brak ceny). */
    public static Listing read(ItemStack st) {
        if (st == null || st.isEmpty()) return null;
        double total = BkBridge.listingPrice(st);
        if (total <= 0) return null;
        int count = Math.max(1, st.getCount());
        String seller = seller(st);
        String me = playerName();
        boolean own = !me.isEmpty() && (seller.equalsIgnoreCase(me) || (seller.isEmpty() && mentions(st, me)));
        return new Listing(total, count, own, seller, match(st));
    }

    /** Dopasowanie do listy BK z pamięcią podręczną (ten sam przedmiot pojawia się wielokrotnie). */
    public static List<BkBridge.BkItem> match(ItemStack st) {
        List<BkBridge.BkItem> owner = BkBridge.items();
        if (owner != cacheOwner) {
            MATCH_CACHE.clear();
            cacheOwner = owner;
        }
        String sig = net.minecraft.registry.Registries.ITEM.getId(st.getItem()).toString() + "|" + st.getCount()
                + "|" + String.valueOf(st.getComponentChanges()).hashCode();
        List<BkBridge.BkItem> r = MATCH_CACHE.get(sig);
        if (r == null) {
            r = BkBridge.match(st);
            if (r.isEmpty()) r = Collections.emptyList();
            MATCH_CACHE.put(sig, r);
        }
        return r;
    }

    /**
     * Identyfikator oferty: ten sam przez cały czas jej wiszenia na rynku
     * (bez linii opisu z cyframi, bo tam jest m.in. odliczanie do wygaśnięcia).
     */
    public static String listingId(ItemStack st, Listing l) {
        StringBuilder sb = new StringBuilder();
        sb.append(net.minecraft.registry.Registries.ITEM.getId(st.getItem()).getPath())
                .append('|').append(l.count)
                .append('|').append(st.getName().getString())
                .append('|').append(Math.round(l.total * 100))
                .append('|').append(l.seller);
        LoreComponent lore = st.get(DataComponentTypes.LORE);
        if (lore != null) {
            int h = 0;
            for (Text line : lore.lines()) {
                String s = line.getString();
                if (!DIGIT.matcher(s).find()) h = 31 * h + s.hashCode();
            }
            sb.append('|').append(Integer.toHexString(h));
        }
        return sb.toString();
    }

    public static void clearCache() {
        MATCH_CACHE.clear();
    }

    private static String playerName() {
        MinecraftClient mc = MinecraftClient.getInstance();
        try {
            return mc.getSession().getUsername();
        } catch (Throwable t) {
            return mc.player == null ? "" : mc.player.getName().getString();
        }
    }

    /** Nick wystawiającego z linii typu "Wystawił: Nick" / "Sprzedawca: Nick". */
    static String seller(ItemStack st) {
        LoreComponent lore = st.get(DataComponentTypes.LORE);
        if (lore == null) return "";
        for (Text line : lore.lines()) {
            String raw = TextUtil.strip(line.getString());
            String n = TextUtil.norm(raw);
            if (n.contains("wystaw") || n.contains("sprzedaj") || n.contains("sprzedawca") || n.contains("seller")
                    || n.contains("wlasciciel")) {
                int i = raw.indexOf(':');
                String rest = i >= 0 ? raw.substring(i + 1) : raw;
                rest = rest.trim();
                String[] parts = rest.split("\\s+");
                return parts.length == 0 ? "" : parts[0].replaceAll("[^A-Za-z0-9_]", "");
            }
        }
        return "";
    }

    private static boolean mentions(ItemStack st, String name) {
        String lower = name.toLowerCase();
        LoreComponent lore = st.get(DataComponentTypes.LORE);
        if (lore == null) return false;
        for (Text line : lore.lines()) {
            String s = TextUtil.strip(line.getString()).toLowerCase();
            int i = s.indexOf(lower);
            if (i < 0) continue;
            boolean startOk = i == 0 || !Character.isLetterOrDigit(s.charAt(i - 1)) && s.charAt(i - 1) != '_';
            int e = i + lower.length();
            boolean endOk = e >= s.length() || !Character.isLetterOrDigit(s.charAt(e)) && s.charAt(e) != '_';
            if (startOk && endOk && s.contains(":")) return true;
        }
        return false;
    }
}
