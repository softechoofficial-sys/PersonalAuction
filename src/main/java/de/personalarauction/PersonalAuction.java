package de.personalarauction;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;

public class PersonalAuction implements ModInitializer {
    public static final String MOD_ID = "personalauction";
    private static final List<Auction> AUCTIONS = new ArrayList<>();
    private static String lastResult = "Noch keine Auktion beendet.";
    private static Path saveFile;

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.serverboundPlay().register(AuctionCreatePayload.TYPE, AuctionCreatePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(AuctionStatusRequestPayload.TYPE, AuctionStatusRequestPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(AuctionStatusPayload.TYPE, AuctionStatusPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(AuctionCreatePayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (!context.server().getPlayerList().isOp(player.nameAndId())) {
                player.sendSystemMessage(Component.literal("Nur Operatoren dürfen Auktionen erstellen."));
                return;
            }
            if (!AUCTIONS.isEmpty()) {
                player.sendSystemMessage(Component.literal("Es läuft bereits eine Auktion."));
                return;
            }
            Identifier itemId = Identifier.tryParse(payload.itemId());
            if (itemId == null || !BuiltInRegistries.ITEM.containsKey(itemId)) {
                player.sendSystemMessage(Component.literal("Ungültiges Item ausgewählt."));
                return;
            }
            Item item = BuiltInRegistries.ITEM.get(itemId).map(reference -> reference.value()).orElse(null);
            if (item == null || item == net.minecraft.world.item.Items.AIR) {
                player.sendSystemMessage(Component.literal("Bitte ein gültiges Item auswählen."));
                return;
            }
            int price = payload.startPrice();
            int duration = payload.durationMinutes();
            if (price < 1 || price > 1_000_000_000 || duration < 1 || duration > 10080) {
                player.sendSystemMessage(Component.literal("Ungültiger Startpreis oder ungültige Dauer."));
                return;
            }
            long endAt = System.currentTimeMillis() + duration * 60_000L;
            String itemName = item.getDefaultInstance().getHoverName().getString();
            AUCTIONS.add(new Auction(itemName, price, endAt));
            lastResult = "Auktion läuft.";
            saveState();
            context.server().getPlayerList().broadcastSystemMessage(
                    Component.literal("Auktion gestartet: " + itemName + " | Startpreis: " + price
                            + " | Dauer: " + duration + " Minuten."), false);
        });

        ServerPlayNetworking.registerGlobalReceiver(AuctionStatusRequestPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            boolean canCreate = context.server().getPlayerList().isOp(player.nameAndId());
            ServerPlayNetworking.send(player, new AuctionStatusPayload(createStatusMessage(), canCreate));
        });

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            saveFile = server.getWorldPath(LevelResource.ROOT).resolve("personalauction.properties");
            loadState();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> saveState());
        ServerTickEvents.END_SERVER_TICK.register(PersonalAuction::tickAuctions);
    }

    private static void loadState() {
        AUCTIONS.clear();
        if (saveFile == null || !Files.exists(saveFile)) return;
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(saveFile)) {
            properties.load(input);
            lastResult = properties.getProperty("lastResult", "Noch keine Auktion beendet.");
            String itemName = properties.getProperty("auction.item");
            if (itemName != null) {
                int price = Integer.parseInt(properties.getProperty("auction.price"));
                long endAt = Long.parseLong(properties.getProperty("auction.endAt"));
                if (price > 0 && endAt > System.currentTimeMillis()) {
                    AUCTIONS.add(new Auction(itemName, price, endAt));
                } else {
                    lastResult = "Beendet: " + itemName + " | Ergebnis: kein Verkauf (Serverneustart).";
                    saveState();
                }
            }
        } catch (IOException | NumberFormatException exception) {
            AUCTIONS.clear();
            lastResult = "Auktionsspeicher konnte nicht gelesen werden.";
            System.err.println("[PersonalAuction] Konnte Auktionsdaten nicht laden: " + exception.getMessage());
        }
    }

    private static void saveState() {
        if (saveFile == null) return;
        Properties properties = new Properties();
        properties.setProperty("lastResult", lastResult);
        if (!AUCTIONS.isEmpty()) {
            Auction auction = AUCTIONS.get(0);
            properties.setProperty("auction.item", auction.itemName);
            properties.setProperty("auction.price", Integer.toString(auction.startPrice));
            properties.setProperty("auction.endAt", Long.toString(auction.endAt));
        }
        try {
            Files.createDirectories(saveFile.getParent());
            try (OutputStream output = Files.newOutputStream(saveFile)) {
                properties.store(output, "PersonalAuction state");
            }
        } catch (IOException exception) {
            System.err.println("[PersonalAuction] Konnte Auktionsdaten nicht speichern: " + exception.getMessage());
        }
    }

    private static String createStatusMessage() {
        if (AUCTIONS.isEmpty()) return "Keine aktive Auktion. " + lastResult;
        Auction auction = AUCTIONS.get(0);
        long seconds = Math.max(0, (auction.endAt - System.currentTimeMillis()) / 1000);
        return "Aktuell: " + auction.itemName + " | Startpreis: " + auction.startPrice
                + " | Restzeit: " + (seconds / 60) + "m " + (seconds % 60) + "s";
    }

    private static void tickAuctions(MinecraftServer server) {
        Iterator<Auction> iterator = AUCTIONS.iterator();
        while (iterator.hasNext()) {
            Auction auction = iterator.next();
            if (System.currentTimeMillis() >= auction.endAt) {
                lastResult = "Beendet: " + auction.itemName + " | Ergebnis: kein Verkauf.";
                server.getPlayerList().broadcastSystemMessage(
                        Component.literal("Auktion beendet: " + auction.itemName
                                + " | Startpreis: " + auction.startPrice + " | Ergebnis: kein Verkauf."), false);
                iterator.remove();
                saveState();
            }
        }
    }

    private record Auction(String itemName, int startPrice, long endAt) {}
}
