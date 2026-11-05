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
    private static final String CLIENT_BUILD = "146";
    private static final String BASE = "https://personalauction-relay.softecho-official.workers.dev";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(8)).build();
    private static final Map<UUID, PublicKey> PEERS = new ConcurrentHashMap<>();
    private static final Map<UUID, LastSeen> HISTORY = new ConcurrentHashMap<>();
    private static KeyPair identity;
    private static UUID selfId;
    private static int ticks;
    private PresenceProtocolClient() {}
    public static void initialize() {}

    public static void onJoin(Minecraft client) {
        ticks=0; PEERS.clear();
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
        if(++ticks<100) return;
        ticks=0;
        try {
            long now=System.currentTimeMillis();
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(DataOutputStream out=new DataOutputStream(bytes)) {
                out.writeLong(now); out.writeUTF(client.getUser().getName()); out.writeUTF(CLIENT_BUILD);
            }
            JsonArray peers=new JsonArray();
            for(var entry:PEERS.entrySet()) {
                UUID recipient=entry.getKey();
                try {
                    String aad=selfId+"|"+recipient;
                    SecretKeySpec key=PresenceCrypto.deriveMessageKey(identity.getPrivate(),entry.getValue(),sharedContext(selfId,recipient));
                    byte[] encrypted=PresenceCrypto.encrypt(key,bytes.toByteArray(),aad.getBytes(StandardCharsets.UTF_8));
                    JsonObject item=new JsonObject();
                    item.addProperty("recipient",recipient.toString());
                    item.addProperty("ciphertext",Base64.getEncoder().encodeToString(encrypted));
                    peers.add(item);
                } catch(Exception ignored) {}
            }
            for(JsonElement element:peers) {
                JsonObject item=element.getAsJsonObject();
                post("/message",new JsonObjectBuilder().put("sender",selfId.toString())
                    .put("recipient",item.get("recipient").getAsString())
                    .put("ciphertext",item.get("ciphertext").getAsString()).json());
            }
            poll(client);
        } catch(Exception e) {
            System.err.println("[PersonalAuction] Relay-Abgleich fehlgeschlagen: "+e.getMessage());
        }
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
                long timestamp=in.readLong(); String name=in.readUTF(); String build=in.readUTF();
                if(timestamp<=0 || name.isBlank() || name.length()>16 || (!build.equals("121")&&!build.equals("146"))) return;
                LastSeen seen=new LastSeen(name,timestamp,build);
                HISTORY.put(sender,seen);
            }
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
    public static void onDisconnect(){selfId=null;identity=null;PEERS.clear();ticks=0;}
    private record LastSeen(String name,long timestamp,String build){}
    private static final class JsonObjectBuilder {
        private final JsonObject o=new JsonObject();
        JsonObjectBuilder put(String k,String v){o.addProperty(k,v);return this;}
        JsonObject json(){return o;}
    }
}