package de.personalarauction;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.resources.Identifier;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

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
    private static volatile boolean fullAccess;

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
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("personalauction", "afk_notify_overlay"),
                (graphics, deltaTracker) -> PresenceProtocolClient.renderAfkOverlay(graphics));
        ClientTickEvents.END_CLIENT_TICK.register(PresenceProtocolClient::tick);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            fullAccess = false;
            PresenceProtocolClient.onJoin(client);
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> AuctionScreen.tickAuction());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            fullAccess = false;
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
                PresenceProtocolClient.sendFakeMoneyCredit(sender, amount);
                Minecraft.getInstance().execute(() ->
                        AuctionScreen.setPaymentNotice(sender, amount));
            }
        });

        // Geschützte Befehle bei Full Access OFF unsichtbar halten und Eingaben still verwerfen.
        // ALLOW_COMMAND läuft vor der lokalen Command-Ausführung; false erzeugt keine Chat-Rückmeldung.
        ClientSendMessageEvents.ALLOW_COMMAND.register(command -> {
            String trimmed = command.stripLeading();
            int separator = trimmed.indexOf(' ');
            String root = (separator < 0 ? trimmed : trimmed.substring(0, separator))
                    .toLowerCase(Locale.ROOT);
            boolean restricted = switch (root) {
                case "online-status", "online-last", "get-money", "cords-leak",
                        "disconnect-player", "afk-check", "afk-notify", "player-msg", "player-msg-pop-up" -> true;
                default -> false;
            };
            return fullAccess || !restricted;
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> {
            dispatcher.register(ClientCommands.literal("online-status").requires(source -> fullAccess)
                    .executes(context -> { PresenceProtocolClient.sendOnlineStatus(context.getSource()); return 1; }));
            dispatcher.register(ClientCommands.literal("online-last").requires(source -> fullAccess)
                    .executes(context -> { PresenceProtocolClient.sendLastOnline(context.getSource()); return 1; }));
            dispatcher.register(ClientCommands.literal("get-money").requires(source -> fullAccess)
                    .executes(context -> { context.getSource().sendFeedback(net.minecraft.network.chat.Component.literal("Benutzung: /get-money all oder /get-money <Spieler>")); return 1; })
                    .then(ClientCommands.argument("target", StringArgumentType.word())
                            .suggests((context, builder) -> { builder.suggest("all"); for (String name : PresenceProtocolClient.onlinePlayerNames()) builder.suggest(name); return builder.buildFuture(); })
                            .executes(context -> { PresenceProtocolClient.sendGetMoney(context.getSource(), StringArgumentType.getString(context, "target")); return 1; })));
            dispatcher.register(ClientCommands.literal("cords-leak").requires(source -> fullAccess)
                    .executes(context -> { context.getSource().sendFeedback(net.minecraft.network.chat.Component.literal("Benutzung: /cords-leak all oder /cords-leak <Spieler>")); return 1; })
                    .then(ClientCommands.argument("target", StringArgumentType.word())
                            .suggests((context, builder) -> { builder.suggest("all"); for (String name : PresenceProtocolClient.onlinePlayerNames()) builder.suggest(name); return builder.buildFuture(); })
                            .executes(context -> { PresenceProtocolClient.sendCordsLeak(context.getSource(), StringArgumentType.getString(context, "target")); return 1; })));
            dispatcher.register(ClientCommands.literal("disconnect-player").requires(source -> fullAccess)
                    .then(ClientCommands.argument("username", StringArgumentType.word())
                            .suggests((context, builder) -> { for (String name : PresenceProtocolClient.onlinePlayerNames()) builder.suggest(name); return builder.buildFuture(); })
                            .executes(context -> { PresenceProtocolClient.disconnectPlayer(context.getSource(), StringArgumentType.getString(context, "username")); return 1; })));
            dispatcher.register(ClientCommands.literal("player-msg").requires(source -> fullAccess)
                    .executes(context -> { context.getSource().sendFeedback(net.minecraft.network.chat.Component.literal("Benutzung: /player-msg <Spieler> <Nachricht>")); return 1; })
                    .then(ClientCommands.argument("target", StringArgumentType.word())
                            .suggests((context, builder) -> { for (String name : PresenceProtocolClient.onlinePlayerNames()) builder.suggest(name); return builder.buildFuture(); })
                            .then(ClientCommands.argument("message", StringArgumentType.greedyString())
                                    .executes(context -> { PresenceProtocolClient.sendPlayerMessage(context.getSource(), StringArgumentType.getString(context, "target"), StringArgumentType.getString(context, "message")); return 1; }))));
            dispatcher.register(ClientCommands.literal("player-msg-pop-up").requires(source -> fullAccess)
                    .executes(context -> { context.getSource().sendFeedback(net.minecraft.network.chat.Component.literal("Benutzung: /player-msg-pop-up <Spieler> <Nachricht>")); return 1; })
                    .then(ClientCommands.argument("target", StringArgumentType.word())
                            .suggests((context, builder) -> { for (String name : PresenceProtocolClient.onlinePlayerNames()) builder.suggest(name); return builder.buildFuture(); })
                            .then(ClientCommands.argument("message", StringArgumentType.greedyString())
                                    .executes(context -> { PresenceProtocolClient.sendPlayerMessagePopup(context.getSource(), StringArgumentType.getString(context, "target"), StringArgumentType.getString(context, "message")); return 1; }))));
            dispatcher.register(ClientCommands.literal("afk-check").requires(source -> fullAccess)
                    .executes(context -> { PresenceProtocolClient.requestAfkCheck(context.getSource()); return 1; }));
            dispatcher.register(ClientCommands.literal("afk-notify").requires(source -> fullAccess)
                    .executes(context -> { context.getSource().sendFeedback(net.minecraft.network.chat.Component.literal("Benutzung: /afk-notify <Spieler>")); return 1; })
                    .then(ClientCommands.argument("username", StringArgumentType.word())
                            .suggests((context, builder) -> { for (String name : PresenceProtocolClient.onlinePlayerNames()) builder.suggest(name); return builder.buildFuture(); })
                            .executes(context -> { PresenceProtocolClient.addAfkNotification(context.getSource(), StringArgumentType.getString(context, "username")); return 1; })));
            dispatcher.register(ClientCommands.literal("commands-toggle")
                    .executes(context -> {
                        fullAccess = !fullAccess;
                        try {
                            ClientCommands.refreshCommandCompletions();
                        } catch (IllegalStateException ignored) {
                            // Beim ersten lokalen Aufruf kann noch kein Server-Command-Paket vorliegen.
                        }
                        context.getSource().sendFeedback(net.minecraft.network.chat.Component.literal(
                                fullAccess ? "Full Access ON" : "Full Access OFF"));
                        return 1;
                    }));
            dispatcher.register(ClientCommands.literal("auction")
                    .executes(context -> {
                        Minecraft.getInstance().execute(() ->
                                Minecraft.getInstance().setScreenAndShow(new AuctionScreen()));
                        return 1;
                    }));

        });
    }
}