package pl.rynek.analityk.market;

import net.minecraft.item.ItemStack;
import pl.rynek.analityk.bk.BkBridge;
import pl.rynek.analityk.core.Buy;
import pl.rynek.analityk.core.TextUtil;
import pl.rynek.analityk.data.DataStore;

import java.util.ArrayList;
import java.util.List;

/** Śledzi historię zakupów BK-Rynek i zapisuje każdy zakup (cena za sztukę) do historii przedmiotu. */
public final class PurchaseTracker {
    private final DataStore store;
    private Object lastTop;
    private boolean primed;

    public PurchaseTracker(DataStore store) {
        this.store = store;
    }

    public void tick() {
        if (!BkBridge.ok()) return;
        List<Object> h = BkBridge.purchaseHistory();
        if (!primed) {
            lastTop = h.isEmpty() ? null : h.get(0);
            primed = true;
            return;
        }
        if (h.isEmpty()) {
            lastTop = null;
            return;
        }
        if (h.get(0) == lastTop) return;
        int idx = -1;
        for (int i = 0; i < h.size(); i++) {
            if (h.get(i) == lastTop) {
                idx = i;
                break;
            }
        }
        int fresh = idx < 0 ? h.size() : idx;
        for (int i = fresh - 1; i >= 0; i--) record(h.get(i));
        lastTop = h.get(0);
    }

    private void record(Object e) {
        double total = BkBridge.hePrice(e);
        int count = Math.max(1, BkBridge.heCount(e));
        if (total <= 0) return;
        double unit = total / count;
        ItemStack st = BkBridge.heStack(e);
        BkBridge.BkItem it = pick(st, unit, total, BkBridge.heName(e));
        if (it == null) return;
        if (!st.isEmpty()) store.noteStack(it.key, st);
        store.addBuy(it, new Buy(System.currentTimeMillis(), unit, count));
    }

    /** Wybiera przedmiot tak jak BK: pasujący, z włączonym kupnem, mieszczący się w cenie, najniższy limit. */
    private BkBridge.BkItem pick(ItemStack st, double unit, double total, String name) {
        List<BkBridge.BkItem> cands = st.isEmpty() ? new ArrayList<>() : new ArrayList<>(BkBridge.match(st));
        if (cands.isEmpty()) {
            String n = TextUtil.norm(name);
            for (BkBridge.BkItem it : BkBridge.items()) {
                if (TextUtil.norm(it.baseName).equals(n) || TextUtil.norm(it.name).equals(n)) cands.add(it);
            }
            if (cands.size() != 1) return null;
        }
        BkBridge.BkItem best = null;
        for (BkBridge.BkItem it : cands) {
            if (!it.buyEnabled()) continue;
            double limit = it.rawMaxPrice();
            double compared = it.buyMultiplier() == 1 ? unit : total;
            if (limit > 0 && compared > limit + 0.01) continue;
            if (best == null || limit < best.rawMaxPrice()) best = it;
        }
        return best != null ? best : cands.get(0);
    }
}
