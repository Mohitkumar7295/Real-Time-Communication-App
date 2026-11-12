package com.codealpha.meetapp.room;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Generates cryptographically secure, unguessable meeting room codes.
 * Uses an unambiguous uppercase alphanumeric character set (excluding 0, O, 1, I).
 */
@Component
public class RoomCodeGenerator {

    private static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final int CODE_LENGTH = 8;
    private final SecureRandom random = new SecureRandom();

    public String generateCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            int index = random.nextInt(ALPHABET.length());
            sb.append(ALPHABET.charAt(index));
        }
        return sb.toString();
    }

    public static String normalizeCode(String code) {
        if (code == null) {
            return "";
        }
        return code.trim().toUpperCase().replace("-", "");
    }
}
