package pl.rynek.analityk.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import pl.rynek.analityk.AnalitykClient;
import pl.rynek.analityk.bk.BkBridge;
import pl.rynek.analityk.core.Analysis;
import pl.rynek.analityk.core.BookEntry;
import pl.rynek.analityk.core.Buy;
import pl.rynek.analityk.core.ItemHistory;
import pl.rynek.analityk.core.Obs;
import pl.rynek.analityk.core.PriceEngine;
import pl.rynek.analityk.core.Settings;
import pl.rynek.analityk.core.Snapshot;
import pl.rynek.analityk.core.Sold;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Historia cen i ustawienia na dysku: config/bkanalityk/{settings,data}.json */
public final class DataStore {
    private static final Gson GSON = new GsonBuilder().serializeSpecialFloatingPointValues().create();
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().serializeSpecialFloatingPointValues().create();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bkanalityk-io");
        t.setDaemon(true);
        return t;
    });

    private final Path dir;
    public Settings settings = new Settings();
    private Map<String, ItemHistory> items = new LinkedHashMap<>();
    private final Map<String, ItemStack> icons = new HashMap<>();
    private final Map<String, ItemStack> materialIcons = new HashMap<>();
    private final Map<String, Analysis> analysisCache = new HashMap<>();
    private long analysisStamp = 0;
    private long dataVersion = 1;
    private boolean dirty;
    private long lastSave = System.currentTimeMillis();

    public DataStore(Path dir) {
        this.dir = dir;
    }

    // ------------------------------------------------------------------ zapis / odczyt

    public void load() {
        try {
            Files.createDirectories(dir);
            Path sp = dir.resolve("settings.json");
            if (Files.exists(sp)) {
                Settings s = GSON.fromJson(Files.readString(sp, StandardCharsets.UTF_8), Settings.class);
                if (s != null) settings = s;
            }
        } catch (Exception e) {
            AnalitykClient.LOG.warn("[BK Analityk] Nie udało się wczytać ustawień: {}", e.toString());
        }
        settings.fix();
        try {
            Path dp = dir.resolve("data.json");
            if (Files.exists(dp)) {
                JsonObject root = JsonParser.parseString(Files.readString(dp, StandardCharsets.UTF_8)).getAsJsonObject();
                Type t = new TypeToken<LinkedHashMap<String, ItemHistory>>() {}.getType();
                Map<String, ItemHistory> m = GSON.fromJson(root.get("items"), t);
                if (m != null) items = m;
            }
        } catch (Exception e) {
            AnalitykClient.LOG.warn("[BK Analityk] Nie udało się wczytać historii: {}", e.toString());
            try {
                Path dp = dir.resolve("data.json");
                if (Files.exists(dp)) Files.copy(dp, dir.resolve("data.broken.json"), StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ignored) {
            }
        }
        for (ItemHistory h : items.values()) h.fix();
        changed();
        dirty = false;
    }

    public void saveSettings() {
        settings.fix();
        String json = PRETTY.toJson(settings);
        IO.execute(() -> write(dir.resolve("settings.json"), json));
        changed();
    }

    /** Zapis historii: serializacja w wątku gry, zapis pliku w tle. */
    public void saveData() {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.add("items", GSON.toJsonTree(items));
        String json = GSON.toJson(root);
        dirty = false;
        lastSave = System.currentTimeMillis();
        IO.execute(() -> write(dir.resolve("data.json"), json));
    }

    public void saveNowBlocking() {
        if (!dirty) return;
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.add("items", GSON.toJsonTree(items));
        String json = GSON.toJson(root);
        dirty = false;
        try {
            IO.submit(() -> write(dir.resolve("data.json"), json)).get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            AnalitykClient.LOG.warn("[BK Analityk] Zapis przy wyjściu nieudany: {}", e.toString());
        }
    }

    private void write(Path target, String json) {
        try {
            Files.createDirectories(dir);
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            try {
                Files.writeString(target, json, StandardCharsets.UTF_8);
            } catch (Exception e2) {
                AnalitykClient.LOG.warn("[BK Analityk] Zapis nieudany {}: {}", target, e2.toString());
            }
        }
    }

    /** Wywoływane co tick — zapisuje historię co 2 minuty, jeśli coś się zmieniło. */
    public void tick(long now) {
        if (dirty && now - lastSave > 120_000L) saveData();
    }

    public void markDirty() {
        dirty = true;
        changed();
    }

    private void changed() {
        dataVersion++;
    }

    // ------------------------------------------------------------------ dane przedmiotów

    public ItemHistory get(BkBridge.BkItem it) {
        ItemHistory h = items.computeIfAbsent(it.key, ItemHistory::new);
        h.name = it.name;
        h.material = it.material;
        return h;
    }

    public ItemHistory byKey(String key) {
        return items.get(key);
    }

    public void addSnapshot(BkBridge.BkItem it, Snapshot s) {
        ItemHistory h = get(it);
        h.scans.add(s);
        h.trim();
        markDirty();
    }

    public void addObs(BkBridge.BkItem it, Obs o) {
        ItemHistory h = get(it);
        h.obs.add(o);
        h.trim();
        markDirty();
    }

    public void addBuy(BkBridge.BkItem it, Buy b) {
        ItemHistory h = get(it);
        h.buys.add(b);
        h.trim();
        markDirty();
    }

    public void addSold(BkBridge.BkItem it, Sold x) {
        ItemHistory h = get(it);
        h.sold.add(x);
        h.trim();
        markDirty();
    }

    /** Czy to my (BK) kupiliśmy tę ofertę — zakup w ciągu ±30 s po tej samej cenie. */
    public boolean isRecentOwnBuy(BkBridge.BkItem it, double unit, long t) {
        ItemHistory h = items.get(it.key);
        if (h == null) return false;
        for (int i = h.buys.size() - 1; i >= 0; i--) {
            Buy b = h.buys.get(i);
            if (b.t < t - 30_000L) break;
            if (Math.abs(b.t - t) <= 30_000L && Math.abs(b.p - unit) <= Math.max(0.01, unit * 0.005)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ "książka" ofert na żywo

    /** Dodaje ofertę albo odświeża czas jej ostatniego zobaczenia. Zwraca czas pierwszego zobaczenia. */
    public long bookTouch(BkBridge.BkItem it, String id, double unit, int count, long first, long now) {
        ItemHistory h = get(it);
        for (BookEntry e : h.book) {
            if (e.id.equals(id)) {
                e.last = now;
                return e.first;
            }
        }
        h.book.add(new BookEntry(id, unit, count, first, now));
        if (h.book.size() > 250) h.trim();
        dirty = true;
        return first;
    }

    /** Czas pierwszego zobaczenia oferty, jeśli jest w książce przedmiotu (albo -1). */
    public long bookFirstSeen(BkBridge.BkItem it, String id) {
        ItemHistory h = items.get(it.key);
        if (h == null) return -1;
        for (BookEntry e : h.book) if (e.id.equals(id)) return e.first;
        return -1;
    }

    public boolean bookRemove(BkBridge.BkItem it, String id) {
        ItemHistory h = items.get(it.key);
        if (h == null) return false;
        boolean r = h.book.removeIf(e -> e.id.equals(id));
        if (r) dirty = true;
        return r;
    }

    /** Po pełnym skanie: książka = dokładnie to, co jest teraz na rynku. */
    public void bookReplace(BkBridge.BkItem it, List<BookEntry> entries) {
        ItemHistory h = get(it);
        Map<String, Long> firsts = new HashMap<>();
        for (BookEntry e : h.book) firsts.put(e.id, e.first);
        for (BookEntry e : entries) {
            Long f = firsts.get(e.id);
            if (f != null) e.first = f;
        }
        h.book = new ArrayList<>(entries);
        h.trim();
        dirty = true;
    }

    /** Usuwa oferty niewidziane dłużej niż ttl. */
    public void bookExpire(long ttlMs, long now) {
        for (ItemHistory h : items.values()) {
            if (h.book.removeIf(e -> now - e.last > ttlMs)) dirty = true;
        }
    }

    /** Ceny z książki (rosnąco). */
    public List<Double> bookPrices(BkBridge.BkItem it) {
        ItemHistory h = items.get(it.key);
        if (h == null || h.book.isEmpty()) return Collections.emptyList();
        List<Double> p = new ArrayList<>(h.book.size());
        for (BookEntry e : h.book) p.add(e.p);
        Collections.sort(p);
        return p;
    }

    /** Zapamiętuje wygląd przedmiotu (ikona w GUI) i rozmiar stacka. */
    public void noteStack(String key, ItemStack st) {
        if (st == null || st.isEmpty()) return;
        if (!icons.containsKey(key)) {
            ItemStack c = st.copy();
            c.setCount(1);
            icons.put(key, c);
        }
        ItemHistory h = items.get(key);
        if (h != null && st.getMaxCount() > h.maxStack) {
            h.maxStack = st.getMaxCount();
        }
    }

    public ItemStack icon(BkBridge.BkItem it) {
        ItemStack s = icons.get(it.key);
        if (s != null) return s;
        return materialIcon(it.material);
    }

    public ItemStack materialIcon(String material) {
        if (material == null) return ItemStack.EMPTY;
        ItemStack cached = materialIcons.get(material);
        if (cached != null) return cached;
        ItemStack found = ItemStack.EMPTY;
        for (Item item : Registries.ITEM) {
            if (Registries.ITEM.getId(item).getPath().equals(material)) {
                found = new ItemStack(item);
                break;
            }
        }
        materialIcons.put(material, found);
        return found;
    }

    /** Rozmiar stacka przedmiotu (z obserwacji, potem z materiału, domyślnie 64). */
    public int maxStack(BkBridge.BkItem it) {
        ItemHistory h = items.get(it.key);
        if (h != null && h.maxStack > 0) return h.maxStack;
        ItemStack s = materialIcon(it.material);
        return s.isEmpty() ? 64 : Math.max(1, s.getMaxCount());
    }

    // ------------------------------------------------------------------ analiza (z pamięcią podręczną)

    public Analysis analysis(BkBridge.BkItem it) {
        long now = System.currentTimeMillis();
        long stamp = dataVersion * 31 + now / 15_000L; // przelicz po zmianie danych albo co 15 s
        if (stamp != analysisStamp) {
            analysisCache.clear();
            analysisStamp = stamp;
        }
        String ck = it.key + "|" + it.buyPerPiece();
        Analysis a = analysisCache.get(ck);
        if (a == null) {
            a = PriceEngine.analyze(get(it), settings, it.buyPerPiece(), now);
            analysisCache.put(ck, a);
        }
        return a;
    }

    /** Ostatnia znana najtańsza cena (za sztukę) lub -1. */
    public double lastCheapest(BkBridge.BkItem it) {
        ItemHistory h = items.get(it.key);
        if (h == null) return -1;
        for (int i = h.scans.size() - 1; i >= 0; i--) {
            Snapshot s = h.scans.get(i);
            if (s.hasListings()) return s.low[0];
        }
        return -1;
    }

    public void clearHistory(BkBridge.BkItem it) {
        ItemHistory h = get(it);
        h.scans.clear();
        h.obs.clear();
        h.buys.clear();
        h.changes.clear();
        markDirty();
    }

    public void invalidateAnalysis() {
        changed();
    }
}
