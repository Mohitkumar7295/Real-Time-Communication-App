package com.codealpha.meetapp.otp;

import com.codealpha.meetapp.common.config.AppProperties;
import com.codealpha.meetapp.common.exception.BadRequestException;
import com.codealpha.meetapp.common.exception.TooManyRequestsException;
import com.codealpha.meetapp.common.exception.UnauthorizedException;
import com.codealpha.meetapp.common.util.IdGenerator;
import com.codealpha.meetapp.email.EmailService;
import com.codealpha.meetapp.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

@Service
public class OtpService {

    private static final Logger log = LoggerFactory.getLogger(OtpService.class);
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final int RESEND_COOLDOWN_SECONDS = 60;
    private static final int MAX_RESEND_COUNT = 3;

    private final OtpChallengeRepository otpChallengeRepository;
    private final EmailService emailService;
    private final AppProperties appProperties;
    private final IdGenerator idGenerator;
    private final Clock clock;

    public OtpService(OtpChallengeRepository otpChallengeRepository,
                      EmailService emailService,
                      AppProperties appProperties,
                      IdGenerator idGenerator,
                      Clock clock) {
        this.otpChallengeRepository = otpChallengeRepository;
        this.emailService = emailService;
        this.appProperties = appProperties;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    @Transactional
    public OtpChallenge createChallenge(User user) {
        String rawOtp = idGenerator.generateNumericCode(6);
        String challengeId = "ch_" + idGenerator.generateAlphanumeric(24);
        Instant now = Instant.now(clock);
        Instant expiresAt = now.plus(Duration.ofMinutes(appProperties.getOtpExpiryMinutes()));
        String otpHash = hashOtp(rawOtp, appProperties.getOtpPepper());

        OtpChallenge challenge = new OtpChallenge(challengeId, user, otpHash, expiresAt);
        challenge.setLastSentAt(now);
        otpChallengeRepository.save(challenge);

        emailService.sendOtpEmail(user.getEmail(), user.getName(), rawOtp, appProperties.getOtpExpiryMinutes());
        log.info("Created OTP challenge [{}] for user [{}]", challengeId, user.getId());
        return challenge;
    }

    @Transactional(noRollbackFor = {UnauthorizedException.class, TooManyRequestsException.class})
    public User verifyOtp(String challengeId, String rawOtp) {
        OtpChallenge challenge = otpChallengeRepository.findByIdWithUser(challengeId)
                .orElseThrow(() -> new UnauthorizedException("Invalid or expired OTP."));

        if (challenge.isConsumed()) {
            throw new UnauthorizedException("Invalid or expired OTP.");
        }

        if (challenge.getAttempts() >= MAX_FAILED_ATTEMPTS) {
            throw new TooManyRequestsException("Challenge locked after 5 failed attempts.", 300);
        }

        Instant now = Instant.now(clock);
        if (now.isAfter(challenge.getExpiresAt())) {
            throw new UnauthorizedException("Invalid or expired OTP.");
        }

        String providedHash = hashOtp(rawOtp, appProperties.getOtpPepper());
        boolean matches = MessageDigest.isEqual(
                challenge.getOtpHash().getBytes(StandardCharsets.UTF_8),
                providedHash.getBytes(StandardCharsets.UTF_8)
        );

        if (!matches) {
            int newAttempts = challenge.getAttempts() + 1;
            challenge.setAttempts(newAttempts);
            otpChallengeRepository.save(challenge);

            if (newAttempts >= MAX_FAILED_ATTEMPTS) {
                log.warn("Challenge [{}] locked due to maximum failed attempts", challengeId);
                throw new TooManyRequestsException("Challenge locked after 5 failed attempts.", 300);
            }
            throw new UnauthorizedException("Invalid or expired OTP.");
        }

        challenge.setConsumed(true);
        otpChallengeRepository.save(challenge);
        log.info("Challenge [{}] successfully verified for user [{}]", challengeId, challenge.getUser().getId());
        return challenge.getUser();
    }

    @Transactional
    public void resendOtp(String challengeId) {
        OtpChallenge challenge = otpChallengeRepository.findByIdWithUser(challengeId)
                .orElseThrow(() -> new BadRequestException("Invalid challenge."));

        if (challenge.isConsumed()) {
            throw new BadRequestException("Invalid challenge: already consumed.");
        }

        if (challenge.getAttempts() >= MAX_FAILED_ATTEMPTS) {
            throw new TooManyRequestsException("Challenge locked after 5 failed attempts.", 300);
        }

        Instant now = Instant.now(clock);
        long elapsedSeconds = Duration.between(challenge.getLastSentAt(), now).toSeconds();
        if (elapsedSeconds < RESEND_COOLDOWN_SECONDS) {
            int remaining = (int) (RESEND_COOLDOWN_SECONDS - elapsedSeconds);
            throw new TooManyRequestsException("Please wait " + remaining + " seconds before requesting a new OTP.", remaining);
        }

        if (challenge.getResendCount() >= MAX_RESEND_COUNT) {
            throw new TooManyRequestsException("Maximum OTP resend limit reached.", 300);
        }

        String newRawOtp = idGenerator.generateNumericCode(6);
        String newHash = hashOtp(newRawOtp, appProperties.getOtpPepper());
        Instant newExpiresAt = now.plus(Duration.ofMinutes(appProperties.getOtpExpiryMinutes()));

        challenge.setOtpHash(newHash);
        challenge.setResendCount(challenge.getResendCount() + 1);
        challenge.setLastSentAt(now);
        challenge.setExpiresAt(newExpiresAt);
        otpChallengeRepository.save(challenge);

        User user = challenge.getUser();
        emailService.sendOtpEmail(user.getEmail(), user.getName(), newRawOtp, appProperties.getOtpExpiryMinutes());
        log.info("Resent new OTP for challenge [{}], attempt count: {}", challengeId, challenge.getResendCount());
    }

    public String maskEmail(String email) {
        if (email == null || !email.contains("@")) {
            return "***";
        }
        int atIndex = email.indexOf('@');
        String local = email.substring(0, atIndex);
        String domain = email.substring(atIndex);
        if (local.length() <= 1) {
            return local + "***" + domain;
        }
        return local.charAt(0) + "***" + domain;
    }

    public String hashOtp(String otp, String pepper) {
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(pepper.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            hmac.init(secretKey);
            byte[] hashBytes = hmac.doFinal(otp.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to calculate HMAC-SHA256 of OTP", ex);
        }
    }
}
