package pl.rynek.analityk.market;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import pl.rynek.analityk.bk.BkBridge;
import pl.rynek.analityk.core.TextUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Rozpoznawanie okna rynku, stron i przycisków. */
public final class MarketUi {
    private MarketUi() {}

    private static final Pattern PAGE = Pattern.compile("(\\d+)\\s*/\\s*(\\d+)");

    public static boolean isMarket(Screen s) {
        if (!(s instanceof HandledScreen<?>)) return false;
        String t = TextUtil.norm(s.getTitle().getString());
        if (t.contains("potwierd") || t.contains("confirm") || t.contains("anuluj")) return false;
        String custom = BkBridge.marketTitle();
        if (!custom.isEmpty() && t.contains(custom)) return true;
        return t.contains("rynek") || t.contains("aukcj") || t.contains("auction") || t.contains("market");
    }

    public static ScreenHandler handler(Screen s) {
        return s instanceof HandledScreen<?> ? ((HandledScreen<?>) s).getScreenHandler() : null;
    }

    /** {aktualna, wszystkie} z tytułu "Rynek (2/9)" albo null. */
    public static int[] page(Screen s) {
        if (s == null) return null;
        Matcher m = PAGE.matcher(s.getTitle().getString());
        int[] out = null;
        while (m.find()) {
            try {
                int a = Integer.parseInt(m.group(1));
                int b = Integer.parseInt(m.group(2));
                if (a >= 1 && b >= a) out = new int[]{a, b};
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    /** Sloty skrzynki (bez ekwipunku gracza). */
    public static List<Slot> containerSlots(ScreenHandler h) {
        List<Slot> out = new ArrayList<>();
        if (h == null) return out;
        for (Slot s : h.slots) {
            if (!(s.inventory instanceof PlayerInventory)) out.add(s);
        }
        return out;
    }

    /** Sloty z ofertami: skrzynka bez dolnego rzędu z przyciskami. */
    public static List<Slot> listingSlots(ScreenHandler h) {
        List<Slot> c = containerSlots(h);
        if (c.size() >= 18) return new ArrayList<>(c.subList(0, c.size() - 9));
        return c;
    }

    public static List<Slot> bottomRow(ScreenHandler h) {
        List<Slot> c = containerSlots(h);
        if (c.size() >= 18) return new ArrayList<>(c.subList(c.size() - 9, c.size()));
        return new ArrayList<>();
    }

    /** Przycisk "Następna strona" (zielony barwnik / strzałka). */
    public static Slot findNextButton(ScreenHandler h) {
        Slot fallback = null;
        for (Slot s : bottomRow(h)) {
            ItemStack st = s.getStack();
            if (st.isEmpty()) continue;
            String n = TextUtil.norm(st.getName().getString());
            if (n.contains("nastep") || n.contains("next") || n.contains("dalej") || n.contains("→") || n.contains("»")) {
                return s;
            }
            if (s.id == 51 && fallback == null) {
                String id = Registries.ITEM.getId(st.getItem()).getPath();
                if (id.contains("lime_dye") || id.contains("arrow")) fallback = s;
            }
        }
        return fallback;
    }

    /** Przycisk sortowania (repeater z listą opcji w opisie). */
    public static Slot findSortButton(ScreenHandler h) {
        Slot fallback = null;
        for (Slot s : bottomRow(h)) {
            ItemStack st = s.getStack();
            if (st.isEmpty()) continue;
            String all = TextUtil.norm(st.getName().getString() + " " + loreText(st));
            if (all.contains("sortow") || (all.contains("nizsz") && all.contains("wyzsz"))) return s;
            if (s.id == 53 && fallback == null && Registries.ITEM.getId(st.getItem()).getPath().contains("repeater")) {
                fallback = s;
            }
        }
        return fallback;
    }

    /** Czy w przycisku sortowania wybrana jest opcja "Najniższej ceny" (podświetlona na żółto/złoto). */
    public static boolean sortLowestSelected(ItemStack st) {
        LoreComponent lore = st.get(DataComponentTypes.LORE);
        if (lore == null) return false;
        for (Text line : lore.lines()) {
            String plain = TextUtil.norm(line.getString());
            if (!plain.contains("nizsz")) continue;
            // jak w BK: wybrana opcja jest podświetlona na żółto/złoto (znaczniki "»" bywają przy każdej linii)
            if (highlighted(line)) return true;
        }
        return false;
    }

    /** Czy linia (albo jej fragment z tekstem) jest żółta/złota — tak serwer oznacza wybraną opcję. */
    public static boolean highlighted(Text t) {
        if (isYellow(t.getStyle().getColor())) {
            return true;
        }
        for (Text sib : t.getSiblings()) {
            if (highlighted(sib) && TextUtil.norm(sib.getString()).length() > 0) return true;
        }
        return false;
    }

    private static boolean isYellow(TextColor c) {
        if (c == null) return false;
        int rgb = c.getRgb() & 0xFFFFFF;
        return rgb == 0xFFFF55 || rgb == 0xFFAA00;
    }

    public static String loreText(ItemStack st) {
        LoreComponent lore = st.get(DataComponentTypes.LORE);
        if (lore == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Text line : lore.lines()) sb.append(line.getString()).append('\n');
        return sb.toString();
    }

    /** Podpis zawartości slotów — zmienia się, gdy przyjdzie nowa strona. */
    public static String signature(List<Slot> slots) {
        StringBuilder sb = new StringBuilder();
        for (Slot s : slots) {
            ItemStack st = s.getStack();
            if (st.isEmpty()) continue;
            sb.append(s.id).append(':')
                    .append(Registries.ITEM.getId(st.getItem()).getPath()).append('x').append(st.getCount())
                    .append(':').append(st.getName().getString().hashCode())
                    .append(':').append(loreText(st).hashCode()).append(';');
        }
        return sb.toString();
    }

    public static String stackSignature(ItemStack st) {
        if (st == null || st.isEmpty()) return "";
        return st.getName().getString() + "|" + loreText(st);
    }

    public static void click(MinecraftClient mc, ScreenHandler h, int slotId, int button) {
        if (mc.interactionManager == null || mc.player == null || h == null) return;
        mc.interactionManager.clickSlot(h.syncId, slotId, button, SlotActionType.PICKUP, mc.player);
    }

    /** Czy serwer to Anarchia (tam sortowanie zmienia się prawym przyciskiem). */
    public static boolean isAnarchia(MinecraftClient mc) {
        try {
            if (mc.getCurrentServerEntry() == null) return false;
            String a = mc.getCurrentServerEntry().address.toLowerCase();
            return a.contains("anarchia");
        } catch (Throwable t) {
            return false;
        }
    }
}
