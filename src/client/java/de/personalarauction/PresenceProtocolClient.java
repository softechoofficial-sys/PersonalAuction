package de.personalarauction;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Client-only encrypted presence exchange and locally decrypted last-seen cache. */
public final class PresenceProtocolClient {
    private static final String CLIENT_BUILD = "121";
    private static final Map<UUID, PublicKey> PEERS = new HashMap<>();
    private static final Map<UUID, LastSeen> HISTORY = new HashMap<>();
    private static final Map<UUID, Long> LAST_PACKET_RECEIVED = new HashMap<>();
    private static final Map<String, PresenceMessagePayload> PENDING = new HashMap<>();
    private static KeyPair identity;
    private static UUID selfId;
    private static int heartbeatTicks;

    private PresenceProtocolClient() {}

    public static void initialize() {
        ClientPlayNetworking.registerGlobalReceiver(PresenceKeyPayload.TYPE, (payload, context) ->
                context.client().execute(() -> acceptPublicKey(payload)));
        ClientPlayNetworking.registerGlobalReceiver(PresenceMessagePayload.TYPE, (payload, context) ->
                context.client().execute(() -> acceptEncryptedMessage(payload)));
    }

    public static void onJoin(Minecraft client) {
        heartbeatTicks = 0;
        PEERS.clear();
        HISTORY.clear();
        LAST_PACKET_RECEIVED.clear();
        PENDING.clear();
        if (client.player == null || !ClientPlayNetworking.canSend(PresenceKeyPayload.TYPE)) return;
        try {
            identity = PresenceIdentityStore.loadOrCreate();
            selfId = client.player.getUUID();
            String encoded = Base64.getEncoder().encodeToString(identity.getPublic().getEncoded());
            ClientPlayNetworking.send(new PresenceKeyPayload(selfId.toString(), encoded));
        } catch (Exception exception) {
            System.err.println("[PersonalAuction] Präsenz-Schlüssel konnte nicht geladen werden: "
                    + exception.getMessage());
        }
    }

    public static void tick(Minecraft client) {
        if (client.player == null || identity == null || selfId == null) return;
        if (++heartbeatTicks < 100) {
            expireStalePlayers();
            return;
        }
        heartbeatTicks = 0;
        long now = System.currentTimeMillis();
        byte[] plaintext;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeLong(now);
                output.writeUTF(client.getUser().getName());
                output.writeUTF(CLIENT_BUILD);
            }
            plaintext = bytes.toByteArray();
        } catch (Exception impossible) {
            return;
        }

        for (Map.Entry<UUID, PublicKey> peer : PEERS.entrySet()) {
            UUID recipient = peer.getKey();
            if (recipient.equals(selfId)) continue;
            try {
                String aadText = selfId + "|" + recipient;
                SecretKeySpec key = PresenceCrypto.deriveMessageKey(
                        identity.getPrivate(), peer.getValue(), sharedContext(selfId, recipient));
                byte[] encrypted = PresenceCrypto.encrypt(key, plaintext, aadText.getBytes(StandardCharsets.UTF_8));
                ClientPlayNetworking.send(new PresenceMessagePayload(selfId.toString(), recipient.toString(),
                        Base64.getEncoder().encodeToString(encrypted)));
            } catch (Exception ignored) {
                // A bad/stale peer key must not stop heartbeats to other peers.
            }
        }
        expireStalePlayers();
    }

    private static void acceptPublicKey(PresenceKeyPayload payload) {
        if (selfId == null || identity == null) return;
        try {
            UUID id = UUID.fromString(payload.playerId());
            if (id.equals(selfId) || payload.publicKey().length() > 512) return;
            PublicKey key = PresenceCrypto.decodePublicKey(Base64.getDecoder().decode(payload.publicKey()));
            PEERS.put(id, key);
            retryPending();
        } catch (Exception ignored) {
            // Ignore malformed directory entries.
        }
    }

    private static void acceptEncryptedMessage(PresenceMessagePayload payload) {
        if (selfId == null || !selfId.toString().equals(payload.recipientId())
                || payload.ciphertext().length() > 22_000) return;
        try {
            UUID sender = UUID.fromString(payload.senderId());
            if (sender.equals(selfId)) return;
            PENDING.put(sender.toString(), payload);
            decryptMessage(payload);
        } catch (IllegalArgumentException ignored) {
            // Ignore invalid routing identifiers.
        }
    }

    private static void retryPending() {
        for (PresenceMessagePayload payload : PENDING.values().toArray(PresenceMessagePayload[]::new)) {
            decryptMessage(payload);
        }
    }

    private static void decryptMessage(PresenceMessagePayload payload) {
        if (identity == null || selfId == null) return;
        try {
            UUID sender = UUID.fromString(payload.senderId());
            PublicKey senderKey = PEERS.get(sender);
            if (senderKey == null) return;
            byte[] envelope = Base64.getDecoder().decode(payload.ciphertext());
            String aadText = sender + "|" + selfId;
            SecretKeySpec key = PresenceCrypto.deriveMessageKey(
                    identity.getPrivate(), senderKey, sharedContext(sender, selfId));
            byte[] plaintext = PresenceCrypto.decrypt(key, envelope, aadText.getBytes(StandardCharsets.UTF_8));
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(plaintext))) {
                long timestamp = input.readLong();
                String name = input.readUTF();
                String build = input.readUTF();
                if (timestamp <= 0 || name.isBlank() || name.length() > 16
                        || (!"121".equals(build) && !"146".equals(build)) || input.available() != 0) return;
                HISTORY.put(sender, new LastSeen(name, timestamp, build));
                LAST_PACKET_RECEIVED.put(sender, System.currentTimeMillis());
                PENDING.remove(sender.toString());
                AuctionScreen.updateModPlayer(sender.toString(), name, true);
            }
        } catch (Exception ignored) {
            // Authentication failure, malformed record, or key mismatch: never display it.
        }
    }

    private static String sharedContext(UUID first, UUID second) {
        String a = first.toString();
        String b = second.toString();
        return "presence-v1|" + (a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a);
    }

    private static void expireStalePlayers() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Long> entry : LAST_PACKET_RECEIVED.entrySet()) {
            if (now - entry.getValue() > 15_000L) {
                LastSeen seen = HISTORY.get(entry.getKey());
                if (seen != null) AuctionScreen.updateModPlayer(entry.getKey().toString(), seen.name(), false);
            }
        }
    }

    public static void sendLastOnline(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source) {
        if (HISTORY.isEmpty()) {
            source.sendFeedback(Component.literal("Noch keine entschlüsselten Präsenzdaten vorhanden."));
            return;
        }
        source.sendFeedback(Component.literal("Letzte Online-Zeiten (nur lokal entschlüsselt):"));
        HISTORY.entrySet().stream()
                .filter(entry -> "146".equals(entry.getValue().build()))
                .sorted(Map.Entry.<UUID, LastSeen>comparingByValue(Comparator.comparingLong(LastSeen::timestamp).reversed()))
                .forEach(entry -> source.sendFeedback(Component.literal(
                        entry.getValue().name() + " – " + java.time.format.DateTimeFormatter
                                .ofPattern("dd.MM.yyyy HH:mm:ss")
                                .withZone(java.time.ZoneId.systemDefault())
                                .format(java.time.Instant.ofEpochMilli(entry.getValue().timestamp())))));
    }

    public static void onDisconnect() {
        identity = null;
        selfId = null;
        PEERS.clear();
        HISTORY.clear();
        LAST_PACKET_RECEIVED.clear();
        PENDING.clear();
        heartbeatTicks = 0;
    }

    private record LastSeen(String name, long timestamp, String build) {}
}
