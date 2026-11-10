package com.codealpha.meetapp.auth.dto;

public record LoginInitResponse(
        boolean otpRequired,
        String challengeId,
        String maskedEmail,
        int expiresInSeconds
) {}
