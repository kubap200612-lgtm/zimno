package pl.rynek.analityk.market;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import pl.rynek.analityk.AnalitykClient;
import pl.rynek.analityk.bk.BkBridge;
import pl.rynek.analityk.core.BookEntry;
import pl.rynek.analityk.core.PageDiff;
import pl.rynek.analityk.core.Settings;
import pl.rynek.analityk.core.Snapshot;
import pl.rynek.analityk.data.DataStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Skan rynku: wstrzymuje BK-Rynek, otwiera /ah, (opcjonalnie) sortuje po najniższej cenie,
 * przechodzi przez strony i zbiera najtańsze oferty każdego przedmiotu z listy BK.
 * Potem liczy ceny, wpisuje je do BK i wznawia BK.
 */
public final class ScanController {
    private enum State { IDLE, WAIT_BK, CLOSING, WAIT_MARKET, SORTING, WAIT_SORT, READING, WAIT_PAGE, RESUMING }

    private static final class Acc {
        final List<Double> prices = new ArrayList<>();
        final List<String> ids = new ArrayList<>();
        final List<double[]> book = new ArrayList<>(); // {cena za sztukę, ilość}
        int own;
        int maxStack;
    }

    private final DataStore store;
    private State state = State.IDLE;
    private long deadline;
    private long readyAt;
    private boolean bkWasRunning;
    private boolean manual;
    private int openAttempts;
    private boolean sortTried;
    private int sortClicks;
    private String sortSig = "";
    private boolean sortedClaimed;
    private boolean orderTotalOk;
    private boolean orderUnitOk;
    private double prevPageMaxTotal;
    private double prevPageMaxUnit;
    private int pagesRead;
    private int lastPageNum = -1;
    private int totalPages = -1;
    private String lastSig = "";
    private String firstSig = null;
    private String stableSig = null;
    private int stableTicks;
    private int clickRetries;
    private long lostSince;
    private int listingsSeen;
    private boolean stoppedEarly;
    private long startedAt;
    private final Map<String, Acc> acc = new HashMap<>();
    private List<BkBridge.BkItem> tracked = Collections.emptyList();
    private long nextAuto = 0;
    private long lastScanEnd = 0;
    private String lastSummary = "";

    public ScanController(DataStore store) {
        this.store = store;
    }

    public boolean active() {
        return state != State.IDLE;
    }

    public long nextAutoScan() {
        return nextAuto;
    }

    public long lastScanEnd() {
        return lastScanEnd;
    }

    public String lastSummary() {
        return lastSummary;
    }

    /** Krótki status do HUD. */
    public String status() {
        switch (state) {
            case IDLE: return "";
            case WAIT_BK: return "Czekam aż BK skończy zakup…";
            case CLOSING:
            case WAIT_MARKET: return "Otwieram rynek…";
            case SORTING:
            case WAIT_SORT: return "Sortuję po najniższej cenie…";
            case RESUMING: return "Wznawiam BK-Rynek…";
            default:
                String pg = totalPages > 0 ? (Math.max(1, lastPageNum) + "/" + totalPages) : String.valueOf(pagesRead + 1);
                return "Analiza rynku: strona " + pg + " • ofert " + listingsSeen + (sortedClaimed ? " • szybki skan" : "");
        }
    }

    // ------------------------------------------------------------------ sterowanie

    /** Ręczne uruchomienie (klawisz / przycisk / komenda). Drugi raz w trakcie = przerwij. */
    public void toggleManual(MinecraftClient mc) {
        if (active()) {
            abort(mc, "Skan przerwany.");
        } else {
            start(mc, true);
        }
    }

    public void start(MinecraftClient mc, boolean manual) {
        if (active() || mc.player == null) return;
        if (!BkBridge.ok()) {
            AnalitykClient.chat("§c" + BkBridge.error());
            return;
        }
        tracked = new ArrayList<>(BkBridge.items());
        if (tracked.isEmpty()) {
            AnalitykClient.chat("§cLista przedmiotów w BK-Rynek jest pusta - nie ma czego analizować.");
            return;
        }
        this.manual = manual;
        acc.clear();
        openAttempts = 0;
        sortTried = false;
        sortClicks = 0;
        sortedClaimed = false;
        orderTotalOk = true;
        orderUnitOk = true;
        prevPageMaxTotal = 0;
        prevPageMaxUnit = 0;
        pagesRead = 0;
        lastPageNum = -1;
        totalPages = -1;
        lastSig = "";
        firstSig = null;
        listingsSeen = 0;
        stoppedEarly = false;
        lostSince = 0;
        startedAt = System.currentTimeMillis();
        resetStable();
        bkWasRunning = BkBridge.running();
        state = State.WAIT_BK;
        deadline = System.currentTimeMillis() + 8000;
        if (manual) AnalitykClient.chat("§7Analiza rynku (" + tracked.size() + " przedmiotów)…"
                + (bkWasRunning ? " BK-Rynek wstrzymany na czas skanu." : ""));
    }

    public void abort(MinecraftClient mc, String why) {
        if (!active()) return;
        boolean resume = bkWasRunning;
        closeScreen(mc);
        state = State.IDLE;
        nextAuto = System.currentTimeMillis() + Math.max(1, store.settings.scanIntervalMin) * 60_000L;
        AnalitykClient.chat("§e" + why);
        if (resume && mc.player != null) {
            String err = BkBridge.start();
            if (err != null) AnalitykClient.chat("§cNie udało się wznowić BK-Rynek: " + err);
        }
    }

    /** Rozłączenie z serwerem — przerwij bez wznawiania BK (BK sam obsługuje reconnect). */
    public void onDisconnect() {
        state = State.IDLE;
        nextAuto = 0;
    }

    // ------------------------------------------------------------------ tick

    public void tick(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) {
            if (active()) state = State.IDLE;
            return;
        }
        long now = System.currentTimeMillis();
        if (state == State.IDLE) {
            autoSchedule(mc, now);
            return;
        }
        Screen scr = mc.currentScreen;
        Settings s = store.settings;
        switch (state) {
            case WAIT_BK: {
                if (bkWasRunning && BkBridge.busy() && now < deadline) return;
                if (bkWasRunning) BkBridge.stop();
                closeScreen(mc);
                state = State.CLOSING;
                deadline = now + 600;
                return;
            }
            case CLOSING: {
                if (now >= deadline) openMarket(mc, now);
                return;
            }
            case WAIT_MARKET: {
                if (MarketUi.isMarket(scr)) {
                    ScreenHandler h = MarketUi.handler(scr);
                    String sig = MarketUi.signature(MarketUi.listingSlots(h));
                    if (!stable(sig)) return;
                    int[] pg = MarketUi.page(scr);
                    if (pg != null) {
                        lastPageNum = pg[0];
                        totalPages = pg[1];
                    }
                    if (s.fastScan && !sortTried) {
                        state = State.SORTING;
                    } else {
                        state = State.READING;
                        readyAt = now;
                    }
                } else if (now > deadline) {
                    if (openAttempts < 3) openMarket(mc, now);
                    else finishFailed(mc, "Nie udało się otworzyć rynku (/" + BkBridge.marketCommand() + ").");
                }
                return;
            }
            case SORTING: {
                if (!MarketUi.isMarket(scr)) {
                    if (lost(mc, now)) return;
                    return;
                }
                lostSince = 0;
                sortTried = true;
                ScreenHandler h = MarketUi.handler(scr);
                Slot sb = MarketUi.findSortButton(h);
                if (sb == null) {
                    sortedClaimed = false;
                    toReading(now);
                    return;
                }
                if (MarketUi.sortLowestSelected(sb.getStack())) {
                    sortedClaimed = true;
                    toReading(now);
                    return;
                }
                if (sortClicks >= 6) {
                    sortedClaimed = false;
                    toReading(now);
                    return;
                }
                sortSig = MarketUi.loreText(sb.getStack()) + "#" + MarketUi.signature(MarketUi.listingSlots(h));
                MarketUi.click(mc, h, sb.id, MarketUi.isAnarchia(mc) ? 1 : 0);
                sortClicks++;
                state = State.WAIT_SORT;
                deadline = now + 2000;
                resetStable();
                return;
            }
            case WAIT_SORT: {
                if (MarketUi.isMarket(scr)) {
                    ScreenHandler h = MarketUi.handler(scr);
                    Slot sb = MarketUi.findSortButton(h);
                    String sig = (sb == null ? "" : MarketUi.loreText(sb.getStack())) + "#"
                            + MarketUi.signature(MarketUi.listingSlots(h));
                    if (!sig.equals(sortSig) && stable(sig)) {
                        state = State.SORTING;
                        return;
                    }
                }
                if (now > deadline) state = State.SORTING; // spróbuj jeszcze raz (licznik kliknięć chroni przed pętlą)
                return;
            }
            case READING: {
                if (!MarketUi.isMarket(scr)) {
                    lost(mc, now);
                    return;
                }
                lostSince = 0;
                ScreenHandler h = MarketUi.handler(scr);
                List<Slot> slots = MarketUi.listingSlots(h);
                String sig = MarketUi.signature(slots);
                if (!stable(sig) || now < readyAt) return;
                int[] pg = MarketUi.page(scr);
                if (pg != null) {
                    lastPageNum = pg[0];
                    totalPages = pg[1];
                }
                if (firstSig != null && pagesRead > 0 && sig.equals(firstSig) && !sig.isEmpty()) {
                    finish(mc, true); // rynek "zawinął" na pierwszą stronę
                    return;
                }
                if (firstSig == null) firstSig = sig;
                readPage(slots);
                pagesRead++;
                boolean lastPage = pg != null && pg[0] >= pg[1];
                Slot next = MarketUi.findNextButton(h);
                boolean early = canStopEarly();
                if (lastPage || next == null) {
                    finish(mc, true);
                } else if (early) {
                    stoppedEarly = true;
                    finish(mc, false);
                } else if ((s.maxPages > 0 && pagesRead >= s.maxPages) || pagesRead >= 400) {
                    finish(mc, false);
                } else {
                    lastSig = sig;
                    MarketUi.click(mc, h, next.id, 0);
                    clickRetries = 0;
                    state = State.WAIT_PAGE;
                    deadline = now + 3500;
                    resetStable();
                }
                return;
            }
            case WAIT_PAGE: {
                if (MarketUi.isMarket(scr)) {
                    lostSince = 0;
                    ScreenHandler h = MarketUi.handler(scr);
                    String sig = MarketUi.signature(MarketUi.listingSlots(h));
                    int[] pg = MarketUi.page(scr);
                    boolean changed = (pg != null && lastPageNum > 0 && pg[0] != lastPageNum) || !sig.equals(lastSig);
                    if (changed) {
                        state = State.READING;
                        readyAt = now + s.pageDelayMs;
                        resetStable();
                        return;
                    }
                } else if (scr == null && lost(mc, now)) {
                    return;
                }
                if (now > deadline) {
                    if (clickRetries < 1 && MarketUi.isMarket(scr)) {
                        ScreenHandler h = MarketUi.handler(scr);
                        Slot next = MarketUi.findNextButton(h);
                        if (next != null) {
                            MarketUi.click(mc, h, next.id, 0);
                            clickRetries++;
                            deadline = now + 3500;
                            return;
                        }
                    }
                    finish(mc, false);
                }
                return;
            }
            case RESUMING: {
                if (now < deadline) return;
                state = State.IDLE;
                if (bkWasRunning) {
                    String err = BkBridge.start();
                    if (err != null) AnalitykClient.chat("§cNie udało się wznowić BK-Rynek: " + err);
                }
                return;
            }
            default:
        }
    }

    private void toReading(long now) {
        state = State.READING;
        readyAt = now + store.settings.pageDelayMs;
        resetStable();
    }

    /** Okno rynku zniknęło — po 2 s uznaj, że gracz je zamknął. */
    private boolean lost(MinecraftClient mc, long now) {
        if (lostSince == 0) lostSince = now;
        if (now - lostSince > 2000) {
            if (pagesRead > 0) {
                finish(mc, false);
            } else {
                abort(mc, "Skan przerwany - zamknięto rynek.");
            }
            return true;
        }
        return false;
    }

    private void openMarket(MinecraftClient mc, long now) {
        if (mc.player == null) return;
        openAttempts++;
        mc.player.networkHandler.sendChatCommand(BkBridge.marketCommand());
        state = State.WAIT_MARKET;
        deadline = now + 4000;
        resetStable();
    }

    private void closeScreen(MinecraftClient mc) {
        if (mc.player == null) return;
        if (mc.currentScreen instanceof HandledScreen<?>) {
            mc.player.closeHandledScreen();
        } else if (mc.currentScreen != null) {
            mc.setScreen(null);
        }
    }

    private void resetStable() {
        stableSig = null;
        stableTicks = 0;
    }

    /** Zawartość musi być taka sama przez kilka ticków (serwer dosyła sloty po otwarciu okna). */
    private boolean stable(String sig) {
        if (sig.equals(stableSig)) {
            stableTicks++;
        } else {
            stableSig = sig;
            stableTicks = 0;
        }
        return stableTicks >= (sig.isEmpty() ? 8 : 2);
    }

    // ------------------------------------------------------------------ czytanie strony

    private void readPage(List<Slot> slots) {
        double prevTotal = -1, prevUnit = -1;
        double pageMinTotal = Double.MAX_VALUE, pageMinUnit = Double.MAX_VALUE;
        double pageMaxTotal = 0, pageMaxUnit = 0;
        int found = 0;
        for (Slot sl : slots) {
            ItemStack st = sl.getStack();
            if (st.isEmpty()) continue;
            Listings.Listing l = Listings.read(st);
            if (l == null) continue;
            found++;
            listingsSeen++;
            if (prevTotal >= 0 && l.total < prevTotal * 0.995) orderTotalOk = false;
            if (prevUnit >= 0 && l.unit < prevUnit * 0.995) orderUnitOk = false;
            prevTotal = l.total;
            prevUnit = l.unit;
            pageMinTotal = Math.min(pageMinTotal, l.total);
            pageMinUnit = Math.min(pageMinUnit, l.unit);
            pageMaxTotal = Math.max(pageMaxTotal, l.total);
            pageMaxUnit = Math.max(pageMaxUnit, l.unit);
            for (BkBridge.BkItem it : l.items) {
                Acc a = acc.computeIfAbsent(it.key, k -> new Acc());
                a.maxStack = Math.max(a.maxStack, st.getMaxCount());
                store.noteStack(it.key, st);
                if (l.own) {
                    a.own++;
                } else {
                    a.prices.add(l.unit);
                    a.ids.add(Listings.listingId(st, l));
                    a.book.add(new double[]{l.unit, l.count});
                }
            }
        }
        if (found > 0) {
            if (prevPageMaxTotal > 0 && pageMinTotal < prevPageMaxTotal * 0.98) orderTotalOk = false;
            if (prevPageMaxUnit > 0 && pageMinUnit < prevPageMaxUnit * 0.98) orderUnitOk = false;
            prevPageMaxTotal = Math.max(prevPageMaxTotal, pageMaxTotal);
            prevPageMaxUnit = Math.max(prevPageMaxUnit, pageMaxUnit);
        }
    }

    /**
     * Szybki skan: rynek posortowany rosnąco, więc dalsze strony mają tylko droższe oferty.
     * Kończymy, gdy dla każdego przedmiotu kolejne oferty nie mogą już wejść do najtańszych.
     */
    private boolean canStopEarly() {
        Settings s = store.settings;
        if (!s.fastScan || !sortedClaimed || listingsSeen < 3) return false;
        if (!orderTotalOk && !orderUnitOk) return false;
        int keep = s.keepLowest;
        for (BkBridge.BkItem it : tracked) {
            Acc a = acc.get(it.key);
            int maxStack = Math.max(a == null ? 0 : a.maxStack, store.maxStack(it));
            double bound = orderUnitOk ? prevPageMaxUnit : prevPageMaxTotal / Math.max(1, maxStack);
            if (a != null && a.prices.size() >= keep) {
                List<Double> p = new ArrayList<>(a.prices);
                Collections.sort(p);
                if (bound < p.get(keep - 1)) return false;
            } else if (a != null && !a.prices.isEmpty()) {
                if (bound < 3 * Collections.min(a.prices)) return false;
            } else {
                double lastKnown = store.lastCheapest(it);
                if (!(lastKnown > 0) || bound < 5 * lastKnown) return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ koniec skanu

    private void finish(MinecraftClient mc, boolean reachedEnd) {
        closeScreen(mc);
        long now = System.currentTimeMillis();
        Settings s = store.settings;
        boolean complete = reachedEnd && !stoppedEarly;
        int withOffers = 0;
        for (BkBridge.BkItem it : tracked) {
            Acc a = acc.get(it.key);
            List<Double> p = a == null ? new ArrayList<>() : new ArrayList<>(a.prices);
            Collections.sort(p);
            int k = Math.min(p.size(), s.keepLowest);
            double[] low = new double[k];
            for (int i = 0; i < k; i++) low[i] = Math.round(p.get(i) * 100) / 100.0;
            if (k > 0) withOffers++;
            // przerwany skan bez ofert danego przedmiotu nic o nim nie mówi — nie zapisuj "pustego" punktu
            if (k == 0 && !reachedEnd && !stoppedEarly) continue;
            store.addSnapshot(it, new Snapshot(now, p.size(), a == null ? 0 : a.own, complete, low, it.buyPerPiece()));
            // kalibracja analizy na żywo: pełny skan pokazuje wszystkie wiszące oferty
            if (a != null) {
                List<String> ids = PageDiff.withOccurrence(a.ids);
                List<BookEntry> entries = new ArrayList<>();
                for (int i = 0; i < ids.size(); i++) {
                    double[] b = a.book.get(i);
                    entries.add(new BookEntry(ids.get(i), b[0], (int) b[1], now, now));
                }
                if (complete) {
                    store.bookReplace(it, entries);
                } else {
                    for (BookEntry e : entries) store.bookTouch(it, e.id, e.p, e.c, now, now);
                }
            } else if (complete) {
                store.bookReplace(it, new ArrayList<>());
            }
        }
        AutoPricer.Result r = AutoPricer.applyAuto(store);
        store.saveData();
        long secs = Math.max(1, (now - startedAt) / 1000);
        lastSummary = "Skan: " + pagesRead + " str., " + listingsSeen + " ofert, " + withOffers + "/" + tracked.size()
                + " przedmiotów z ofertami, " + secs + " s" + (stoppedEarly ? " (szybki)" : "");
        StringBuilder msg = new StringBuilder("§a" + lastSummary);
        if (r.any()) {
            msg.append("§7 | ceny: §a▲").append(r.up).append(" §c▼").append(r.down);
            if (r.sell > 0) msg.append(" §e$").append(r.sell);
        } else if (!s.autoBuy && !s.autoSell) {
            msg.append("§7 | auto-ceny wyłączone");
        }
        AnalitykClient.chat(msg.toString());
        if (manual || r.lines.size() <= 4) {
            for (String line : r.lines) AnalitykClient.chat("§7 • " + line);
        }
        lastScanEnd = now;
        nextAuto = now + Math.max(1, s.scanIntervalMin) * 60_000L;
        state = State.RESUMING;
        deadline = now + 700;
    }

    private void finishFailed(MinecraftClient mc, String why) {
        abort(mc, why);
    }

    // ------------------------------------------------------------------ automatyczne skany

    private boolean bkRunningLast;
    private long calibrateAt;

    private void autoSchedule(MinecraftClient mc, long now) {
        Settings s = store.settings;
        // jednorazowa kalibracja po uruchomieniu BK (opcjonalna)
        boolean bkNow = BkBridge.ok() && BkBridge.running();
        if (bkNow && !bkRunningLast && s.calibrateOnStart) calibrateAt = now + 20_000L;
        bkRunningLast = bkNow;
        if (calibrateAt > 0 && now >= calibrateAt) {
            Screen scr = mc.currentScreen;
            if (scr != null && !MarketUi.isMarket(scr)) {
                calibrateAt = now + 5_000L;
                return;
            }
            calibrateAt = 0;
            if (bkNow && !BkBridge.items().isEmpty()) start(mc, false);
            return;
        }
        if (s.scanIntervalMin <= 0) return;
        if (nextAuto == 0) {
            nextAuto = now + 45_000L; // pierwszy skan chwilę po wejściu na serwer
            return;
        }
        if (now < nextAuto) return;
        if (!BkBridge.ok()) {
            nextAuto = now + 600_000L;
            return;
        }
        if (s.scanOnlyWhenBkRuns && !BkBridge.running()) {
            nextAuto = now + 10_000L;
            return;
        }
        Screen scr = mc.currentScreen;
        if (scr != null && !MarketUi.isMarket(scr)) {
            nextAuto = now + 5_000L; // gracz ma otwarte inne okno — nie przeszkadzamy
            return;
        }
        if (BkBridge.items().isEmpty()) {
            nextAuto = now + 60_000L;
            return;
        }
        start(mc, false);
    }

    /** Ustawia następny automatyczny skan za X ms (np. po zmianie interwału). */
    public void reschedule(long inMs) {
        nextAuto = System.currentTimeMillis() + inMs;
    }
}
