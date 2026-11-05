package de.personalarauction;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * Opaque presence relay. Player names and timestamps are never sent to or
 * stored by this server-side component. It only sees UUID routing metadata,
 * public keys and AES-GCM ciphertext.
 */
public final class PresenceRelay implements ModInitializer {
    private static final int MAX_IDENTITIES = 512;
    private static final int MAX_HISTORY_PER_RECIPIENT = 512;
    private static final Map<UUID, String> PUBLIC_KEYS = new HashMap<>();
    // recipient -> sender -> opaque encrypted latest-seen record
    private static final Map<UUID, Map<UUID, String>> HISTORY = new HashMap<>();
    private static Path saveFile;

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.serverboundPlay().register(PresenceKeyPayload.TYPE, PresenceKeyPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(PresenceKeyPayload.TYPE, PresenceKeyPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(PresenceMessagePayload.TYPE, PresenceMessagePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(PresenceMessagePayload.TYPE, PresenceMessagePayload.CODEC);

        // Legacy payload is accepted for wire compatibility but is deliberately ignored.
        PayloadTypeRegistry.serverboundPlay().register(PlayerPresencePayload.TYPE, PlayerPresencePayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(PresenceKeyPayload.TYPE, (payload, context) -> {
            ServerPlayer sender = context.player();
            UUID senderId = sender.getUUID();
            if (!senderId.toString().equals(payload.playerId()) || payload.publicKey().length() > 512) return;
            try {
                byte[] encoded = Base64.getDecoder().decode(payload.publicKey());
                PresenceCrypto.decodePublicKey(encoded);
            } catch (Exception invalidKey) {
                return;
            }

            String previous = PUBLIC_KEYS.put(senderId, payload.publicKey());
            if (PUBLIC_KEYS.size() > MAX_IDENTITIES && previous == null) {
                PUBLIC_KEYS.remove(senderId);
                return;
            }

            // Give this client the public-key directory, then its encrypted backlog.
            for (Map.Entry<UUID, String> entry : PUBLIC_KEYS.entrySet()) {
                sendKey(sender, entry.getKey(), entry.getValue());
            }
            for (Map.Entry<UUID, String> entry : HISTORY.getOrDefault(senderId, Map.of()).entrySet()) {
                sendMessage(sender, entry.getKey(), senderId, entry.getValue());
            }

            if (!payload.publicKey().equals(previous)) {
                for (ServerPlayer target : context.server().getPlayerList().getPlayers()) {
                    if (!target.getUUID().equals(senderId)) sendKey(target, senderId, payload.publicKey());
                }
            }
            saveState();
        });

        ServerPlayNetworking.registerGlobalReceiver(PresenceMessagePayload.TYPE, (payload, context) -> {
            UUID senderId = context.player().getUUID();
            if (!senderId.toString().equals(payload.senderId())) return;
            if (payload.ciphertext().length() > 22_000) return;
            UUID recipientId;
            try {
                recipientId = UUID.fromString(payload.recipientId());
            } catch (IllegalArgumentException invalidId) {
                return;
            }
            if (recipientId.equals(senderId) || !PUBLIC_KEYS.containsKey(recipientId)) return;

            Map<UUID, String> recipientHistory =
                    HISTORY.computeIfAbsent(recipientId, ignored -> new HashMap<>());
            recipientHistory.put(senderId, payload.ciphertext());
            if (recipientHistory.size() > MAX_HISTORY_PER_RECIPIENT) {
                UUID oldestArbitrary = recipientHistory.keySet().iterator().next();
                recipientHistory.remove(oldestArbitrary);
            }

            ServerPlayer recipient = context.server().getPlayerList().getPlayer(recipientId);
            if (recipient != null) sendMessage(recipient, senderId, recipientId, payload.ciphertext());
            saveState();
        });

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            synchronized (PresenceRelay.class) {
                PUBLIC_KEYS.clear();
                HISTORY.clear();
                saveFile = server.getWorldPath(LevelResource.ROOT).resolve("personalauction-presence.properties");
                loadState();
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            synchronized (PresenceRelay.class) { saveState(); }
        });
    }

    private static void sendKey(ServerPlayer target, UUID playerId, String publicKey) {
        if (ServerPlayNetworking.canSend(target, PresenceKeyPayload.TYPE)) {
            ServerPlayNetworking.send(target, new PresenceKeyPayload(playerId.toString(), publicKey));
        }
    }

    private static void sendMessage(ServerPlayer target, UUID senderId, UUID recipientId, String ciphertext) {
        if (ServerPlayNetworking.canSend(target, PresenceMessagePayload.TYPE)) {
            ServerPlayNetworking.send(target, new PresenceMessagePayload(
                    senderId.toString(), recipientId.toString(), ciphertext));
        }
    }

    private static void loadState() {
        if (saveFile == null || !Files.exists(saveFile)) return;
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(saveFile)) {
            properties.load(input);
            for (String key : properties.stringPropertyNames()) {
                try {
                    if (key.startsWith("key.")) {
                        UUID id = UUID.fromString(key.substring(4));
                        String value = properties.getProperty(key);
                        PresenceCrypto.decodePublicKey(Base64.getDecoder().decode(value));
                        PUBLIC_KEYS.put(id, value);
                    } else if (key.startsWith("history.")) {
                        String[] parts = key.substring(8).split("\\.", 2);
                        if (parts.length != 2) continue;
                        UUID recipient = UUID.fromString(parts[0]);
                        UUID sender = UUID.fromString(parts[1]);
                        String value = properties.getProperty(key);
                        if (value.length() <= 22_000) {
                            HISTORY.computeIfAbsent(recipient, ignored -> new HashMap<>()).put(sender, value);
                        }
                    }
                } catch (Exception ignored) {
                    // Skip malformed individual records; do not discard valid entries.
                }
            }
        } catch (IOException exception) {
            System.err.println("[PersonalAuction] Encrypted presence cache could not be loaded: " + exception.getMessage());
        }
    }

    private static void saveState() {
        if (saveFile == null) return;
        Properties properties = new Properties();
        PUBLIC_KEYS.forEach((id, key) -> properties.setProperty("key." + id, key));
        HISTORY.forEach((recipient, entries) -> entries.forEach((sender, value) ->
                properties.setProperty("history." + recipient + "." + sender, value)));
        Path temporary = saveFile.resolveSibling(saveFile.getFileName() + ".tmp");
        try {
            Files.createDirectories(saveFile.getParent());
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, "PersonalAuction opaque encrypted presence records; no player names or timestamps");
            }
            try {
                Files.move(temporary, saveFile, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, saveFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            System.err.println("[PersonalAuction] Encrypted presence cache could not be saved: " + exception.getMessage());
            try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
    }
}
