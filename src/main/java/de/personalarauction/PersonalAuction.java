package de.personalarauction;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.ScoreboardCriterion;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class PersonalAuction implements ModInitializer {
    public static final String MOD_ID = "personalauction";
    // Setze hier deine UUID ein. Leer = nur Konsole darf Admin-Aktionen ausführen.
    private static final Set<UUID> ADMINS = Set.of();
    private static final Map<Integer, Auction> AUCTIONS = new ConcurrentHashMap<>();
    private static int nextId = 1;
    private static final String MONEY_OBJECTIVE = "money";

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(literal("auction")
                .executes(ctx -> showAuctions(ctx.getSource()))
                .then(literal("create")
                    .then(argument("startpreis", IntegerArgumentType.integer(1))
                        .then(argument("dauerMinuten", IntegerArgumentType.integer(1, 10080))
                            .executes(ctx -> createAuction(
                                ctx.getSource(),
                                IntegerArgumentType.getInteger(ctx, "startpreis"),
                                IntegerArgumentType.getInteger(ctx, "dauerMinuten"))))))
                .then(literal("bid")
                    .then(argument("id", IntegerArgumentType.integer(1))
                        .then(argument("gebot", IntegerArgumentType.integer(1))
                            .executes(ctx -> bid(ctx.getSource(),
                                IntegerArgumentType.getInteger(ctx, "id"),
                                IntegerArgumentType.getInteger(ctx, "gebot"))))))
                .then(literal("admin").executes(ctx -> adminHelp(ctx.getSource())))
            );
        });
    }

    private static boolean isAdmin(ServerCommandSource source) {
        return source.getEntity() instanceof ServerPlayerEntity player && ADMINS.contains(player.getUuid());
    }

    private static int createAuction(ServerCommandSource source, int startPrice, int minutes) {
        if (!isAdmin(source)) {
            source.sendError(Text.literal("Nur der konfigurierte Admin darf Auktionen erstellen."));
            return 0;
        }
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendError(Text.literal("Diesen Befehl bitte im Spiel ausführen."));
            return 0;
        }
        ItemStack held = player.getMainHandStack();
        if (held.isEmpty()) {
            source.sendError(Text.literal("Halte das Item in der Haupthand, das du versteigern möchtest."));
            return 0;
        }
        ItemStack auctionItem = held.copy();
        auctionItem.setCount(held.getCount());
        held.decrement(held.getCount());

        long end = System.currentTimeMillis() + minutes * 60_000L;
        int id = nextId++;
        AUCTIONS.put(id, new Auction(id, auctionItem, startPrice, startPrice, end, player.getUuid(), null));
        source.sendFeedback(() -> Text.literal("Auktion #" + id + " erstellt: " + auctionItem.getCount()
            + "x " + auctionItem.getName().getString() + ", Startpreis " + startPrice
            + ", Dauer " + minutes + " Minuten."), true);
        return 1;
    }

    private static int bid(ServerCommandSource source, int id, int amount) {
        if (!(source.getEntity() instanceof ServerPlayerEntity bidder)) {
            source.sendError(Text.literal("Nur Spieler können mitbieten."));
            return 0;
        }
        Auction auction = AUCTIONS.get(id);
        if (auction == null) {
            source.sendError(Text.literal("Diese Auktion existiert nicht."));
            return 0;
        }
        if (System.currentTimeMillis() >= auction.endAt) {
            source.sendError(Text.literal("Diese Auktion ist bereits beendet."));
            announceWinner(source.getServer(), auction);
            return 0;
        }
        if (amount <= auction.highestBid) {
            source.sendError(Text.literal("Dein Gebot muss höher als " + auction.highestBid + " sein."));
            return 0;
        }
        if (!hasMoney(source.getServer(), bidder, amount)) {
            source.sendError(Text.literal("Du hast nicht genug Scoreboard-Geld (" + MONEY_OBJECTIVE + ")."));
            return 0;
        }
        // Abziehen des vorherigen Gebots und Abbuchen des neuen Gebots.
        if (auction.highestBidder != null) {
            addMoney(source.getServer(), auction.highestBidder, auction.highestBid);
        }
        addMoney(source.getServer(), bidder.getUuid(), -amount);
        auction.highestBid = amount;
        auction.highestBidder = bidder.getUuid();
        source.sendFeedback(() -> Text.literal("Gebot angenommen: " + amount + " für Auktion #" + id), false);
        return 1;
    }

    private static int showAuctions(ServerCommandSource source) {
        if (AUCTIONS.isEmpty()) {
            source.sendFeedback(() -> Text.literal("Aktuell gibt es keine Auktionen."), false);
            return 1;
        }
        for (Auction a : AUCTIONS.values()) {
            boolean ended = System.currentTimeMillis() >= a.endAt;
            String winner = a.highestBidder == null ? "Kein Gebot" :
                source.getServer().getUserCache().getByUuid(a.highestBidder)
                    .map(u -> u.getName()).orElse(a.highestBidder.toString());
            source.sendFeedback(() -> Text.literal("#" + a.id + " | " + a.stack.getCount() + "x "
                + a.stack.getName().getString() + " | " + a.highestBid + " $ | "
                + (ended ? "BEENDET – Gewinner: " + winner : "endet in "
                + Math.max(0, (a.endAt - System.currentTimeMillis()) / 60000) + " Min.")), false);
        }
        return 1;
    }

    private static int adminHelp(ServerCommandSource source) {
        if (!isAdmin(source)) {
            source.sendError(Text.literal("Keine Admin-Berechtigung."));
            return 0;
        }
        source.sendFeedback(() -> Text.literal("Halte ein Item und nutze /auction create <startpreis> <dauerMinuten>."), false);
        return 1;
    }

    private static boolean hasMoney(MinecraftServer server, ServerPlayerEntity player, int amount) {
        ScoreboardObjective objective = server.getScoreboard().getNullableObjective(MONEY_OBJECTIVE);
        if (objective == null) return false;
        return server.getScoreboard().getScore(player, objective).getScore() >= amount;
    }

    private static void addMoney(MinecraftServer server, UUID uuid, int amount) {
        ScoreboardObjective objective = server.getScoreboard().getNullableObjective(MONEY_OBJECTIVE);
        if (objective == null) return;
        server.getUserCache().getByUuid(uuid).ifPresent(user -> {
            var score = server.getScoreboard().getScore(user, objective);
            score.setScore(score.getScore() + amount);
        });
    }

    private static void announceWinner(MinecraftServer server, Auction auction) {
        if (auction.announced) return;
        auction.announced = true;
        String winner = auction.highestBidder == null ? "Niemand (kein Gebot)" :
            server.getUserCache().getByUuid(auction.highestBidder).map(u -> u.getName()).orElse("Unbekannt");
        server.getPlayerManager().broadcast(Text.literal("Auktion #" + auction.id
            + " beendet! Gewinner: " + winner + " | Endpreis: " + auction.highestBid + " $"), false);
    }

    private static final class Auction {
        final int id;
        final ItemStack stack;
        final int startPrice;
        int highestBid;
        final long endAt;
        final UUID seller;
        UUID highestBidder;
        boolean announced;

        Auction(int id, ItemStack stack, int startPrice, int highestBid, long endAt, UUID seller, UUID highestBidder) {
            this.id = id; this.stack = stack; this.startPrice = startPrice;
            this.highestBid = highestBid; this.endAt = endAt; this.seller = seller;
            this.highestBidder = highestBidder;
        }
    }
}
