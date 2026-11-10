package com.codealpha.meetapp.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record ResendOtpRequest(
        @NotBlank(message = "Challenge ID is required")
        String challengeId
) {}
