package com.codealpha.meetapp.room;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RoomCodeGeneratorTest {

    private final RoomCodeGenerator generator = new RoomCodeGenerator();

    @Test
    @DisplayName("generateCode produces 8-char codes strictly from unambiguous alphabet without 0, 1, I, O, L")
    void generateCode_producesEightCharsWithoutAmbiguousCharacters() {
        for (int i = 0; i < 1000; i++) {
            String code = generator.generateCode();
            assertNotNull(code);
            assertEquals(8, code.length());
            assertTrue(code.matches("^[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{8}$"),
                    "Code contains characters outside unambiguous alphabet: " + code);
            assertFalse(code.matches(".*[01IOoLiIl].*"),
                    "Code contains ambiguous character: " + code);
        }
    }

    @Test
    @DisplayName("normalizeCode trims, converts to uppercase, strips hyphens, and keeps legacy characters like L")
    void normalizeCode_normalizesAndPreservesLegacyCharacters() {
        assertEquals("K7L2QX9P", RoomCodeGenerator.normalizeCode("  k7l2qx9p  "));
        assertEquals("K7L2QX9P", RoomCodeGenerator.normalizeCode("k7-l2-qx9p"));
        assertEquals("ROOM1234", RoomCodeGenerator.normalizeCode("room-1234"));
        assertEquals("", RoomCodeGenerator.normalizeCode(null));
        assertEquals("", RoomCodeGenerator.normalizeCode("   "));
    }
}

