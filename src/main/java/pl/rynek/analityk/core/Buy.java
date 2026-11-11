package pl.rynek.analityk.core;

/** Zakup wykonany przez BK-Rynek. Cena za sztukę. */
public final class Buy {
    public long t;
    public double p;
    public int c;

    public Buy() {}

    public Buy(long t, double p, int c) {
        this.t = t;
        this.p = p;
        this.c = c;
    }
}
