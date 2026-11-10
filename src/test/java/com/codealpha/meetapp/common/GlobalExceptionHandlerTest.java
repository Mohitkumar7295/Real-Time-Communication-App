package com.codealpha.meetapp.common;

import com.codealpha.meetapp.common.exception.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;
    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-11-10T12:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders
                .standaloneSetup(new TestExceptionController())
                .setControllerAdvice(new GlobalExceptionHandler(fixedClock))
                .build();
    }

    @Test
    @DisplayName("BadRequestException maps to HTTP 400 with standard ApiError envelope")
    void handleBadRequest() throws Exception {
        mockMvc.perform(get("/test/bad-request"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.timestamp").value("2026-11-10T12:00:00Z"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Malformed request parameters."))
                .andExpect(jsonPath("$.path").value("/test/bad-request"))
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }

    @Test
    @DisplayName("NotFoundException maps to HTTP 404 with standard envelope")
    void handleNotFound() throws Exception {
        mockMvc.perform(get("/test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Resource not found."))
                .andExpect(jsonPath("$.path").value("/test/not-found"));
    }

    @Test
    @DisplayName("ForbiddenException maps to HTTP 403")
    void handleForbidden() throws Exception {
        mockMvc.perform(get("/test/forbidden"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.message").value("You do not have permission."));
    }

    @Test
    @DisplayName("ConflictException maps to HTTP 409")
    void handleConflict() throws Exception {
        mockMvc.perform(get("/test/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value("Email already registered."));
    }

    @Test
    @DisplayName("RoomFullException maps to HTTP 409 Conflict")
    void handleRoomFull() throws Exception {
        mockMvc.perform(get("/test/room-full"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value("Room has reached its maximum participant limit."));
    }

    @Test
    @DisplayName("TooManyRequestsException maps to HTTP 429 and includes Retry-After header")
    void handleTooManyRequests() throws Exception {
        mockMvc.perform(get("/test/too-many-requests"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "45"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.error").value("Too Many Requests"))
                .andExpect(jsonPath("$.message").value("Rate limit exceeded. Please wait."));
    }

    @Test
    @DisplayName("PayloadTooLargeException maps to HTTP 413")
    void handlePayloadTooLarge() throws Exception {
        mockMvc.perform(get("/test/payload-too-large"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.status").value(413))
                .andExpect(jsonPath("$.error").value("Payload Too Large"))
                .andExpect(jsonPath("$.message").value("File size exceeds 10MB limit."));
    }

    @Test
    @DisplayName("ServiceUnavailableException maps to HTTP 503")
    void handleServiceUnavailable() throws Exception {
        mockMvc.perform(get("/test/service-unavailable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.error").value("Service Unavailable"))
                .andExpect(jsonPath("$.message").value("Downstream service is currently unreachable."));
    }

    @Test
    @DisplayName("MethodArgumentNotValidException formats fieldErrors without leaking rejected values")
    void handleValidationErrors() throws Exception {
        String invalidPayload = "{\"email\":\"not-an-email\",\"name\":\"\"}";

        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Validation failed for one or more fields."))
                .andExpect(jsonPath("$.fieldErrors", hasSize(2)))
                .andExpect(jsonPath("$.fieldErrors[*].field", containsInAnyOrder("email", "name")))
                .andExpect(jsonPath("$.fieldErrors[*].message", not(empty())))
                .andExpect(jsonPath("$..rejectedValue").doesNotExist());
    }

    @Test
    @DisplayName("Malformed JSON request returns 400 with clean message")
    void handleMalformedJson() throws Exception {
        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{malformed-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Malformed JSON request body."));
    }

    @Test
    @DisplayName("Unhandled exception returns 500 with generic message and no stack trace")
    void handleUnexpectedException_suppressesDetails() throws Exception {
        mockMvc.perform(get("/test/unhandled"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error").value("Internal Server Error"))
                .andExpect(jsonPath("$.message").value("An unexpected server error occurred."))
                .andExpect(jsonPath("$.stackTrace").doesNotExist())
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist());
    }

    @RestController
    @RequestMapping("/test")
    static class TestExceptionController {

        @GetMapping("/bad-request")
        public void badRequest() {
            throw new BadRequestException("Malformed request parameters.");
        }

        @GetMapping("/not-found")
        public void notFound() {
            throw new NotFoundException("Resource not found.");
        }

        @GetMapping("/forbidden")
        public void forbidden() {
            throw new ForbiddenException("You do not have permission.");
        }

        @GetMapping("/conflict")
        public void conflict() {
            throw new ConflictException("Email already registered.");
        }

        @GetMapping("/room-full")
        public void roomFull() {
            throw new RoomFullException();
        }

        @GetMapping("/too-many-requests")
        public void tooManyRequests() {
            throw new TooManyRequestsException("Rate limit exceeded. Please wait.", 45);
        }

        @GetMapping("/payload-too-large")
        public void payloadTooLarge() {
            throw new PayloadTooLargeException("File size exceeds 10MB limit.");
        }

        @GetMapping("/service-unavailable")
        public void serviceUnavailable() {
            throw new ServiceUnavailableException("Downstream service is currently unreachable.");
        }

        @PostMapping("/validate")
        public void validate(@Valid @RequestBody TestRequest request) {
            // Echo endpoint for validation testing
        }

        @GetMapping("/unhandled")
        public void unhandled() {
            throw new RuntimeException("Sensitive internal database connection error: password=secret");
        }
    }

    static class TestRequest {
        @NotBlank(message = "Name must not be blank.")
        private String name;

        @NotBlank(message = "Email must not be blank.")
        @Email(message = "Must be a well-formed email address.")
        private String email;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }
    }
}
