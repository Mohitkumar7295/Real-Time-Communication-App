package com.codealpha.meetapp.auth;

import com.codealpha.meetapp.auth.dto.LoginRequest;
import com.codealpha.meetapp.auth.dto.RegisterRequest;
import com.codealpha.meetapp.auth.dto.VerifyOtpRequest;
import com.codealpha.meetapp.otp.OtpChallenge;
import com.codealpha.meetapp.otp.OtpChallengeRepository;
import com.codealpha.meetapp.otp.OtpService;
import com.codealpha.meetapp.user.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OtpChallengeRepository otpChallengeRepository;

    @Autowired
    private OtpService otpService;

    @BeforeEach
    void setUp() {
        otpChallengeRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("POST /api/auth/register creates user in database and returns 201 Created")
    void register_success() throws Exception {
        RegisterRequest request = new RegisterRequest("Aarav Sharma", "aarav@example.com", "Password123!");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value("Aarav Sharma"))
                .andExpect(jsonPath("$.email").value("aarav@example.com"));

        assertThat(userRepository.findByEmailIgnoreCase("aarav@example.com")).isPresent();
    }

    @Test
    @DisplayName("POST /api/auth/register with duplicate email returns 409 Conflict")
    void register_duplicateEmail_returns409() throws Exception {
        RegisterRequest request = new RegisterRequest("Aarav Sharma", "aarav@example.com", "Password123!");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value(containsString("already exists")));
    }

    @Test
    @DisplayName("POST /api/auth/register with invalid email or weak password returns 400 Bad Request")
    void register_invalidInput_returns400() throws Exception {
        RegisterRequest request = new RegisterRequest("A", "bad-email", "short");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }

    @Test
    @DisplayName("Full Login Flow: Step 1 initiates OTP, Step 2 verifies OTP and issues valid JWT")
    void fullLoginAndOtpFlow_success() throws Exception {
        // 1. Register
        RegisterRequest regReq = new RegisterRequest("Mohit Kumar", "mohit@example.com", "SecretPass1!");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(regReq)))
                .andExpect(status().isCreated());

        // 2. Login step 1
        LoginRequest loginReq = new LoginRequest("mohit@example.com", "SecretPass1!");
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.otpRequired").value(true))
                .andExpect(jsonPath("$.challengeId").isNotEmpty())
                .andExpect(jsonPath("$.maskedEmail").value("m***@example.com"))
                .andReturn();

        JsonNode loginNode = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        String challengeId = loginNode.get("challengeId").asText();

        // Retrieve challenge to simulate valid OTP
        OtpChallenge challenge = otpChallengeRepository.findById(challengeId).orElseThrow();
        String validTestOtp = "123456";
        challenge.setOtpHash(otpService.hashOtp(validTestOtp, "default-otp-pepper-secret-value-32chars"));
        otpChallengeRepository.save(challenge);

        // 3. Login step 2 (verify OTP)
        VerifyOtpRequest verifyReq = new VerifyOtpRequest(challengeId, validTestOtp);
        MvcResult verifyResult = mockMvc.perform(post("/api/auth/verify-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(verifyReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value("mohit@example.com"))
                .andReturn();

        JsonNode verifyNode = objectMapper.readTree(verifyResult.getResponse().getContentAsString());
        String jwtToken = verifyNode.get("token").asText();

        // 4. Access protected GET /api/users/me with token
        mockMvc.perform(get("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Mohit Kumar"))
                .andExpect(jsonPath("$.email").value("mohit@example.com"));
    }

    @Test
    @DisplayName("POST /api/auth/login with invalid password returns 401 Unauthorized")
    void login_wrongPassword_returns401() throws Exception {
        RegisterRequest regReq = new RegisterRequest("Priya Sharma", "priya@example.com", "ValidPass123!");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(regReq)))
                .andExpect(status().isCreated());

        LoginRequest loginReq = new LoginRequest("priya@example.com", "WrongPassword999!");
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Invalid email or password."));
    }

    @Test
    @DisplayName("POST /api/auth/verify-otp with wrong OTP increments attempts and returns 401")
    void verifyOtp_wrongOtp_returns401() throws Exception {
        RegisterRequest regReq = new RegisterRequest("Test User", "test@example.com", "TestPass123!");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(regReq)))
                .andExpect(status().isCreated());

        LoginRequest loginReq = new LoginRequest("test@example.com", "TestPass123!");
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode loginNode = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        String challengeId = loginNode.get("challengeId").asText();

        VerifyOtpRequest wrongOtpReq = new VerifyOtpRequest(challengeId, "999999");
        mockMvc.perform(post("/api/auth/verify-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(wrongOtpReq)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Invalid or expired OTP."));

        OtpChallenge challenge = otpChallengeRepository.findById(challengeId).orElseThrow();
        assertThat(challenge.getAttempts()).isEqualTo(1);
    }
}
