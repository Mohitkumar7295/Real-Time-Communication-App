package com.codealpha.meetapp.auth.dto;

public record ResendOtpResponse(
        String message,
        int expiresInSeconds
) {}
