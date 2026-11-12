package com.codealpha.meetapp.security;

import com.codealpha.meetapp.common.config.AppProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@org.springframework.context.annotation.Import(SecurityIntegrationTest.TestProtectedController.class)
class SecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private AppProperties appProperties;

    @Test
    @DisplayName("GET /actuator/health is public and accessible without authentication")
    void health_isPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("Unauthenticated request to protected /api endpoint returns standard 401 ApiError")
    void protectedEndpoint_withoutToken_returns401ApiError() throws Exception {
        mockMvc.perform(get("/api/test-protected"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Full authentication is required to access this resource."))
                .andExpect(jsonPath("$.path").value("/api/test-protected"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("Request with invalid Bearer token returns 401 ApiError")
    void protectedEndpoint_withInvalidToken_returns401ApiError() throws Exception {
        mockMvc.perform(get("/api/test-protected")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-tampered-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    @Test
    @DisplayName("Request with valid Bearer token successfully accesses protected endpoint")
    void protectedEndpoint_withValidToken_succeeds() throws Exception {
        String token = jwtService.generateToken(100L, "authorized@example.com");

        mockMvc.perform(get("/api/test-protected")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(100))
                .andExpect(jsonPath("$.email").value("authorized@example.com"));
    }

    @Test
    @DisplayName("Security headers (HSTS, nosniff, FrameOptions DENY, strict referrer) are present")
    void securityHeaders_areEnforced() throws Exception {
        mockMvc.perform(get("/actuator/health").secure(true))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
                .andExpect(header().string("Strict-Transport-Security", containsString("max-age=31536000")));
    }

    @Test
    @DisplayName("CORS preflight through Spring Security chain permits allowed origin")
    void corsPreflight_allowedOrigin_succeeds() throws Exception {
        String allowedOrigin = appProperties.getAllowedOrigin();

        mockMvc.perform(options("/api/test-protected")
                        .header(HttpHeaders.ORIGIN, allowedOrigin)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization,Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, allowedOrigin))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString("GET")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, containsString("Authorization")));
    }

    @Test
    @DisplayName("CORS preflight through Spring Security chain rejects disallowed origin with 403")
    void corsPreflight_disallowedOrigin_rejected() throws Exception {
        mockMvc.perform(options("/api/test-protected")
                        .header(HttpHeaders.ORIGIN, "http://untrusted-site.com")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @RestController
    @RequestMapping("/api")
    static class TestProtectedController {

        @GetMapping("/test-protected")
        public ResponseEntity<Map<String, Object>> testProtected(@AuthenticationPrincipal UserPrincipal principal) {
            return ResponseEntity.ok(Map.of("userId", principal.getId(), "email", principal.getEmail()));
        }
    }
}
