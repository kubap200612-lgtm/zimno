package pl.rynek.analityk.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import pl.rynek.analityk.AnalitykClient;

/** Pasek stanu skanu (nad oknem rynku albo na HUD). */
public final class StatusOverlay {
    private StatusOverlay() {}

    public static void renderHud(DrawContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.currentScreen != null || mc.options.hudHidden) return;
        draw(ctx, mc.textRenderer, ctx.getScaledWindowWidth(), 4);
    }

    public static void renderScreen(Screen screen, DrawContext ctx) {
        draw(ctx, MinecraftClient.getInstance().textRenderer, screen.width, 3);
    }

    private static void draw(DrawContext ctx, TextRenderer tr, int screenW, int y) {
        if (AnalitykClient.SCAN == null || !AnalitykClient.SCAN.active()) return;
        String s = "⌛ " + AnalitykClient.SCAN.status();
        int w = tr.getWidth(s) + 10;
        int x = (screenW - w) / 2;
        ctx.getMatrices().push();
        ctx.getMatrices().translate(0f, 0f, 400f);
        ctx.fill(x, y, x + w, y + 13, 0xE0101010);
        ctx.fill(x, y + 12, x + w, y + 13, Ui.S_CHEAPEST);
        ctx.drawText(tr, s, x + 5, y + 2, Ui.TEXT, false);
        ctx.getMatrices().pop();
    }
}
