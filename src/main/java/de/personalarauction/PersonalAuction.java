package de.personalarauction;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

public class PersonalAuction implements ModInitializer {
    public static final String MOD_ID = "personalauction";
    private static final List<Auction> AUCTIONS = new ArrayList<>();

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.serverboundPlay().register(AuctionCreatePayload.TYPE, AuctionCreatePayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(AuctionCreatePayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (!context.server().getPlayerList().isOp(player.nameAndId())) {
                player.sendSystemMessage(Component.literal("Nur Operatoren dürfen Auktionen erstellen."));
                return;
            }
            int slot = payload.inventorySlot();
            if (slot < 0 || slot >= 36) {
                player.sendSystemMessage(Component.literal("Ungültiger Inventar-Slot."));
                return;
            }
            ItemStack held = player.getInventory().getItem(slot);
            if (held.isEmpty()) {
                player.sendSystemMessage(Component.literal("Der ausgewählte Inventar-Slot ist leer."));
                return;
            }
            int price = payload.startPrice();
            int duration = payload.durationMinutes();
            if (price < 1 || price > 1_000_000_000 || duration < 1 || duration > 10080) {
                player.sendSystemMessage(Component.literal("Ungültiger Startpreis oder ungültige Dauer."));
                return;
            }

            ItemStack auctionItem = held.copy();
            player.getInventory().removeItem(slot, auctionItem.getCount());
            long endAt = System.currentTimeMillis() + duration * 60_000L;
            AUCTIONS.add(new Auction(auctionItem, price, endAt, player.getUUID()));
            String announcement = "Auktion gestartet: " + auctionItem.getCount() + "x "
                    + auctionItem.getHoverName().getString() + " | Startpreis: " + price
                    + " | Dauer: " + duration + " Minuten.";
            context.server().getPlayerList().broadcastSystemMessage(Component.literal(announcement), false);
        });

        ServerTickEvents.END_SERVER_TICK.register(PersonalAuction::tickAuctions);
    }

    private static void tickAuctions(MinecraftServer server) {
        long now = System.currentTimeMillis();
        Iterator<Auction> iterator = AUCTIONS.iterator();
        while (iterator.hasNext()) {
            Auction auction = iterator.next();
            if (now >= auction.endAt) {
                ServerPlayer seller = server.getPlayerList().getPlayer(auction.seller);
                if (seller != null && !seller.getInventory().add(auction.item.copy())) {
                    seller.drop(auction.item.copy(), false);
                }
                server.getPlayerList().broadcastSystemMessage(
                        Component.literal("Auktion beendet: " + auction.item.getCount() + "x "
                                + auction.item.getHoverName().getString()
                                + " | Startpreis: " + auction.startPrice + " | Ergebnis: beendet."),
                        false
                );
                iterator.remove();
            }
        }
    }

    private record Auction(ItemStack item, int startPrice, long endAt, UUID seller) {}
}
