package com.codealpha.meetapp.common.config;

import jakarta.annotation.PostConstruct;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Strongly typed and validated application properties bound from the 'app' prefix.
 * Sensitive fields (e.g. turnCredential) are strictly masked in toString() to prevent
 * secret leakage into log outputs.
 */
@Validated
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    @NotBlank(message = "app.allowed-origin must not be blank")
    @Pattern(
            regexp = "^https?://[a-zA-Z0-9.-]+(:[0-9]+)?$",
            message = "app.allowed-origin must be a valid http or https origin with no trailing slash and never '*'"
    )
    private String allowedOrigin = "http://localhost:8080";

    @Positive(message = "app.max-file-size-mb must be positive")
    private int maxFileSizeMb = 10;

    @Positive(message = "app.max-room-participants must be positive")
    private int maxRoomParticipants = 5;

    @NotBlank(message = "app.upload-dir must not be blank")
    private String uploadDir = "uploads";

    @Positive(message = "app.otp-expiry-minutes must be positive")
    private int otpExpiryMinutes = 5;

    private List<String> stunUrls = new ArrayList<>(List.of("stun:stun.l.google.com:19302"));

    private String turnUrl;
    private String turnUsername;
    private String turnCredential;

    @PostConstruct
    public void validateOrigin() {
        if (allowedOrigin == null || allowedOrigin.isBlank()) {
            throw new IllegalArgumentException("app.allowed-origin must not be blank.");
        }
        String trimmed = allowedOrigin.trim();
        if ("*".equals(trimmed)) {
            throw new IllegalArgumentException("app.allowed-origin must never be wildcard '*'.");
        }
        if (trimmed.endsWith("/")) {
            throw new IllegalArgumentException("app.allowed-origin must not end with a trailing slash: " + trimmed);
        }
        try {
            URI uri = URI.create(trimmed);
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                throw new IllegalArgumentException("app.allowed-origin must use http or https scheme: " + trimmed);
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException("app.allowed-origin must include a valid host: " + trimmed);
            }
            if (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath())) {
                throw new IllegalArgumentException("app.allowed-origin must not include a path component: " + trimmed);
            }
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("app.allowed-origin is not a valid origin URI: " + trimmed, ex);
        }
    }

    public String getAllowedOrigin() {
        return allowedOrigin;
    }

    public void setAllowedOrigin(String allowedOrigin) {
        this.allowedOrigin = allowedOrigin;
    }

    public int getMaxFileSizeMb() {
        return maxFileSizeMb;
    }

    public void setMaxFileSizeMb(int maxFileSizeMb) {
        this.maxFileSizeMb = maxFileSizeMb;
    }

    public int getMaxRoomParticipants() {
        return maxRoomParticipants;
    }

    public void setMaxRoomParticipants(int maxRoomParticipants) {
        this.maxRoomParticipants = maxRoomParticipants;
    }

    public String getUploadDir() {
        return uploadDir;
    }

    public void setUploadDir(String uploadDir) {
        this.uploadDir = uploadDir;
    }

    public int getOtpExpiryMinutes() {
        return otpExpiryMinutes;
    }

    public void setOtpExpiryMinutes(int otpExpiryMinutes) {
        this.otpExpiryMinutes = otpExpiryMinutes;
    }

    public List<String> getStunUrls() {
        return stunUrls != null ? Collections.unmodifiableList(stunUrls) : Collections.emptyList();
    }

    public void setStunUrls(List<String> stunUrls) {
        this.stunUrls = stunUrls != null ? new ArrayList<>(stunUrls) : new ArrayList<>();
    }

    public String getTurnUrl() {
        return turnUrl;
    }

    public void setTurnUrl(String turnUrl) {
        this.turnUrl = turnUrl;
    }

    public String getTurnUsername() {
        return turnUsername;
    }

    public void setTurnUsername(String turnUsername) {
        this.turnUsername = turnUsername;
    }

    public String getTurnCredential() {
        return turnCredential;
    }

    public void setTurnCredential(String turnCredential) {
        this.turnCredential = turnCredential;
    }

    @Override
    public String toString() {
        return "AppProperties{" +
                "allowedOrigin='" + allowedOrigin + '\'' +
                ", maxFileSizeMb=" + maxFileSizeMb +
                ", maxRoomParticipants=" + maxRoomParticipants +
                ", uploadDir='" + uploadDir + '\'' +
                ", otpExpiryMinutes=" + otpExpiryMinutes +
                ", stunUrls=" + stunUrls +
                ", turnUrl='" + turnUrl + '\'' +
                ", turnUsername='" + turnUsername + '\'' +
                ", turnCredential=" + (turnCredential != null && !turnCredential.isBlank() ? "'****'" : "null") +
                '}';
    }
}
