package com.codealpha.meetapp.security;

import com.codealpha.meetapp.common.config.AppProperties;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String VALID_SECRET_32_BYTES = "super-secret-jwt-key-minimum-32-bytes-123456";
    private final Instant baseTime = Instant.parse("2026-11-10T12:00:00Z");
    private final Clock clock = Clock.fixed(baseTime, ZoneOffset.UTC);

    private AppProperties appProperties;
    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        appProperties.setJwtSecret(VALID_SECRET_32_BYTES);
        appProperties.setJwtExpiryMinutes(1440); // 24 hours

        jwtService = new JwtService(appProperties, clock);
        jwtService.init();
    }

    @Test
    @DisplayName("Startup fails when JWT_SECRET is shorter than 32 bytes")
    void init_failsWhenSecretTooShort() {
        AppProperties shortSecretProps = new AppProperties();
        shortSecretProps.setJwtSecret("too-short-secret");

        JwtService shortSecretJwtService = new JwtService(shortSecretProps, clock);
        assertThatThrownBy(shortSecretJwtService::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes (256 bits)");
    }

    @Test
    @DisplayName("generateToken issues valid token with subject, email, and 24h expiration")
    void generateToken_success() {
        String token = jwtService.generateToken(42L, "test@example.com");

        assertThat(token).isNotBlank();
        assertThat(jwtService.validateToken(token)).isTrue();
        assertThat(jwtService.extractUserId(token)).contains(42L);
        assertThat(jwtService.extractEmail(token)).contains("test@example.com");

        Optional<Claims> claimsOpt = jwtService.parseAndValidateToken(token);
        assertThat(claimsOpt).isPresent();
        Claims claims = claimsOpt.get();
        assertThat(claims.getSubject()).isEqualTo("42");
        assertThat(claims.get("email", String.class)).isEqualTo("test@example.com");
        assertThat(claims.getIssuedAt().toInstant()).isEqualTo(baseTime);
        assertThat(claims.getExpiration().toInstant()).isEqualTo(baseTime.plusSeconds(1440 * 60));
    }

    @Test
    @DisplayName("Tampered token is rejected")
    void validateToken_tamperedSignature_rejected() {
        String token = jwtService.generateToken(42L, "test@example.com");
        String tampered = token.substring(0, token.length() - 5) + "ABCDE";

        assertThat(jwtService.validateToken(tampered)).isFalse();
        assertThat(jwtService.parseAndValidateToken(tampered)).isEmpty();
    }

    @Test
    @DisplayName("Expired token is rejected")
    void validateToken_expired_rejected() {
        String token = jwtService.generateToken(42L, "test@example.com");

        // Fast forward clock past 24h expiration
        Clock futureClock = Clock.fixed(baseTime.plusSeconds(1441 * 60), ZoneOffset.UTC);
        JwtService futureJwtService = new JwtService(appProperties, futureClock);
        futureJwtService.init();

        assertThat(futureJwtService.validateToken(token)).isFalse();
        assertThat(futureJwtService.parseAndValidateToken(token)).isEmpty();
    }

    @Test
    @DisplayName("Null or blank tokens are safely rejected without exceptions")
    void validateToken_nullOrBlank_rejected() {
        assertThat(jwtService.validateToken(null)).isFalse();
        assertThat(jwtService.validateToken("")).isFalse();
        assertThat(jwtService.validateToken("   ")).isFalse();
        assertThat(jwtService.extractUserId(null)).isEmpty();
        assertThat(jwtService.extractEmail(null)).isEmpty();
    }
}
