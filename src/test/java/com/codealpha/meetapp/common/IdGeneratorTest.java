package com.codealpha.meetapp.common;

import com.codealpha.meetapp.common.util.IdGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdGeneratorTest {

    private final IdGenerator idGenerator = new IdGenerator();

    @Test
    @DisplayName("generateAlphanumeric produces correct length and unique strings")
    void generateAlphanumeric_works() {
        String token = idGenerator.generateAlphanumeric(32);
        assertThat(token).hasSize(32);
        assertThat(token).matches("^[a-zA-Z0-9]{32}$");

        Set<String> set = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            set.add(idGenerator.generateAlphanumeric(16));
        }
        assertThat(set).hasSize(50);
    }

    @Test
    @DisplayName("generateRoomCode produces uppercase readable alphanumeric codes")
    void generateRoomCode_works() {
        String code = idGenerator.generateRoomCode(8);
        assertThat(code).hasSize(8);
        assertThat(code).matches("^[2-9A-HJ-NP-Z]{8}$");
    }

    @Test
    @DisplayName("generateNumericCode produces exact digits with numeric characters")
    void generateNumericCode_works() {
        for (int i = 0; i < 20; i++) {
            String otp = idGenerator.generateNumericCode(6);
            assertThat(otp).hasSize(6);
            assertThat(otp).matches("^[0-9]{6}$");
        }
    }

    @Test
    @DisplayName("generateUuid produces standard UUID v4")
    void generateUuid_works() {
        String uuidStr = idGenerator.generateUuid();
        UUID parsed = UUID.fromString(uuidStr);
        assertThat(parsed).isNotNull();
    }

    @Test
    @DisplayName("Non-positive arguments throw IllegalArgumentException")
    void nonPositiveArguments_throw() {
        assertThatThrownBy(() -> idGenerator.generateAlphanumeric(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> idGenerator.generateRoomCode(-5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> idGenerator.generateNumericCode(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
