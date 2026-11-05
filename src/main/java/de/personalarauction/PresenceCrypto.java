package de.personalarauction;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

/**
 * Cryptographic building blocks for client-side presence messages.
 *
 * The relay can forward ciphertext without learning its contents. This class
 * deliberately does not claim peer authentication: if a relay substitutes
 * public keys during first contact, it can perform a man-in-the-middle attack.
 */
public final class PresenceCrypto {
    private static final String PROTOCOL_CONTEXT = "PersonalAuction presence v1";
    private static final byte[] HKDF_SALT =
            "PersonalAuction X25519 HKDF-SHA256 v1".getBytes(StandardCharsets.UTF_8);
    private static final int NONCE_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final int KEY_LENGTH = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PresenceCrypto() {}

    public static KeyPair createIdentityKeyPair() throws GeneralSecurityException {
        return KeyPairGenerator.getInstance("X25519").generateKeyPair();
    }

    /** Returns the X.509-encoded X25519 public key suitable for transport. */
    public static byte[] encodePublicKey(PublicKey publicKey) {
        return publicKey.getEncoded().clone();
    }

    public static PublicKey decodePublicKey(byte[] encoded) throws GeneralSecurityException {
        if (encoded == null || encoded.length < 32 || encoded.length > 256) {
            throw new GeneralSecurityException("Invalid X25519 public key length");
        }
        return KeyFactory.getInstance("X25519").generatePublic(new X509EncodedKeySpec(encoded));
    }

    /**
     * Derives a 256-bit AES key. The context must be identical on both clients
     * and should bind the intended peer identities and protocol version.
     */
    public static SecretKeySpec deriveMessageKey(
            java.security.PrivateKey localPrivateKey,
            PublicKey remotePublicKey,
            String context) throws GeneralSecurityException {
        if (context == null || context.isBlank() || context.length() > 512) {
            throw new GeneralSecurityException("Invalid key context");
        }
        KeyAgreement agreement = KeyAgreement.getInstance("X25519");
        agreement.init(localPrivateKey);
        agreement.doPhase(remotePublicKey, true);
        byte[] sharedSecret = agreement.generateSecret();
        try {
            byte[] prk = hmac(HKDF_SALT, sharedSecret);
            byte[] info = (PROTOCOL_CONTEXT + "|" + context).getBytes(StandardCharsets.UTF_8);
            byte[] okm = hmac(prk, concat(info, new byte[]{1}));
            return new SecretKeySpec(Arrays.copyOf(okm, KEY_LENGTH), "AES");
        } finally {
            Arrays.fill(sharedSecret, (byte) 0);
        }
    }

    /** AES-256-GCM envelope: 12-byte random nonce followed by ciphertext and tag. */
    public static byte[] encrypt(SecretKeySpec key, byte[] plaintext, byte[] associatedData)
            throws GeneralSecurityException {
        if (plaintext == null || plaintext.length > 16_384) {
            throw new GeneralSecurityException("Presence message exceeds size limit");
        }
        byte[] nonce = new byte[NONCE_LENGTH];
        RANDOM.nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
        cipher.updateAAD(associatedData == null ? new byte[0] : associatedData);
        return concat(nonce, cipher.doFinal(plaintext));
    }

    public static byte[] decrypt(SecretKeySpec key, byte[] envelope, byte[] associatedData)
            throws GeneralSecurityException {
        if (envelope == null || envelope.length < NONCE_LENGTH + 16
                || envelope.length > 16_384 + NONCE_LENGTH + 16) {
            throw new GeneralSecurityException("Invalid encrypted presence envelope");
        }
        byte[] nonce = Arrays.copyOfRange(envelope, 0, NONCE_LENGTH);
        byte[] ciphertext = Arrays.copyOfRange(envelope, NONCE_LENGTH, envelope.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
        cipher.updateAAD(associatedData == null ? new byte[0] : associatedData);
        return cipher.doFinal(ciphertext);
    }

    private static byte[] hmac(byte[] key, byte[] input) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(input);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
