package com.codealpha.meetapp.common.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.Set;

/**
 * Production startup fail-fast security guard.
 * Active exclusively under the 'prod' profile.
 * Verifies that all cryptographic secrets, email configurations, allowed origins,
 * and database security flags meet production standards and are not default placeholders.
 * Never prints secret values in exception messages or logs.
 */
@Component
@Profile("prod")
public class ProductionStartupGuard {

    private static final Logger log = LoggerFactory.getLogger(ProductionStartupGuard.class);

    private static final Set<String> PLACEHOLDER_SECRETS = Set.of(
            "your_jwt_secret_min_32_characters_long",
            "test-dummy-jwt-secret-minimum-32-chars-long-abcdef",
            "your_otp_pepper_secret_value",
            "default-otp-pepper-secret-value-32chars",
            "k7x8V9uN1Z3pQ4yL6wE2mR0tS8vK5jH1bF4cO9aP3qE=",
            "re_your_resend_api_key",
            "your_database_password"
    );

    private final AppProperties appProperties;
    private final String dbUrl;

    public ProductionStartupGuard(AppProperties appProperties,
                                  @Value("${spring.datasource.url:}") String dbUrl) {
        this.appProperties = appProperties;
        this.dbUrl = dbUrl;
    }

    @PostConstruct
    public void validateProductionSettings() {
        log.info("Executing production startup security verification assertions...");

        // 1. Email provider must be 'resend' in production
        String emailProvider = appProperties.getEmailProvider();
        if ("log".equalsIgnoreCase(emailProvider)) {
            throw new IllegalStateException("Production security failure: EMAIL_PROVIDER must not be 'log' in production profile. Set EMAIL_PROVIDER=resend.");
        }

        // 2. Resend API key must be configured and valid format
        String resendKey = appProperties.getResendApiKey();
        if (resendKey == null || resendKey.isBlank() || isPlaceholder(resendKey) || !resendKey.startsWith("re_")) {
            throw new IllegalStateException("Production security failure: RESEND_API_KEY is missing, malformed, or using a placeholder value.");
        }

        // 3. Mail from must be configured
        String mailFrom = appProperties.getMailFrom();
        if (mailFrom == null || mailFrom.isBlank()) {
            throw new IllegalStateException("Production security failure: MAIL_FROM must not be blank in production.");
        }

        // 4. JWT Secret minimum length 32 chars and not placeholder
        String jwtSecret = appProperties.getJwtSecret();
        if (jwtSecret == null || jwtSecret.length() < 32 || isPlaceholder(jwtSecret)) {
            throw new IllegalStateException("Production security failure: JWT_SECRET must be at least 32 characters long and not a placeholder value.");
        }

        // 5. OTP Pepper minimum length 32 chars and not placeholder
        String otpPepper = appProperties.getOtpPepper();
        if (otpPepper == null || otpPepper.length() < 32 || isPlaceholder(otpPepper)) {
            throw new IllegalStateException("Production security failure: OTP_PEPPER must be at least 32 characters long and not a placeholder value.");
        }

        // 6. AES key must decode to exactly 32 bytes (256 bits) and not placeholder
        String aesKey = appProperties.getAesKey();
        if (aesKey == null || aesKey.isBlank() || isPlaceholder(aesKey)) {
            throw new IllegalStateException("Production security failure: AES_KEY must not be blank or a placeholder value.");
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(aesKey.trim());
            if (decoded.length != 32) {
                throw new IllegalStateException("Production security failure: AES_KEY must decode to exactly 32 bytes (256 bits). Found: " + decoded.length + " bytes.");
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Production security failure: AES_KEY is not a valid Base64 encoded string.");
        }

        // 7. Allowed origin must be set and not localhost in production
        String allowedOrigin = appProperties.getAllowedOrigin();
        if (allowedOrigin == null || allowedOrigin.isBlank() || allowedOrigin.contains("localhost") || allowedOrigin.contains("127.0.0.1")) {
            throw new IllegalStateException("Production security failure: APP_ALLOWED_ORIGIN must be set to a valid public HTTPS production origin and not localhost.");
        }

        // 8. Database URL must require SSL in production
        if (dbUrl != null && !dbUrl.isBlank() && !dbUrl.contains("sslmode=require")) {
            throw new IllegalStateException("Production security failure: Production DB_URL must enforce encrypted transport with ?sslmode=require.");
        }

        log.info("Production security startup assertions passed successfully.");
    }

    private boolean isPlaceholder(String value) {
        if (value == null) return false;
        String trimmed = value.trim();
        return PLACEHOLDER_SECRETS.contains(trimmed) || trimmed.toLowerCase().contains("placeholder") || trimmed.toLowerCase().contains("your_");
    }
}

