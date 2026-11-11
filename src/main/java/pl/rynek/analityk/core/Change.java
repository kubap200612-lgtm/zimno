package pl.rynek.analityk.core;

/** Zmiana ceny wpisanej do BK (za sztukę). */
public final class Change {
    public long t;
    public double from;
    public double to;
    public boolean sell;
    public String why;

    public Change() {}

    public Change(long t, double from, double to, boolean sell, String why) {
        this.t = t;
        this.from = from;
        this.to = to;
        this.sell = sell;
        this.why = why;
    }
}
