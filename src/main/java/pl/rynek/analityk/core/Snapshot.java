package pl.rynek.analityk.core;

/** Wynik jednego skanu rynku dla jednego przedmiotu. Ceny zawsze za 1 sztukę. */
public final class Snapshot {
    /** czas skanu (ms) */
    public long t;
    /** ile cudzych ofert znaleziono */
    public int n;
    /** ile Twoich ofert (pominiętych w analizie) */
    public int own;
    /** czy przeskanowano cały rynek (false = szybki skan zatrzymany wcześniej) */
    public boolean complete;
    /** najtańsze cudze ceny za sztukę, rosnąco */
    public double[] low = new double[0];
    /** cena skupu w BK (za sztukę) w chwili skanu, 0 = brak */
    public double bk;
    /** true = stan "na żywo" z obserwacji pracy BK, false = pełny skan rynku */
    public boolean live;

    public Snapshot() {}

    public Snapshot(long t, int n, int own, boolean complete, double[] low, double bk) {
        this.t = t;
        this.n = n;
        this.own = own;
        this.complete = complete;
        this.low = low == null ? new double[0] : low;
        this.bk = bk;
    }

    public boolean hasListings() {
        return low != null && low.length > 0;
    }
}
