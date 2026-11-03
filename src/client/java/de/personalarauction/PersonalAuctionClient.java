package de.personalarauction;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PersonalAuctionClient implements net.fabricmc.api.ClientModInitializer {
    private static final Pattern CASH_PAYMENT_MESSAGE =
            Pattern.compile("^(.+?) hat dir \\$([0-9][0-9.,]*) gezahlt$", Pattern.CASE_INSENSITIVE);

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> AuctionScreen.tickAuction());

        // Systemmeldungen des Servers; normale Spieler-/Privatnachrichten werden nicht ausgewertet.
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            Matcher matcher = CASH_PAYMENT_MESSAGE.matcher(message.getString().trim());
            if (matcher.matches()) {
                String sender = matcher.group(1).trim();
                String amount = matcher.group(2);
                Minecraft.getInstance().execute(() ->
                        AuctionScreen.setPaymentNotice("Zahlung erkannt: " + sender + " -> $" + amount
                                + " (Chatmeldung, nicht serverseitig bestätigt)"));
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
                dispatcher.register(ClientCommands.literal("auction")
                        .executes(context -> {
                            Minecraft.getInstance().execute(() ->
                                    Minecraft.getInstance().setScreenAndShow(new AuctionScreen()));
                            return 1;
                        })));
    }
}