package pl.rynek.analityk.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Silnik cen. Z historii skanów, obserwacji i zakupów liczy:
 *  - realną najtańszą cenę rynkową (z odrzuceniem anomalii),
 *  - cenę sprzedaży, która sprzeda się szybko (o krok taniej niż najtańsza oferta), ale nie poniżej kosztu,
 *  - maksymalną cenę skupu, która po 3% podatku zostawia zadaną marżę.
 */
public final class PriceEngine {
    private PriceEngine() {}

    /** oferta tańsza niż ten ułamek ceny odniesienia jest traktowana jako anomalia (np. pomyłka wystawiającego) */
    public static final double OUTLIER_RATIO = 0.5;
    /** waga EMA */
    public static final double EMA_ALPHA = 0.35;
    public static final long HOUR = 3_600_000L;
    public static final long DAY = 24 * HOUR;

    /** Punkt szeregu cen dla wykresu i analizy. */
    public static final class FloorPoint {
        public final long t;
        public final double cheapest;
        public final double raw;
        public final double ref3;
        public final double ema;
        public final int n;
        public final double bk;

        FloorPoint(long t, double cheapest, double raw, double ref3, double ema, int n, double bk) {
            this.t = t;
            this.cheapest = cheapest;
            this.raw = raw;
            this.ref3 = ref3;
            this.ema = ema;
            this.n = n;
            this.bk = bk;
        }
    }

    /** Szereg najtańszych cen ze wszystkich skanów (tylko skany z ofertami), chronologicznie. */
    public static List<FloorPoint> floors(ItemHistory h) {
        List<FloorPoint> out = new ArrayList<>();
        double ema = Double.NaN;
        for (Snapshot s : h.scans) {
            if (!s.hasListings()) continue;
            double[] p = s.low;
            double reference;
            if (p.length >= 3) reference = median(p);
            else if (!Double.isNaN(ema)) reference = ema;
            else reference = p[0];
            int i = 0;
            while (i < p.length - 1 && p[i] < OUTLIER_RATIO * reference) i++;
            double cheapest = p[i];
            double sum = 0;
            int cnt = 0;
            for (int j = i; j < p.length && cnt < 3; j++, cnt++) sum += p[j];
            double ref3 = sum / cnt;
            ema = Double.isNaN(ema) ? cheapest : ema + EMA_ALPHA * (cheapest - ema);
            out.add(new FloorPoint(s.t, cheapest, p[0], ref3, ema, s.n, s.bk));
        }
        return out;
    }

    /** Krok cenowy: ~1% ceny zaokrąglony do potęgi 10 (999 → 1, 1500 → 10, 25k → 100). */
    public static double step(double price) {
        if (!(price > 0)) return 1;
        double e = Math.floor(Math.log10(price)) - 2;
        return Math.max(0.01, Math.pow(10, e));
    }

    /** Ziarnistość zaokrągleń cen (od 100$ w górę pełne dolary). */
    public static double gran(double price) {
        double g = step(price) / 10.0;
        if (price >= 100) g = Math.max(1, g);
        return Math.max(0.01, g);
    }

    public static double floorTo(double v, double g) {
        return round2(Math.floor(v / g + 1e-9) * g);
    }

    public static double ceilTo(double v, double g) {
        return round2(Math.ceil(v / g - 1e-9) * g);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /**
     * Pełna analiza przedmiotu.
     *
     * @param bkBuy aktualna cena skupu w BK (za sztukę), 0 = nieustawiona
     */
    public static Analysis analyze(ItemHistory h, Settings s, double bkBuy, long now) {
        Analysis a = new Analysis();
        double tax = s.tax();
        List<FloorPoint> fl = floors(h);
        a.scansWithData = fl.size();

        Snapshot last = h.lastScan();
        if (last != null) {
            a.lastScanT = last.t;
            a.listings = last.n;
            a.ownListings = last.own;
            a.complete = last.complete;
            a.live = last.live;
            a.stale = now - last.t > s.staleMinutes * 60_000L;
        }
        FloorPoint lastFloor = fl.isEmpty() ? null : fl.get(fl.size() - 1);
        boolean lastHasListings = last != null && last.hasListings();
        if (lastHasListings) {
            a.cheapest = lastFloor.cheapest;
            a.rawCheapest = lastFloor.raw;
            a.ref3 = lastFloor.ref3;
        }
        if (lastFloor != null) a.ema = lastFloor.ema;
        a.hasData = last != null && (lastHasListings || lastFloor != null);

        // --- zmiana 24h i zmienność
        List<Double> day = new ArrayList<>();
        long firstT = 0, lastT = 0;
        for (FloorPoint p : fl) {
            if (p.t >= now - DAY) {
                if (day.isEmpty()) firstT = p.t;
                lastT = p.t;
                day.add(p.cheapest);
            }
        }
        if (day.size() >= 2 && lastT - firstT >= HOUR) {
            a.change24h = (day.get(day.size() - 1) - day.get(0)) / day.get(0);
        }
        List<Double> volSample = day.size() >= 3 ? day : tail(fl, 10);
        if (volSample.size() >= 3) {
            double mean = 0;
            for (double v : volSample) mean += v;
            mean /= volSample.size();
            double var = 0;
            for (double v : volSample) var += (v - mean) * (v - mean);
            var /= (volSample.size() - 1);
            a.volatility = mean > 0 ? Math.sqrt(var) / mean : Double.NaN;
        }

        // --- płynność: nowe oferty na godzinę
        int obsDay = 0;
        long obsFirst = Long.MAX_VALUE;
        for (Obs o : h.obs) {
            if (o.t >= now - DAY) {
                obsDay++;
                obsFirst = Math.min(obsFirst, o.t);
            }
        }
        if (obsDay > 0) {
            double hours = Math.max(1.0, (now - obsFirst) / (double) HOUR);
            a.newPerHour = obsDay / hours;
        }

        // --- popyt: oferty, które zniknęły z rynku (kupione przez innych)
        List<Double> ages = new ArrayList<>();
        long soldFirst = Long.MAX_VALUE;
        for (Sold x : h.sold) {
            if (x.t < now - DAY) continue;
            a.sold24h++;
            soldFirst = Math.min(soldFirst, x.t);
            ages.add(x.age / 60_000.0);
            if (x.age <= 15 * 60_000L && (Double.isNaN(a.quickSaleMax) || x.p > a.quickSaleMax)) a.quickSaleMax = x.p;
        }
        if (a.sold24h > 0) {
            double hours = Math.max(1.0, (now - soldFirst) / (double) HOUR);
            a.soldPerHour = a.sold24h / hours;
            java.util.Collections.sort(ages);
            int n = ages.size();
            a.medianSaleMin = n % 2 == 1 ? ages.get(n / 2) : (ages.get(n / 2 - 1) + ages.get(n / 2)) / 2;
        }

        // --- sugerowana marża (większa przy dużej zmienności i małej liczbie ofert)
        double cv = Double.isNaN(a.volatility) ? 0.05 : a.volatility;
        double sm = 0.05 + 1.5 * cv;
        if (lastHasListings && a.listings < 3) sm += 0.04;
        sm = Math.max(0.05, Math.min(0.35, sm));
        a.suggestedMarginPct = Math.round(sm * 100) / 100.0;

        MarginSpec margin = MarginSpec.resolve(h.margin, s.defaultMargin);
        a.marginLabel = margin.mode == MarginSpec.Mode.AUTO
                ? "auto " + PriceFmt.pct(a.suggestedMarginPct, false)
                : margin.label();

        // --- cena sprzedaży "szybko się sprzeda"
        double sellRef = Double.NaN;
        if (lastHasListings) {
            a.step = step(a.cheapest);
            a.sellFast = Math.max(a.step, floorTo(a.cheapest - a.step, a.step));
            sellRef = a.sellFast;
            // ostrożnie przy nagłych wzrostach: bierz niższą z (teraz, wygładzona)
            if (a.scansWithData >= 2 && !Double.isNaN(a.ema)) {
                double emaSell = floorTo(a.ema - step(a.ema), step(a.ema));
                if (emaSell > 0) sellRef = Math.min(sellRef, emaSell);
            }
        } else if (last != null && !Double.isNaN(a.ema)) {
            a.noCompetition = true;
            a.step = step(a.ema);
            sellRef = floorTo(a.ema - a.step, a.step);
        }

        // --- cena skupu
        if (!Double.isNaN(sellRef) && sellRef > 0) {
            double net = sellRef * (1 - tax);
            double m;
            switch (margin.mode) {
                case PCT: m = margin.value * sellRef; break;
                case ABS: m = margin.value; break;
                default: m = a.suggestedMarginPct * sellRef; break;
            }
            a.marginAbs = m;
            double buy = floorTo(net - m, gran(sellRef));
            if (buy < gran(sellRef)) buy = gran(sellRef);
            a.suggestedBuy = buy;
            a.profitAtBuy = net - buy;
        }

        // --- koszt zakupu (ostatnie 7 dni, max 30 zakupów)
        double cost = 0;
        int pcs = 0;
        int taken = 0;
        for (int i = h.buys.size() - 1; i >= 0 && taken < 30; i--, taken++) {
            Buy b = h.buys.get(i);
            if (b.t < now - 7 * DAY && taken > 0) break;
            cost += b.p * Math.max(1, b.c);
            pcs += Math.max(1, b.c);
        }
        if (pcs > 0) {
            a.avgCost = cost / pcs;
            a.costPieces = pcs;
        } else {
            a.costEstimated = true;
            if (bkBuy > 0) a.avgCost = bkBuy;
            else if (!Double.isNaN(a.suggestedBuy)) a.avgCost = a.suggestedBuy;
        }

        // --- minimalna cena bez straty i sugerowana cena sprzedaży
        if (!Double.isNaN(a.avgCost) && a.avgCost > 0) {
            double need = a.avgCost * (1 + s.minProfitPct / 100.0) / (1 - tax);
            a.minSell = ceilTo(need, gran(need));
        }
        if (lastHasListings) {
            if (!Double.isNaN(a.minSell) && a.sellFast < a.minSell) {
                a.belowCost = true;
                a.suggestedSell = a.minSell;
            } else {
                a.suggestedSell = a.sellFast;
            }
        } else if (a.noCompetition) {
            double emaUp = ceilTo(a.ema, step(a.ema));
            a.suggestedSell = Double.isNaN(a.minSell) ? emaUp : Math.max(a.minSell, emaUp);
        }
        if (!Double.isNaN(a.suggestedSell) && !Double.isNaN(a.avgCost)) {
            a.profitPerPiece = a.suggestedSell * (1 - tax) - a.avgCost;
        }

        // --- werdykt
        if (!a.hasData) {
            a.level = Analysis.LEVEL_NONE;
            a.verdict = "Brak danych - zrób skan rynku";
        } else if (a.belowCost) {
            a.level = Analysis.LEVEL_BAD;
            a.verdict = "Rynek (" + PriceFmt.full(a.cheapest) + ") poniżej Twojego kosztu - wystaw min. "
                    + PriceFmt.full(a.minSell) + " albo poczekaj";
        } else if (a.noCompetition) {
            a.level = Analysis.LEVEL_WARN;
            a.verdict = "Brak ofert na rynku - możesz wystawić drożej (ostatnio ~" + PriceFmt.full(a.ema) + ")";
        } else if (a.stale) {
            a.level = Analysis.LEVEL_WARN;
            a.verdict = "Dane sprzed " + ((now - a.lastScanT) / 60_000L) + " min - zrób nowy skan";
        } else {
            a.level = Analysis.LEVEL_OK;
            a.verdict = "Skupuj do " + PriceFmt.full(a.suggestedBuy) + ", sprzedawaj po "
                    + PriceFmt.full(a.suggestedSell);
        }
        return a;
    }

    private static List<Double> tail(List<FloorPoint> fl, int n) {
        List<Double> out = new ArrayList<>();
        for (int i = Math.max(0, fl.size() - n); i < fl.size(); i++) out.add(fl.get(i).cheapest);
        return out;
    }

    static double median(double[] sorted) {
        int n = sorted.length;
        if (n == 0) return Double.NaN;
        return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
    }

    // ------------------------------------------------------------------ automatyczne zmiany

    /** Decyzja o zmianie ceny w BK. */
    public static final class Plan {
        public final boolean change;
        public final double value;
        public final String reason;

        Plan(boolean change, double value, String reason) {
            this.change = change;
            this.value = value;
            this.reason = reason;
        }

        static Plan keep(String why) {
            return new Plan(false, Double.NaN, why);
        }
    }

    /**
     * Nowa cena skupu z ograniczeniem kroku:
     *  - podwyżka max {@code maxRaisePct} na raz i dopiero gdy potwierdzą ją 2 skany,
     *  - obniżka max {@code maxDropPct}, a natychmiast gdy obecna cena skupu jest już nieopłacalna,
     *  - drobne zmiany (poniżej progu) są pomijane.
     */
    public static Plan planBuy(double current, Analysis a, Settings s) {
        return planBuy(current, a, s, true);
    }

    /**
     * @param raiseConfirmed czy wyższa cena docelowa utrzymuje się już wystarczająco długo
     */
    public static Plan planBuy(double current, Analysis a, Settings s, boolean raiseConfirmed) {
        if (!a.hasData || a.stale || Double.isNaN(a.suggestedBuy)) return Plan.keep("brak świeżych danych");
        if (a.noCompetition) return Plan.keep("brak ofert na rynku");
        double target = a.suggestedBuy;
        if (!(current > 0)) {
            return new Plan(true, target, "pierwsza wycena");
        }
        double next;
        String why;
        if (target > current) {
            if (a.scansWithData < 2 || !raiseConfirmed) return Plan.keep("czekam na potwierdzenie wzrostu");
            double cap = current * (1 + s.maxRaisePct / 100.0);
            next = Math.min(target, cap);
            why = next < target ? "rynek w górę (limit kroku)" : "rynek w górę";
        } else {
            boolean losing = !Double.isNaN(a.cheapest) && a.cheapest * (1 - s.tax()) <= current;
            if (losing) {
                next = target;
                why = "obecna cena nieopłacalna";
            } else {
                double cap = current * (1 - s.maxDropPct / 100.0);
                next = Math.max(target, cap);
                why = next > target ? "rynek w dół (limit kroku)" : "rynek w dół";
            }
        }
        next = floorTo(next, gran(next));
        if (next <= 0) return Plan.keep("cena <= 0");
        if (Math.abs(next - current) / current < s.hysteresisPct / 100.0) return Plan.keep("zmiana poniżej progu");
        return new Plan(true, next, why);
    }

    /** Nowa cena sprzedaży (za sztukę): podąża za rynkiem, ale nigdy poniżej ceny bez straty. */
    public static Plan planSell(double current, Analysis a, Settings s) {
        if (!a.hasData || a.stale || Double.isNaN(a.suggestedSell)) return Plan.keep("brak świeżych danych");
        double next = a.suggestedSell;
        if (next <= 0) return Plan.keep("cena <= 0");
        if (current > 0 && Math.abs(next - current) / current < 0.01) return Plan.keep("zmiana poniżej progu");
        return new Plan(true, next, a.belowCost ? "min. cena bez straty" : "o krok taniej niż rynek");
    }
}
