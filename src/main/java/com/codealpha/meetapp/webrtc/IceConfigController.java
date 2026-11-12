package com.codealpha.meetapp.webrtc;

import com.codealpha.meetapp.common.config.AppProperties;
import com.codealpha.meetapp.common.exception.TooManyRequestsException;
import com.codealpha.meetapp.common.exception.UnauthorizedException;
import com.codealpha.meetapp.security.RateLimiter;
import com.codealpha.meetapp.security.UserPrincipal;
import com.codealpha.meetapp.webrtc.dto.IceConfigResponse;
import com.codealpha.meetapp.webrtc.dto.IceServerResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Controller providing authenticated clients with dynamic WebRTC ICE server configurations (STUN and TURN).
 * Responses strictly enforce Cache-Control: no-store to prevent credential caching.
 */
@RestController
@RequestMapping("/api/webrtc")
public class IceConfigController {

    private static final Logger log = LoggerFactory.getLogger(IceConfigController.class);

    private static final int RATE_LIMIT_MAX_ATTEMPTS = 10;
    private static final int RATE_LIMIT_WINDOW_SECONDS = 60;

    private final AppProperties appProperties;
    private final RateLimiter rateLimiter;

    public IceConfigController(AppProperties appProperties, RateLimiter rateLimiter) {
        this.appProperties = appProperties;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/ice-config")
    public ResponseEntity<IceConfigResponse> getIceConfig(@AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) {
            throw new UnauthorizedException("Authentication is required to retrieve ICE configuration.");
        }

        String rateLimitKey = "ice-config:user:" + principal.getId();
        if (!rateLimiter.tryAcquire(rateLimitKey, RATE_LIMIT_MAX_ATTEMPTS, RATE_LIMIT_WINDOW_SECONDS)) {
            throw new TooManyRequestsException("Rate limit exceeded for ICE configuration. Please wait a moment.");
        }

        List<IceServerResponse> servers = new ArrayList<>();

        // 1. STUN Servers (always included)
        List<String> stunUrls = appProperties.getStunUrls();
        if (stunUrls != null && !stunUrls.isEmpty()) {
            servers.add(new IceServerResponse(new ArrayList<>(stunUrls)));
        }

        // 2. TURN Server (included only if turnUrl, turnUsername, and turnCredential are all configured)
        String turnUrl = appProperties.getTurnUrl();
        String turnUsername = appProperties.getTurnUsername();
        String turnCredential = appProperties.getTurnCredential();

        if (turnUrl != null && !turnUrl.isBlank() &&
                turnUsername != null && !turnUsername.isBlank() &&
                turnCredential != null && !turnCredential.isBlank()) {

            Set<String> uniqueEndpoints = new LinkedHashSet<>();
            int ignoredCount = 0;
            String[] rawUrls = turnUrl.split(",");

            for (String raw : rawUrls) {
                if (raw == null) continue;
                String trimmed = raw.trim();
                if (trimmed.isEmpty()) continue;

                String lower = trimmed.toLowerCase();
                if (lower.startsWith("turn:") || lower.startsWith("turns:")) {
                    if (trimmed.contains("?transport=")) {
                        uniqueEndpoints.add(trimmed);
                    } else {
                        uniqueEndpoints.add(trimmed + "?transport=udp");
                        uniqueEndpoints.add(trimmed + "?transport=tcp");
                    }
                } else {
                    ignoredCount++;
                }
            }

            if (ignoredCount > 0) {
                log.warn("Ignored {} invalid TURN server URL entries in configuration", ignoredCount);
            }

            if (!uniqueEndpoints.isEmpty()) {
                servers.add(new IceServerResponse(new ArrayList<>(uniqueEndpoints), turnUsername, turnCredential));
                log.debug("Delivering ICE config including {} TURN relay endpoints to user ID: {}", uniqueEndpoints.size(), principal.getId());
            } else {
                log.debug("Delivering STUN-only ICE config to user ID: {} (no valid TURN URLs found)", principal.getId());
            }
        } else {
            log.debug("Delivering STUN-only ICE config to user ID: {}", principal.getId());
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate")
                .header("Pragma", "no-cache")
                .body(new IceConfigResponse(servers));
    }
}

