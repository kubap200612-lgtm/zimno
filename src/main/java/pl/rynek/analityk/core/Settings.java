package pl.rynek.analityk.core;

/** Ustawienia globalne (zapisywane do config/bkanalityk/settings.json). */
public final class Settings {
    /** podatek od sprzedaży na rynku, % */
    public double taxPct = 3.0;
    /** domyślna marża: "auto", "10%" albo kwota "250" (za sztukę) */
    public String defaultMargin = "auto";
    /** sam podnosi / obniża ceny skupu w BK */
    public boolean autoBuy = true;
    /** sam ustawia ceny sprzedaży w BK (tylko przedmioty z włączoną sprzedażą) */
    public boolean autoSell = false;
    /** analiza na żywo z tego, co BK ogląda podczas skanowania (bez wstrzymywania BK) */
    public boolean liveAnalysis = true;
    /** co ile minut korygować ceny w BK na podstawie analizy na żywo */
    public int adjustIntervalMin = 5;
    /** ile minut pamiętać ofertę, która zeszła z 1. strony rynku */
    public int bookTtlMin = 360;
    /** ile minut wyższa cena musi się utrzymać, zanim podniesiemy skup */
    public int raiseConfirmMin = 10;
    /** jednorazowy pełny skan rynku po uruchomieniu BK (kalibracja) */
    public boolean calibrateOnStart = false;
    /** komunikaty na czacie o zmianach cen */
    public boolean notifyChanges = true;
    /** co ile minut robić PEŁNY skan rynku z pauzą BK (0 = tylko ręcznie klawiszem K) */
    public int scanIntervalMin = 0;
    /** skanuj automatycznie tylko gdy BK-Rynek pracuje */
    public boolean scanOnlyWhenBkRuns = true;
    /** maksymalna podwyżka ceny skupu w jednym kroku, % */
    public double maxRaisePct = 5;
    /** maksymalna obniżka ceny skupu w jednym kroku, % */
    public double maxDropPct = 25;
    /** zmiany mniejsze niż ten próg są pomijane, % */
    public double hysteresisPct = 2;
    /** minimalny zysk przy sugerowanej sprzedaży, % kosztu */
    public double minProfitPct = 1;
    /** sortuj rynek po najniższej cenie i kończ skan, gdy wiadomo już wszystko */
    public boolean fastScan = true;
    /** przerwa po załadowaniu strony, ms */
    public int pageDelayMs = 300;
    /** limit stron (0 = bez limitu) */
    public int maxPages = 0;
    /** ile najtańszych ofert zapamiętać na skan */
    public int keepLowest = 8;
    /** po ilu minutach dane uznać za nieaktualne */
    public int staleMinutes = 90;

    public double tax() {
        return Math.max(0, Math.min(50, taxPct)) / 100.0;
    }

    public void fix() {
        if (defaultMargin == null || defaultMargin.isBlank()) defaultMargin = "auto";
        if (scanIntervalMin < 0) scanIntervalMin = 0;
        if (adjustIntervalMin < 1) adjustIntervalMin = 1;
        if (adjustIntervalMin > 240) adjustIntervalMin = 240;
        if (bookTtlMin < 10) bookTtlMin = 10;
        if (bookTtlMin > 2880) bookTtlMin = 2880;
        if (raiseConfirmMin < 0) raiseConfirmMin = 0;
        if (raiseConfirmMin > 240) raiseConfirmMin = 240;
        if (pageDelayMs < 100) pageDelayMs = 100;
        if (pageDelayMs > 5000) pageDelayMs = 5000;
        if (keepLowest < 3) keepLowest = 3;
        if (keepLowest > 20) keepLowest = 20;
        if (staleMinutes < 10) staleMinutes = 10;
        maxRaisePct = clamp(maxRaisePct, 0, 100);
        maxDropPct = clamp(maxDropPct, 0, 100);
        hysteresisPct = clamp(hysteresisPct, 0, 50);
        minProfitPct = clamp(minProfitPct, 0, 100);
        taxPct = clamp(taxPct, 0, 50);
    }

    private static double clamp(double v, double lo, double hi) {
        if (Double.isNaN(v)) return lo;
        return Math.max(lo, Math.min(hi, v));
    }
}
