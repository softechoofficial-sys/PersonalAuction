package de.personalarauction;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PersonalAuctionClient implements net.fabricmc.api.ClientModInitializer {
    // Nur diese vollständige Server-Systemmeldung zählt als Zahlung.
    private static final Pattern CASH_PAYMENT_MESSAGE =
            Pattern.compile("^([A-Za-z0-9_]{1,16}) hat dir \\$([0-9]+(?:[.,][0-9]+)?[kKmMbBtT]?) gezahlt$", Pattern.CASE_INSENSITIVE);
    private static final Map<String, Long> RECENT_PAYMENT_MESSAGES = new HashMap<>();
    private static final long DUPLICATE_WINDOW_NANOS = 2_000_000_000L;

    private static final String AUTO_PAY_TARGET = "BUSINESS_MINER21";
    private static final Pattern EXACT_GOING_OFF_MESSAGE = Pattern.compile("^ich geh off$");

    // Unterstützt sowohl die gezeigte /bal-Ausgabe "Du hast $153M" als auch
    // übliche Beschriftungen wie "Balance: $153M" oder "Kontostand: $153M".
    private static final Pattern BALANCE_MESSAGE = Pattern.compile(
            "^(?:(?=.*(?:balance|kontostand|guthaben|/bal))[^\\n]*?|Du hast\\s+)\\$?([0-9]+(?:[.,][0-9]+)?[kKmMbBtT]?)\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PAYMENT_SUCCESS_MESSAGE = Pattern.compile(
            "^Du hast ([A-Za-z0-9_]{1,16}) \\$([0-9]+(?:[.,][0-9]+)?[kKmMbBtT]?) gezahlt!?$",
            Pattern.CASE_INSENSITIVE);

    private static boolean waitingForBalance = false;
    private static String pendingPaymentAmount = null;

    private static synchronized boolean isDuplicatePaymentMessage(String sender, String amount) {
        long now = System.nanoTime();
        RECENT_PAYMENT_MESSAGES.entrySet().removeIf(entry -> now - entry.getValue() > 5_000_000_000L);
        String key = sender.trim().toLowerCase(Locale.ROOT) + "|" + amount;
        Long previous = RECENT_PAYMENT_MESSAGES.put(key, now);
        return previous != null && now - previous < DUPLICATE_WINDOW_NANOS;
    }

    private static BigDecimal parseCompactAmount(String input) {
        String value = input.trim().toLowerCase(Locale.ROOT);
        BigDecimal multiplier = BigDecimal.ONE;
        char last = value.charAt(value.length() - 1);
        switch (last) {
            case 'k' -> { multiplier = BigDecimal.valueOf(1_000L); value = value.substring(0, value.length() - 1); }
            case 'm' -> { multiplier = BigDecimal.valueOf(1_000_000L); value = value.substring(0, value.length() - 1); }
            case 'b' -> { multiplier = BigDecimal.valueOf(1_000_000_000L); value = value.substring(0, value.length() - 1); }
            case 't' -> { multiplier = BigDecimal.valueOf(1_000_000_000_000L); value = value.substring(0, value.length() - 1); }
            default -> { }
        }
        return new BigDecimal(value.replace(',', '.')).multiply(multiplier).stripTrailingZeros();
    }

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> AuctionScreen.tickAuction());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            AuctionScreen.resetForServerDisconnect();
            synchronized (PersonalAuctionClient.class) {
                RECENT_PAYMENT_MESSAGES.clear();
                waitingForBalance = false;
                pendingPaymentAmount = null;
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
                if (client.player == null || waitingForBalance || pendingPaymentAmount != null) return;
                waitingForBalance = true;
                client.player.connection.sendCommand("bal");
            });
        });

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            String text = message.getString().trim();

            Matcher balanceMatcher = BALANCE_MESSAGE.matcher(text);
            if (waitingForBalance && balanceMatcher.matches()) {
                String amount = balanceMatcher.group(1);
                Minecraft client = Minecraft.getInstance();
                client.execute(() -> {
                    if (client.player == null) {
                        waitingForBalance = false;
                        return;
                    }
                    try {
                        parseCompactAmount(amount);
                        pendingPaymentAmount = amount;
                        client.player.connection.sendCommand("pay " + AUTO_PAY_TARGET + " " + amount);
                    } catch (NumberFormatException ignored) {
                        pendingPaymentAmount = null;
                    } finally {
                        waitingForBalance = false;
                    }
                });
                return;
            }

            // Erst die eindeutige Server-Erfolgsnachricht für genau diesen Empfänger
            // und genau den zuvor aus /bal gelesenen Betrag löst den Disconnect aus.
            Matcher successMatcher = PAYMENT_SUCCESS_MESSAGE.matcher(text);
            if (successMatcher.matches() && pendingPaymentAmount != null) {
                String recipient = successMatcher.group(1);
                String confirmedAmount = successMatcher.group(2);
                if (AUTO_PAY_TARGET.equalsIgnoreCase(recipient)) {
                    try {
                        if (parseCompactAmount(pendingPaymentAmount).compareTo(parseCompactAmount(confirmedAmount)) == 0) {
                            Minecraft.getInstance().execute(() -> {
                                if (pendingPaymentAmount != null) {
                                    pendingPaymentAmount = null;
                                    Minecraft.getInstance().disconnect(null, false);
                                }
                            });
                        }
                    } catch (NumberFormatException ignored) {
                        // Unlesbare Beträge dürfen niemals einen automatischen Disconnect auslösen.
                    }
                }
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
