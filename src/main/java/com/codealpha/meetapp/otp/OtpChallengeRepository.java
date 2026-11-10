package com.codealpha.meetapp.otp;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface OtpChallengeRepository extends JpaRepository<OtpChallenge, String> {

    Optional<OtpChallenge> findByIdAndConsumedFalseAndExpiresAtAfter(String id, Instant now);

    @Modifying
    @Query("DELETE FROM OtpChallenge o WHERE o.expiresAt < :cutoff")
    int deleteExpiredOlderThan(@Param("cutoff") Instant cutoff);
}
