package pl.rynek.analityk.core;

import java.util.ArrayList;
import java.util.List;

/** Cała historia jednego przedmiotu z listy BK-Rynek. */
public final class ItemHistory {
    public String key;
    public String name;
    public String material;
    /** maksymalny rozmiar stacka (0 = nieznany) */
    public int maxStack;

    /** marża tego przedmiotu: "" = domyślna, "auto", "10%", "250" */
    public String margin = "";
    /** czy mod może sam zmieniać ceny tego przedmiotu */
    public boolean auto = true;

    public List<Snapshot> scans = new ArrayList<>();
    public List<Obs> obs = new ArrayList<>();
    public List<Buy> buys = new ArrayList<>();
    public List<Change> changes = new ArrayList<>();
    /** oferty, które zniknęły z rynku (kupione przez innych) */
    public List<Sold> sold = new ArrayList<>();
    /** oferty aktualnie wiszące na rynku (stan "na żywo") */
    public List<BookEntry> book = new ArrayList<>();

    public ItemHistory() {}

    public ItemHistory(String key) {
        this.key = key;
    }

    /** Uzupełnia null-e po wczytaniu z pliku. */
    public void fix() {
        if (margin == null) margin = "";
        if (scans == null) scans = new ArrayList<>();
        if (obs == null) obs = new ArrayList<>();
        if (buys == null) buys = new ArrayList<>();
        if (changes == null) changes = new ArrayList<>();
        if (sold == null) sold = new ArrayList<>();
        if (book == null) book = new ArrayList<>();
        sold.removeIf(x -> x == null);
        book.removeIf(x -> x == null || x.id == null);
        scans.removeIf(s -> s == null);
        obs.removeIf(o -> o == null);
        buys.removeIf(b -> b == null);
        changes.removeIf(c -> c == null);
        for (Snapshot s : scans) if (s.low == null) s.low = new double[0];
    }

    public void trim() {
        cut(scans, 3000);
        cut(obs, 1500);
        cut(buys, 1000);
        cut(changes, 300);
        cut(sold, 1500);
        if (book.size() > 200) {
            book.sort((a, b) -> Double.compare(a.p, b.p));
            book.subList(200, book.size()).clear();
        }
    }

    private static <T> void cut(List<T> list, int max) {
        int extra = list.size() - max;
        if (extra > 0) list.subList(0, extra).clear();
    }

    public Snapshot lastScan() {
        return scans.isEmpty() ? null : scans.get(scans.size() - 1);
    }
}
