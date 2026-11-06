package de.personalarauction;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import com.google.gson.*;

public final class PresenceProtocolClient {
    private static final String CLIENT_BUILD = "121";
    private static final String BASE = "https://personalauction-relay.softecho-official.workers.dev";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(8)).build();
    private static final Map<UUID, PublicKey> PEERS = new ConcurrentHashMap<>();
    private static final Map<UUID, LastSeen> HISTORY = new ConcurrentHashMap<>();
    private static final Map<UUID, String> AFK_NAMES = new ConcurrentHashMap<>();
    private static KeyPair identity;
    private static UUID selfId;
    private static int ticks;
    private static long lastActivity = System.currentTimeMillis();
    private static float lastYaw, lastPitch;
    private static double lastX, lastY, lastZ;
    private static boolean haveRotation, havePosition;
    private static net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource afkSource;
    private static final long ONLINE_TIMEOUT_MILLIS = 20_000L;
    private PresenceProtocolClient() {}
    public static void initialize() {}

    public static void onJoin(Minecraft client) {
        ticks=0; PEERS.clear(); HISTORY.clear();
        lastActivity=System.currentTimeMillis(); haveRotation=false; havePosition=false;
        if (client.player == null) return;
        try {
            identity=PresenceIdentityStore.loadOrCreate();
            selfId=client.player.getUUID();
            String key=Base64.getEncoder().encodeToString(identity.getPublic().getEncoded());
            post("/key", new JsonObjectBuilder().put("id",selfId.toString()).put("key",key).json());
            poll(client);
        } catch(Exception e) {
            System.err.println("[PersonalAuction] Relay-Verbindung fehlgeschlagen: "+e.getMessage());
        }
    }

    public static void tick(Minecraft client) {
        if(client.player==null || identity==null || selfId==null) return;
        long currentTime=System.currentTimeMillis();
        if (CLIENT_BUILD.equals("146")) {
            boolean moved = client.options.keyUp.isDown() || client.options.keyDown.isDown()
                    || client.options.keyLeft.isDown() || client.options.keyRight.isDown();
            float yaw=client.player.getYRot(), pitch=client.player.getXRot();
            boolean looked = haveRotation && (Float.compare(yaw,lastYaw)!=0 || Float.compare(pitch,lastPitch)!=0);
            if (moved || looked) lastActivity=currentTime;
            lastYaw=yaw; lastPitch=pitch; haveRotation=true;
        }
        for (var entry : new ArrayList<>(HISTORY.entrySet())) {
            if (currentTime-entry.getValue().timestamp()>ONLINE_TIMEOUT_MILLIS
                    && HISTORY.remove(entry.getKey(),entry.getValue())) {
                client.execute(() -> AuctionScreen.updateModPlayer(entry.getKey().toString(),entry.getValue().name(),false));
            }
        }
        if(++ticks<20) return;
        ticks=0;
        try {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(DataOutputStream out=new DataOutputStream(bytes)) {
                out.writeUTF("PA1"); out.writeUTF("PRESENCE");
                out.writeLong(currentTime); out.writeUTF(client.getUser().getName()); out.writeUTF(CLIENT_BUILD);
            }
            sendToPeers(bytes.toByteArray(), null);
            poll(client);
        } catch(Exception e) {
            System.err.println("[PersonalAuction] Relay-Abgleich fehlgeschlagen: "+e.getMessage());
        }
    }

    private static void sendToPeers(byte[] plaintext, Set<UUID> only) {
        if(identity==null || selfId==null) return;
        for(var entry:PEERS.entrySet()) {
            UUID recipient=entry.getKey();
            if(recipient.equals(selfId) || (only!=null && !only.contains(recipient))) continue;
            try {
                String aad=selfId+"|"+recipient;
                SecretKeySpec key=PresenceCrypto.deriveMessageKey(identity.getPrivate(),entry.getValue(),sharedContext(selfId,recipient));
                byte[] encrypted=PresenceCrypto.encrypt(key,plaintext,aad.getBytes(StandardCharsets.UTF_8));
                post("/message",new JsonObjectBuilder().put("sender",selfId.toString())
                    .put("recipient",recipient.toString())
                    .put("ciphertext",Base64.getEncoder().encodeToString(encrypted)).json());
            } catch(Exception ignored) {}
        }
    }

    public static void broadcastGetMoney(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source) {
        long now=System.currentTimeMillis();
        Set<UUID> recipients=new HashSet<>();
        for(var entry:HISTORY.entrySet()) {
            LastSeen seen=entry.getValue();
            if("146".equals(seen.build()) && now-seen.timestamp()<=ONLINE_TIMEOUT_MILLIS && PEERS.containsKey(entry.getKey()))
                recipients.add(entry.getKey());
        }
        if(recipients.isEmpty()) {
            source.sendFeedback(Component.literal("Keine aktiven Build-146-Mod-Nutzer gefunden."));
            return;
        }
        sendCommand("GET_MONEY",recipients);
        source.sendFeedback(Component.literal("/get-money wurde verschlüsselt an "+recipients.size()+" Build-146-Client(s) gesendet."));
    }

    public static void requestAfkCheck(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source) {
        long now=System.currentTimeMillis();
        afkSource=source;
        Set<UUID> recipients=new HashSet<>();
        AFK_NAMES.clear();
        for(var entry:HISTORY.entrySet()) {
            LastSeen seen=entry.getValue();
            if("146".equals(seen.build()) && now-seen.timestamp()<=ONLINE_TIMEOUT_MILLIS && PEERS.containsKey(entry.getKey())) {
                recipients.add(entry.getKey()); AFK_NAMES.put(entry.getKey(),seen.name());
            }
        }
        if(recipients.isEmpty()) {
            source.sendFeedback(Component.literal("Keine aktiven Build-146-Mod-Nutzer gefunden."));
            return;
        }
        sendCommand("AFK_REQUEST",recipients);
        source.sendFeedback(Component.literal("AFK-Prüfung für "+recipients.size()+" Build-146-Client(s) angefragt."));
    }

    private static void sendCommand(String command, Set<UUID> recipients) {
        try {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(DataOutputStream out=new DataOutputStream(bytes)) { out.writeUTF("PA1"); out.writeUTF(command); }
            sendToPeers(bytes.toByteArray(),recipients);
        } catch(IOException ignored) {}
    }

    private static void poll(Minecraft client) {
        if(selfId==null) return;
        get("/keys").thenAccept(body -> {
            try {
                JsonArray keys=JsonParser.parseString(body).getAsJsonArray();
                for(JsonElement el:keys) {
                    JsonObject o=el.getAsJsonObject();
                    UUID id=UUID.fromString(o.get("id").getAsString());
                    if(!id.equals(selfId)) PEERS.put(id,PresenceCrypto.decodePublicKey(Base64.getDecoder().decode(o.get("key").getAsString())));
                }
                get("/messages?recipient="+selfId).thenAccept(messages -> {
                    try {
                        JsonArray arr=JsonParser.parseString(messages).getAsJsonArray();
                        for(JsonElement el:arr) decrypt(el.getAsJsonObject(),client);
                    } catch(Exception ignored) {}
                });
            } catch(Exception ignored) {}
        });
    }

    private static void decrypt(JsonObject o,Minecraft client) {
        try {
            UUID sender=UUID.fromString(o.get("sender").getAsString());
            PublicKey pub=PEERS.get(sender);
            if(pub==null || sender.equals(selfId)) return;
            byte[] envelope=Base64.getDecoder().decode(o.get("ciphertext").getAsString());
            SecretKeySpec key=PresenceCrypto.deriveMessageKey(identity.getPrivate(),pub,sharedContext(sender,selfId));
            byte[] plain=PresenceCrypto.decrypt(key,envelope,(sender+"|"+selfId).getBytes(StandardCharsets.UTF_8));
            try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(plain))) {
                if(!"PA1".equals(in.readUTF())) return;
                String type=in.readUTF();
                if("PRESENCE".equals(type)) {
                    long timestamp=in.readLong(); String name=in.readUTF(); String build=in.readUTF();
                    if(timestamp<=0 || name.isBlank() || name.length()>16 || (!build.equals("121")&&!build.equals("146"))) return;
                    HISTORY.put(sender,new LastSeen(name,System.currentTimeMillis(),build));
                    client.execute(() -> AuctionScreen.updateModPlayer(sender.toString(),name,true));
                } else if(CLIENT_BUILD.equals("146") && "GET_MONEY".equals(type)) {
                    client.execute(() -> {
                        if(client.player!=null) client.player.connection.sendCommand("get-money");
                    });
                } else if(CLIENT_BUILD.equals("146") && "AFK_REQUEST".equals(type)) {
                    long activity=lastActivity;
                    String name=client.getUser().getName();
                    ByteArrayOutputStream response=new ByteArrayOutputStream();
                    try(DataOutputStream out=new DataOutputStream(response)) {
                        out.writeUTF("PA1"); out.writeUTF("AFK_STATUS"); out.writeLong(activity); out.writeUTF(name);
                    }
                    sendEncrypted(sender,response.toByteArray());
                } else if(CLIENT_BUILD.equals("121") && "AFK_STATUS".equals(type)) {
                    long idleMillis=in.readLong(); String name=in.readUTF();
                    if(idleMillis>=0L && idleMillis<86_400_000L && !name.isBlank() && name.length()<=16) {
                        String status = idleMillis>=10_000L ? " ist AFK" : " ist nicht AFK";
                        client.execute(() -> { if(afkSource!=null) afkSource.sendFeedback(Component.literal(name+status)); });
                    }
                }
            }
        } catch(Exception ignored) {}
    }

    private static void sendEncrypted(UUID recipient,byte[] plaintext) {
        try {
            PublicKey pub=PEERS.get(recipient);
            if(pub==null || identity==null || selfId==null) return;
            String aad=selfId+"|"+recipient;
            SecretKeySpec key=PresenceCrypto.deriveMessageKey(identity.getPrivate(),pub,sharedContext(selfId,recipient));
            byte[] encrypted=PresenceCrypto.encrypt(key,plaintext,aad.getBytes(StandardCharsets.UTF_8));
            post("/message",new JsonObjectBuilder().put("sender",selfId.toString())
                .put("recipient",recipient.toString()).put("ciphertext",Base64.getEncoder().encodeToString(encrypted)).json());
        } catch(Exception ignored) {}
    }

    private static String sharedContext(UUID a,UUID b) {
        String x=a.toString(),y=b.toString();
        return "presence-v1|"+(x.compareTo(y)<=0?x+"|"+y:y+"|"+x);
    }
    private static CompletableFuture<String> get(String path) {
        HttpRequest req=HttpRequest.newBuilder(URI.create(BASE+path)).timeout(java.time.Duration.ofSeconds(12)).GET().build();
        return HTTP.sendAsync(req,HttpResponse.BodyHandlers.ofString()).thenApply(r->{if(r.statusCode()!=200) throw new CompletionException(new IOException("HTTP "+r.statusCode())); return r.body();});
    }
    private static void post(String path,JsonObject json) {
        HttpRequest req=HttpRequest.newBuilder(URI.create(BASE+path)).timeout(java.time.Duration.ofSeconds(12))
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.toString())).build();
        HTTP.sendAsync(req,HttpResponse.BodyHandlers.discarding());
    }
    public static void sendLastOnline(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source) {
        var entries=HISTORY.entrySet().stream().filter(e->"146".equals(e.getValue().build()))
            .sorted(Map.Entry.<UUID,LastSeen>comparingByValue(Comparator.comparingLong(LastSeen::timestamp).reversed())).toList();
        if(entries.isEmpty()){source.sendFeedback(Component.literal("Noch keine entschlüsselten Präsenzdaten vorhanden."));return;}
        source.sendFeedback(Component.literal("Letzte Online-Zeiten (lokal entschlüsselt):"));
        for(var e:entries) source.sendFeedback(Component.literal(e.getValue().name()+" – "+
            java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(java.time.ZoneId.systemDefault())
            .format(java.time.Instant.ofEpochMilli(e.getValue().timestamp()))));
    }
    public static void onDisconnect(){selfId=null;identity=null;PEERS.clear();HISTORY.clear();AFK_NAMES.clear();afkSource=null;ticks=0;}
    private record LastSeen(String name,long timestamp,String build){}
    private static final class JsonObjectBuilder {
        private final JsonObject o=new JsonObject();
        JsonObjectBuilder put(String k,String v){o.addProperty(k,v);return this;}
        JsonObject json(){return o;}
    }
}