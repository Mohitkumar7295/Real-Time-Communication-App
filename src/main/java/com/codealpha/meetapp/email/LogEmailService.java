package com.codealpha.meetapp.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "app.email-provider", havingValue = "log", matchIfMissing = true)
public class LogEmailService implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(LogEmailService.class);

    @Override
    public void sendOtpEmail(String toEmail, String recipientName, String otpCode, int expiryMinutes) {
        log.info("[OTP LOGIN] Dev Email Log -> To: {}, Name: {}, Code: [{}], Valid: {} min",
                toEmail, recipientName, otpCode, expiryMinutes);
    }
}
