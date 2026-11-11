package pl.rynek.analityk.market;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import pl.rynek.analityk.AnalitykClient;
import pl.rynek.analityk.bk.BkBridge;
import pl.rynek.analityk.core.Obs;
import pl.rynek.analityk.core.PageDiff;
import pl.rynek.analityk.core.Settings;
import pl.rynek.analityk.core.Snapshot;
import pl.rynek.analityk.core.Sold;
import pl.rynek.analityk.core.TextUtil;
import pl.rynek.analityk.data.DataStore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Analiza na żywo — w trakcie skanowania BK-Rynek, bez jego wstrzymywania.
 *
 * Na Anarchii BK stoi na 1. stronie rynku posortowanej "Najnowsze", a serwer na bieżąco dosyła zmiany.
 * Porównujemy kolejne widoki tej strony:
 *  - nowa oferta na górze → zapisujemy jej cenę (podaż) i dodajemy do "książki" aktywnych ofert,
 *  - oferta zniknęła ze środka strony → ktoś ją kupił (popyt, czas sprzedaży) → usuwamy z książki,
 *  - oferta zepchnięta z końca strony → dalej wisi na rynku, zostaje w książce (do czasu TTL).
 * Z książki co chwilę powstaje "skan na żywo" (najtańsze aktywne oferty), a co kilka minut
 * ceny w BK są korygowane — tak samo jak po pełnym skanie, ale bez przerw w pracy BK.
 */
public final class LiveTracker {
    private static final long FIRST_SEEN_KEEP = 48 * 3_600_000L;
    private static final long CONTINUITY_GAP = 10_000L;
    private static final long PENDING_DELAY = 6_000L;

    private static final class Seen {
        final String id;
        final double unit;
        final int count;
        final boolean own;
        final List<BkBridge.BkItem> items;

        Seen(String id, double unit, int count, boolean own, List<BkBridge.BkItem> items) {
            this.id = id;
            this.unit = unit;
            this.count = count;
            this.own = own;
            this.items = items;
        }
    }

    private static final class Pending {
        final Seen seen;
        final long at;
        final long age;

        Pending(Seen seen, long at, long age) {
            this.seen = seen;
            this.at = at;
            this.age = age;
        }
    }

    private final DataStore store;

    private List<String> prevIds;
    private Map<String, Seen> prevById = new HashMap<>();
    private boolean prevOrdered;
    private long prevAt;
    private String pendingSig;
    private int stableTicks;
    private String processedSig;
    private final Map<String, Long> firstSeen = new HashMap<>();
    private final List<Pending> pendingSold = new ArrayList<>();
    private final Map<String, Long> lastSnap = new HashMap<>();
    private final Map<String, String> lastSnapSig = new HashMap<>();

    private long lastMarketSeen;
    private long lastSweep;
    private long lastAdjust;
    private long lastPrune;
    private boolean orderedNow;
    private int addedTop;
    private int addedBottom;
    private int sessionNew;
    private int sessionSold;
    private long sessionStart;

    public LiveTracker(DataStore store) {
        this.store = store;
    }

    // ------------------------------------------------------------------ status dla GUI

    /** true gdy w ostatniej minucie widzieliśmy rynek (BK skanuje). */
    public boolean watching() {
        return System.currentTimeMillis() - lastMarketSeen < 60_000L;
    }

    public boolean orderedView() {
        return orderedNow;
    }

    public int sessionNew() {
        return sessionNew;
    }

    public int sessionSold() {
        return sessionSold;
    }

    public long sessionStart() {
        return sessionStart;
    }

    public long lastAdjust() {
        return lastAdjust;
    }

    public long nextAdjust() {
        return lastAdjust + store.settings.adjustIntervalMin * 60_000L;
    }

    // ------------------------------------------------------------------ tick

    public void tick(MinecraftClient mc, boolean scanActive) {
        long now = System.currentTimeMillis();
        Settings s = store.settings;
        if (!s.liveAnalysis || scanActive || mc.player == null) {
            prevIds = null;
            periodic(now, false);
            return;
        }
        Screen scr = mc.currentScreen;
        if (!MarketUi.isMarket(scr)) {
            if (prevIds != null && now - prevAt > CONTINUITY_GAP) prevIds = null;
            periodic(now, false);
            return;
        }
        ScreenHandler h = MarketUi.handler(scr);
        if (h == null) return;
        List<Slot> slots = MarketUi.listingSlots(h);
        String sig = MarketUi.signature(slots);
        lastMarketSeen = now;
        if (sessionStart == 0) sessionStart = now;
        if (!sig.equals(pendingSig)) {
            pendingSig = sig;
            stableTicks = 0;
            periodic(now, true);
            return;
        }
        stableTicks++;
        if (stableTicks < 1 || sig.equals(processedSig)) {
            periodic(now, true);
            return;
        }
        processedSig = sig;
        processView(scr, h, slots, now);
        periodic(now, true);
    }

    private void processView(Screen scr, ScreenHandler h, List<Slot> slots, long now) {
        int[] pg = MarketUi.page(scr);
        boolean page1 = pg == null || pg[0] == 1;
        boolean ordered = page1 && sortNewest(h);
        orderedNow = ordered;

        List<String> raw = new ArrayList<>();
        List<Seen> seens = new ArrayList<>();
        List<ItemStack> stacks = new ArrayList<>();
        for (Slot sl : slots) {
            ItemStack st = sl.getStack();
            if (st.isEmpty()) continue;
            Listings.Listing l = Listings.read(st);
            if (l == null) continue;
            String id = Listings.listingId(st, l);
            raw.add(id);
            seens.add(new Seen(id, l.unit, l.count, l.own, l.items));
            stacks.add(st);
        }
        List<String> ids = PageDiff.withOccurrence(raw);

        // zabezpieczenie przed niedoładowaną stroną (serwer dosyła sloty po kawałku)
        if (prevIds != null && prevIds.size() >= 6 && ids.size() < prevIds.size() / 2) return;

        Map<String, Seen> byId = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            Seen sOld = seens.get(i);
            Seen sv = new Seen(ids.get(i), sOld.unit, sOld.count, sOld.own, sOld.items);
            byId.put(sv.id, sv);
            ItemStack st = stacks.get(i);
            Long fs = firstSeen.get(sv.id);
            boolean fresh = false;
            if (fs == null) {
                long known = -1;
                for (BkBridge.BkItem it : sv.items) {
                    long f = store.bookFirstSeen(it, sv.id);
                    if (f > 0) known = known < 0 ? f : Math.min(known, f);
                }
                fs = known > 0 ? known : now;
                fresh = known <= 0;
                firstSeen.put(sv.id, fs);
            }
            for (BkBridge.BkItem it : sv.items) {
                store.noteStack(it.key, st);
                if (sv.own) continue;
                if (fresh) store.addObs(it, new Obs(now, sv.unit, sv.count));
                store.bookTouch(it, sv.id, sv.unit, sv.count, fs, now);
            }
            if (fresh && !sv.items.isEmpty() && !sv.own) sessionNew++;
        }

        // zniknięcia — tylko gdy oba widoki to 1. strona "Najnowsze" i nie było przerwy
        if (ordered && prevOrdered && prevIds != null && now - prevAt <= CONTINUITY_GAP) {
            // gdzie pojawiają się nowe oferty? (na wypadek, gdyby serwer pokazywał najnowsze na końcu strony)
            java.util.Set<String> prevSet = new java.util.HashSet<>(prevIds);
            for (int i = 0; i < ids.size(); i++) {
                if (prevSet.contains(ids.get(i))) continue;
                if (i < ids.size() / 2) addedTop++;
                else addedBottom++;
            }
            boolean reversed = addedBottom >= 10 && addedBottom > 2 * addedTop;
            List<String> a = prevIds, b = ids;
            if (reversed) {
                a = new ArrayList<>(prevIds);
                b = new ArrayList<>(ids);
                java.util.Collections.reverse(a);
                java.util.Collections.reverse(b);
            }
            PageDiff d = PageDiff.diff(a, b, slots.size());
            for (String rid : d.removed) {
                Seen ps = prevById.get(rid);
                if (ps == null || ps.own || ps.items.isEmpty()) continue;
                long age = now - firstSeen.getOrDefault(rid, now);
                for (BkBridge.BkItem it : ps.items) store.bookRemove(it, rid);
                pendingSold.add(new Pending(ps, now, age));
            }
        }
        prevIds = ids;
        prevById = byId;
        prevOrdered = ordered;
        prevAt = now;
    }

    /** Czy przycisk sortowania ma wybrane "Najnowsze". */
    private static boolean sortNewest(ScreenHandler h) {
        Slot sb = MarketUi.findSortButton(h);
        if (sb == null) return false;
        LoreComponent lore = sb.getStack().get(DataComponentTypes.LORE);
        if (lore == null) return false;
        for (Text line : lore.lines()) {
            if (TextUtil.norm(line.getString()).contains("nowsz") && MarketUi.highlighted(line)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ zadania okresowe

    private void periodic(long now, boolean marketVisible) {
        Settings s = store.settings;

        // zniknięcia potwierdzamy po kilku sekundach — jeśli to był nasz zakup (BK), nie liczymy go jako popyt
        if (!pendingSold.isEmpty()) {
            Iterator<Pending> it = pendingSold.iterator();
            while (it.hasNext()) {
                Pending p = it.next();
                if (now - p.at < PENDING_DELAY) continue;
                it.remove();
                boolean ours = false;
                for (BkBridge.BkItem item : p.seen.items) {
                    if (store.isRecentOwnBuy(item, p.seen.unit, p.at)) ours = true;
                }
                if (ours) continue;
                for (BkBridge.BkItem item : p.seen.items) {
                    store.addSold(item, new Sold(p.at, p.seen.unit, p.seen.count, p.age));
                }
                sessionSold++;
            }
        }

        if (now - lastSweep < 15_000L) return;
        lastSweep = now;
        store.bookExpire(s.bookTtlMin * 60_000L, now);

        // migawki "na żywo" dla wykresu i analizy
        if (s.liveAnalysis && watching()) {
            for (BkBridge.BkItem it : BkBridge.items()) {
                List<Double> prices = store.bookPrices(it);
                int k = Math.min(prices.size(), s.keepLowest);
                double[] low = new double[k];
                for (int i = 0; i < k; i++) low[i] = Math.round(prices.get(i) * 100) / 100.0;
                String sig = prices.size() + ":" + Arrays.toString(low);
                long ls = lastSnap.getOrDefault(it.key, 0L);
                String prevSig = lastSnapSig.get(it.key);
                boolean changed = !sig.equals(prevSig);
                boolean wasEmpty = prevSig == null || prevSig.startsWith("0:");
                if (k == 0 && wasEmpty) continue; // nic nie wiemy o tym przedmiocie — nie zaśmiecaj historii
                if ((changed && now - ls >= 30_000L) || now - ls >= 5 * 60_000L) {
                    Snapshot snap = new Snapshot(now, prices.size(), 0, false, low, it.buyPerPiece());
                    snap.live = true;
                    store.addSnapshot(it, snap);
                    lastSnap.put(it.key, now);
                    lastSnapSig.put(it.key, sig);
                }
            }
        }

        // korekta cen w BK co kilka minut (bez pauzy BK)
        if (lastAdjust == 0) lastAdjust = now;
        if (s.liveAnalysis && watching() && now - lastAdjust >= s.adjustIntervalMin * 60_000L) {
            lastAdjust = now;
            AutoPricer.Result r = AutoPricer.applyAuto(store);
            if (r.any() && s.notifyChanges) {
                StringBuilder msg = new StringBuilder("§7Ceny w BK (na żywo): §a▲").append(r.up)
                        .append(" §c▼").append(r.down);
                if (r.sell > 0) msg.append(" §e$").append(r.sell);
                msg.append(" §8(J - szczegóły)");
                AnalitykClient.chat(msg.toString());
            }
        }

        if (now - lastPrune > 600_000L) {
            lastPrune = now;
            firstSeen.values().removeIf(t -> now - t > FIRST_SEEN_KEEP);
        }
    }

    /** Wymusza korektę cen przy najbliższym ticku. */
    public void adjustSoon() {
        lastAdjust = 1;
    }
}
