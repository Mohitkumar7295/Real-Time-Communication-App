package com.codealpha.meetapp.otp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Component
public class OtpChallengeCleaner {

    private static final Logger log = LoggerFactory.getLogger(OtpChallengeCleaner.class);

    private final OtpChallengeRepository otpChallengeRepository;

    public OtpChallengeCleaner(OtpChallengeRepository otpChallengeRepository) {
        this.otpChallengeRepository = otpChallengeRepository;
    }

    /**
     * Periodically prunes expired challenges older than 24 hours. Runs once every hour.
     */
    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void cleanupExpiredChallenges() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(1));
        int deleted = otpChallengeRepository.deleteExpiredOlderThan(cutoff);
        if (deleted > 0) {
            log.info("Pruned {} expired OTP challenges older than 24 hours.", deleted);
        }
    }
}
