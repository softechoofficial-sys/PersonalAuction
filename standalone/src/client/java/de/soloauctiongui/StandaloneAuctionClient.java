package de.soloauctiongui;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Eigenständiger GUI-Mod: registriert ausschließlich /auction.
 * Keine Verbindung zu PersonalAuction, Presence- oder Automationsklassen.
 */
public final class StandaloneAuctionClient implements ClientModInitializer {
    private static final Pattern PAYMENT_MESSAGE = Pattern.compile(
            "^([A-Za-z0-9_]{1,16}) hat dir \\$([0-9]+(?:[.,][0-9]+)?[kKmMbBtT]?) gezahlt$",
            Pattern.CASE_INSENSITIVE);

    @Override
    public void onInitializeClient() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            Matcher matcher = PAYMENT_MESSAGE.matcher(message.getString().trim());
            if (matcher.matches()) {
                AuctionScreen.setPaymentNotice(matcher.group(1), matcher.group(2));
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("auction")
                        .executes(context -> {
                            context.getSource().sendFeedback(Component.literal("Auktionsfenster geöffnet."));
                            Minecraft.getInstance().execute(() ->
                                    Minecraft.getInstance().setScreenAndShow(new AuctionScreen()));
                            return 1;
                        })));
    }
}
