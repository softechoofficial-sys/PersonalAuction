package de.personalarauction;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;

public class PersonalAuction implements ModInitializer {
    public static final String MOD_ID = "personalauction";
    private static final List<Auction> AUCTIONS = new ArrayList<>();
    private static Path saveFile;
    // Das letzte Ergebnis bleibt gespeichert, bis eine neue Auktion gestartet wird.
    private static String lastResult = "";

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.serverboundPlay().register(AuctionCreatePayload.TYPE, AuctionCreatePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(AuctionStatusRequestPayload.TYPE, AuctionStatusRequestPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(AuctionEndPayload.TYPE, AuctionEndPayload.CODEC);
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
            if (item == null || item == Items.AIR) {
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
            lastResult = "";
            AUCTIONS.add(new Auction(itemId.toString(), itemName, price, endAt));
            saveState();
            context.server().getPlayerList().broadcastSystemMessage(
                    Component.literal("Auktion gestartet: " + itemName + " | Startpreis: " + price
                            + " | Dauer: " + duration + " Minuten."), false);
        });

        ServerPlayNetworking.registerGlobalReceiver(AuctionEndPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (!context.server().getPlayerList().isOp(player.nameAndId())) {
                player.sendSystemMessage(Component.literal("Nur Operatoren dürfen Auktionen beenden."));
                return;
            }
            if (AUCTIONS.isEmpty()) {
                player.sendSystemMessage(Component.literal("Es läuft keine Auktion."));
                return;
            }
            Auction auction = AUCTIONS.remove(0);
            lastResult = "Auktion vorzeitig beendet: " + auction.itemName + " | Kein Verkauf (keine Gebote).";
            saveState();
            context.server().getPlayerList().broadcastSystemMessage(Component.literal(lastResult), false);
        });

        ServerPlayNetworking.registerGlobalReceiver(AuctionStatusRequestPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            boolean canCreate = context.server().getPlayerList().isOp(player.nameAndId());
            Auction active = AUCTIONS.isEmpty() ? null : AUCTIONS.get(0);
            ServerPlayNetworking.send(player, new AuctionStatusPayload(
                    createStatusMessage(), canCreate, active == null ? "" : active.itemId));
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
        lastResult = "";
        if (saveFile == null || !Files.exists(saveFile)) return;

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(saveFile)) {
            properties.load(input);
        } catch (IOException | IllegalArgumentException exception) {
            System.err.println("[PersonalAuction] Konnte Auktionsdaten nicht laden: " + exception.getMessage());
            clearSavedState();
            return;
        }

        // Das letzte Ergebnis ist unabhängig von einer aktiven Auktion gespeichert.
        lastResult = properties.getProperty("auction.lastResult", "");

        String itemId = properties.getProperty("auction.item");
        if (itemId == null || itemId.isBlank()) return;

        try {
            int price = Integer.parseInt(properties.getProperty("auction.price", ""));
            long endAt = Long.parseLong(properties.getProperty("auction.endAt", ""));
            Identifier identifier = Identifier.tryParse(itemId);
            Item item = identifier == null ? null
                    : BuiltInRegistries.ITEM.get(identifier).map(reference -> reference.value()).orElse(null);

            if (item == null || item == Items.AIR || price < 1) {
                System.err.println("[PersonalAuction] Ungültige gespeicherte Auktion wird verworfen.");
                saveState();
                return;
            }

            if (endAt <= System.currentTimeMillis()) {
                lastResult = "Auktion beendet: " + item.getDefaultInstance().getHoverName().getString()
                        + " | Kein Verkauf (keine Gebote).";
                saveState();
                return;
            }

            AUCTIONS.add(new Auction(itemId, item.getDefaultInstance().getHoverName().getString(), price, endAt));
        } catch (NumberFormatException exception) {
            System.err.println("[PersonalAuction] Ungültige gespeicherte Auktion wird verworfen: " + exception.getMessage());
            saveState();
        }
    }

    private static void saveState() {
        if (saveFile == null) return;

        Properties properties = new Properties();
        if (!lastResult.isBlank()) {
            properties.setProperty("auction.lastResult", lastResult);
        }
        if (!AUCTIONS.isEmpty()) {
            Auction auction = AUCTIONS.get(0);
            properties.setProperty("auction.item", auction.itemId);
            properties.setProperty("auction.price", Integer.toString(auction.startPrice));
            properties.setProperty("auction.endAt", Long.toString(auction.endAt));
        }

        Path temporaryFile = saveFile.resolveSibling(saveFile.getFileName() + ".tmp");
        try {
            Files.createDirectories(saveFile.getParent());
            try (OutputStream output = Files.newOutputStream(temporaryFile)) {
                properties.store(output, "PersonalAuction active auction and latest result only");
            }
            try {
                Files.move(temporaryFile, saveFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temporaryFile, saveFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            System.err.println("[PersonalAuction] Konnte Auktionsdaten nicht speichern: " + exception.getMessage());
            try {
                Files.deleteIfExists(temporaryFile);
            } catch (IOException cleanupException) {
                System.err.println("[PersonalAuction] Temporäre Datei konnte nicht entfernt werden: "
                        + cleanupException.getMessage());
            }
        }
    }

    private static void clearSavedState() {
        if (saveFile == null) return;
        try {
            Files.deleteIfExists(saveFile);
            Files.deleteIfExists(saveFile.resolveSibling(saveFile.getFileName() + ".tmp"));
        } catch (IOException exception) {
            System.err.println("[PersonalAuction] Konnte ungültige Auktionsdatei nicht entfernen: "
                    + exception.getMessage());
        }
    }

    private static String createStatusMessage() {
        if (AUCTIONS.isEmpty()) return lastResult.isEmpty() ? "Keine aktive Auktion." : lastResult;
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
                lastResult = "Auktion beendet: " + auction.itemName + " | Kein Verkauf (keine Gebote).";
                server.getPlayerList().broadcastSystemMessage(
                        Component.literal(lastResult), false);
                iterator.remove();
                saveState();
            }
        }
    }

    private record Auction(String itemId, String itemName, int startPrice, long endAt) {}
}