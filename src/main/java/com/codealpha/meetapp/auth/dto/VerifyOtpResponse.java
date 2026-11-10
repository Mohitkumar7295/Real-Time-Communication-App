package com.codealpha.meetapp.auth.dto;

public record VerifyOtpResponse(
        String token,
        UserResponse user
) {}
