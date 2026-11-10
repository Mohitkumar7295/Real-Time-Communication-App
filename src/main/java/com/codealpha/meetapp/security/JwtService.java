package com.codealpha.meetapp.security;

import com.codealpha.meetapp.common.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Optional;

/**
 * Service for issuing, parsing, and validating JSON Web Tokens (JJWT HS256).
 * Enforces a minimum 256-bit (32 byte) signing key on startup.
 */
@Service
public class JwtService {

    private final AppProperties appProperties;
    private final Clock clock;
    private SecretKey signingKey;

    public JwtService(AppProperties appProperties, Clock clock) {
        this.appProperties = appProperties;
        this.clock = clock;
    }

    @PostConstruct
    public void init() {
        String secret = appProperties.getJwtSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT_SECRET must not be blank.");
        }
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalStateException("JWT_SECRET must be at least 32 bytes (256 bits) long for secure HS256 signing.");
        }
        this.signingKey = Keys.hmacShaKeyFor(secretBytes);
    }

    /**
     * Issues a signed HS256 JWT containing user id (subject) and email claims.
     *
     * @param userId user identifier
     * @param email  user email address
     * @return signed compact JWT string
     */
    public String generateToken(Long userId, String email) {
        if (userId == null) {
            throw new IllegalArgumentException("userId must not be null");
        }
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("email must not be blank");
        }
        Instant now = Instant.now(clock);
        long expiryMinutes = appProperties.getJwtExpiryMinutes() > 0 ? appProperties.getJwtExpiryMinutes() : 1440;
        Instant expiry = now.plus(expiryMinutes, ChronoUnit.MINUTES);

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("email", email.toLowerCase())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Validates a JWT and extracts all claims. Returns Optional.empty() if expired, malformed, or tampered.
     *
     * @param token compact JWT string
     * @return Optional of Claims if valid, empty otherwise
     */
    public Optional<Claims> parseAndValidateToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .clock(() -> Date.from(Instant.now(clock)))
                    .build()
                    .parseSignedClaims(token.trim())
                    .getPayload();
            return Optional.of(claims);
        } catch (JwtException | IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    public boolean validateToken(String token) {
        return parseAndValidateToken(token).isPresent();
    }

    public Optional<Long> extractUserId(String token) {
        return parseAndValidateToken(token).map(claims -> {
            try {
                return Long.parseLong(claims.getSubject());
            } catch (NumberFormatException ex) {
                return null;
            }
        });
    }

    public Optional<String> extractEmail(String token) {
        return parseAndValidateToken(token).map(claims -> claims.get("email", String.class));
    }
}
