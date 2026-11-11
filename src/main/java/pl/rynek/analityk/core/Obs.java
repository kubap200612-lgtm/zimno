package pl.rynek.analityk.core;

/** Oferta zauważona "w locie" na rynku (podczas pracy BK). Cena za sztukę. */
public final class Obs {
    public long t;
    public double p;
    public int c;

    public Obs() {}

    public Obs(long t, double p, int c) {
        this.t = t;
        this.p = p;
        this.c = c;
    }
}
