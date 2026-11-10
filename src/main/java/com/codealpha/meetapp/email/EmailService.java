package com.codealpha.meetapp.email;

public interface EmailService {
    void sendOtpEmail(String toEmail, String recipientName, String otpCode, int expiryMinutes);
}
