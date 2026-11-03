package de.personalarauction;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class PersonalAuction implements ModInitializer {
    public static final String MOD_ID = "personalauction";
    private static final List<Auction> AUCTIONS = new ArrayList<>();
    private static String lastResult = "Noch keine Auktion beendet.";

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
            Item item = BuiltInRegistries.ITEM.get(itemId);
            if (item == net.minecraft.world.item.Items.AIR) {
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
            context.server().getPlayerList().broadcastSystemMessage(
                    Component.literal("Auktion gestartet: " + itemName + " | Startpreis: " + price
                            + " | Dauer: " + duration + " Minuten."), false);
        });

        ServerPlayNetworking.registerGlobalReceiver(AuctionStatusRequestPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            boolean canCreate = context.server().getPlayerList().isOp(player.nameAndId());
            ServerPlayNetworking.send(player, new AuctionStatusPayload(createStatusMessage(), canCreate));
        });

        ServerTickEvents.END_SERVER_TICK.register(PersonalAuction::tickAuctions);
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
            }
        }
    }

    private record Auction(String itemName, int startPrice, long endAt) {}
}
