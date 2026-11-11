package pl.rynek.analityk;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.rynek.analityk.bk.BkBridge;
import pl.rynek.analityk.data.DataStore;
import pl.rynek.analityk.gui.AnalitykScreen;
import pl.rynek.analityk.gui.StatusOverlay;
import pl.rynek.analityk.market.LiveTracker;
import pl.rynek.analityk.market.PurchaseTracker;
import pl.rynek.analityk.market.ScanController;

public final class AnalitykClient implements ClientModInitializer {
    public static final String MOD_ID = "bkanalityk";
    public static final Logger LOG = LoggerFactory.getLogger("BK Analityk");

    public static DataStore STORE;
    public static ScanController SCAN;
    public static LiveTracker LIVE;
    private static PurchaseTracker BUYS;
    public static KeyBinding OPEN_KEY;
    public static KeyBinding SCAN_KEY;
    private static boolean openGui;

    @Override
    public void onInitializeClient() {
        STORE = new DataStore(FabricLoader.getInstance().getConfigDir().resolve(MOD_ID));
        STORE.load();
        SCAN = new ScanController(STORE);
        LIVE = new LiveTracker(STORE);
        BUYS = new PurchaseTracker(STORE);

        OPEN_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.bkanalityk.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J, "category.bkanalityk"));
        SCAN_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.bkanalityk.scan", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, "category.bkanalityk"));

        ClientTickEvents.END_CLIENT_TICK.register(AnalitykClient::tick);
        HudRenderCallback.EVENT.register((ctx, tickCounter) -> StatusOverlay.renderHud(ctx));
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof HandledScreen<?>) {
                ScreenEvents.afterRender(screen).register((scr, ctx, mx, my, delta) -> StatusOverlay.renderScreen(scr, ctx));
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            SCAN.onDisconnect();
            STORE.saveData();
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> STORE.saveNowBlocking());
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> Commands.register(dispatcher));
        LOG.info("[BK Analityk] Załadowano");
    }

    private static void tick(MinecraftClient mc) {
        while (OPEN_KEY.wasPressed()) {
            openGui = true;
        }
        while (SCAN_KEY.wasPressed()) {
            SCAN.toggleManual(mc);
        }
        if (openGui) {
            openGui = false;
            if (SCAN.active()) {
                chat("§eTrwa skan rynku - okno otworzysz po jego zakończeniu (K przerywa skan).");
            } else if (mc.player != null) {
                BkBridge.invalidate();
                mc.setScreen(new AnalitykScreen());
            }
        }
        if (mc.player != null && BkBridge.ok()) {
            try {
                BUYS.tick();
                LIVE.tick(mc, SCAN.active());
            } catch (RuntimeException e) {
                LOG.warn("[BK Analityk] Błąd obserwacji: {}", e.toString());
            }
        }
        try {
            SCAN.tick(mc);
        } catch (RuntimeException e) {
            LOG.error("[BK Analityk] Błąd skanu", e);
            SCAN.abort(mc, "Skan przerwany przez błąd: " + e);
        }
        STORE.tick(System.currentTimeMillis());
    }

    static void openGuiNextTick() {
        openGui = true;
    }

    public static void chat(String msg) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.inGameHud == null) return;
        mc.inGameHud.getChatHud().addMessage(Text.literal("§6[Analityk] §r" + msg));
    }
}
