package pl.rynek.analityk.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Porównanie dwóch widoków 1. strony rynku posortowanej "Najnowsze" (najnowsza oferta w pierwszym slocie).
 * Nowe oferty wchodzą na początek i spychają najstarsze z końca strony. Oferta, która zniknęła
 * ze środka strony (a starsze od niej zostały), została kupiona albo zdjęta.
 */
public final class PageDiff {
    public final List<String> added = new ArrayList<>();
    public final List<String> removed = new ArrayList<>();
    public final List<String> pushed = new ArrayList<>();

    private PageDiff() {}

    /**
     * @param prev     poprzedni widok (identyfikatory w kolejności slotów)
     * @param curr     obecny widok
     * @param capacity ile ofert mieści strona (np. 45)
     */
    public static PageDiff diff(List<String> prev, List<String> curr, int capacity) {
        PageDiff d = new PageDiff();
        Set<String> currSet = new HashSet<>(curr);
        Set<String> prevSet = new HashSet<>(prev);
        for (String k : curr) if (!prevSet.contains(k)) d.added.add(k);

        // indeks najstarszej (najdalszej) oferty z poprzedniego widoku, która wciąż jest na stronie
        int lastSurvivor = -1;
        for (int i = prev.size() - 1; i >= 0; i--) {
            if (currSet.contains(prev.get(i))) {
                lastSurvivor = i;
                break;
            }
        }
        List<String> tail = new ArrayList<>();
        for (int i = 0; i < prev.size(); i++) {
            String k = prev.get(i);
            if (currSet.contains(k)) continue;
            if (i < lastSurvivor) d.removed.add(k);  // zniknęła ze środka -> kupiona / zdjęta
            else tail.add(k);                         // koniec strony -> mogła zostać zepchnięta
        }
        // zepchnąć z końca mogło najwyżej tyle ofert, o ile nowe przepełniły stronę
        int maxPush = Math.max(0, prev.size() + d.added.size() - capacity);
        for (int i = tail.size() - 1; i >= 0; i--) {
            if (maxPush > 0) {
                d.pushed.add(0, tail.get(i));
                maxPush--;
            } else {
                d.removed.add(tail.get(i));
            }
        }
        return d;
    }

    /** Dokleja numer wystąpienia do powtarzających się identyfikatorów ("a", "a#2", ...). */
    public static List<String> withOccurrence(List<String> raw) {
        Map<String, Integer> cnt = new HashMap<>();
        List<String> out = new ArrayList<>(raw.size());
        for (String k : raw) {
            int n = cnt.merge(k, 1, Integer::sum);
            out.add(n == 1 ? k : k + "#" + n);
        }
        return out;
    }
}
