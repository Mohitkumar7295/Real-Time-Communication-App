package com.codealpha.meetapp.common;

import com.codealpha.meetapp.common.config.AppProperties;
import com.codealpha.meetapp.common.config.CorsConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CorsTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:8080";
    private static final String DISALLOWED_ORIGIN = "http://malicious-site.com";

    private MockMvc mockMvc;

    @RestController
    @RequestMapping("/api")
    static class TestCorsController {
        @GetMapping("/test-cors")
        public ResponseEntity<Map<String, String>> testCors() {
            return ResponseEntity.ok(Map.of("status", "ok"));
        }
    }

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties();
        properties.setAllowedOrigin(ALLOWED_ORIGIN);

        CorsConfig corsConfig = new CorsConfig(properties);

        this.mockMvc = MockMvcBuilders
                .standaloneSetup(new TestCorsController())
                .addFilters(new org.springframework.web.filter.CorsFilter(corsConfig.corsConfigurationSource()))
                .build();
    }

    @Test
    @DisplayName("OPTIONS preflight from allowed origin succeeds and returns CORS headers")
    void preflight_allowedOrigin_succeeds() throws Exception {
        mockMvc.perform(options("/api/test-cors")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization,Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString("GET")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString("POST")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, containsString("Authorization")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "3600"));
    }

    @Test
    @DisplayName("GET request from allowed origin returns Access-Control-Allow-Origin header")
    void getRequest_allowedOrigin_includesHeader() throws Exception {
        mockMvc.perform(get("/api/test-cors")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    @DisplayName("OPTIONS preflight from disallowed origin is rejected with 403 and omits Access-Control-Allow-Origin")
    void preflight_disallowedOrigin_omitsAllowOriginHeader() throws Exception {
        mockMvc.perform(options("/api/test-cors")
                        .header(HttpHeaders.ORIGIN, DISALLOWED_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    @DisplayName("GET request from disallowed origin is rejected with 403 and omits Access-Control-Allow-Origin")
    void getRequest_disallowedOrigin_omitsAllowOriginHeader() throws Exception {
        mockMvc.perform(get("/api/test-cors")
                        .header(HttpHeaders.ORIGIN, DISALLOWED_ORIGIN))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }
}
