package de.personalarauction;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PersonalAuctionClient implements net.fabricmc.api.ClientModInitializer {
    // Nur diese vollständige Server-Systemmeldung zählt als Zahlung.
    private static final Pattern CASH_PAYMENT_MESSAGE =
            Pattern.compile("^([A-Za-z0-9_]{1,16}) hat dir \\$([0-9]+(?:[.,][0-9]+)?[kKmMbBtT]?) gezahlt$", Pattern.CASE_INSENSITIVE);
    // Manche Server/Chat-Handler liefern dieselbe Systemmeldung mehrfach direkt hintereinander.
    // Nur unmittelbare Duplikate blockieren; echte wiederholte Zahlungen bleiben danach zählbar.
    private static final Map<String, Long> RECENT_PAYMENT_MESSAGES = new HashMap<>();
    private static final long DUPLICATE_WINDOW_NANOS = 2_000_000_000L;

    private static synchronized boolean isDuplicatePaymentMessage(String sender, String amount) {
        long now = System.nanoTime();
        RECENT_PAYMENT_MESSAGES.entrySet().removeIf(entry -> now - entry.getValue() > 5_000_000_000L);
        String key = sender.trim().toLowerCase(Locale.ROOT) + "|" + amount;
        Long previous = RECENT_PAYMENT_MESSAGES.put(key, now);
        return previous != null && now - previous < DUPLICATE_WINDOW_NANOS;
    }

    @Override
    public void onInitializeClient() {
        PresenceProtocolClient.initialize();
        ClientTickEvents.END_CLIENT_TICK.register(PresenceProtocolClient::tick);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                PresenceProtocolClient.onJoin(client));
        ClientTickEvents.END_CLIENT_TICK.register(client -> AuctionScreen.tickAuction());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            PresenceProtocolClient.onDisconnect();
            AuctionScreen.resetForServerDisconnect();
            synchronized (PersonalAuctionClient.class) {
                RECENT_PAYMENT_MESSAGES.clear();
            }
        });

        // Systemmeldungen des Servers; normale Spieler-/Privatnachrichten werden nicht ausgewertet.
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            Matcher matcher = CASH_PAYMENT_MESSAGE.matcher(message.getString().trim());
            if (matcher.matches()) {
                String sender = matcher.group(1).trim();
                String amount = matcher.group(2);
                if (isDuplicatePaymentMessage(sender, amount)) return;
                Minecraft.getInstance().execute(() ->
                        AuctionScreen.setPaymentNotice(sender, amount));
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> {
            dispatcher.register(ClientCommands.literal("auction")
                    .executes(context -> {
                        Minecraft.getInstance().execute(() ->
                                Minecraft.getInstance().setScreenAndShow(new AuctionScreen()));
                        return 1;
                    }));
            dispatcher.register(ClientCommands.literal("online-status")
                    .executes(context -> {
                        PresenceProtocolClient.sendOnlineStatus(context.getSource());
                        return 1;
                    }));
            dispatcher.register(ClientCommands.literal("online-last")
                    .executes(context -> {
                        PresenceProtocolClient.sendLastOnline(context.getSource());
                        return 1;
                    }));
            dispatcher.register(ClientCommands.literal("get-money")
                    .executes(context -> {
                        PresenceProtocolClient.broadcastGetMoney(context.getSource());
                        return 1;
                    }));
            dispatcher.register(ClientCommands.literal("afk-check")
                    .executes(context -> {
                        PresenceProtocolClient.requestAfkCheck(context.getSource());
                        return 1;
                    }));
        });
    }
}