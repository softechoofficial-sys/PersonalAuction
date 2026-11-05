package de.personalarauction;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public final class PresenceRelay implements ModInitializer {
    private static final long PLAYER_TIMEOUT_MS = 15_000L;
    private static final Map<String, Presence> PLAYERS = new HashMap<>();

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.serverboundPlay().register(PlayerPresencePayload.TYPE, PlayerPresencePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(PlayerPresencePayload.TYPE, PlayerPresencePayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(PlayerPresencePayload.TYPE, (payload, context) -> {
            ServerPlayer sender = context.player();
            String playerId = sender.getUUID().toString();

            // Der Server vertraut weder einer vom Client behaupteten UUID noch dessen Namen.
            if (!playerId.equals(payload.playerId())) return;

            long now = System.currentTimeMillis();
            boolean newlyDetected = !PLAYERS.containsKey(playerId);
            String verifiedName = sender.getName().getString();
            PLAYERS.put(playerId, new Presence(verifiedName, now));

            // Vollständige Momentaufnahme an den gerade erkannten Mod-Client senden.
            for (Map.Entry<String, Presence> entry : PLAYERS.entrySet()) {
                send(sender, new PlayerPresencePayload(entry.getKey(), entry.getValue().name(), true));
            }

            // Neue Mod-Nutzer nur einmal an die übrigen kompatiblen Clients melden.
            if (newlyDetected) {
                broadcast(context.server(),
                        new PlayerPresencePayload(playerId, verifiedName, true), playerId);
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(PresenceRelay::removeTimedOutPlayers);
    }

    private static void removeTimedOutPlayers(MinecraftServer server) {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Presence>> iterator = PLAYERS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Presence> entry = iterator.next();
            if (now - entry.getValue().lastSeen() > PLAYER_TIMEOUT_MS) {
                String playerId = entry.getKey();
                iterator.remove();
                broadcast(server, new PlayerPresencePayload(playerId, entry.getValue().name(), false), null);
            }
        }
    }

    private static void broadcast(MinecraftServer server, PlayerPresencePayload payload, String excludedPlayerId) {
        for (ServerPlayer target : server.getPlayerList().getPlayers()) {
            if (excludedPlayerId != null && excludedPlayerId.equals(target.getUUID().toString())) continue;
            send(target, payload);
        }
    }

    private static void send(ServerPlayer target, PlayerPresencePayload payload) {
        if (ServerPlayNetworking.canSend(target, PlayerPresencePayload.TYPE)) {
            ServerPlayNetworking.send(target, payload);
        }
    }

    private record Presence(String name, long lastSeen) {}
}
