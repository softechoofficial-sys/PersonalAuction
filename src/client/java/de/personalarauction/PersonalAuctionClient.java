package de.personalarauction;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PersonalAuctionClient implements ClientModInitializer {
    private static final Pattern CASH_PAYMENT_MESSAGE =
            Pattern.compile("^(.+?) hat dir \\$([0-9][0-9.,]*) gezahlt$", Pattern.CASE_INSENSITIVE);

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(AuctionStatusPayload.TYPE, (payload, context) ->
                context.client().execute(() ->
                        AuctionScreen.setAuctionStatus(payload.message(), payload.canCreate(), payload.itemId())));

        // GAME receives server-originated system messages. Player chat such as /msg is a separate CHAT event.
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            Matcher matcher = CASH_PAYMENT_MESSAGE.matcher(message.getString().trim());
            if (matcher.matches()) {
                String sender = matcher.group(1).trim();
                String amount = matcher.group(2);
                Minecraft.getInstance().execute(() ->
                        AuctionScreen.setPaymentNotice("Zahlung erkannt: " + sender + " → $" + amount
                                + " (nur Anzeige, kein bestätigtes Auktionsgebot)"));
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
                dispatcher.register(ClientCommands.literal("auction")
                        .requires(source -> source.getPlayer() != null)
                        .executes(context -> {
                            Minecraft.getInstance().execute(() ->
                                    Minecraft.getInstance().gui.setScreen(new AuctionScreen()));
                            return 1;
                        })));
    }
}
