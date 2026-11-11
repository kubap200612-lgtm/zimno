package pl.rynek.analityk.market;

import pl.rynek.analityk.bk.BkBridge;
import pl.rynek.analityk.core.Analysis;
import pl.rynek.analityk.core.Change;
import pl.rynek.analityk.core.ItemHistory;
import pl.rynek.analityk.core.PriceEngine;
import pl.rynek.analityk.core.PriceFmt;
import pl.rynek.analityk.core.Settings;
import pl.rynek.analityk.data.DataStore;

import java.util.ArrayList;
import java.util.List;

/** Wpisuje wyliczone ceny do BK-Rynek (automatycznie po skanie albo ręcznie z GUI). */
public final class AutoPricer {
    private AutoPricer() {}

    public static final class Result {
        public int up, down, sell;
        public final List<String> lines = new ArrayList<>();

        public boolean any() {
            return up + down + sell > 0;
        }
    }

    /** od kiedy cena docelowa jest wyższa niż obecny skup (potwierdzanie podwyżek) */
    private static final java.util.Map<String, Long> RAISE_SINCE = new java.util.HashMap<>();

    /** Automatyczna regulacja (po skanie albo cyklicznie w trybie na żywo), z limitami kroku. */
    public static Result applyAuto(DataStore store) {
        Result r = new Result();
        Settings s = store.settings;
        if (!s.autoBuy && !s.autoSell) return r;
        long now = System.currentTimeMillis();
        for (BkBridge.BkItem it : BkBridge.items()) {
            ItemHistory h = store.get(it);
            if (!h.auto) continue;
            Analysis a = PriceEngine.analyze(h, s, it.buyPerPiece(), now);
            if (s.autoBuy) {
                double cur = it.buyPerPiece();
                boolean higher = !Double.isNaN(a.suggestedBuy) && cur > 0
                        && a.suggestedBuy > cur * (1 + s.hysteresisPct / 100.0);
                if (higher) RAISE_SINCE.putIfAbsent(it.key, now);
                else RAISE_SINCE.remove(it.key);
                Long since = RAISE_SINCE.get(it.key);
                boolean confirmed = since != null && now - since >= s.raiseConfirmMin * 60_000L;
                PriceEngine.Plan p = PriceEngine.planBuy(cur, a, s, confirmed);
                if (p.change) {
                    if (p.value > cur) RAISE_SINCE.put(it.key, now); // kolejna podwyżka znów wymaga potwierdzenia
                    BkBridge.setBuyPerPiece(it, p.value);
                    double after = it.buyPerPiece();
                    h.changes.add(new Change(now, cur, after, false, p.reason));
                    if (after >= cur) r.up++;
                    else r.down++;
                    r.lines.add(it.name + ": skup " + PriceFmt.full(cur) + " → " + PriceFmt.full(after) + " (" + p.reason + ")");
                }
            }
            if (s.autoSell && it.sellEnabled()) {
                double cur = it.sellPerPiece();
                PriceEngine.Plan p = PriceEngine.planSell(cur, a, s);
                if (p.change) {
                    BkBridge.setSellPerPiece(it, p.value);
                    double after = it.sellPerPiece();
                    h.changes.add(new Change(now, cur, after, true, p.reason));
                    r.sell++;
                    r.lines.add(it.name + ": sprzedaż " + PriceFmt.full(cur) + " → " + PriceFmt.full(after));
                }
            }
            h.trim();
        }
        if (r.any()) {
            BkBridge.commit();
            store.markDirty();
        }
        return r;
    }

    /** Ręczne ustawienie sugerowanej ceny skupu (bez limitu kroku). */
    public static String applyBuy(DataStore store, BkBridge.BkItem it) {
        Analysis a = store.analysis(it);
        if (Double.isNaN(a.suggestedBuy)) return "Brak danych - zrób skan rynku";
        double cur = it.buyPerPiece();
        BkBridge.setBuyPerPiece(it, a.suggestedBuy);
        store.get(it).changes.add(new Change(System.currentTimeMillis(), cur, it.buyPerPiece(), false, "ręcznie"));
        BkBridge.commit();
        store.markDirty();
        return "Skup " + it.name + ": " + PriceFmt.full(cur) + " → " + PriceFmt.full(it.buyPerPiece());
    }

    /** Ręczne ustawienie sugerowanej ceny sprzedaży. */
    public static String applySell(DataStore store, BkBridge.BkItem it) {
        Analysis a = store.analysis(it);
        if (Double.isNaN(a.suggestedSell)) return "Brak danych - zrób skan rynku";
        double cur = it.sellPerPiece();
        BkBridge.setSellPerPiece(it, a.suggestedSell);
        store.get(it).changes.add(new Change(System.currentTimeMillis(), cur, it.sellPerPiece(), true, "ręcznie"));
        BkBridge.commit();
        store.markDirty();
        return "Sprzedaż " + it.name + ": " + PriceFmt.full(cur) + " → " + PriceFmt.full(it.sellPerPiece())
                + (it.sellCount() > 1 ? " /szt (oferta " + it.sellCount() + " szt)" : "");
    }
}
