package com.codealpha.meetapp.crypto;

import com.codealpha.meetapp.common.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class EncryptionServiceTest {

    // Valid 32-byte (256-bit) Base64 encoded test key
    private static final String VALID_BASE64_KEY = "k7x8V9uN1Z3pQ4yL6wE2mR0tS8vK5jH1bF4cO9aP3qE=";

    private EncryptionService encryptionService;
    private AppProperties appProperties;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        appProperties.setAesKey(VALID_BASE64_KEY);
        encryptionService = new EncryptionService(appProperties);
        encryptionService.validateAndInitializeKey();
    }

    @Test
    @DisplayName("encryption round-trip: plaintext decrypts to original string")
    void encryptionRoundTrip() {
        String plaintext = "Hello from MeetApp! Special chars: \uD83D\uDC4B \n Line 2";
        Long roomId = 42L;

        String ciphertext = encryptionService.encrypt(plaintext, roomId);
        assertNotNull(ciphertext);
        assertNotEquals(plaintext, ciphertext);

        String decrypted = encryptionService.decrypt(ciphertext, roomId);
        assertEquals(plaintext, decrypted);
    }

    @Test
    @DisplayName("random IV: encrypting identical text twice produces different ciphertexts")
    void differentIvForIdenticalText() {
        String plaintext = "Sensitive conference secret";
        Long roomId = 100L;

        String cipher1 = encryptionService.encrypt(plaintext, roomId);
        String cipher2 = encryptionService.encrypt(plaintext, roomId);

        assertNotNull(cipher1);
        assertNotNull(cipher2);
        assertNotEquals(cipher1, cipher2, "Different random 12-byte IV must be generated per message");

        // Both decrypt to identical plaintext
        assertEquals(plaintext, encryptionService.decrypt(cipher1, roomId));
        assertEquals(plaintext, encryptionService.decrypt(cipher2, roomId));
    }

    @Test
    @DisplayName("GCM AAD: ciphertext copied into another room fails to decrypt")
    void crossRoomTamperingWithAad_failsDecryption() {
        String plaintext = "Confidential chat in Room 1";
        Long room1Id = 1L;
        Long room2Id = 2L;

        // Encrypted with Room 1 AAD
        String cipher = encryptionService.encrypt(plaintext, room1Id);

        // Attempting to decrypt with Room 2 AAD must fail
        assertThrows(CryptoException.class, () -> encryptionService.decrypt(cipher, room2Id),
                "Decryption must throw CryptoException when AAD (roomId) does not match");
    }

    @Test
    @DisplayName("tampered ciphertext: modified payload fails GCM authentication tag verification")
    void tamperedCiphertext_failsDecryption() {
        String plaintext = "Authentic message";
        Long roomId = 55L;

        String cipher = encryptionService.encrypt(plaintext, roomId);
        byte[] raw = Base64.getDecoder().decode(cipher);

        // Flip a bit in the ciphertext / tag region
        raw[raw.length - 1] ^= 0x01;
        String tamperedCipher = Base64.getEncoder().encodeToString(raw);

        assertThrows(CryptoException.class, () -> encryptionService.decrypt(tamperedCipher, roomId));
    }

    @Test
    @DisplayName("truncated payload: malformed Base64 throws CryptoException")
    void truncatedPayload_throwsException() {
        Long roomId = 10L;
        assertThrows(CryptoException.class, () -> encryptionService.decrypt("AAAA", roomId));
        assertThrows(CryptoException.class, () -> encryptionService.decrypt("not-base-64!!", roomId));
    }

    @Test
    @DisplayName("startup check: missing AES_KEY throws IllegalStateException")
    void missingKey_throwsIllegalStateException() {
        AppProperties emptyProps = new AppProperties();
        emptyProps.setAesKey(null);
        EncryptionService emptyService = new EncryptionService(emptyProps);

        assertThrows(IllegalStateException.class, emptyService::validateAndInitializeKey);
    }

    @Test
    @DisplayName("startup check: key not 32 bytes throws IllegalStateException")
    void invalidKeyLength_throwsIllegalStateException() {
        // 16-byte key (AES-128 instead of AES-256)
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        AppProperties shortProps = new AppProperties();
        shortProps.setAesKey(shortKey);
        EncryptionService shortService = new EncryptionService(shortProps);

        assertThrows(IllegalStateException.class, shortService::validateAndInitializeKey);
    }
}
