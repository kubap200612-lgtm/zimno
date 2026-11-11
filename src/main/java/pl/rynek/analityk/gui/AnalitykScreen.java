package pl.rynek.analityk.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import pl.rynek.analityk.AnalitykClient;
import pl.rynek.analityk.bk.BkBridge;
import pl.rynek.analityk.core.Analysis;
import pl.rynek.analityk.core.Change;
import pl.rynek.analityk.core.ItemHistory;
import pl.rynek.analityk.core.MarginSpec;
import pl.rynek.analityk.core.PriceEngine;
import pl.rynek.analityk.core.PriceFmt;
import pl.rynek.analityk.core.Settings;
import pl.rynek.analityk.core.Snapshot;
import pl.rynek.analityk.data.DataStore;
import pl.rynek.analityk.market.AutoPricer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/** Główne okno: lista przedmiotów BK, wykres, analiza, akcje i ustawienia. */
public final class AnalitykScreen extends Screen {
    private static final long[] RANGES = {PriceEngine.HOUR, 6 * PriceEngine.HOUR, PriceEngine.DAY, 7 * PriceEngine.DAY, 30 * PriceEngine.DAY};
    private static final String[] RANGE_LABELS = {"1h", "6h", "24h", "7d", "30d"};
    private static final int ROW_H = 22;
    private static final int PAD = 6;
    private static final int FIELD_OK = 0xFFE0E0E0;
    private static final int FIELD_BAD = 0xFFFF5555;

    private static String lastKey;
    private static int rangeIdx = 2;
    private static boolean settingsTab;

    private final DataStore store = AnalitykClient.STORE;
    private List<BkBridge.BkItem> items = Collections.emptyList();
    private BkBridge.BkItem selected;
    private double scroll;
    private boolean settingsDirty;

    // układ
    private int lx1, ly1, lx2, ly2, rowsTop;
    private int rx1, ry1, rx2, ry2;
    private int controlsY, verdictY, legendY, chartY, chartH, rangeY, statsY;
    private int marginLabelX;
    private List<String> chartTip = Collections.emptyList();
    private List<String> hoverTip = Collections.emptyList();
    private final List<Object[]> labels = new ArrayList<>(); // {x, y, text, color}

    private String toast = "";
    private long toastUntil;
    private boolean toastOk = true;

    public AnalitykScreen() {
        super(Text.literal("BK Analityk"));
    }

    // ------------------------------------------------------------------ widgety

    @Override
    protected void init() {
        labels.clear();
        items = BkBridge.ok() ? BkBridge.items() : Collections.emptyList();
        selected = null;
        if (lastKey != null) {
            for (BkBridge.BkItem it : items) if (it.key.equals(lastKey)) selected = it;
        }
        if (selected == null && !items.isEmpty()) {
            selected = items.get(0);
            lastKey = selected.key;
        }

        // pasek górny
        int tabX = PAD + textRenderer.getWidth("BK Analityk") + 12;
        addDrawableChild(ButtonWidget.builder(Text.literal(settingsTab ? "Przedmioty" : "▸ Przedmioty"), b -> {
            settingsTab = false;
            clearAndInit();
        }).dimensions(tabX, 5, 78, 16).build());
        addDrawableChild(ButtonWidget.builder(Text.literal(settingsTab ? "▸ Ustawienia" : "Ustawienia"), b -> {
            settingsTab = true;
            clearAndInit();
        }).dimensions(tabX + 82, 5, 78, 16).build());
        boolean scanning = AnalitykClient.SCAN.active();
        addDrawableChild(ButtonWidget.builder(Text.literal(scanning ? "Przerwij skan" : "Skanuj rynek (K)"), b -> {
            close();
            AnalitykClient.SCAN.toggleManual(client);
        }).dimensions(width - PAD - 100, 5, 100, 16).build());

        int top = 26;
        int listW = Math.max(118, Math.min(180, (int) (width * 0.30)));
        lx1 = PAD;
        ly1 = top;
        lx2 = PAD + listW;
        ly2 = height - PAD - 12;
        rowsTop = ly1 + 17;
        rx1 = lx2 + PAD;
        ry1 = top;
        rx2 = width - PAD;
        ry2 = height - PAD;

        if (settingsTab) {
            initSettings();
        } else if (selected != null) {
            initItem();
        }
    }

    private void initItem() {
        ItemHistory h = store.get(selected);
        controlsY = ry1 + 28;
        int x = rx1 + 5;
        int avail = rx2 - rx1 - 10;
        boolean narrow = avail < 300;

        addDrawableChild(ButtonWidget.builder(Text.literal(h.auto ? "Auto: TAK" : "Auto: NIE"), b -> {
            ItemHistory hh = store.get(selected);
            hh.auto = !hh.auto;
            store.markDirty();
            b.setMessage(Text.literal(hh.auto ? "Auto: TAK" : "Auto: NIE"));
        }).dimensions(x, controlsY, 56, 16).build());
        x += 60;

        marginLabelX = x;
        x += textRenderer.getWidth("Marża") + 4;
        TextFieldWidget margin = new TextFieldWidget(textRenderer, x, controlsY, 46, 16, Text.literal("Marża"));
        margin.setMaxLength(10);
        margin.setText(h.margin == null ? "" : h.margin);
        margin.setPlaceholder(Text.literal(store.settings.defaultMargin));
        margin.setChangedListener(s -> {
            boolean ok = s.isBlank() || MarginSpec.parse(s) != null;
            margin.setEditableColor(ok ? FIELD_OK : FIELD_BAD);
            if (ok) {
                store.get(selected).margin = s.trim();
                store.markDirty();
            }
        });
        addDrawableChild(margin);
        x += 50;

        int bw1 = narrow ? 52 : 64;
        int bw2 = narrow ? 64 : 82;
        addDrawableChild(ButtonWidget.builder(Text.literal(narrow ? "Skup→BK" : "Skup → BK"), b -> {
            String r = AutoPricer.applyBuy(store, selected);
            toast(r, !r.startsWith("Brak"));
        }).dimensions(x, controlsY, bw1, 16).build());
        x += bw1 + 4;
        addDrawableChild(ButtonWidget.builder(Text.literal(narrow ? "Sprzed.→BK" : "Sprzedaż → BK"), b -> {
            String r = AutoPricer.applySell(store, selected);
            toast(r, !r.startsWith("Brak"));
        }).dimensions(x, controlsY, bw2, 16).build());

        verdictY = controlsY + 21;
        legendY = verdictY + 12;
        int legendH = ChartRenderer.legendHeight(textRenderer, rx2 - rx1 - 10);
        chartY = legendY + legendH + 2;
        statsY = ry2 - 5 - 60;
        rangeY = statsY - 18;
        chartH = Math.max(30, rangeY - 3 - chartY);

        int bx = rx1 + 5;
        for (int i = 0; i < RANGES.length; i++) {
            final int idx = i;
            String label = i == rangeIdx ? "[" + RANGE_LABELS[i] + "]" : RANGE_LABELS[i];
            addDrawableChild(ButtonWidget.builder(Text.literal(label), b -> {
                rangeIdx = idx;
                clearAndInit();
            }).dimensions(bx, rangeY, 32, 14).build());
            bx += 34;
        }
    }

    private void initSettings() {
        Settings s = store.settings;
        int x0 = PAD + 8;
        int colW = (width - 2 * PAD - 26) / 2;
        int[] cx = {x0, x0 + colW + 10};
        int y0 = ry1 + 20;
        int rowH = 18;
        int tw = Math.min(200, colW);

        String[] tl = {"Analiza na żywo", "Auto ceny skupu", "Auto ceny sprzedaży", "Komunikaty o zmianach",
                "Kalibracja po starcie BK", "Szybki pełny skan", "Pełny skan tylko z BK"};
        BooleanSupplier[] tg = {() -> s.liveAnalysis, () -> s.autoBuy, () -> s.autoSell, () -> s.notifyChanges,
                () -> s.calibrateOnStart, () -> s.fastScan, () -> s.scanOnlyWhenBkRuns};
        List<Consumer<Boolean>> ts = List.of(v -> s.liveAnalysis = v, v -> s.autoBuy = v, v -> s.autoSell = v,
                v -> s.notifyChanges = v, v -> s.calibrateOnStart = v, v -> s.fastScan = v, v -> s.scanOnlyWhenBkRuns = v);
        for (int i = 0; i < tl.length; i++) {
            addToggle(cx[i % 2], y0 + (i / 2) * rowH, tw, tl[i], tg[i], ts.get(i));
        }
        int ny0 = y0 + ((tl.length + 1) / 2) * rowH + 6;
        int fieldW = 46;

        List<Object[]> nums = new ArrayList<>();
        nums.add(new Object[]{"Korekta cen co (min)", String.valueOf(s.adjustIntervalMin), (Function<String, Boolean>) t -> {
            int v = Integer.parseInt(t.trim());
            if (v < 1 || v > 240) return false;
            s.adjustIntervalMin = v;
            return true;
        }});
        nums.add(new Object[]{"Potwierdź podwyżkę (min)", String.valueOf(s.raiseConfirmMin), (Function<String, Boolean>) t -> {
            int v = Integer.parseInt(t.trim());
            if (v < 0 || v > 240) return false;
            s.raiseConfirmMin = v;
            return true;
        }});
        nums.add(new Object[]{"Maks. podwyżka (%)", num(s.maxRaisePct), (Function<String, Boolean>) t -> {
            double v = parse(t);
            if (v < 0 || v > 100) return false;
            s.maxRaisePct = v;
            return true;
        }});
        nums.add(new Object[]{"Maks. obniżka (%)", num(s.maxDropPct), (Function<String, Boolean>) t -> {
            double v = parse(t);
            if (v < 0 || v > 100) return false;
            s.maxDropPct = v;
            return true;
        }});
        nums.add(new Object[]{"Próg zmiany (%)", num(s.hysteresisPct), (Function<String, Boolean>) t -> {
            double v = parse(t);
            if (v < 0 || v > 50) return false;
            s.hysteresisPct = v;
            return true;
        }});
        nums.add(new Object[]{"Domyślna marża", s.defaultMargin, (Function<String, Boolean>) t -> {
            if (MarginSpec.parse(t) == null) return false;
            s.defaultMargin = t.isBlank() ? "auto" : t.trim();
            return true;
        }});
        nums.add(new Object[]{"Podatek rynku (%)", num(s.taxPct), (Function<String, Boolean>) t -> {
            double v = parse(t);
            if (v < 0 || v > 50) return false;
            s.taxPct = v;
            return true;
        }});
        nums.add(new Object[]{"Min. zysk sprzedaży (%)", num(s.minProfitPct), (Function<String, Boolean>) t -> {
            double v = parse(t);
            if (v < 0 || v > 100) return false;
            s.minProfitPct = v;
            return true;
        }});
        nums.add(new Object[]{"Pamiętaj ofertę (min)", String.valueOf(s.bookTtlMin), (Function<String, Boolean>) t -> {
            int v = Integer.parseInt(t.trim());
            if (v < 10 || v > 2880) return false;
            s.bookTtlMin = v;
            return true;
        }});
        nums.add(new Object[]{"Pełny skan co (min, 0=nie)", String.valueOf(s.scanIntervalMin), (Function<String, Boolean>) t -> {
            int v = Integer.parseInt(t.trim());
            if (v < 0 || v > 1440) return false;
            if (v != s.scanIntervalMin && v > 0) AnalitykClient.SCAN.reschedule(v * 60_000L);
            s.scanIntervalMin = v;
            return true;
        }});
        nums.add(new Object[]{"Przerwa stron skanu (ms)", String.valueOf(s.pageDelayMs), (Function<String, Boolean>) t -> {
            int v = Integer.parseInt(t.trim());
            if (v < 100 || v > 5000) return false;
            s.pageDelayMs = v;
            return true;
        }});
        int rows = (nums.size() + 1) / 2;
        for (int i = 0; i < nums.size(); i++) {
            Object[] n = nums.get(i);
            int col = i / rows;            // najpierw lewa kolumna, potem prawa
            int row = i % rows;
            int lx = cx[col];
            int fx = lx + colW - fieldW;
            @SuppressWarnings("unchecked")
            Function<String, Boolean> fn = (Function<String, Boolean>) n[2];
            addNumber(lx, fx, ny0 + row * rowH, (String) n[0], (String) n[1], fn, fieldW);
        }
        statsY = ny0 + rows * rowH + 6; // początek tekstu pomocy
    }

    private void addToggle(int x, int y, int w, String label, BooleanSupplier get, Consumer<Boolean> set) {
        addDrawableChild(ButtonWidget.builder(toggleText(label, get.getAsBoolean()), b -> {
            set.accept(!get.getAsBoolean());
            b.setMessage(toggleText(label, get.getAsBoolean()));
            settingsDirty = true;
            store.invalidateAnalysis();
        }).dimensions(x, y, w, 16).build());
    }

    private static Text toggleText(String label, boolean on) {
        return Text.literal(label + ": " + (on ? "TAK" : "NIE"));
    }

    private void addNumber(int labelX, int fieldX, int y, String label, String value, Function<String, Boolean> apply, int fieldW) {
        labels.add(new Object[]{labelX, y + 4, label, Ui.TEXT_2, fieldX - labelX - 4});
        TextFieldWidget f = new TextFieldWidget(textRenderer, fieldX, y, fieldW, 16, Text.literal(label));
        f.setMaxLength(12);
        f.setText(value);
        f.setChangedListener(t -> {
            boolean ok;
            try {
                ok = apply.apply(t);
            } catch (RuntimeException e) {
                ok = false;
            }
            f.setEditableColor(ok ? FIELD_OK : FIELD_BAD);
            if (ok) {
                settingsDirty = true;
                store.invalidateAnalysis();
            }
        });
        addDrawableChild(f);
    }

    private static double parse(String t) {
        return Double.parseDouble(t.trim().replace(',', '.').replace("%", ""));
    }

    private static String num(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    private void toast(String msg, boolean ok) {
        toast = msg;
        toastOk = ok;
        toastUntil = System.currentTimeMillis() + 5000;
    }

    // ------------------------------------------------------------------ rysowanie

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, width, height, Ui.BG);
        Ui.text(ctx, textRenderer, "BK Analityk", PAD, 9, Ui.TEXT);
        hoverTip = Collections.emptyList();
        chartTip = Collections.emptyList();

        if (!BkBridge.ok()) {
            Ui.panel(ctx, PAD, 26, width - PAD, height - PAD);
            Ui.text(ctx, textRenderer, "Nie znaleziono BK-Rynek 3.0 (albo zmieniła się jego wersja).", PAD + 8, 36, Ui.BAD);
            Ui.textFit(ctx, textRenderer, BkBridge.error(), PAD + 8, 50, width - 2 * PAD - 16, Ui.TEXT_2);
            return;
        }
        if (settingsTab) {
            renderSettings(ctx);
            return;
        }
        renderList(ctx, mouseX, mouseY);
        Ui.panel(ctx, rx1, ry1, rx2, ry2);
        if (selected == null) {
            Ui.text(ctx, textRenderer, "Dodaj przedmioty do listy w BK-Rynek - pojawią się tutaj.", rx1 + 8, ry1 + 10, Ui.TEXT_2);
            return;
        }
        renderItem(ctx, mouseX, mouseY);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        if (!chartTip.isEmpty()) ChartRenderer.tooltip(ctx, textRenderer, chartTip, mouseX, mouseY, 0, 0, width, height);
        else if (!hoverTip.isEmpty()) ChartRenderer.tooltip(ctx, textRenderer, hoverTip, mouseX, mouseY, 0, 0, width, height);
    }

    private void renderList(DrawContext ctx, int mx, int my) {
        Ui.panel(ctx, lx1, ly1, lx2, ly2);
        String profile = BkBridge.activeProfile();
        Ui.textFit(ctx, textRenderer, "Lista BK" + (profile == null ? "" : ": " + profile) + " (" + items.size() + ")",
                lx1 + 5, ly1 + 5, lx2 - lx1 - 10, Ui.MUTED);

        int viewH = ly2 - 1 - rowsTop;
        int contentH = items.size() * ROW_H;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, contentH - viewH)));
        ctx.enableScissor(lx1 + 1, rowsTop, lx2 - 1, ly2 - 1);
        for (int i = 0; i < items.size(); i++) {
            int y = rowsTop + i * ROW_H - (int) scroll;
            if (y + ROW_H < rowsTop || y > ly2) continue;
            BkBridge.BkItem it = items.get(i);
            boolean sel = it == selected;
            boolean hov = Ui.inside(mx, my, lx1 + 1, Math.max(y, rowsTop), lx2 - 1, Math.min(y + ROW_H, ly2 - 1));
            if (sel) ctx.fill(lx1 + 1, y, lx2 - 1, y + ROW_H, Ui.SELECT);
            else if (hov) ctx.fill(lx1 + 1, y, lx2 - 1, y + ROW_H, Ui.HOVER);
            if (sel) ctx.fill(lx1 + 1, y, lx1 + 3, y + ROW_H, Ui.S_CHEAPEST);
            ItemStack icon = store.icon(it);
            if (!icon.isEmpty()) ctx.drawItem(icon, lx1 + 5, y + 3);
            int tx = lx1 + 24;
            int tw = lx2 - tx - 4;
            Ui.textFit(ctx, textRenderer, it.name, tx, y + 3, tw, it.buyEnabled() ? Ui.TEXT : Ui.MUTED);

            Analysis a = store.analysis(it);
            double cur = it.buyPerPiece();
            String line = "skup " + PriceFmt.compact(cur);
            int color = Ui.TEXT_2;
            if (!a.hasData) {
                line += " • brak danych";
                color = Ui.MUTED;
            } else if (!Double.isNaN(a.suggestedBuy) && cur > 0 && Math.abs(a.suggestedBuy - cur) / cur >= 0.01) {
                line += (a.suggestedBuy > cur ? " ▲ " : " ▼ ") + PriceFmt.compact(a.suggestedBuy);
            } else if (!Double.isNaN(a.suggestedBuy) && cur <= 0) {
                line += " → " + PriceFmt.compact(a.suggestedBuy);
            } else {
                line += " ✔";
            }
            if (!store.get(it).auto) line += " • ręcznie";
            if (a.belowCost) {
                Ui.text(ctx, textRenderer, "⚠", tx, y + 12, Ui.WARN);
                Ui.textFit(ctx, textRenderer, line, tx + 9, y + 12, tw - 9, color);
            } else {
                Ui.textFit(ctx, textRenderer, line, tx, y + 12, tw, color);
            }
        }
        ctx.disableScissor();
        if (contentH > viewH && viewH > 0) {
            int barH = Math.max(12, viewH * viewH / contentH);
            int barY = rowsTop + (int) ((viewH - barH) * (scroll / Math.max(1, contentH - viewH)));
            ctx.fill(lx2 - 3, barY, lx2 - 1, barY + barH, Ui.BORDER);
        }

        // status pod listą
        String status;
        int statusColor = Ui.MUTED;
        Settings st = store.settings;
        if (AnalitykClient.SCAN.active()) {
            status = AnalitykClient.SCAN.status();
        } else if (st.liveAnalysis && AnalitykClient.LIVE.watching()) {
            status = "● Na żywo: +" + AnalitykClient.LIVE.sessionNew() + " nowych, "
                    + AnalitykClient.LIVE.sessionSold() + " kupionych przez innych • korekta cen "
                    + Ui.in(AnalitykClient.LIVE.nextAdjust())
                    + (AnalitykClient.LIVE.orderedView() ? "" : " • czekam na sortowanie Najnowsze");
            statusColor = Ui.TEXT_2;
        } else if (st.liveAnalysis) {
            status = "Na żywo: czekam aż BK otworzy rynek • pełny skan: " + Ui.ago(lastScanTime(false)) + " (K)";
        } else {
            status = "Analiza na żywo wyłączona • ostatni skan: " + Ui.ago(lastScanTime(true)) + " (K)";
        }
        Ui.textFit(ctx, textRenderer, status, lx1 + 2, height - PAD - 9, width - 2 * PAD - 4, statusColor);
    }

    /** Czas ostatniego pełnego skanu (albo dowolnej migawki, gdy anyKind). */
    private long lastScanTime(boolean anyKind) {
        long last = AnalitykClient.SCAN.lastScanEnd();
        for (BkBridge.BkItem it : items) {
            ItemHistory h = store.byKey(it.key);
            if (h == null) continue;
            for (int i = h.scans.size() - 1; i >= 0; i--) {
                Snapshot s = h.scans.get(i);
                if (anyKind || !s.live) {
                    last = Math.max(last, s.t);
                    break;
                }
            }
        }
        return last;
    }

    private void renderItem(DrawContext ctx, int mx, int my) {
        BkBridge.BkItem it = selected;
        ItemHistory h = store.get(it);
        Analysis a = store.analysis(it);
        Settings s = store.settings;

        // nagłówek
        ItemStack icon = store.icon(it);
        if (!icon.isEmpty()) ctx.drawItem(icon, rx1 + 5, ry1 + 5);
        Ui.textFit(ctx, textRenderer, it.name, rx1 + 25, ry1 + 4, rx2 - rx1 - 30, Ui.TEXT);
        String sub = "BK: skup " + (it.buyPerPiece() > 0 ? PriceFmt.full(it.buyPerPiece()) : "-")
                + (it.buyMultiplier() > 1 ? " (×" + it.buyMultiplier() + " szt)" : "")
                + " • sprzedaż " + (it.sellPerPiece() > 0 ? PriceFmt.full(it.sellPerPiece()) : "-")
                + (it.sellEnabled() ? "" : " (wył.)")
                + (it.buyEnabled() ? "" : " • kupno wył.");
        Ui.textFit(ctx, textRenderer, sub, rx1 + 25, ry1 + 15, rx2 - rx1 - 30, Ui.MUTED);
        if (Ui.inside(mx, my, rx1 + 25, ry1 + 13, rx2 - 5, ry1 + 25) && !h.changes.isEmpty()) {
            List<String> tip = new ArrayList<>();
            tip.add("Ostatnie zmiany cen w BK");
            for (int i = h.changes.size() - 1; i >= 0 && tip.size() < 9; i--) {
                Change c = h.changes.get(i);
                tip.add(Ui.dateTime(c.t) + "  " + (c.sell ? "sprzedaż " : "skup ") + PriceFmt.full(c.from) + " → "
                        + PriceFmt.full(c.to) + (c.why == null ? "" : "  (" + c.why + ")"));
            }
            hoverTip = tip;
        }

        Ui.text(ctx, textRenderer, "Marża", marginLabelX, controlsY + 4, Ui.TEXT_2);

        // werdykt (ikona + tekst, nigdy sam kolor)
        long now = System.currentTimeMillis();
        if (now < toastUntil) {
            Ui.textFit(ctx, textRenderer, (toastOk ? "✔ " : "✖ ") + toast, rx1 + 5, verdictY, rx2 - rx1 - 10, toastOk ? Ui.GOOD : Ui.BAD);
        } else {
            String icon2;
            int col;
            switch (a.level) {
                case Analysis.LEVEL_OK: icon2 = "✔ "; col = Ui.GOOD; break;
                case Analysis.LEVEL_WARN: icon2 = "⚠ "; col = Ui.WARN; break;
                case Analysis.LEVEL_BAD: icon2 = "✖ "; col = Ui.BAD; break;
                default: icon2 = "• "; col = Ui.MUTED;
            }
            Ui.text(ctx, textRenderer, icon2, rx1 + 5, verdictY, col);
            Ui.textFit(ctx, textRenderer, a.verdict, rx1 + 5 + textRenderer.getWidth(icon2), verdictY,
                    rx2 - rx1 - 12 - textRenderer.getWidth(icon2), Ui.TEXT);
        }

        // legenda + wykres
        ChartRenderer.legend(ctx, textRenderer, rx1 + 5, legendY, rx2 - rx1 - 10);
        chartTip = ChartRenderer.draw(ctx, textRenderer, rx1 + 5, chartY, rx2 - rx1 - 10, chartH, h, a,
                RANGES[rangeIdx], mx, my);

        // statystyki: lewa kolumna = rynek, prawa = Twoje ceny
        int colW = (rx2 - rx1 - 15) / 2;
        int c1 = rx1 + 5;
        int c2 = c1 + colW + 5;
        int y = statsY;
        String cheapest = Double.isNaN(a.cheapest) ? "-" : PriceFmt.full(a.cheapest);
        if (!Double.isNaN(a.rawCheapest) && a.rawCheapest < a.cheapest) {
            cheapest += " (anomalia " + PriceFmt.full(a.rawCheapest) + ")";
        }
        stat(ctx, c1, y, colW, "Najtańsza", cheapest);
        stat(ctx, c2, y, colW, "Sug. skup", Double.isNaN(a.suggestedBuy) ? "-"
                : PriceFmt.full(a.suggestedBuy) + "  (marża " + a.marginLabel + ")");
        y += 10;
        stat(ctx, c1, y, colW, "Śr. 3 najt.", Double.isNaN(a.ref3) ? "-" : PriceFmt.full(a.ref3));
        stat(ctx, c2, y, colW, "Sug. sprzedaż", Double.isNaN(a.suggestedSell) ? "-" : PriceFmt.full(a.suggestedSell)
                + (it.sellCount() > 1 ? "  (oferta " + PriceFmt.full(a.suggestedSell * it.sellCount()) + ")" : ""));
        y += 10;
        String offers;
        if (!a.hasData) offers = "-";
        else if (a.live) offers = a.listings + " znanych (na żywo)";
        else offers = (a.complete ? String.valueOf(a.listings) : "≥ " + a.listings + " (szybki skan)");
        if (a.hasData && a.ownListings > 0) offers += " + " + a.ownListings + " Twoich";
        stat(ctx, c1, y, colW, "Ofert", offers);
        stat(ctx, c2, y, colW, "Bez straty od", Double.isNaN(a.minSell) ? "-" : PriceFmt.full(a.minSell));
        y += 10;
        stat(ctx, c1, y, colW, "Zmiana 24h", PriceFmt.pct(a.change24h, true) + "   zmienn. " + PriceFmt.pct(a.volatility, false));
        stat(ctx, c2, y, colW, "Śr. koszt", Double.isNaN(a.avgCost) ? "-"
                : PriceFmt.full(a.avgCost) + (a.costEstimated ? " (szac.)" : " (" + a.costPieces + " szt)"));
        y += 10;
        stat(ctx, c1, y, colW, "Nowe/h", rate(a.newPerHour) + "   kupowane/h " + rate(a.soldPerHour));
        String profit = Double.isNaN(a.profitPerPiece) ? "-"
                : (a.profitPerPiece >= 0 ? "+" : "") + PriceFmt.full(a.profitPerPiece) + " (po " + PriceFmt.pct(s.tax(), false) + " podatku)";
        stat(ctx, c2, y, colW, "Zysk/szt", profit);
        y += 10;
        stat(ctx, c1, y, colW, "Schodzi szybko do", Double.isNaN(a.quickSaleMax) ? "-"
                : PriceFmt.full(a.quickSaleMax) + (Double.isNaN(a.medianSaleMin) ? "" : "  (mediana " + Math.round(a.medianSaleMin) + " min)"));
        stat(ctx, c2, y, colW, "Dane", a.lastScanT <= 0 ? "brak" : (a.live ? "na żywo, " : "pełny skan, ") + Ui.ago(a.lastScanT));
    }

    private static String rate(double v) {
        return Double.isNaN(v) ? "-" : String.format(java.util.Locale.ROOT, "%.1f", v).replace('.', ',');
    }

    private void stat(DrawContext ctx, int x, int y, int w, String label, String value) {
        int lw = textRenderer.getWidth(label + ": ");
        Ui.text(ctx, textRenderer, label + ":", x, y, Ui.MUTED);
        Ui.textFit(ctx, textRenderer, value, x + lw, y, w - lw, Ui.TEXT);
    }

    private void renderSettings(DrawContext ctx) {
        Ui.panel(ctx, PAD, ry1, width - PAD, ry2);
        Ui.text(ctx, textRenderer, "Przełączniki", PAD + 8, ry1 + 7, Ui.MUTED);
        for (Object[] l : labels) {
            Ui.textFit(ctx, textRenderer, (String) l[2], (Integer) l[0], (Integer) l[1], (Integer) l[4], (Integer) l[3]);
        }
        int y = statsY;
        int w = width - 2 * PAD - 16;
        String[] help = {
                "Cena skupu = (najtańsza oferta - krok) × (1 - podatek) - marża",
                "Cena sprzedaży = najtańsza oferta - krok, ale nie mniej niż koszt × (1 + min. zysk) / (1 - podatek)",
                "Marża: auto (wg zmienności i liczby ofert), procent np. 10% albo kwota za sztukę np. 250",
                "Na żywo: mod czyta 1. stronę rynku (Najnowsze), którą przegląda BK - bez wstrzymywania BK.",
                "Podwyżki wchodzą stopniowo i po potwierdzeniu; obniżki od razu, gdy cena przestaje się opłacać.",
                "K - pełny skan rynku z krótką pauzą BK (kalibruje listę wiszących ofert). J - to okno."
        };
        for (String line : help) {
            if (y > ry2 - 12) break;
            Ui.textFit(ctx, textRenderer, line, PAD + 8, y, w, Ui.TEXT_2);
            y += 11;
        }
    }

    // ------------------------------------------------------------------ wejście

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (!settingsTab && Ui.inside(mx, my, lx1 + 1, rowsTop, lx2 - 1, ly2 - 1)) {
            int idx = (int) ((my - rowsTop + scroll) / ROW_H);
            if (idx >= 0 && idx < items.size()) {
                selected = items.get(idx);
                lastKey = selected.key;
                setFocused(null);
                clearAndInit();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontalAmount, double verticalAmount) {
        if (!settingsTab && Ui.inside(mx, my, lx1, ly1, lx2, ly2)) {
            scroll -= verticalAmount * ROW_H;
            return true;
        }
        return super.mouseScrolled(mx, my, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        boolean typing = getFocused() instanceof TextFieldWidget;
        if (!typing && AnalitykClient.OPEN_KEY.matchesKey(keyCode, scanCode)) {
            close();
            return true;
        }
        if (!typing && !settingsTab && !items.isEmpty() && selected != null) {
            int idx = items.indexOf(selected);
            if (keyCode == 264 && idx + 1 < items.size()) { // strzałka w dół
                selected = items.get(idx + 1);
                lastKey = selected.key;
                clearAndInit();
                return true;
            }
            if (keyCode == 265 && idx > 0) { // strzałka w górę
                selected = items.get(idx - 1);
                lastKey = selected.key;
                clearAndInit();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void removed() {
        if (settingsDirty) {
            store.saveSettings();
            settingsDirty = false;
        }
        store.markDirty();
        super.removed();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
