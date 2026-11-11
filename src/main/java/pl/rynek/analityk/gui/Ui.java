package pl.rynek.analityk.gui;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Kolory (ciemny motyw, paleta sprawdzona pod daltonizm) i drobne helpery rysowania. */
public final class Ui {
    private Ui() {}

    // powierzchnie i tekst
    public static final int BG = 0xE0101010;
    public static final int SURFACE = 0xFF1A1A19;
    public static final int SURFACE_2 = 0xFF222220;
    public static final int BORDER = 0xFF383835;
    public static final int GRID = 0x80383835;
    public static final int TEXT = 0xFFF0EFEC;
    public static final int TEXT_2 = 0xFFC3C2B7;
    public static final int MUTED = 0xFF898781;
    public static final int SELECT = 0xFF2B2B29;
    public static final int HOVER = 0xFF242422;

    // serie wykresu
    public static final int S_CHEAPEST = 0xFF3987E5; // niebieski
    public static final int S_BK = 0xFFD95926;       // pomarańczowy
    public static final int S_BUYS = 0xFF199E70;     // morski
    public static final int S_SELL = 0xFFC98500;     // żółty
    public static final int S_OBS = 0xFF9085E9;      // fiolet
    public static final int S_SOLD = 0xFFD55181;     // magenta (krzyżyk — inny kształt niż zakupy)

    // statusy (zawsze z ikoną i tekstem)
    public static final int GOOD = 0xFF0CA30C;
    public static final int WARN = 0xFFFAB219;
    public static final int BAD = 0xFFD03B3B;

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DM = DateTimeFormatter.ofPattern("dd.MM");
    private static final DateTimeFormatter DMHM = DateTimeFormatter.ofPattern("dd.MM HH:mm");

    public static void panel(DrawContext ctx, int x1, int y1, int x2, int y2) {
        ctx.fill(x1, y1, x2, y2, SURFACE);
        ctx.fill(x1, y1, x2, y1 + 1, BORDER);
        ctx.fill(x1, y2 - 1, x2, y2, BORDER);
        ctx.fill(x1, y1, x1 + 1, y2, BORDER);
        ctx.fill(x2 - 1, y1, x2, y2, BORDER);
    }

    public static void text(DrawContext ctx, TextRenderer tr, String s, int x, int y, int color) {
        ctx.drawText(tr, s, x, y, color, false);
    }

    /** Tekst przycięty do szerokości (z wielokropkiem). */
    public static void textFit(DrawContext ctx, TextRenderer tr, String s, int x, int y, int maxW, int color) {
        ctx.drawText(tr, fit(tr, s, maxW), x, y, color, false);
    }

    public static String fit(TextRenderer tr, String s, int maxW) {
        if (maxW <= 0) return "";
        if (tr.getWidth(s) <= maxW) return s;
        String cut = tr.trimToWidth(s, Math.max(0, maxW - tr.getWidth("…")));
        return cut + "…";
    }

    public static void textRight(DrawContext ctx, TextRenderer tr, String s, int right, int y, int color) {
        ctx.drawText(tr, s, right - tr.getWidth(s), y, color, false);
    }

    public static String time(long t, boolean longRange) {
        LocalDateTime d = LocalDateTime.ofInstant(Instant.ofEpochMilli(t), ZoneId.systemDefault());
        return (longRange ? DM : HM).format(d);
    }

    public static String dateTime(long t) {
        return DMHM.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(t), ZoneId.systemDefault()));
    }

    /** "3 min temu", "2 h temu" */
    public static String ago(long t) {
        if (t <= 0) return "nigdy";
        long s = (System.currentTimeMillis() - t) / 1000;
        if (s < 60) return "przed chwilą";
        if (s < 3600) return (s / 60) + " min temu";
        if (s < 86400) return (s / 3600) + " h temu";
        return (s / 86400) + " d temu";
    }

    /** "za 12 min" */
    public static String in(long t) {
        long s = (t - System.currentTimeMillis()) / 1000;
        if (s <= 0) return "zaraz";
        if (s < 60) return "za " + s + " s";
        return "za " + ((s + 59) / 60) + " min";
    }

    public static boolean inside(double mx, double my, int x1, int y1, int x2, int y2) {
        return mx >= x1 && mx < x2 && my >= y1 && my < y2;
    }
}
