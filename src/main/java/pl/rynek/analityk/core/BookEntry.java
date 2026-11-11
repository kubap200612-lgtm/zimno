package pl.rynek.analityk.core;

/** Oferta, która (według naszej wiedzy) wciąż wisi na rynku. Cena za sztukę. */
public final class BookEntry {
    /** identyfikator oferty (przedmiot + cena + ilość + sprzedawca) */
    public String id;
    public double p;
    public int c;
    /** kiedy pierwszy raz ją zobaczyliśmy */
    public long first;
    /** kiedy ostatnio ją widzieliśmy */
    public long last;

    public BookEntry() {}

    public BookEntry(String id, double p, int c, long first, long last) {
        this.id = id;
        this.p = p;
        this.c = c;
        this.first = first;
        this.last = last;
    }
}
