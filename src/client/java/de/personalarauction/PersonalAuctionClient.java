package de.personalarauction;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;

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

    private static synchronized boolean isDuplicatePaymentMessage(String sender, String amount) {
        long now = System.nanoTime();
        RECENT_PAYMENT_MESSAGES.entrySet().removeIf(entry -> now - entry.getValue() > 5_000_000_000L);
        String key = sender.trim().toLowerCase(Locale.ROOT) + "|" + amount;
        Long previous = RECENT_PAYMENT_MESSAGES.put(key, now);
        return previous != null && now - previous < DUPLICATE_WINDOW_NANOS;
    }

    @Override
    public void onInitializeClient() {
        KeyMapping.Category keyCategory = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("personalauction", "controls"));
        // P öffnet das Fenster; Shift+P wird von derselben Tastenbelegung erkannt.
        OPEN_AUCTION_P_KEY = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.personalauction.open-auction-gui", InputConstants.Type.KEYSYM,
                        InputConstants.KEY_P, keyCategory));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            AuctionScreen.tickAuction();
            boolean openWithP = OPEN_AUCTION_P_KEY.consumeClick();
            if (openWithP && client.player != null) {
                client.setScreenAndShow(new AuctionScreen());
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            AuctionScreen.resetForServerDisconnect();
            synchronized (PersonalAuctionClient.class) {
                RECENT_PAYMENT_MESSAGES.clear();
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
                dispatcher.register(ClientCommands.literal("auction")
                        .executes(context -> {
                            Minecraft.getInstance().execute(() ->
                                    Minecraft.getInstance().setScreenAndShow(new AuctionScreen()));
                            return 1;
                        })));
    }
}
