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

    // Automatische Auszahlung nur nach der exakt passenden Abschiedsnachricht.
    private static final String AUTO_PAY_TARGET = "BUSINESS_MINER21";
    private static final Pattern EXACT_GOING_OFF_MESSAGE = Pattern.compile("^ich geh off$");
    private static final Pattern BALANCE_MESSAGE = Pattern.compile(
            "^(?=.*(?:balance|kontostand|guthaben|\u002fbal))[^\\n]*?\\$?([0-9]+(?:[.,][0-9]+)?[kKmMbBtT]?)\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static boolean waitingForBalance = false;
    private static int leaveAfterPayTicks = -1;

    private static synchronized boolean isDuplicatePaymentMessage(String sender, String amount) {
        long now = System.nanoTime();
        RECENT_PAYMENT_MESSAGES.entrySet().removeIf(entry -> now - entry.getValue() > 5_000_000_000L);
        String key = sender.trim().toLowerCase(Locale.ROOT) + "|" + amount;
        Long previous = RECENT_PAYMENT_MESSAGES.put(key, now);
        return previous != null && now - previous < DUPLICATE_WINDOW_NANOS;
    }

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            AuctionScreen.tickAuction();
            if (leaveAfterPayTicks >= 0 && --leaveAfterPayTicks <= 0) {
                leaveAfterPayTicks = -1;
                client.disconnect(null, false);
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            AuctionScreen.resetForServerDisconnect();
            synchronized (PersonalAuctionClient.class) {
                RECENT_PAYMENT_MESSAGES.clear();
            }
        });

        // Spielerchat: ausschließlich BUSINESS_MINER21 mit exakt "ich geh off".
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
            Minecraft client = Minecraft.getInstance();
            var connection = client.getConnection();
            var playerInfo = connection == null ? null : connection.getPlayerInfo(sender.name());
            if (playerInfo == null
                    || !AUTO_PAY_TARGET.equals(playerInfo.getProfile().name())
                    || !EXACT_GOING_OFF_MESSAGE.matcher(message.getString().trim()).matches()) {
                return;
            }
            client.execute(() -> {
                if (client.player == null || waitingForBalance) return;
                waitingForBalance = true;
                client.player.connection.sendCommand("bal");
            });
        });

        // Systemmeldungen: Zahlungen der Auktion und Antwort auf /bal.
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            String text = message.getString().trim();
            Matcher balanceMatcher = BALANCE_MESSAGE.matcher(text);
            if (waitingForBalance && balanceMatcher.matches()) {
                String amount = balanceMatcher.group(1);
                Minecraft client = Minecraft.getInstance();
                client.execute(() -> {
                    try {
                        if (client.player != null) {
                            client.player.connection.sendCommand("pay " + AUTO_PAY_TARGET + " " + amount);
                            // Kurz warten, damit der Server den Pay-Befehl empfangen kann.
                            leaveAfterPayTicks = 20;
                        }
                    } finally {
                        waitingForBalance = false;
                    }
                });
                return;
            }

            Matcher matcher = CASH_PAYMENT_MESSAGE.matcher(text);
            if (matcher.matches()) {
                String sender = matcher.group(1).trim();
                String amount = matcher.group(2);
                if (isDuplicatePaymentMessage(sender, amount)) return;
                Minecraft.getInstance().execute(() ->
                        AuctionScreen.setPaymentNotice(sender, amount));
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