package pl.rynek.analityk.core;

/** Wynik analizy jednego przedmiotu. Wszystkie ceny za 1 sztukę; NaN = brak danych. */
public final class Analysis {
    public static final int LEVEL_NONE = -1, LEVEL_OK = 0, LEVEL_WARN = 1, LEVEL_BAD = 2;

    public boolean hasData;
    public boolean stale;
    public long lastScanT;
    public int scansWithData;

    public int listings;
    public int ownListings;
    public boolean complete;

    /** najtańsza cudza oferta po odrzuceniu anomalii */
    public double cheapest = Double.NaN;
    /** najtańsza cudza oferta bez filtrowania */
    public double rawCheapest = Double.NaN;
    /** średnia z 3 najtańszych (po filtrze) */
    public double ref3 = Double.NaN;
    /** wygładzona najtańsza cena (EMA ze skanów) */
    public double ema = Double.NaN;
    /** zmiana najtańszej ceny w 24h (ułamek) */
    public double change24h = Double.NaN;
    /** zmienność: odchylenie std / średnia z 24h (ułamek) */
    public double volatility = Double.NaN;
    /** nowe oferty na godzinę (z obserwacji w trakcie pracy BK) */
    public double newPerHour = Double.NaN;
    /** true = ostatni stan pochodzi z analizy na żywo (nie z pełnego skanu) */
    public boolean live;
    /** ile ofert zniknęło (kupione przez innych) w ostatnich 24h */
    public int sold24h;
    /** zniknięcia ofert na godzinę (24h) */
    public double soldPerHour = Double.NaN;
    /** najwyższa cena, po której oferty schodziły w ciągu 15 min (24h) */
    public double quickSaleMax = Double.NaN;
    /** mediana czasu od wystawienia do zniknięcia oferty (minuty, 24h) */
    public double medianSaleMin = Double.NaN;

    /** średni koszt zakupu (ostatnie 7 dni) */
    public double avgCost = Double.NaN;
    public int costPieces;
    /** true = brak zakupów, koszt przyjęty z ceny skupu */
    public boolean costEstimated;

    public double suggestedMarginPct;
    public String marginLabel = "auto";
    /** marża użyta do wyliczenia ceny skupu (kwota za sztukę) */
    public double marginAbs = Double.NaN;

    /** krok przebijania ceny */
    public double step = 1;
    /** cena "o krok taniej niż najtańsza oferta" */
    public double sellFast = Double.NaN;
    /** najniższa cena sprzedaży bez straty (z podatkiem i min. zyskiem) */
    public double minSell = Double.NaN;
    /** sugerowana cena sprzedaży */
    public double suggestedSell = Double.NaN;
    public boolean belowCost;
    public boolean noCompetition;

    /** sugerowana (maksymalna) cena skupu */
    public double suggestedBuy = Double.NaN;
    /** zysk na sztuce: sprzedaż po sugerowanej cenie (po podatku) minus średni koszt */
    public double profitPerPiece = Double.NaN;
    /** zysk na sztuce przy zakupie dokładnie po sugerowanej cenie skupu */
    public double profitAtBuy = Double.NaN;

    public String verdict = "";
    public int level = LEVEL_NONE;
}
