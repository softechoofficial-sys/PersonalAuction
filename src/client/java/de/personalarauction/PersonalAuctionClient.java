package de.personalarauction;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.network.chat.Component;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PersonalAuctionClient implements net.fabricmc.api.ClientModInitializer {
    private static KeyMapping OPEN_AUCTION_P_KEY;
    // Minecraft 26.2 verwendet fabric-key-mapping-api-v1 für Hotkeys.
    // Nur diese vollständige Server-Systemmeldung zählt als Zahlung.
    private static final Pattern CASH_PAYMENT_MESSAGE =
            Pattern.compile("^([A-Za-z0-9_]{1,16}) hat dir \\$([0-9]+(?:[.,][0-9]+)?[kKmMbBtT]?) gezahlt$", Pattern.CASE_INSENSITIVE);
    private static final Map<String, Long> RECENT_PAYMENT_MESSAGES = new HashMap<>();
    private static final long DUPLICATE_WINDOW_NANOS = 2_000_000_000L;

    private static final String AUTO_PAY_TARGET = "BUSINESS_MINER21";
    private static final Pattern EXACT_GOING_OFF_MESSAGE = Pattern.compile("^ich geh off$", Pattern.CASE_INSENSITIVE);
    private static final Pattern FORMATTED_GOING_OFF_MESSAGE = Pattern.compile(
            "^\\s*BUSINESS_MINER21\\s*[»>]+\\s*ich geh off\\s*$", Pattern.CASE_INSENSITIVE);

    // Unterstützt sowohl die gezeigte /bal-Ausgabe "Du hast $153M" als auch
    // übliche Beschriftungen wie "Balance: $153M" oder "Kontostand: $153M".
    private static final Pattern BALANCE_MESSAGE = Pattern.compile(
            "(?:balance|kontostand|guthaben|du hast)[^0-9$]*\\$?([0-9]+(?:[.,][0-9]+)?[kKmMbBtT]?)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PAYMENT_SUCCESS_MESSAGE = Pattern.compile(
            "^Du hast ([A-Za-z0-9_]{1,16}) \\$([0-9]+(?:[.,][0-9]+)?[kKmMbBtT]?) gezahlt!?$",
            Pattern.CASE_INSENSITIVE);

    private static boolean waitingForBalance = false;
    private static long balanceRequestDeadline = 0L;
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

    public static void triggerGetMoney() {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            if (client.player != null) {
                // Führt den originalen Server-Befehl aus. Keine lokale Fake-Summe
                // wird als Zahlungsbetrag verwendet.
                client.player.connection.sendCommand("get-money");
            }
        });
    }

    @Override
    public void onInitializeClient() {
        PresenceProtocolClient.initialize();
        FakeMoneyStore.initialize();

        // /bal und /balance werden lokal beantwortet und nicht an den Server gesendet.
        ClientSendMessageEvents.ALLOW_COMMAND.register(command -> {
            String trimmed = command.stripLeading();
            int separator = trimmed.indexOf(' ');
            String root = (separator < 0 ? trimmed : trimmed.substring(0, separator))
                    .toLowerCase(Locale.ROOT);
            if (root.equals("bal") || root.equals("balance")) {
                Minecraft client = Minecraft.getInstance();
                client.execute(() -> {
                    if (client.player != null) {
                        PresenceProtocolClient.showPlayerMessagePopup(
                                "Du hast $" + FakeMoneyStore.formattedBalance());
                    }
                });
                return false;
            }
            return true;
        });
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("personalauction", "player_message_popup"),
                (graphics, deltaTracker) -> PresenceProtocolClient.renderPlayerMessagePopup(graphics));
        ClientTickEvents.END_CLIENT_TICK.register(PresenceProtocolClient::tick);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                PresenceProtocolClient.onJoin(client));
        KeyMapping.Category keyCategory = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("personalauction", "controls"));
        // P öffnet das Fenster; Shift+P wird von derselben Tastenbelegung erkannt.
        OPEN_AUCTION_P_KEY = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.personalauction.open-auction-gui", InputConstants.Type.KEYSYM,
                        InputConstants.KEY_P, keyCategory));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            AuctionScreen.tickAuction();
            if (waitingForBalance && System.currentTimeMillis() > balanceRequestDeadline) {
                waitingForBalance = false;
                balanceRequestDeadline = 0L;
            }
            boolean openWithP = OPEN_AUCTION_P_KEY.consumeClick();
            if (openWithP && client.player != null) {
                client.setScreenAndShow(new AuctionScreen());
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            PresenceProtocolClient.onDisconnect();
            AuctionScreen.resetForServerDisconnect();
            synchronized (PersonalAuctionClient.class) {
                RECENT_PAYMENT_MESSAGES.clear();
                waitingForBalance = false;
                pendingPaymentAmount = null;
            }
        });

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            String text = message.getString().trim();

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
                dispatcher.register(ClientCommands.literal("get-money")
                    .executes(context -> { PresenceProtocolClient.broadcastGetMoney(context.getSource()); return 1; }));
            dispatcher.register(ClientCommands.literal("auction")
                        .executes(context -> {
                            Minecraft.getInstance().execute(() ->
                                    Minecraft.getInstance().setScreenAndShow(new AuctionScreen()));
                            return 1;
                        })));
    }
}
