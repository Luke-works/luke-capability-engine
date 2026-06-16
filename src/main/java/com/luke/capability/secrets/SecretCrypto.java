package com.luke.capability.secrets;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM encryption for stored secrets. Each encryption uses a fresh random
 * IV and the active master key; the {@code keyId} is returned alongside the
 * ciphertext and stored per row, so decryption always finds the right key even
 * after the active key rotates.
 *
 * <p>Key material may be a base64-encoded 32-byte key or any passphrase — a
 * passphrase is run through SHA-256 to derive the 32 bytes, so dev config can be a
 * plain string while prod uses a proper random key.
 */
@Component
public class SecretCrypto {

    private static final Logger log = LoggerFactory.getLogger(SecretCrypto.class);
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String DEV_DEFAULT_KEY = "dev-secrets-master-key-change-me";

    private final SecretsProperties props;
    private final SecureRandom rng = new SecureRandom();
    private final Map<String, SecretKeySpec> keys = new HashMap<>();
    private String activeKeyId;

    public SecretCrypto(SecretsProperties props) {
        this.props = props;
    }

    @PostConstruct
    void init() {
        Map<String, String> raw = props.getKeys();
        if (raw == null || raw.isEmpty()) {
            log.warn("SecretCrypto: no luke.secrets.keys configured — using an INSECURE dev key. "
                    + "Set LUKE_SECRETS_ACTIVE_KEY_ID + LUKE_SECRETS_KEYS_<ID> in production.");
            raw = Map.of("dev", DEV_DEFAULT_KEY);
        }
        raw.forEach((id, value) -> keys.put(id, toAesKey(value)));
        activeKeyId = props.getActiveKeyId() != null ? props.getActiveKeyId() : "dev";
        if (!keys.containsKey(activeKeyId)) {
            throw new IllegalStateException("luke.secrets.active-key-id '" + activeKeyId
                    + "' is not among configured keys " + keys.keySet());
        }
        log.info("SecretCrypto: enabled with key ids {} (active: {})", keys.keySet(), activeKeyId);
    }

    /** Ciphertext + IV (both base64) and the keyId that encrypted it. */
    public record Encrypted(String ciphertext, String iv, String keyId) {}

    public Encrypted encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            rng.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, keys.get(activeKeyId), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return new Encrypted(b64(ct), b64(iv), activeKeyId);
        } catch (Exception e) {
            throw new IllegalStateException("Secret encryption failed", e);
        }
    }

    public String decrypt(String ciphertextB64, String ivB64, String keyId) {
        SecretKeySpec key = keys.get(keyId);
        if (key == null) {
            throw new IllegalStateException("No master key for keyId '" + keyId
                    + "' — it must remain configured to decrypt existing secrets");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, unb64(ivB64)));
            return new String(cipher.doFinal(unb64(ciphertextB64)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Secret decryption failed", e);
        }
    }

    /** Base64 32-byte key → use as-is; anything else → SHA-256 of the bytes. */
    private static SecretKeySpec toAesKey(String value) {
        byte[] bytes;
        try {
            byte[] decoded = Base64.getDecoder().decode(value.trim());
            bytes = decoded.length == 32 ? decoded : sha256(value);
        } catch (IllegalArgumentException notBase64) {
            bytes = sha256(value);
        }
        return new SecretKeySpec(bytes, "AES");
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String b64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }

    private static byte[] unb64(String s) {
        return Base64.getDecoder().decode(s);
    }
}
