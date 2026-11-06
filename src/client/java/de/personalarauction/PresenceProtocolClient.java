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
import net.fabricmc.loader.api.FabricLoader;
import java.nio.file.Files;
import java.nio.file.Path;

public final class PresenceProtocolClient {
    private static final String CLIENT_BUILD = "121";
    private static final String BASE = "https://personalauction-relay.softecho-official.workers.dev";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(8)).build();
    private static final Map<UUID, PublicKey> PEERS = new ConcurrentHashMap<>();
    private static final Map<UUID, LastSeen> HISTORY = new ConcurrentHashMap<>();
    private static final Set<UUID> ACTIVE = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Long> LAST_RECEIVED = new ConcurrentHashMap<>();
    private static final Map<UUID, String> PEER_SERVERS = new ConcurrentHashMap<>();
    private static final Set<UUID> AFK_PENDING = ConcurrentHashMap.newKeySet();
    private static volatile long afkDeadline;
    private static final Map<UUID, String> AFK_NAMES = new ConcurrentHashMap<>();
    private static KeyPair identity;
    private static UUID selfId;
    private static int pollTicks;
    private static int presenceTicks;
    private static long lastActivity = System.currentTimeMillis();
    private static float lastYaw, lastPitch;
    private static double lastX, lastY, lastZ;
    private static boolean haveRotation, havePosition;
    private static net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource afkSource;
    private static final long ONLINE_TIMEOUT_MILLIS = 75_000L;
    private static final Path LAST_ONLINE_FILE = FabricLoader.getInstance().getConfigDir().resolve("personalauction-last-online.properties");
    private static long lastHistorySave;
    private PresenceProtocolClient() {}
    public static void initialize() { loadHistory(); }

    private static synchronized void loadHistory() {
        if (!Files.exists(LAST_ONLINE_FILE)) return;
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(LAST_ONLINE_FILE)) {
            properties.load(input);
            for (String key : properties.stringPropertyNames()) {
                String[] parts = properties.getProperty(key, "").split("\\|", 3);
                if (parts.length != 3) continue;
                UUID id = UUID.fromString(key);
                long timestamp = Long.parseLong(parts[1]);
                if (timestamp > 0 && !parts[0].isBlank() && parts[0].length() <= 16)
                    HISTORY.put(id, new LastSeen(parts[0], timestamp, parts[2]));
            }
        } catch (Exception e) {
            System.err.println("[PersonalAuction] Letzte Online-Zeiten konnten nicht geladen werden: " + e.getMessage());
        }
    }

    private static synchronized void saveHistory(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastHistorySave < 5_000L) return;
        Properties properties = new Properties();
        for (var entry : HISTORY.entrySet()) {
            LastSeen seen = entry.getValue();
            properties.setProperty(entry.getKey().toString(), seen.name() + "|" + seen.timestamp() + "|" + seen.build());
        }
        try {
            Files.createDirectories(LAST_ONLINE_FILE.getParent());
            try (OutputStream output = Files.newOutputStream(LAST_ONLINE_FILE)) {
                properties.store(output, "PersonalAuction last online history");
            }
            lastHistorySave = now;
        } catch (IOException e) {
            System.err.println("[PersonalAuction] Letzte Online-Zeiten konnten nicht gespeichert werden: " + e.getMessage());
        }
    }

    public static void onJoin(Minecraft client) {
        pollTicks=0; presenceTicks=0; PEERS.clear(); ACTIVE.clear(); LAST_RECEIVED.clear(); PEER_SERVERS.clear();
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
        if (afkDeadline>0 && currentTime>=afkDeadline) {
            for (UUID id : new HashSet<>(AFK_PENDING)) {
                String pendingName=AFK_NAMES.getOrDefault(id,"Build-146-Spieler");
                client.execute(() -> { if (afkSource!=null) afkSource.sendFeedback(Component.literal(pendingName+" – keine AFK-Rückmeldung")); });
            }
            AFK_PENDING.clear(); afkDeadline=0;
        }
        if (CLIENT_BUILD.equals("146")) {
            boolean moved = client.options.keyUp.isDown() || client.options.keyDown.isDown()
                    || client.options.keyLeft.isDown() || client.options.keyRight.isDown();
            float yaw=client.player.getYRot(), pitch=client.player.getXRot();
            boolean looked = haveRotation && (Float.compare(yaw,lastYaw)!=0 || Float.compare(pitch,lastPitch)!=0);
            if (moved || looked) lastActivity=currentTime;
            lastYaw=yaw; lastPitch=pitch; haveRotation=true;
        }
        for (var entry : new ArrayList<>(LAST_RECEIVED.entrySet())) {
            if (currentTime-entry.getValue()>ONLINE_TIMEOUT_MILLIS
                    && LAST_RECEIVED.remove(entry.getKey(),entry.getValue())
                    && ACTIVE.remove(entry.getKey())) {
                LastSeen seen=HISTORY.get(entry.getKey());
                if(seen!=null) client.execute(() -> AuctionScreen.updateModPlayer(entry.getKey().toString(),seen.name(),false));
            }
        }
        if (++pollTicks >= 100) {
            pollTicks=0;
            poll(client);
        }
        if (++presenceTicks >= 600) {
            presenceTicks=0;
            try {
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                try(DataOutputStream out=new DataOutputStream(bytes)) {
                    out.writeUTF("PA1"); out.writeUTF("PRESENCE");
                    out.writeLong(currentTime); out.writeUTF(client.getUser().getName()); out.writeUTF(CLIENT_BUILD); out.writeUTF(currentServerId(client));
                }
                sendToPeers(bytes.toByteArray(), null);
            } catch(Exception e) {
                System.err.println("[PersonalAuction] Präsenzmeldung fehlgeschlagen: "+e.getMessage());
            }
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
            if("146".equals(seen.build()) && ACTIVE.contains(entry.getKey()) && PEERS.containsKey(entry.getKey()))
                recipients.add(entry.getKey());
        }
        if(recipients.isEmpty()) {
            source.sendFeedback(Component.literal("No Players Online"));
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
            if("146".equals(seen.build()) && ACTIVE.contains(entry.getKey()) && PEERS.containsKey(entry.getKey())
                    && !currentServerId(Minecraft.getInstance()).isBlank()
                    && currentServerId(Minecraft.getInstance()).equals(PEER_SERVERS.get(entry.getKey()))) {
                recipients.add(entry.getKey()); AFK_NAMES.put(entry.getKey(),seen.name());
            }
        }
        if(recipients.isEmpty()) {
            source.sendFeedback(Component.literal("No Players Online"));
            return;
        }
        AFK_PENDING.clear(); AFK_PENDING.addAll(recipients); afkDeadline=System.currentTimeMillis()+15_000L;
        sendCommand("AFK_REQUEST",recipients);
        source.sendFeedback(Component.literal("AFK-Prüfung für "+recipients.size()+" Build-146-Client(s) angefragt."));
    }

    private static void sendCommand(String command, Set<UUID> recipients) {
        sendCommand(command, recipients, null);
    }

    private static void sendCommand(String command, Set<UUID> recipients, String argument) {
        try {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(DataOutputStream out=new DataOutputStream(bytes)) {
                out.writeUTF("PA1"); out.writeUTF(command);
                if (argument != null) out.writeUTF(argument);
            }
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
                    long timestamp=in.readLong(); String name=in.readUTF(); String build=in.readUTF(); String server=in.readUTF();
                    if(timestamp<=0 || name.isBlank() || name.length()>16 || (!build.equals("121")&&!build.equals("146"))) return;
                    LastSeen previous = HISTORY.get(sender);
                    if (previous == null || timestamp >= previous.timestamp()) {
                        HISTORY.put(sender,new LastSeen(name,timestamp,build));
                        saveHistory(false);
                    }
                    ACTIVE.add(sender);
                    LAST_RECEIVED.put(sender,System.currentTimeMillis());
                    PEER_SERVERS.put(sender,server);
                    client.execute(() -> AuctionScreen.updateModPlayer(sender.toString(),name,true));
                } else if("OFFLINE".equals(type)) {
                    long timestamp=in.readLong(); String name=in.readUTF(); String build=in.readUTF(); String server=in.readUTF();
                    if(timestamp>0 && !name.isBlank() && name.length()<=16 && "146".equals(build)) {
                        LastSeen previous=HISTORY.get(sender);
                        if(previous==null || timestamp>=previous.timestamp()) {
                            HISTORY.put(sender,new LastSeen(name,timestamp,build));
                            saveHistory(true);
                        }
                        if (server.equals(PEER_SERVERS.get(sender))) { ACTIVE.remove(sender); LAST_RECEIVED.remove(sender); }
                        PEER_SERVERS.remove(sender);
                        client.execute(() -> AuctionScreen.updateModPlayer(sender.toString(),name,false));
                    }
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
                        AFK_PENDING.remove(sender);
                        if (AFK_PENDING.isEmpty()) afkDeadline=0;
                        String status = idleMillis>=10_000L ? " AFK" : " not AFK";
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
    public static void sendOnlineStatus(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source) {
        String server=currentServerId(Minecraft.getInstance());
        long now=System.currentTimeMillis();
        var entries=HISTORY.entrySet().stream().filter(e->!server.isBlank() && "146".equals(e.getValue().build())
                && ACTIVE.contains(e.getKey()) && PEERS.containsKey(e.getKey())
                && server.equals(PEER_SERVERS.get(e.getKey()))
                && now-LAST_RECEIVED.getOrDefault(e.getKey(),0L)<=ONLINE_TIMEOUT_MILLIS)
            .sorted(Map.Entry.comparingByValue(Comparator.comparing(LastSeen::name,String.CASE_INSENSITIVE_ORDER))).toList();
        if(entries.isEmpty()){source.sendFeedback(Component.literal("No Players Online"));return;}
        source.sendFeedback(Component.literal("Build-146-Spieler aktuell auf diesem Server online:"));
        for(var e:entries) source.sendFeedback(Component.literal(e.getValue().name()));
    }


    public static List<String> onlinePlayerNames() {
        String server = currentServerId(Minecraft.getInstance());
        long now = System.currentTimeMillis();
        return HISTORY.entrySet().stream()
                .filter(e -> "146".equals(e.getValue().build())
                        && ACTIVE.contains(e.getKey()) && PEERS.containsKey(e.getKey())
                        && !server.isBlank() && server.equals(PEER_SERVERS.get(e.getKey()))
                        && now - LAST_RECEIVED.getOrDefault(e.getKey(), 0L) <= ONLINE_TIMEOUT_MILLIS)
                .map(e -> e.getValue().name())
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    public static void disconnectPlayer(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source, String username) {
        String server = currentServerId(Minecraft.getInstance());
        var match = HISTORY.entrySet().stream()
                .filter(e -> "146".equals(e.getValue().build()) && ACTIVE.contains(e.getKey())
                        && PEERS.containsKey(e.getKey()) && !server.isBlank()
                        && server.equals(PEER_SERVERS.get(e.getKey()))
                        && System.currentTimeMillis() - LAST_RECEIVED.getOrDefault(e.getKey(), 0L) <= ONLINE_TIMEOUT_MILLIS
                        && e.getValue().name().equalsIgnoreCase(username))
                .findFirst().orElse(null);
        if (match == null) {
            source.sendFeedback(Component.literal("Spieler ist nicht mehr online oder kein Build-146-Spieler: " + username));
            return;
        }
        sendCommand("DISCONNECT_PLAYER", Set.of(match.getKey()), match.getValue().name());
        source.sendFeedback(Component.literal("Disconnect-Anfrage an " + match.getValue().name() + " gesendet."));
    }

    public static void sendLastOnline(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source) {
        saveHistory(true);
        long cutoff=System.currentTimeMillis()-30L*24L*60L*60L*1000L;
        var entries=HISTORY.entrySet().stream().filter(e->"146".equals(e.getValue().build()) && e.getValue().timestamp()>=cutoff)
            .sorted(Map.Entry.<UUID,LastSeen>comparingByValue(Comparator.comparingLong(LastSeen::timestamp).reversed())).toList();
        if(entries.isEmpty()){source.sendFeedback(Component.literal("Noch keine Build-146-Online-Zeit gespeichert. Sobald ein Build-146-Client erkannt wurde, wird sein letzter Online-Zeitpunkt dauerhaft gemerkt."));return;}
        source.sendFeedback(Component.literal("Spieler zuletzt online:"));
        for(var e:entries) source.sendFeedback(Component.literal(e.getValue().name()+" – "+
            java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(java.time.ZoneId.systemDefault())
            .format(java.time.Instant.ofEpochMilli(e.getValue().timestamp()))));
    }
    private static String currentServerId(Minecraft client) {
        try { var server=client.getCurrentServer(); return server==null || server.ip==null ? "" : server.ip.trim().toLowerCase(Locale.ROOT); }
        catch(Exception ignored) { return ""; }
    }
    public static void onDisconnect(){saveHistory(true);selfId=null;identity=null;PEERS.clear();ACTIVE.clear();LAST_RECEIVED.clear();PEER_SERVERS.clear();AFK_NAMES.clear();AFK_PENDING.clear();afkDeadline=0;afkSource=null;pollTicks=0;presenceTicks=0;}
    private record LastSeen(String name,long timestamp,String build){}
    private static final class JsonObjectBuilder {
        private final JsonObject o=new JsonObject();
        JsonObjectBuilder put(String k,String v){o.addProperty(k,v);return this;}
        JsonObject json(){return o;}
    }
}