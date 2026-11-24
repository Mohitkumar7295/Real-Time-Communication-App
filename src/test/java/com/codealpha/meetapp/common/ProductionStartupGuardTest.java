package com.codealpha.meetapp.common;

import com.codealpha.meetapp.common.config.AppProperties;
import com.codealpha.meetapp.common.config.ProductionStartupGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class ProductionStartupGuardTest {

    private AppProperties appProperties;
    private String validDbUrl;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        appProperties.setEmailProvider("resend");
        appProperties.setResendApiKey("re_live_valid_test_resend_api_key_12345");
        appProperties.setMailFrom("no-reply@meetapp.live");
        appProperties.setJwtSecret("production_secure_jwt_secret_with_more_than_32_characters_12345");
        appProperties.setOtpPepper("production_secure_otp_pepper_with_more_than_32_characters_67890");
        // Valid 32-byte Base64 key
        byte[] validKeyBytes = new byte[32];
        for (int i = 0; i < 32; i++) validKeyBytes[i] = (byte) (i + 1);
        appProperties.setAesKey(Base64.getEncoder().encodeToString(validKeyBytes));
        appProperties.setAllowedOrigin("https://meetapp.onrender.com");

        validDbUrl = "jdbc:postgresql://ep-example.supabase.com:5432/postgres?sslmode=require";
    }

    @Test
    @DisplayName("Validation succeeds when all production configurations are secure and valid")
    void validate_succeedsWithValidSettings() {
        ProductionStartupGuard guard = new ProductionStartupGuard(appProperties, validDbUrl);
        assertDoesNotThrow(guard::validateProductionSettings);
    }

    @Test
    @DisplayName("Validation fails when EMAIL_PROVIDER is 'log'")
    void validate_failsWhenEmailProviderIsLog() {
        appProperties.setEmailProvider("log");
        ProductionStartupGuard guard = new ProductionStartupGuard(appProperties, validDbUrl);
        IllegalStateException ex = assertThrows(IllegalStateException.class, guard::validateProductionSettings);
        assertTrue(ex.getMessage().contains("EMAIL_PROVIDER must not be 'log'"));
    }

    @Test
    @DisplayName("Validation fails when RESEND_API_KEY is placeholder or malformed")
    void validate_failsWhenResendApiKeyIsPlaceholder() {
        appProperties.setResendApiKey("re_your_resend_api_key");
        ProductionStartupGuard guard = new ProductionStartupGuard(appProperties, validDbUrl);
        IllegalStateException ex = assertThrows(IllegalStateException.class, guard::validateProductionSettings);
        assertTrue(ex.getMessage().contains("RESEND_API_KEY"));
        assertFalse(ex.getMessage().contains("re_your_resend_api_key")); // Never leak value
    }

    @Test
    @DisplayName("Validation fails when JWT_SECRET is too short (< 32 chars)")
    void validate_failsWhenJwtSecretTooShort() {
        appProperties.setJwtSecret("short_secret_20chars");
        ProductionStartupGuard guard = new ProductionStartupGuard(appProperties, validDbUrl);
        IllegalStateException ex = assertThrows(IllegalStateException.class, guard::validateProductionSettings);
        assertTrue(ex.getMessage().contains("JWT_SECRET must be at least 32 characters"));
        assertFalse(ex.getMessage().contains("short_secret_20chars"));
    }

    @Test
    @DisplayName("Validation fails when OTP_PEPPER is placeholder")
    void validate_failsWhenOtpPepperIsPlaceholder() {
        appProperties.setOtpPepper("default-otp-pepper-secret-value-32chars");
        ProductionStartupGuard guard = new ProductionStartupGuard(appProperties, validDbUrl);
        IllegalStateException ex = assertThrows(IllegalStateException.class, guard::validateProductionSettings);
        assertTrue(ex.getMessage().contains("OTP_PEPPER"));
        assertFalse(ex.getMessage().contains("default-otp-pepper"));
    }

    @Test
    @DisplayName("Validation fails when AES_KEY does not decode to 32 bytes")
    void validate_failsWhenAesKeyNot32Bytes() {
        byte[] shortBytes = new byte[16]; // 128-bit key instead of 256-bit
        appProperties.setAesKey(Base64.getEncoder().encodeToString(shortBytes));
        ProductionStartupGuard guard = new ProductionStartupGuard(appProperties, validDbUrl);
        IllegalStateException ex = assertThrows(IllegalStateException.class, guard::validateProductionSettings);
        assertTrue(ex.getMessage().contains("AES_KEY must decode to exactly 32 bytes"));
    }

    @Test
    @DisplayName("Validation fails when APP_ALLOWED_ORIGIN is localhost in production")
    void validate_failsWhenAllowedOriginIsLocalhost() {
        appProperties.setAllowedOrigin("http://localhost:8080");
        ProductionStartupGuard guard = new ProductionStartupGuard(appProperties, validDbUrl);
        IllegalStateException ex = assertThrows(IllegalStateException.class, guard::validateProductionSettings);
        assertTrue(ex.getMessage().contains("APP_ALLOWED_ORIGIN"));
    }

    @Test
    @DisplayName("Validation fails when DB_URL lacks sslmode=require")
    void validate_failsWhenDbUrlLacksSslMode() {
        String nonSslUrl = "jdbc:postgresql://ep-example.supabase.com:5432/postgres";
        ProductionStartupGuard guard = new ProductionStartupGuard(appProperties, nonSslUrl);
        IllegalStateException ex = assertThrows(IllegalStateException.class, guard::validateProductionSettings);
        assertTrue(ex.getMessage().contains("sslmode=require"));
    }
}

