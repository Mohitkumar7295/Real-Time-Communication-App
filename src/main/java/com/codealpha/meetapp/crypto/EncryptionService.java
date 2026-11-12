package com.codealpha.meetapp.crypto;

import com.codealpha.meetapp.common.config.AppProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Enterprise AES-256-GCM encryption service protecting message text at rest.
 * Uses a 12-byte random IV per message, binds the room ID as GCM Additional
 * Authenticated Data (AAD) to prevent cross-room ciphertext injection,
 * and packs a version byte for future key rotation support:
 * Base64( [version: 1 byte] || [iv: 12 bytes] || [ciphertext + 16-byte GCM tag] )
 */
@Service
public class EncryptionService {

    private static final Logger log = LoggerFactory.getLogger(EncryptionService.class);

    private static final byte CURRENT_VERSION = 0x01;
    private static final int GCM_IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final String ALGORITHM = "AES/GCM/NoPadding";

    private final AppProperties appProperties;
    private final SecureRandom secureRandom;
    private SecretKey secretKey;

    public EncryptionService(AppProperties appProperties) {
        this.appProperties = appProperties;
        this.secureRandom = new SecureRandom();
    }

    /**
     * Fails application startup in every profile if AES_KEY is missing or not 32 bytes (256 bits).
     */
    @PostConstruct
    public void validateAndInitializeKey() {
        String keyBase64 = appProperties.getAesKey();
        if (keyBase64 == null || keyBase64.isBlank()) {
            keyBase64 = System.getenv("AES_KEY");
        }

        if (keyBase64 == null || keyBase64.isBlank()) {
            throw new IllegalStateException("AES_KEY is missing. A Base64-encoded 256-bit (32-byte) key is required in all profiles.");
        }

        byte[] decodedKey;
        try {
            decodedKey = Base64.getDecoder().decode(keyBase64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("AES_KEY is not valid Base64.", e);
        }

        if (decodedKey.length != 32) {
            throw new IllegalStateException("AES_KEY must be exactly 32 bytes (256 bits) after Base64 decoding, but was " + decodedKey.length + " bytes.");
        }

        this.secretKey = new SecretKeySpec(decodedKey, "AES");
        log.info("EncryptionService initialized successfully with AES-256-GCM");
    }

    /**
     * Encrypts plaintext message content with AES-256-GCM.
     * Binds roomId as AAD. Returns single Base64 encoded payload.
     * Never logs message text or key material.
     */
    public String encrypt(String plaintext, Long roomId) {
        if (plaintext == null) {
            return null;
        }
        if (roomId == null) {
            throw new IllegalArgumentException("roomId is required as GCM Additional Authenticated Data (AAD)");
        }

        try {
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec);

            // Bind room ID as AAD to prevent cross-room injection
            cipher.updateAAD(createAad(roomId));

            byte[] plaintextBytes = plaintext.getBytes(StandardCharsets.UTF_8);
            byte[] ciphertextWithTag = cipher.doFinal(plaintextBytes);

            ByteBuffer buffer = ByteBuffer.allocate(1 + iv.length + ciphertextWithTag.length);
            buffer.put(CURRENT_VERSION);
            buffer.put(iv);
            buffer.put(ciphertextWithTag);

            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            throw new CryptoException("Failed to encrypt message", e);
        }
    }

    /**
     * Decrypts AES-256-GCM Base64 payload.
     * Verifies AAD against roomId. Throws CryptoException if tampered or wrong room.
     */
    public String decrypt(String base64Payload, Long roomId) {
        if (base64Payload == null) {
            return null;
        }
        if (roomId == null) {
            throw new IllegalArgumentException("roomId is required as GCM Additional Authenticated Data (AAD)");
        }

        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Payload.trim());
        } catch (IllegalArgumentException e) {
            throw new CryptoException("Invalid Base64 ciphertext payload", e);
        }

        if (raw.length < 1 + GCM_IV_LENGTH_BYTES + (GCM_TAG_LENGTH_BITS / 8)) {
            throw new CryptoException("Ciphertext payload is truncated or invalid");
        }

        ByteBuffer buffer = ByteBuffer.wrap(raw);
        byte version = buffer.get();
        if (version != CURRENT_VERSION) {
            throw new CryptoException("Unsupported encryption payload version: " + version);
        }

        byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
        buffer.get(iv);

        byte[] ciphertextWithTag = new byte[buffer.remaining()];
        buffer.get(ciphertextWithTag);

        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, parameterSpec);

            // Verify room ID AAD
            cipher.updateAAD(createAad(roomId));

            byte[] decrypted = cipher.doFinal(ciphertextWithTag);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new CryptoException("Failed to decrypt message (tampered ciphertext or wrong room AAD)", e);
        }
    }

    private byte[] createAad(Long roomId) {
        return ByteBuffer.allocate(Long.BYTES).putLong(roomId).array();
    }

    /**
     * Encrypts raw file binary data using AES-256-GCM.
     * Binds both roomId and fileId as Additional Authenticated Data (AAD).
     * Output format: [version: 1 byte] || [iv: 12 bytes] || [ciphertext + 16-byte GCM tag]
     */
    public byte[] encryptFile(byte[] plaintextBytes, Long roomId, Long fileId) {
        if (plaintextBytes == null) {
            return null;
        }
        if (roomId == null || fileId == null) {
            throw new IllegalArgumentException("roomId and fileId are required as GCM Additional Authenticated Data (AAD)");
        }

        try {
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec);

            // Bind room ID and file ID as AAD
            cipher.updateAAD(createFileAad(roomId, fileId));

            byte[] ciphertextWithTag = cipher.doFinal(plaintextBytes);

            ByteBuffer buffer = ByteBuffer.allocate(1 + iv.length + ciphertextWithTag.length);
            buffer.put(CURRENT_VERSION);
            buffer.put(iv);
            buffer.put(ciphertextWithTag);

            return buffer.array();
        } catch (Exception e) {
            throw new CryptoException("Failed to encrypt file binary", e);
        }
    }

    /**
     * Decrypts AES-256-GCM binary file data in memory.
     * Verifies AAD against roomId and fileId.
     * Throws CryptoException if tampered, corrupted, or wrong room/file AAD.
     */
    public byte[] decryptFile(byte[] ciphertextBytes, Long roomId, Long fileId) {
        if (ciphertextBytes == null) {
            return null;
        }
        if (roomId == null || fileId == null) {
            throw new IllegalArgumentException("roomId and fileId are required as GCM Additional Authenticated Data (AAD)");
        }

        if (ciphertextBytes.length < 1 + GCM_IV_LENGTH_BYTES + (GCM_TAG_LENGTH_BITS / 8)) {
            throw new CryptoException("File ciphertext is truncated or invalid");
        }

        ByteBuffer buffer = ByteBuffer.wrap(ciphertextBytes);
        byte version = buffer.get();
        if (version != CURRENT_VERSION) {
            throw new CryptoException("Unsupported encryption payload version: " + version);
        }

        byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
        buffer.get(iv);

        byte[] ciphertextWithTag = new byte[buffer.remaining()];
        buffer.get(ciphertextWithTag);

        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, parameterSpec);

            // Verify room ID and file ID AAD
            cipher.updateAAD(createFileAad(roomId, fileId));

            return cipher.doFinal(ciphertextWithTag);
        } catch (Exception e) {
            throw new CryptoException("Failed to decrypt file (tampered ciphertext or wrong room/file AAD)", e);
        }
    }

    private byte[] createFileAad(Long roomId, Long fileId) {
        return ByteBuffer.allocate(Long.BYTES * 2).putLong(roomId).putLong(fileId).array();
    }
}
