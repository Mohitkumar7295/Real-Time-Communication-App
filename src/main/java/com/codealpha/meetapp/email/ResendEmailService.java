package com.codealpha.meetapp.email;

import com.codealpha.meetapp.common.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Service
@ConditionalOnProperty(name = "app.email-provider", havingValue = "resend")
public class ResendEmailService implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(ResendEmailService.class);

    private final RestClient restClient;
    private final AppProperties appProperties;

    public ResendEmailService(RestClient.Builder restClientBuilder, AppProperties appProperties) {
        this.appProperties = appProperties;
        this.restClient = restClientBuilder
                .baseUrl("https://api.resend.com")
                .build();
    }

    @Override
    public void sendOtpEmail(String toEmail, String recipientName, String otpCode, int expiryMinutes) {
        String apiKey = appProperties.getResendApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.error("Resend API key is not configured.");
            throw new IllegalStateException("Email service misconfigured: missing RESEND_API_KEY");
        }

        String from = appProperties.getMailFrom();
        String subject = "Your MeetApp Verification Code: " + otpCode;
        String text = "Hello " + recipientName + ",\n\nYour MeetApp login verification code is: " + otpCode
                + "\n\nThis code is valid for " + expiryMinutes + " minutes. If you did not request this code, please ignore this email.";
        String html = "<div style=\"font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; max-width: 500px; margin: 0 auto; padding: 24px; border: 1px solid #e2e8f0; border-radius: 8px; background: #ffffff;\">"
                + "<h2 style=\"color: #0f172a; margin-top: 0;\">MeetApp Verification Code</h2>"
                + "<p style=\"color: #334155; font-size: 15px;\">Hello <strong>" + escapeHtml(recipientName) + "</strong>,</p>"
                + "<p style=\"color: #334155; font-size: 15px;\">Use the following one-time code to complete your login:</p>"
                + "<div style=\"font-size: 32px; font-weight: bold; letter-spacing: 6px; color: #0284c7; padding: 16px; background: #f8fafc; text-align: center; border-radius: 6px; margin: 20px 0; border: 1px dashed #cbd5e1;\">"
                + otpCode + "</div>"
                + "<p style=\"color: #64748b; font-size: 13px; margin-bottom: 0;\">This code is valid for " + expiryMinutes + " minutes. Never share this code with anyone.</p>"
                + "</div>";

        Map<String, Object> body = Map.of(
                "from", from,
                "to", List.of(toEmail),
                "subject", subject,
                "text", text,
                "html", html
        );

        try {
            restClient.post()
                    .uri("/emails")
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Dispatched OTP email via Resend to recipient: {}", maskEmail(toEmail));
        } catch (Exception ex) {
            log.error("Failed to send OTP email via Resend to {}: {}", maskEmail(toEmail), ex.getMessage());
            throw new RuntimeException("Failed to send OTP verification email. Please try again later.", ex);
        }
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private String maskEmail(String email) {
        if (email == null || !email.contains("@")) return "***";
        int atIdx = email.indexOf('@');
        String name = email.substring(0, atIdx);
        String domain = email.substring(atIdx);
        if (name.length() <= 1) return name + "***" + domain;
        return name.charAt(0) + "***" + domain;
    }
}
