package com.codealpha.meetapp.common.util;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Thread-safe cryptographically secure random identifier generator.
 * Uses SecureRandom for unguessable tokens, challenge IDs, and codes.
 */
@Component
public class IdGenerator {

    private static final String ALPHANUMERIC_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final String UPPER_ALPHANUMERIC_CHARS = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"; // omits ambiguous 0/O, 1/I
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Generates a random alphanumeric string of the requested length.
     *
     * @param length number of characters
     * @return cryptographically random alphanumeric string
     */
    public String generateAlphanumeric(int length) {
        if (length <= 0) {
            throw new IllegalArgumentException("Length must be positive");
        }
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            int index = RANDOM.nextInt(ALPHANUMERIC_CHARS.length());
            sb.append(ALPHANUMERIC_CHARS.charAt(index));
        }
        return sb.toString();
    }

    /**
     * Generates a room code of the requested length using unambiguous uppercase alphanumeric characters.
     *
     * @param length number of characters (e.g. 6 to 12)
     * @return readable random room code
     */
    public String generateRoomCode(int length) {
        if (length <= 0) {
            throw new IllegalArgumentException("Length must be positive");
        }
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            int index = RANDOM.nextInt(UPPER_ALPHANUMERIC_CHARS.length());
            sb.append(UPPER_ALPHANUMERIC_CHARS.charAt(index));
        }
        return sb.toString();
    }

    /**
     * Generates a numeric code with exact digits (padded with leading zeros if necessary).
     *
     * @param digits number of digits (e.g. 6 for OTP)
     * @return numeric code string
     */
    public String generateNumericCode(int digits) {
        if (digits <= 0) {
            throw new IllegalArgumentException("Digits must be positive");
        }
        StringBuilder sb = new StringBuilder(digits);
        for (int i = 0; i < digits; i++) {
            sb.append(RANDOM.nextInt(10));
        }
        return sb.toString();
    }

    /**
     * Generates a standard random UUID (v4) string.
     *
     * @return UUID string
     */
    public String generateUuid() {
        return UUID.randomUUID().toString();
    }
}
