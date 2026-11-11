package pl.rynek.analityk.gui;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import pl.rynek.analityk.core.Analysis;
import pl.rynek.analityk.core.Buy;
import pl.rynek.analityk.core.Change;
import pl.rynek.analityk.core.ItemHistory;
import pl.rynek.analityk.core.Obs;
import pl.rynek.analityk.core.PriceEngine;
import pl.rynek.analityk.core.PriceFmt;
import pl.rynek.analityk.core.Snapshot;
import pl.rynek.analityk.core.Sold;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Wykres cen jednego przedmiotu:
 *  linia niebieska  – najtańsza oferta na rynku (po odrzuceniu anomalii),
 *  linia pomarańczowa (schodkowa) – cena skupu w BK,
 *  kropki morskie   – Twoje zakupy,
 *  kropki fioletowe – nowe oferty zauważone w trakcie pracy BK,
 *  przerywana żółta – sugerowana cena sprzedaży.
 */
public final class ChartRenderer {
    private ChartRenderer() {}

    private static final class Pt {
        final long t;
        final double v;

        Pt(long t, double v) {
            this.t = t;
            this.v = v;
        }
    }

    private static final String[] LEGEND = {"Najtańsza", "Skup BK", "Twoje zakupy", "Nowe oferty", "Kupione (inni)", "Sug. sprzedaż"};

    /** Wysokość legendy dla danej szerokości (bez rysowania). */
    public static int legendHeight(TextRenderer tr, int maxW) {
        int cx = 0, lines = 1;
        for (String l : LEGEND) {
            int w = 12 + tr.getWidth(l) + 8;
            if (cx + w > maxW && cx > 0) {
                cx = 0;
                lines++;
            }
            cx += w;
        }
        return lines * 10;
    }

    /** Legenda nad wykresem. Zwraca wysokość. */
    public static int legend(DrawContext ctx, TextRenderer tr, int x, int y, int maxW) {
        String[] labels = LEGEND;
        int[] colors = {Ui.S_CHEAPEST, Ui.S_BK, Ui.S_BUYS, Ui.S_OBS, Ui.S_SOLD, Ui.S_SELL};
        int cx = x;
        int cy = y;
        for (int i = 0; i < labels.length; i++) {
            int w = 12 + tr.getWidth(labels[i]) + 8;
            if (cx + w > x + maxW && cx > x) {
                cx = x;
                cy += 10;
            }
            swatch(ctx, i, cx, cy + 3, colors[i]);
            ctx.drawText(tr, labels[i], cx + 12, cy, Ui.TEXT_2, false);
            cx += w;
        }
        return cy - y + 10;
    }

    private static void swatch(DrawContext ctx, int kind, int x, int y, int color) {
        switch (kind) {
            case 0: ctx.fill(x, y, x + 9, y + 2, color); break;                  // linia
            case 1: ctx.fill(x, y + 2, x + 5, y + 3, color); ctx.fill(x + 4, y - 1, x + 5, y + 3, color);
                ctx.fill(x + 4, y - 1, x + 9, y, color); break;                     // schodek
            case 2: ctx.fill(x + 2, y - 1, x + 7, y + 4, color); break;           // kropka
            case 3: ctx.fill(x + 3, y, x + 6, y + 3, color); break;               // mała kropka
            case 4: cross(ctx, x + 4, y + 1, color); break;                        // krzyżyk
            default: ctx.fill(x, y + 1, x + 3, y + 2, color); ctx.fill(x + 6, y + 1, x + 9, y + 2, color); // przerywana
        }
    }

    /** Rysuje wykres; zwraca linie dymka dla pozycji myszy (pusta lista = brak). */
    public static List<String> draw(DrawContext ctx, TextRenderer tr, int x, int y, int w, int h,
                                    ItemHistory hist, Analysis a, long rangeMs, int mx, int my) {
        List<String> tip = new ArrayList<>();
        ctx.fill(x, y, x + w, y + h, Ui.SURFACE_2);
        long now = System.currentTimeMillis();
        long t0 = now - rangeMs;
        boolean longRange = rangeMs > PriceEngine.DAY;

        // --- dane w zakresie
        List<PriceEngine.FloorPoint> floors = new ArrayList<>();
        PriceEngine.FloorPoint before = null;
        for (PriceEngine.FloorPoint p : PriceEngine.floors(hist)) {
            if (p.t < t0) before = p;
            else floors.add(p);
        }
        if (before != null) floors.add(0, before);

        List<Pt> bk = new ArrayList<>();
        Pt bkBefore = null;
        List<Pt> bkAll = new ArrayList<>();
        for (Snapshot s : hist.scans) if (s.bk > 0) bkAll.add(new Pt(s.t, s.bk));
        for (Change c : hist.changes) if (!c.sell && c.to > 0) bkAll.add(new Pt(c.t, c.to));
        bkAll.sort((p1, p2) -> Long.compare(p1.t, p2.t));
        for (Pt p : bkAll) {
            if (p.t < t0) bkBefore = p;
            else bk.add(p);
        }
        if (bkBefore != null) bk.add(0, new Pt(t0, bkBefore.v));

        List<Buy> buys = new ArrayList<>();
        for (Buy b : hist.buys) if (b.t >= t0) buys.add(b);
        List<Obs> obs = new ArrayList<>();
        for (Obs o : hist.obs) if (o.t >= t0) obs.add(o);
        List<Sold> sold = new ArrayList<>();
        for (Sold x2 : hist.sold) if (x2.t >= t0) sold.add(x2);

        double sugSell = a.suggestedSell;

        // --- zakres osi Y
        List<Double> vals = new ArrayList<>();
        for (PriceEngine.FloorPoint p : floors) vals.add(p.cheapest);
        for (Pt p : bk) vals.add(p.v);
        for (Buy b : buys) vals.add(b.p);
        if (!Double.isNaN(sugSell)) vals.add(sugSell);
        double med = median(vals);
        for (Obs o : obs) {
            if (Double.isNaN(med) || o.p <= med * 3) vals.add(o.p);
        }
        for (Sold x2 : sold) {
            if (Double.isNaN(med) || x2.p <= med * 3) vals.add(x2.p);
        }
        if (vals.isEmpty()) {
            String msg = "Brak danych w tym zakresie - zrób skan rynku (K)";
            ctx.drawText(tr, msg, x + (w - tr.getWidth(msg)) / 2, y + h / 2 - 4, Ui.MUTED, false);
            return tip;
        }
        double lo = Collections.min(vals), hi = Collections.max(vals);
        if (hi - lo < hi * 0.02) {
            double mid = (hi + lo) / 2;
            lo = mid * 0.97;
            hi = mid * 1.03;
            if (hi <= lo) {
                lo = mid - 1;
                hi = mid + 1;
            }
        }
        double pad = (hi - lo) * 0.08;
        lo = Math.max(0, lo - pad);
        hi = hi + pad;

        // --- obszar rysowania (miejsce na etykiety)
        int labelW = 0;
        double[] ticks = new double[4];
        for (int i = 0; i < 4; i++) {
            ticks[i] = lo + (hi - lo) * i / 3.0;
            labelW = Math.max(labelW, tr.getWidth(PriceFmt.compact(ticks[i])));
        }
        int px1 = x + labelW + 6;
        int px2 = x + w - 4;
        int py1 = y + 5;
        int py2 = y + h - 12;
        if (px2 - px1 < 20 || py2 - py1 < 20) return tip;
        final double flo = lo, fhi = hi;
        final int fpx1 = px1, fpx2 = px2, fpy1 = py1, fpy2 = py2;
        java.util.function.LongToIntFunction X = t -> fpx1 + (int) Math.round((double) (t - t0) / rangeMs * (fpx2 - fpx1));
        java.util.function.DoubleToIntFunction Y = v -> fpy2 - (int) Math.round((v - flo) / (fhi - flo) * (fpy2 - fpy1));

        // siatka + etykiety Y
        for (int i = 0; i < 4; i++) {
            int gy = Y.applyAsInt(ticks[i]);
            ctx.fill(px1, gy, px2, gy + 1, i == 0 ? Ui.BORDER : Ui.GRID);
            String l = PriceFmt.compact(ticks[i]);
            ctx.drawText(tr, l, px1 - 4 - tr.getWidth(l), gy - 4, Ui.MUTED, false);
        }
        // etykiety X
        for (int i = 0; i <= 3; i++) {
            long t = t0 + rangeMs * i / 3;
            String l = Ui.time(t, longRange);
            int lx = X.applyAsInt(t) - tr.getWidth(l) / 2;
            lx = Math.max(px1, Math.min(px2 - tr.getWidth(l), lx));
            ctx.drawText(tr, l, lx, py2 + 3, Ui.MUTED, false);
        }

        ctx.enableScissor(px1, py1 - 2, px2 + 1, py2 + 2);
        long gap = Math.max(3 * PriceEngine.HOUR, rangeMs / 10);

        // cena skupu BK (schodki)
        for (int i = 0; i < bk.size(); i++) {
            Pt p = bk.get(i);
            int xa = Math.max(px1, X.applyAsInt(p.t));
            int xb = i + 1 < bk.size() ? X.applyAsInt(bk.get(i + 1).t) : px2;
            int ya = Y.applyAsInt(p.v);
            ctx.fill(xa, ya, Math.max(xa + 1, xb), ya + 1, Ui.S_BK);
            if (i + 1 < bk.size()) {
                int yb = Y.applyAsInt(bk.get(i + 1).v);
                ctx.fill(xb, Math.min(ya, yb), xb + 1, Math.max(ya, yb) + 1, Ui.S_BK);
            }
        }

        // najtańsza oferta (linia 2 px, przerwy przy długich lukach w danych)
        int n = floors.size();
        int[] xs = new int[n];
        int[] ys = new int[n];
        int seg = 0;
        for (int i = 0; i < n; i++) {
            PriceEngine.FloorPoint p = floors.get(i);
            if (seg > 0 && p.t - floors.get(i - 1).t > gap) {
                polyline(ctx, xs, ys, seg, Ui.S_CHEAPEST);
                seg = 0;
            }
            xs[seg] = X.applyAsInt(Math.max(p.t, t0));
            ys[seg] = Y.applyAsInt(p.cheapest);
            seg++;
        }
        polyline(ctx, xs, ys, seg, Ui.S_CHEAPEST);

        // nowe oferty
        for (Obs o : obs) {
            if (o.p > hi || o.p < lo) continue;
            int ox = X.applyAsInt(o.t), oy = Y.applyAsInt(o.p);
            ctx.fill(ox - 1, oy - 1, ox + 1, oy + 1, Ui.S_OBS);
        }

        // oferty kupione przez innych (krzyżyk)
        for (Sold x2 : sold) {
            if (x2.p > hi || x2.p < lo) continue;
            cross(ctx, X.applyAsInt(x2.t), Y.applyAsInt(x2.p), Ui.S_SOLD);
        }

        // sugerowana sprzedaż
        if (!Double.isNaN(sugSell)) {
            int sy = Y.applyAsInt(sugSell);
            for (int dx = px1; dx < px2; dx += 6) ctx.fill(dx, sy, Math.min(dx + 3, px2), sy + 1, Ui.S_SELL);
        }

        // zakupy (kropka z obwódką w kolorze tła)
        for (Buy b : buys) {
            int bx = X.applyAsInt(b.t), by = Y.applyAsInt(b.p);
            ctx.fill(bx - 3, by - 3, bx + 3, by + 3, Ui.SURFACE_2);
            ctx.fill(bx - 2, by - 2, bx + 2, by + 2, Ui.S_BUYS);
        }
        ctx.disableScissor();

        // --- najechanie myszą: pionowa linia i dymek
        if (mx >= px1 && mx <= px2 && my >= py1 && my <= py2) {
            ctx.fill(mx, py1, mx + 1, py2, 0x60C3C2B7);
            List<String> lines = tip;
            PriceEngine.FloorPoint best = null;
            int bestD = 14;
            for (PriceEngine.FloorPoint p : floors) {
                if (p.t < t0) continue;
                int d = Math.abs(X.applyAsInt(p.t) - mx);
                if (d < bestD) {
                    bestD = d;
                    best = p;
                }
            }
            Buy bestBuy = null;
            int bestBuyD = 6;
            for (Buy b : buys) {
                int d = Math.abs(X.applyAsInt(b.t) - mx) + Math.abs(Y.applyAsInt(b.p) - my);
                if (d < bestBuyD) {
                    bestBuyD = d;
                    bestBuy = b;
                }
            }
            Sold bestSold = null;
            int bestSoldD = 6;
            for (Sold x2 : sold) {
                int d = Math.abs(X.applyAsInt(x2.t) - mx) + Math.abs(Y.applyAsInt(x2.p) - my);
                if (d < bestSoldD) {
                    bestSoldD = d;
                    bestSold = x2;
                }
            }
            if (bestSold != null && (bestBuy == null || bestSoldD < bestBuyD)) {
                lines.add(Ui.dateTime(bestSold.t));
                lines.add("Kupione przez kogoś: " + PriceFmt.full(bestSold.p) + "/szt" + (bestSold.c > 1 ? " × " + bestSold.c : ""));
                lines.add("Wisiała na rynku: " + Math.max(1, Math.round(bestSold.age / 60_000.0)) + " min");
            } else if (bestBuy != null) {
                lines.add(Ui.dateTime(bestBuy.t));
                lines.add("Zakup: " + PriceFmt.full(bestBuy.p) + "/szt" + (bestBuy.c > 1 ? " × " + bestBuy.c : ""));
            } else if (best != null) {
                lines.add(Ui.dateTime(best.t));
                lines.add("Najtańsza: " + PriceFmt.full(best.cheapest)
                        + (best.raw < best.cheapest ? " (anomalia " + PriceFmt.full(best.raw) + ")" : ""));
                lines.add("Śr. 3 najtańszych: " + PriceFmt.full(best.ref3));
                lines.add("Ofert: " + best.n);
                if (best.bk > 0) lines.add("Skup BK: " + PriceFmt.full(best.bk));
            } else {
                long t = t0 + (long) ((double) (mx - px1) / (px2 - px1) * rangeMs);
                double v = flo + (double) (py2 - my) / (py2 - py1) * (fhi - flo);
                lines.add(Ui.dateTime(t) + "  •  " + PriceFmt.full(v));
            }
        }
        return tip;
    }

    private static void cross(DrawContext ctx, int cx, int cy, int color) {
        for (int i = -2; i <= 2; i++) {
            ctx.fill(cx + i, cy + i, cx + i + 1, cy + i + 1, color);
            ctx.fill(cx + i, cy - i, cx + i + 1, cy - i + 1, color);
        }
    }

    private static void polyline(DrawContext ctx, int[] xs, int[] ys, int n, int color) {
        if (n <= 0) return;
        if (n == 1) {
            ctx.fill(xs[0] - 2, ys[0] - 2, xs[0] + 2, ys[0] + 2, color);
            return;
        }
        for (int i = 0; i + 1 < n; i++) {
            int x0 = xs[i], y0 = ys[i], x1 = xs[i + 1], y1 = ys[i + 1];
            if (x1 <= x0) {
                ctx.fill(x0, Math.min(y0, y1), x0 + 2, Math.max(y0, y1) + 2, color);
                continue;
            }
            int prevY = y0;
            for (int cx = x0; cx <= x1; cx++) {
                int cy = y0 + (int) Math.round((double) (y1 - y0) * (cx - x0) / (x1 - x0));
                ctx.fill(cx, Math.min(prevY, cy), cx + 2, Math.max(prevY, cy) + 2, color);
                prevY = cy;
            }
        }
    }

    public static void tooltip(DrawContext ctx, TextRenderer tr, List<String> lines, int mx, int my,
                               int minX, int minY, int maxX, int maxY) {
        if (lines.isEmpty()) return;
        int w = 0;
        for (String l : lines) w = Math.max(w, tr.getWidth(l));
        int h = lines.size() * 10 + 4;
        int tx = mx + 8;
        int ty = my - h - 4;
        if (tx + w + 8 > maxX) tx = mx - w - 16;
        if (ty < minY) ty = my + 10;
        if (ty + h > maxY + 40) ty = maxY + 40 - h;
        tx = Math.max(minX, tx);
        ctx.getMatrices().push();
        ctx.getMatrices().translate(0f, 0f, 300f);
        ctx.fill(tx - 1, ty - 1, tx + w + 7, ty + h + 1, Ui.BORDER);
        ctx.fill(tx, ty, tx + w + 6, ty + h, 0xF0101010);
        int ly = ty + 3;
        for (int i = 0; i < lines.size(); i++) {
            ctx.drawText(tr, lines.get(i), tx + 3, ly, i == 0 ? Ui.MUTED : Ui.TEXT, false);
            ly += 10;
        }
        ctx.getMatrices().pop();
    }

    private static double median(List<Double> v) {
        if (v.isEmpty()) return Double.NaN;
        List<Double> c = new ArrayList<>(v);
        Collections.sort(c);
        int n = c.size();
        return n % 2 == 1 ? c.get(n / 2) : (c.get(n / 2 - 1) + c.get(n / 2)) / 2;
    }
}
