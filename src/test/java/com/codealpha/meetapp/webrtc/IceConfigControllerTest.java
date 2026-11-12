package com.codealpha.meetapp.webrtc;

import com.codealpha.meetapp.common.config.AppProperties;
import com.codealpha.meetapp.security.JwtService;
import com.codealpha.meetapp.security.RateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IceConfigControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private AppProperties appProperties;

    @Autowired
    private RateLimiter rateLimiter;

    private String validToken;

    @BeforeEach
    void setUp() {
        validToken = jwtService.generateToken(1L, "aarav@example.com");
        rateLimiter.reset("ice-config:user:1");
    }

    @Test
    @DisplayName("GET /api/webrtc/ice-config without token returns 401 Unauthorized")
    void getIceConfig_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/webrtc/ice-config"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/webrtc/ice-config returns STUN urls and never-cached headers")
    void getIceConfig_stunOnly_returnsStunUrlsAndNoCacheHeaders() throws Exception {
        appProperties.setTurnUrl(null);
        appProperties.setTurnUsername(null);
        appProperties.setTurnCredential(null);

        mockMvc.perform(get("/api/webrtc/ice-config")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-cache")))
                .andExpect(jsonPath("$.iceServers", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$.iceServers[0].urls", hasItem("stun:stun.l.google.com:19302")))
                .andExpect(jsonPath("$.iceServers[0].username").doesNotExist())
                .andExpect(jsonPath("$.iceServers[0].credential").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/webrtc/ice-config with TURN configured returns both STUN and TURN")
    void getIceConfig_withTurn_returnsStunAndTurn() throws Exception {
        appProperties.setTurnUrl("turn:turn.meetapp.com:3478");
        appProperties.setTurnUsername("relay_user");
        appProperties.setTurnCredential("relay_secret_123");

        mockMvc.perform(get("/api/webrtc/ice-config")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.iceServers", hasSize(2)))
                .andExpect(jsonPath("$.iceServers[0].urls", hasItem("stun:stun.l.google.com:19302")))
                .andExpect(jsonPath("$.iceServers[1].urls", hasItem("turn:turn.meetapp.com:3478?transport=udp")))
                .andExpect(jsonPath("$.iceServers[1].username", is("relay_user")))
                .andExpect(jsonPath("$.iceServers[1].credential", is("relay_secret_123")));

        // Cleanup
        appProperties.setTurnUrl(null);
        appProperties.setTurnUsername(null);
        appProperties.setTurnCredential(null);
    }

    @Test
    @DisplayName("GET /api/webrtc/ice-config rate limit exceeded triggers 429 Too Many Requests")
    void getIceConfig_rateLimitExceeded_returns429() throws Exception {
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(get("/api/webrtc/ice-config")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken))
                    .andExpect(status().isOk());
        }

        // 11th request in same minute should fail
        mockMvc.perform(get("/api/webrtc/ice-config")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status", is(429)))
                .andExpect(jsonPath("$.message", containsString("Rate limit exceeded")));
    }

    @Test
    @DisplayName("AppProperties.toString() strictly masks TURN credential")
    void turnCredential_isMaskedInAppPropertiesToString() {
        appProperties.setTurnCredential("super-secret-turn-key");
        String stringRepr = appProperties.toString();
        assertFalse(stringRepr.contains("super-secret-turn-key"), "Plaintext TURN credential leaked in toString()");
        assertTrue(stringRepr.contains("'****'"), "Masked placeholder missing in toString()");
        appProperties.setTurnCredential(null);
    }

    @Test
    @DisplayName("GET /api/webrtc/ice-config with multi-URL TURN parses comma-separated, deduplicates, and expands transports")
    void getIceConfig_multiTurnUrls_deduplicatesAndExpands() throws Exception {
        appProperties.setTurnUrl("turn:relay1.meetapp.com:3478, turns:relay2.meetapp.com:5349?transport=tcp, turn:relay1.meetapp.com:3478");
        appProperties.setTurnUsername("relay_user");
        appProperties.setTurnCredential("relay_secret_123");

        mockMvc.perform(get("/api/webrtc/ice-config")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.iceServers", hasSize(2)))
                .andExpect(jsonPath("$.iceServers[1].urls", hasItem("turn:relay1.meetapp.com:3478?transport=udp")))
                .andExpect(jsonPath("$.iceServers[1].urls", hasItem("turn:relay1.meetapp.com:3478?transport=tcp")))
                .andExpect(jsonPath("$.iceServers[1].urls", hasItem("turns:relay2.meetapp.com:5349?transport=tcp")))
                .andExpect(jsonPath("$.iceServers[1].urls", hasSize(3))); // deduplicated from 4 to 3

        // Cleanup
        appProperties.setTurnUrl(null);
        appProperties.setTurnUsername(null);
        appProperties.setTurnCredential(null);
    }

    @Test
    @DisplayName("GET /api/webrtc/ice-config ignores malformed schemes in comma-separated TURN config")
    void getIceConfig_invalidTurnUrls_ignoresMalformed() throws Exception {
        appProperties.setTurnUrl("http://malformed.turn.com, turn:valid.meetapp.com:3478, stun:invalid-in-turn");
        appProperties.setTurnUsername("relay_user");
        appProperties.setTurnCredential("relay_secret_123");

        mockMvc.perform(get("/api/webrtc/ice-config")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.iceServers", hasSize(2)))
                .andExpect(jsonPath("$.iceServers[1].urls", hasItem("turn:valid.meetapp.com:3478?transport=udp")))
                .andExpect(jsonPath("$.iceServers[1].urls", hasItem("turn:valid.meetapp.com:3478?transport=tcp")))
                .andExpect(jsonPath("$.iceServers[1].urls", hasSize(2))); // only valid.meetapp.com kept

        // Cleanup
        appProperties.setTurnUrl(null);
        appProperties.setTurnUsername(null);
        appProperties.setTurnCredential(null);
    }

    @Test
    @DisplayName("GET /api/webrtc/ice-config with only invalid schemes falls back to STUN-only")
    void getIceConfig_allInvalidTurnUrls_fallsBackToStun() throws Exception {
        appProperties.setTurnUrl("http://bad1.com, https://bad2.com");
        appProperties.setTurnUsername("relay_user");
        appProperties.setTurnCredential("relay_secret_123");

        mockMvc.perform(get("/api/webrtc/ice-config")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.iceServers", hasSize(1))) // STUN only
                .andExpect(jsonPath("$.iceServers[0].urls", hasItem("stun:stun.l.google.com:19302")));

        // Cleanup
        appProperties.setTurnUrl(null);
        appProperties.setTurnUsername(null);
        appProperties.setTurnCredential(null);
    }
}

