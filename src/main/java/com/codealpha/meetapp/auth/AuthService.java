package com.codealpha.meetapp.auth;

import com.codealpha.meetapp.auth.dto.*;
import com.codealpha.meetapp.common.config.AppProperties;
import com.codealpha.meetapp.common.exception.ConflictException;
import com.codealpha.meetapp.common.exception.TooManyRequestsException;
import com.codealpha.meetapp.common.exception.UnauthorizedException;
import com.codealpha.meetapp.otp.OtpChallenge;
import com.codealpha.meetapp.otp.OtpService;
import com.codealpha.meetapp.security.JwtService;
import com.codealpha.meetapp.security.RateLimiter;
import com.codealpha.meetapp.user.User;
import com.codealpha.meetapp.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final int MAX_LOGIN_ATTEMPTS = 5;
    private static final int LOGIN_WINDOW_SECONDS = 60;

    private final UserRepository userRepository;
    private final OtpService otpService;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final RateLimiter rateLimiter;
    private final AppProperties appProperties;
    private final String dummyPasswordHash;

    public AuthService(UserRepository userRepository,
                       OtpService otpService,
                       JwtService jwtService,
                       PasswordEncoder passwordEncoder,
                       RateLimiter rateLimiter,
                       AppProperties appProperties) {
        this.userRepository = userRepository;
        this.otpService = otpService;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.rateLimiter = rateLimiter;
        this.appProperties = appProperties;
        // Dynamically generated at startup with the same encoder and cost factor to eliminate timing side-channels
        this.dummyPasswordHash = passwordEncoder.encode("DUMMY_TIMING_SAFE_CONSTANT_HASH_VALUE_987654321");
    }

    @Transactional
    public UserResponse register(RegisterRequest request) {
        String email = request.email().trim().toLowerCase();
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new ConflictException("An account with this email already exists.");
        }

        String passwordHash = passwordEncoder.encode(request.password());
        User user = new User(request.name().trim(), email, passwordHash);
        User savedUser = userRepository.save(user);

        log.info("Registered new user with id [{}]", savedUser.getId());
        return new UserResponse(savedUser.getId(), savedUser.getName(), savedUser.getEmail());
    }

    @Transactional
    public LoginInitResponse login(LoginRequest request, String clientIp) {
        String email = request.email().trim().toLowerCase();

        String ipKey = "login:ip:" + (clientIp != null ? clientIp : "unknown");
        String emailKey = "login:email:" + email;

        if (!rateLimiter.tryAcquire(ipKey, MAX_LOGIN_ATTEMPTS * 2, LOGIN_WINDOW_SECONDS)) {
            throw new TooManyRequestsException("Too many login attempts from this network. Please wait.", LOGIN_WINDOW_SECONDS);
        }
        if (!rateLimiter.tryAcquire(emailKey, MAX_LOGIN_ATTEMPTS, LOGIN_WINDOW_SECONDS)) {
            throw new TooManyRequestsException("Too many login attempts for this account. Please wait.", LOGIN_WINDOW_SECONDS);
        }

        Optional<User> userOptional = userRepository.findByEmailIgnoreCase(email);
        if (userOptional.isEmpty()) {
            // Constant-time execution to prevent user enumeration via timing attack
            passwordEncoder.matches(request.password(), dummyPasswordHash);
            throw new UnauthorizedException("Invalid email or password.");
        }

        User user = userOptional.get();
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new UnauthorizedException("Invalid email or password.");
        }

        // Credentials valid -> create OTP challenge and email the code
        OtpChallenge challenge = otpService.createChallenge(user);
        int expiresInSeconds = appProperties.getOtpExpiryMinutes() * 60;

        return new LoginInitResponse(
                true,
                challenge.getId(),
                otpService.maskEmail(user.getEmail()),
                expiresInSeconds
        );
    }

    public VerifyOtpResponse verifyOtp(VerifyOtpRequest request) {
        User user = otpService.verifyOtp(request.challengeId(), request.otp());
        String token = jwtService.generateToken(user.getId(), user.getEmail());
        UserResponse userResponse = new UserResponse(user.getId(), user.getName(), user.getEmail());
        return new VerifyOtpResponse(token, userResponse);
    }

    public ResendOtpResponse resendOtp(ResendOtpRequest request) {
        otpService.resendOtp(request.challengeId());
        int expiresInSeconds = appProperties.getOtpExpiryMinutes() * 60;
        return new ResendOtpResponse("OTP resent successfully", expiresInSeconds);
    }
}
