package pl.rynek.analityk.bk;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import pl.rynek.analityk.AnalitykClient;
import pl.rynek.analityk.core.PriceFmt;
import pl.rynek.analityk.core.TextUtil;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Most do BK-Rynek 3.0 (refleksja — BK nie musi być w zależnościach kompilacji).
 * Rozpoznawanie przedmiotów i odczyt ceny robi sam BK (jego AutoMarketScanner),
 * więc analiza dotyczy dokładnie tych samych przedmiotów, które BK kupuje.
 */
public final class BkBridge {
    private BkBridge() {}

    private static final String P = "pl.bkubiak.bkrynek.";

    private static boolean initDone;
    private static boolean ok;
    private static String error = "";

    // ClientPriceListManager
    private static Method mGetActiveProfile, mGetList, mGetVersion, mMarkDirty;
    // core PriceEntry
    private static Field fName, fLore, fMaterial, fEnchants, fEnchantMap, fComponentCount, fCmd, fDye, fHead,
            fMaxPrice, fSellPrice, fRequiredCount, fSellCount, fBuyEnabled, fSellEnabled, fUnitMode, fPotions;
    // BkRynekClient / ServerEntry
    private static Method mFindServer;
    private static Field fSrvUnitMode, fSrvMarketCommands, fSrvMarketGuiTitle;
    // CleanMarketEngine / AutoMarketScanner / clean PriceEntry
    private static Class<?> cEngine;
    private static Method mAllEntries, mCleanMaterial, mMatches, mExtractPrice, mToLegacy;
    private static Field fScanner, fOriginals;
    // MarketService / SearchAutomationController
    private static Method mShared, mRunning, mStart, mStop, mSave, mInsideConfirm, mIsBuying;
    private static Field fBuyController;
    // AutoMarketHistoryManager
    private static Method mHistInstance, mGetHistory, mHeName, mHePrice, mHeCount, mHeStack;

    // ------------------------------------------------------------------ init

    public static synchronized boolean init() {
        if (initDone) return ok;
        initDone = true;
        try {
            Class<?> plm = Class.forName(P + "core.manager.ClientPriceListManager");
            mGetActiveProfile = plm.getMethod("getActiveProfile");
            mGetList = plm.getMethod("getPriceListForProfile", String.class);
            mGetVersion = plm.getMethod("getVersion");
            mMarkDirty = plm.getDeclaredMethod("markDirty");
            mMarkDirty.setAccessible(true);

            Class<?> ce = Class.forName(P + "core.config.PriceEntry");
            fName = ce.getField("name");
            fLore = ce.getField("lore");
            fMaterial = ce.getField("material");
            fEnchants = ce.getField("enchants");
            fEnchantMap = ce.getField("enchantments");
            fComponentCount = ce.getField("componentCount");
            fCmd = ce.getField("customModelData");
            fDye = ce.getField("dyeColor");
            fHead = ce.getField("headTextureValue");
            fMaxPrice = ce.getField("maxPrice");
            fSellPrice = ce.getField("sellPrice");
            fRequiredCount = ce.getField("requiredCount");
            fSellCount = ce.getField("sellCount");
            fBuyEnabled = ce.getField("buyEnabled");
            fSellEnabled = ce.getField("sellEnabled");
            fUnitMode = ce.getField("unitMode");
            fPotions = ce.getField("potionEffects");

            Class<?> client = Class.forName(P + "client.BkRynekClient");
            mFindServer = client.getMethod("findServerEntryByProfile", String.class);
            Class<?> se = Class.forName(P + "core.config.ServerEntry");
            fSrvUnitMode = se.getField("unitMode");
            fSrvMarketCommands = se.getField("marketCommands");
            fSrvMarketGuiTitle = se.getField("marketGuiTitle");

            cEngine = Class.forName(P + "client.market.CleanMarketEngine");
            mAllEntries = cEngine.getMethod("allEntries");
            fScanner = cEngine.getDeclaredField("SCANNER");
            fScanner.setAccessible(true);
            fOriginals = cEngine.getDeclaredField("originals");
            fOriginals.setAccessible(true);
            Class<?> cleanEntry = Class.forName(P + "client.market.clean.PriceEntry");
            mCleanMaterial = cleanEntry.getMethod("getMaterial");
            Class<?> scanner = Class.forName(P + "client.market.clean.AutoMarketScanner");
            mMatches = scanner.getMethod("matchesEntry", ItemStack.class, cleanEntry);
            mExtractPrice = scanner.getMethod("extractPrice", List.class);
            Class<?> extractor = Class.forName(P + "client.market.clean.ItemStackExtractor");
            mToLegacy = extractor.getMethod("toLegacy", Text.class);

            Class<?> service = Class.forName(P + "client.market.MarketService");
            mShared = service.getMethod("shared");
            mRunning = service.getMethod("running");
            mStart = service.getMethod("start");
            mStop = service.getMethod("stop");
            mSave = service.getMethod("save");
            Class<?> sac = Class.forName(P + "client.util.SearchAutomationController");
            mInsideConfirm = sac.getMethod("isInsideConfirmScreen");
            fBuyController = sac.getDeclaredField("BUY");
            fBuyController.setAccessible(true);
            Class<?> amc = Class.forName(P + "client.market.clean.AutoMarketController");
            mIsBuying = amc.getMethod("isBuyingItem");

            Class<?> hist = Class.forName(P + "client.market.clean.AutoMarketHistoryManager");
            mHistInstance = hist.getMethod("getInstance");
            mGetHistory = hist.getMethod("getHistory");
            Class<?> he = Class.forName(P + "client.market.clean.AutoMarketHistoryManager$HistoryEntry");
            mHeName = he.getMethod("name");
            mHePrice = he.getMethod("price");
            mHeCount = he.getMethod("count");
            mHeStack = he.getMethod("stack");
            ok = true;
        } catch (Throwable t) {
            ok = false;
            error = "Nie znaleziono zgodnego BK-Rynek 3.0 (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")";
            AnalitykClient.LOG.error("[BK Analityk] {}", error, t);
        }
        return ok;
    }

    public static boolean ok() {
        return init();
    }

    public static String error() {
        return error;
    }

    private static void fail(String where, Throwable t) {
        AnalitykClient.LOG.warn("[BK Analityk] Błąd BK ({}): {}", where, t.toString());
    }

    // ------------------------------------------------------------------ lista przedmiotów BK

    /** Przedmiot z aktywnego profilu BK (żywy obiekt — zmiany cen trafiają prosto do BK). */
    public static final class BkItem {
        public final Object core;
        public final String key;
        public final String material;
        public final String baseName;
        public final String loreHint;
        public String name;

        BkItem(Object core, String key, String material, String baseName, String loreHint) {
            this.core = core;
            this.key = key;
            this.material = material;
            this.baseName = baseName;
            this.loreHint = loreHint;
            this.name = baseName;
        }

        public double rawMaxPrice() {
            return getD(fMaxPrice, core);
        }

        public double rawSellPrice() {
            return getD(fSellPrice, core);
        }

        public boolean buyEnabled() {
            return !Boolean.FALSE.equals(get(fBuyEnabled, core));
        }

        public boolean sellEnabled() {
            return Boolean.TRUE.equals(get(fSellEnabled, core));
        }

        /** ile sztuk obejmuje cena skupu w BK (1 = cena za sztukę) */
        public int buyMultiplier() {
            boolean unit = Boolean.TRUE.equals(get(fUnitMode, core)) || serverUnitMode();
            if (unit) return 1;
            Object rc = get(fRequiredCount, core);
            return rc instanceof Integer ? Math.max(1, (Integer) rc) : 1;
        }

        /** ile sztuk BK wystawia w jednej ofercie sprzedaży */
        public int sellCount() {
            Object sc = get(fSellCount, core);
            return sc instanceof Integer ? Math.max(1, (Integer) sc) : 1;
        }

        /** cena skupu w BK za sztukę (0 = nieustawiona) */
        public double buyPerPiece() {
            double raw = rawMaxPrice();
            return raw > 0 ? raw / buyMultiplier() : 0;
        }

        /** cena sprzedaży w BK za sztukę (0 = nieustawiona) */
        public double sellPerPiece() {
            double raw = rawSellPrice();
            return raw > 0 ? raw / sellCount() : 0;
        }
    }

    private static long cachedVersion = Long.MIN_VALUE;
    private static String cachedProfile = null;
    private static List<BkItem> cachedItems = Collections.emptyList();
    private static IdentityHashMap<Object, BkItem> byCore = new IdentityHashMap<>();
    private static Map<String, List<Object[]>> matchIndex = new HashMap<>();
    private static Object scannerInstance;

    public static String activeProfile() {
        if (!ok()) return null;
        try {
            return (String) mGetActiveProfile.invoke(null);
        } catch (Throwable t) {
            fail("activeProfile", t);
            return null;
        }
    }

    private static long version() {
        try {
            return (long) mGetVersion.invoke(null);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Przedmioty z aktywnego profilu BK. Lista odświeża się, gdy zmieni się profil lub lista w BK. */
    public static synchronized List<BkItem> items() {
        if (!ok()) return Collections.emptyList();
        String profile = activeProfile();
        long v = version();
        if (profile == null) return Collections.emptyList();
        // BK nie zawsze podbija wersję przy edycji w GUI, więc dodatkowo porównujemy rozmiar listy
        List<?> live = liveList(profile);
        if (v == cachedVersion && profile.equals(cachedProfile) && live.size() == cachedItems.size()) {
            return cachedItems;
        }
        rebuild(profile, v, live);
        return cachedItems;
    }

    /** Wymusza przebudowę listy (np. po otwarciu GUI). */
    public static synchronized void invalidate() {
        cachedVersion = Long.MIN_VALUE;
    }

    private static List<?> liveList(String profile) {
        try {
            Object l = mGetList.invoke(null, profile);
            return l instanceof List ? (List<?>) l : Collections.emptyList();
        } catch (Throwable t) {
            fail("getPriceListForProfile", t);
            return Collections.emptyList();
        }
    }

    private static void rebuild(String profile, long v, List<?> live) {
        List<BkItem> out = new ArrayList<>();
        IdentityHashMap<Object, BkItem> map = new IdentityHashMap<>();
        Map<String, Integer> nameCount = new HashMap<>();
        for (Object core : new ArrayList<>(live)) {
            if (core == null) continue;
            String material = normMaterial(str(fMaterial, core));
            String base = TextUtil.strip(str(fName, core));
            if (base.isEmpty()) base = material.replace('_', ' ');
            String hint = TextUtil.firstLoreLine(str(fLore, core));
            BkItem it = new BkItem(core, keyOf(core), material, base, hint);
            out.add(it);
            map.put(core, it);
            nameCount.merge(base, 1, Integer::sum);
        }
        for (BkItem it : out) {
            if (nameCount.getOrDefault(it.baseName, 0) > 1 && !it.loreHint.isEmpty()) {
                it.name = it.baseName + " · " + it.loreHint;
            }
        }
        cachedItems = Collections.unmodifiableList(out);
        byCore = map;
        cachedProfile = profile;
        cachedVersion = v;
        buildMatchIndex();
    }

    /** Stały klucz przedmiotu (nie zmienia się przy zmianie ceny). */
    static String keyOf(Object core) {
        StringBuilder sb = new StringBuilder();
        sb.append(normMaterial(str(fMaterial, core))).append('|')
                .append(str(fName, core)).append('|')
                .append(str(fLore, core)).append('|')
                .append(str(fEnchants, core)).append('|');
        Object em = get(fEnchantMap, core);
        if (em instanceof Map) {
            TreeMap<String, String> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) em).entrySet()) {
                sorted.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
            sb.append(sorted);
        }
        sb.append('|').append(get(fComponentCount, core))
                .append('|').append(get(fCmd, core))
                .append('|').append(get(fDye, core))
                .append('|').append(TextUtil.sha(str(fHead, core)));
        Object pot = get(fPotions, core);
        if (pot instanceof List && !((List<?>) pot).isEmpty()) {
            sb.append('|').append(((List<?>) pot).size());
            for (Object p : (List<?>) pot) {
                if (p == null) continue;
                try {
                    sb.append(',').append(p.getClass().getField("effectId").get(p))
                            .append(':').append(p.getClass().getField("amplifier").get(p));
                } catch (Throwable ignored) {
                    sb.append(',').append(p);
                }
            }
        }
        return TextUtil.sha(sb.toString());
    }

    static String normMaterial(String m) {
        if (m == null) return "*";
        String s = m.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("minecraft:")) s = s.substring(10);
        return s.isEmpty() ? "*" : s;
    }

    // ------------------------------------------------------------------ rozpoznawanie (silnik BK)

    private static void buildMatchIndex() {
        Map<String, List<Object[]>> idx = new HashMap<>();
        try {
            List<?> clean;
            Map<?, ?> originals;
            synchronized (cEngine) {
                clean = new ArrayList<>((List<?>) mAllEntries.invoke(null));
                originals = new IdentityHashMap<>((Map<?, ?>) fOriginals.get(null));
                scannerInstance = fScanner.get(null);
            }
            for (Object ce : clean) {
                Object core = originals.get(ce);
                BkItem it = core == null ? null : byCore.get(core);
                if (it == null) continue;
                String mat = normMaterial((String) mCleanMaterial.invoke(ce));
                idx.computeIfAbsent(mat, k -> new ArrayList<>()).add(new Object[]{ce, it});
            }
        } catch (Throwable t) {
            fail("buildMatchIndex", t);
        }
        matchIndex = idx;
    }

    /** Wszystkie przedmioty z listy BK, do których pasuje ten stack (identycznie jak w BK). */
    public static List<BkItem> match(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !ok()) return Collections.emptyList();
        items(); // odśwież indeks, jeśli lista w BK się zmieniła
        Map<String, List<Object[]>> idx = matchIndex;
        Object scanner = scannerInstance;
        if (scanner == null || idx.isEmpty()) return Collections.emptyList();
        String path = Registries.ITEM.getId(stack.getItem()).getPath().toLowerCase(Locale.ROOT);
        List<BkItem> out = null;
        for (String k : new String[]{path, "*"}) {
            List<Object[]> cands = idx.get(k);
            if (cands == null) continue;
            for (Object[] c : cands) {
                try {
                    if ((Boolean) mMatches.invoke(scanner, stack, c[0])) {
                        if (out == null) out = new ArrayList<>(2);
                        BkItem it = (BkItem) c[1];
                        if (!out.contains(it)) out.add(it);
                    }
                } catch (Throwable t) {
                    fail("matchesEntry", t);
                    return Collections.emptyList();
                }
            }
        }
        return out == null ? Collections.emptyList() : out;
    }

    /** Linie opisu stacka w formacie BK (kody §). */
    public static List<String> legacyLore(ItemStack stack) {
        List<String> out = new ArrayList<>();
        LoreComponent lore = stack.get(DataComponentTypes.LORE);
        if (lore == null) return out;
        for (Text t : lore.lines()) {
            String s = null;
            if (ok()) {
                try {
                    s = (String) mToLegacy.invoke(null, t);
                } catch (Throwable ignored) {
                }
            }
            out.add(s != null ? s : t.getString());
        }
        return out;
    }

    /** Cena całej oferty z opisu (tak jak czyta ją BK), -1 gdy brak. */
    public static double listingPrice(ItemStack stack) {
        List<String> lines = legacyLore(stack);
        if (lines.isEmpty()) return -1;
        Object scanner = scannerInstance;
        if (scanner != null) {
            try {
                double p = (double) mExtractPrice.invoke(scanner, lines);
                if (p > 0) return p;
            } catch (Throwable t) {
                fail("extractPrice", t);
            }
        }
        for (String l : lines) {
            double p = PriceFmt.fromLoreLine(TextUtil.strip(l));
            if (p > 0) return p;
        }
        return -1;
    }

    // ------------------------------------------------------------------ zapis cen do BK

    /** Ustawia cenę skupu (podaną za sztukę) — przelicza na format BK. */
    public static void setBuyPerPiece(BkItem it, double perPiece) {
        double v = perPiece * it.buyMultiplier();
        v = v >= 100 ? Math.floor(v) : Math.floor(v * 100) / 100.0;
        if (v <= 0) v = 1;
        try {
            fMaxPrice.setDouble(it.core, v);
        } catch (Throwable t) {
            fail("setMaxPrice", t);
        }
    }

    /** Ustawia cenę sprzedaży (podaną za sztukę) — BK wystawia ofertę na sellCount sztuk. */
    public static void setSellPerPiece(BkItem it, double perPiece) {
        double v = Math.max(1, Math.round(perPiece * it.sellCount()));
        try {
            fSellPrice.setDouble(it.core, v);
        } catch (Throwable t) {
            fail("setSellPrice", t);
        }
    }

    /** Powiadamia BK o zmianie cen (silnik przeładuje listę w ~0,5 s) i zapisuje profil. */
    public static void commit() {
        if (!ok()) return;
        try {
            mMarkDirty.invoke(null);
        } catch (Throwable t) {
            fail("markDirty", t);
        }
        try {
            mSave.invoke(mShared.invoke(null));
        } catch (Throwable t) {
            fail("save", t);
        }
        invalidate();
    }

    // ------------------------------------------------------------------ sterowanie BK

    public static boolean running() {
        if (!ok()) return false;
        try {
            return (Boolean) mRunning.invoke(mShared.invoke(null));
        } catch (Throwable t) {
            fail("running", t);
            return false;
        }
    }

    /** true gdy BK właśnie kupuje / jest w oknie potwierdzenia. */
    public static boolean busy() {
        if (!ok()) return false;
        try {
            if ((Boolean) mInsideConfirm.invoke(null)) return true;
            Object buy = fBuyController.get(null);
            return buy != null && (Boolean) mIsBuying.invoke(buy);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void stop() {
        if (!ok()) return;
        try {
            mStop.invoke(mShared.invoke(null));
        } catch (Throwable t) {
            fail("stop", t);
        }
    }

    /** Uruchamia BK. Zwraca null albo komunikat błędu. */
    public static String start() {
        if (!ok()) return error;
        try {
            mStart.invoke(mShared.invoke(null));
            return null;
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable c = e.getCause() == null ? e : e.getCause();
            return c.getMessage() == null ? c.toString() : c.getMessage();
        } catch (Throwable t) {
            fail("start", t);
            return t.toString();
        }
    }

    private static Object serverEntry() {
        String profile = activeProfile();
        if (profile == null) return null;
        try {
            return mFindServer.invoke(null, profile);
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean serverUnitMode() {
        Object se = serverEntry();
        return se != null && Boolean.TRUE.equals(get(fSrvUnitMode, se));
    }

    /** Komenda rynku z profilu BK (bez "/"), domyślnie "ah". */
    public static String marketCommand() {
        Object se = serverEntry();
        Object cmds = se == null ? null : get(fSrvMarketCommands, se);
        String c = null;
        if (cmds instanceof List && !((List<?>) cmds).isEmpty()) c = String.valueOf(((List<?>) cmds).get(0));
        if (c == null || c.isBlank()) c = "ah";
        c = c.trim();
        while (c.startsWith("/")) c = c.substring(1);
        return c;
    }

    /** Własny tytuł okna rynku z profilu BK (może być pusty). */
    public static String marketTitle() {
        Object se = serverEntry();
        Object t = se == null ? null : get(fSrvMarketGuiTitle, se);
        return t == null ? "" : TextUtil.norm(String.valueOf(t));
    }

    // ------------------------------------------------------------------ historia zakupów BK

    /** Kopia historii zakupów BK (najnowszy na początku). */
    public static List<Object> purchaseHistory() {
        if (!ok()) return Collections.emptyList();
        try {
            Object mgr = mHistInstance.invoke(null);
            Object l = mGetHistory.invoke(mgr);
            if (!(l instanceof List)) return Collections.emptyList();
            return new ArrayList<Object>((List<?>) l);
        } catch (Throwable t) {
            return Collections.emptyList(); // np. zmiana listy w trakcie kopiowania — spróbujemy w kolejnym ticku
        }
    }

    public static String heName(Object e) {
        try {
            return String.valueOf(mHeName.invoke(e));
        } catch (Throwable t) {
            return "";
        }
    }

    /** łączna cena zakupu (cała oferta) */
    public static double hePrice(Object e) {
        try {
            return (double) mHePrice.invoke(e);
        } catch (Throwable t) {
            return -1;
        }
    }

    public static int heCount(Object e) {
        try {
            return (int) mHeCount.invoke(e);
        } catch (Throwable t) {
            return 1;
        }
    }

    public static ItemStack heStack(Object e) {
        try {
            Object s = mHeStack.invoke(e);
            return s instanceof ItemStack ? (ItemStack) s : ItemStack.EMPTY;
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }

    // ------------------------------------------------------------------ util

    private static Object get(Field f, Object o) {
        try {
            return f.get(o);
        } catch (Throwable t) {
            return null;
        }
    }

    private static double getD(Field f, Object o) {
        try {
            return f.getDouble(o);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String str(Field f, Object o) {
        Object v = get(f, o);
        return v == null ? "" : String.valueOf(v);
    }

    /** Do debugowania: mapa nazw → klucze. */
    public static Map<String, String> debugKeys() {
        Map<String, String> m = new LinkedHashMap<>();
        for (BkItem it : items()) m.put(it.name, it.key);
        return m;
    }
}
