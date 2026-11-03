package de.personalarauction;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

public class PersonalAuction implements ModInitializer {
    public static final String MOD_ID = "personalauction";

    private static Auction activeAuction;

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.serverboundPlay().register(AuctionPayloads.Request.TYPE, AuctionPayloads.Request.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(AuctionPayloads.Create.TYPE, AuctionPayloads.Create.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(AuctionPayloads.Sync.TYPE, AuctionPayloads.Sync.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(AuctionPayloads.Request.TYPE, (payload, context) ->
                context.player().getServer().execute(() -> sendState(context.player())));

        ServerPlayNetworking.registerGlobalReceiver(AuctionPayloads.Create.TYPE, (payload, context) ->
                context.player().getServer().execute(() -> createAuction(context.player(), payload)));

        ServerTickEvents.END_SERVER_TICK.register(PersonalAuction::tickAuction);
    }

    private static void createAuction(ServerPlayer player, AuctionPayloads.Create payload) {
        if (!player.hasPermissions(2)) {
            player.sendSystemMessage(Component.literal("Du brauchst Operator-Rechte, um eine Auktion zu erstellen."));
            return;
        }
        if (activeAuction != null && !activeAuction.ended) {
            player.sendSystemMessage(Component.literal("Es läuft bereits eine Auktion."));
            sendState(player);
            return;
        }
        if (payload.slot() < 0 || payload.slot() > 8
                || payload.startPrice() < 1 || payload.startPrice() > 1_000_000_000
                || payload.durationMinutes() < 1 || payload.durationMinutes() > 10080) {
            player.sendSystemMessage(Component.literal("Ungültige Auktionsdaten."));
            return;
        }

        ItemStack selected = player.getInventory().getItem(payload.slot());
        if (selected.isEmpty()) {
            player.sendSystemMessage(Component.literal("Der ausgewählte Hotbar-Slot ist leer."));
            return;
        }

        ItemStack auctionItem = selected.copy();
        player.getInventory().setItem(payload.slot(), ItemStack.EMPTY);

        long endAt = System.currentTimeMillis() + payload.durationMinutes() * 60_000L;
        activeAuction = new Auction(player.getUUID(), auctionItem.getHoverName().getString(),
                auctionItem.getCount(), payload.startPrice(), endAt);
        player.sendSystemMessage(Component.literal("Auktion gestartet: " + auctionItem.getCount() + "x "
                + auctionItem.getHoverName().getString() + " | Startpreis: " + payload.startPrice()));
        sendState(player);
    }

    private static void tickAuction(MinecraftServer server) {
        if (activeAuction == null || activeAuction.ended || System.currentTimeMillis() < activeAuction.endAtMillis) {
            return;
        }

        activeAuction.ended = true;
        activeAuction.result = "Beendet – keine Gebote (nur Anzeigeauktion).";
        ServerPlayer owner = server.getPlayerList().getPlayer(activeAuction.owner);
        if (owner != null) {
            owner.sendSystemMessage(Component.literal("Deine Auktion ist beendet: " + activeAuction.itemName));
            sendState(owner);
        }
    }

    private static void sendState(ServerPlayer player) {
        if (activeAuction == null) {
            ServerPlayNetworking.send(player, new AuctionPayloads.Sync(false, "", 0, 0, 0L, false, ""));
            return;
        }
        ServerPlayNetworking.send(player, new AuctionPayloads.Sync(true, activeAuction.itemName,
                activeAuction.itemCount, activeAuction.startPrice, activeAuction.endAtMillis,
                activeAuction.ended, activeAuction.result));
    }

    private static final class Auction {
        private final UUID owner;
        private final String itemName;
        private final int itemCount;
        private final int startPrice;
        private final long endAtMillis;
        private boolean ended;
        private String result = "";

        private Auction(UUID owner, String itemName, int itemCount, int startPrice, long endAtMillis) {
            this.owner = owner;
            this.itemName = itemName;
            this.itemCount = itemCount;
            this.startPrice = startPrice;
            this.endAtMillis = endAtMillis;
        }
    }
}
