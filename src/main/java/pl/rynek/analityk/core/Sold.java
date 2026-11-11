package pl.rynek.analityk.core;

/** Oferta, która zniknęła z rynku (kupiona przez kogoś innego albo zdjęta). Cena za sztukę. */
public final class Sold {
    public long t;
    public double p;
    public int c;
    /** ile czasu oferta wisiała na rynku (ms) */
    public long age;

    public Sold() {}

    public Sold(long t, double p, int c, long age) {
        this.t = t;
        this.p = p;
        this.c = c;
        this.age = age;
    }
}
