package dev.autobuild;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.network.chat.Component;

public class AutoBuildClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(AutoBuilder::onTick);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommandManager.literal("autobuild")
                .executes(c -> setEnabled(c.getSource(), !AutoBuilder.enabled))
                .then(ClientCommandManager.literal("on")
                    .executes(c -> setEnabled(c.getSource(), true)))
                .then(ClientCommandManager.literal("off")
                    .executes(c -> setEnabled(c.getSource(), false)))
                .then(ClientCommandManager.literal("reach")
                    .then(ClientCommandManager.argument("blocks", DoubleArgumentType.doubleArg(1.0, 4.5))
                        .executes(c -> {
                            AutoBuilder.reach = DoubleArgumentType.getDouble(c, "blocks");
                            return msg(c.getSource(), "Reach = " + AutoBuilder.reach);
                        })))
                .then(ClientCommandManager.literal("delay")
                    .then(ClientCommandManager.argument("ticks", IntegerArgumentType.integer(1, 40))
                        .executes(c -> {
                            AutoBuilder.delayTicks = IntegerArgumentType.getInteger(c, "ticks");
                            return msg(c.getSource(), "Delay = " + AutoBuilder.delayTicks + " tick (+0-2 ngau nhien)");
                        })))
                .then(ClientCommandManager.literal("spoof")
                    .then(ClientCommandManager.argument("value", BoolArgumentType.bool())
                        .executes(c -> {
                            AutoBuilder.spoofRotation = BoolArgumentType.getBool(c, "value");
                            return msg(c.getSource(), "Spoof xoay huong = " + AutoBuilder.spoofRotation);
                        })))
                .then(ClientCommandManager.literal("status")
                    .executes(c -> msg(c.getSource(),
                        "AutoBuild " + (AutoBuilder.enabled ? "ON" : "OFF")
                            + " | reach " + AutoBuilder.reach
                            + " | delay " + AutoBuilder.delayTicks
                            + " | spoof " + AutoBuilder.spoofRotation
                            + " | da dat " + AutoBuilder.placed)))
            ));
    }

    private static int setEnabled(FabricClientCommandSource src, boolean on) {
        AutoBuilder.enabled = on;
        if (on) AutoBuilder.reset();
        return msg(src, "AutoBuild: " + (on ? "ON" : "OFF"));
    }

    private static int msg(FabricClientCommandSource src, String text) {
        src.sendFeedback(Component.literal(text));
        return 1;
    }
}
