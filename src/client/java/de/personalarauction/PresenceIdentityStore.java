package de.personalarauction;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Properties;

/**
 * Persistent client identity for the presence protocol.
 *
 * The private key stays in the local config directory and is never sent to
 * the relay. This provides continuity across restarts, not peer authentication:
 * a relay can still substitute a public key on first contact.
 */
public final class PresenceIdentityStore {
    private static final String PRIVATE_KEY = "presence.identity.private";
    private static final String PUBLIC_KEY = "presence.identity.public";
    private static KeyPair cached;

    private PresenceIdentityStore() {}

    public static synchronized KeyPair loadOrCreate() throws GeneralSecurityException, IOException {
        if (cached != null) return cached;

        Path file = FabricLoader.getInstance().getConfigDir().resolve("personalauction-presence.properties");
        if (Files.exists(file)) {
            Properties properties = new Properties();
            try (InputStream input = Files.newInputStream(file)) {
                properties.load(input);
            }
            String privateEncoded = properties.getProperty(PRIVATE_KEY, "");
            String publicEncoded = properties.getProperty(PUBLIC_KEY, "");
            if (!privateEncoded.isBlank() && !publicEncoded.isBlank()) {
                KeyFactory factory = KeyFactory.getInstance("X25519");
                cached = new KeyPair(
                        factory.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicEncoded))),
                        factory.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateEncoded))));
                return cached;
            }
        }

        KeyPairGenerator generator = KeyPairGenerator.getInstance("X25519");
        cached = generator.generateKeyPair();
        Properties properties = new Properties();
        properties.setProperty(PRIVATE_KEY, Base64.getEncoder().encodeToString(cached.getPrivate().getEncoded()));
        properties.setProperty(PUBLIC_KEY, Base64.getEncoder().encodeToString(cached.getPublic().getEncoded()));
        Files.createDirectories(file.getParent());
        try (OutputStream output = Files.newOutputStream(file)) {
            properties.store(output, "PersonalAuction local presence identity; do not share this file");
        }
        return cached;
    }
}
