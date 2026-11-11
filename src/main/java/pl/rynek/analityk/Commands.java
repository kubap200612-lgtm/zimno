package pl.rynek.analityk;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;

/** /analityk, /analityk skan, /analityk auto on|off */
final class Commands {
    private Commands() {}

    static void register(CommandDispatcher<FabricClientCommandSource> d) {
        d.register(ClientCommandManager.literal("analityk")
                .executes(c -> {
                    AnalitykClient.openGuiNextTick();
                    return 1;
                })
                .then(ClientCommandManager.literal("skan").executes(c -> {
                    AnalitykClient.SCAN.toggleManual(MinecraftClient.getInstance());
                    return 1;
                }))
                .then(ClientCommandManager.literal("auto")
                        .then(ClientCommandManager.literal("on").executes(c -> setAuto(true)))
                        .then(ClientCommandManager.literal("off").executes(c -> setAuto(false)))));
    }

    private static int setAuto(boolean on) {
        AnalitykClient.STORE.settings.autoBuy = on;
        AnalitykClient.STORE.saveSettings();
        AnalitykClient.chat(on ? "§aAutomatyczne ceny skupu: WŁĄCZONE" : "§eAutomatyczne ceny skupu: WYŁĄCZONE");
        return 1;
    }
}
