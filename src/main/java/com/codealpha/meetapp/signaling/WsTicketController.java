package com.codealpha.meetapp.signaling;

import com.codealpha.meetapp.common.exception.TooManyRequestsException;
import com.codealpha.meetapp.common.exception.UnauthorizedException;
import com.codealpha.meetapp.security.RateLimiter;
import com.codealpha.meetapp.security.UserPrincipal;
import com.codealpha.meetapp.signaling.dto.WsTicketResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller issuing short-lived, single-use tickets for WebSocket connection establishment.
 */
@RestController
@RequestMapping("/api/ws-ticket")
public class WsTicketController {

    private static final int RATE_LIMIT_MAX_ATTEMPTS = 30;
    private static final int RATE_LIMIT_WINDOW_SECONDS = 60;

    private final WsTicketService wsTicketService;
    private final RateLimiter rateLimiter;

    public WsTicketController(WsTicketService wsTicketService, RateLimiter rateLimiter) {
        this.wsTicketService = wsTicketService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping
    public ResponseEntity<WsTicketResponse> issueTicket(@AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null || principal.getId() == null) {
            throw new UnauthorizedException("Authentication required to obtain WebSocket ticket");
        }

        String rateLimitKey = "ws-ticket:" + principal.getId();
        if (!rateLimiter.tryAcquire(rateLimitKey, RATE_LIMIT_MAX_ATTEMPTS, RATE_LIMIT_WINDOW_SECONDS)) {
            throw new TooManyRequestsException("Too many ticket requests. Please try again shortly.");
        }

        String ticket = wsTicketService.createTicket(principal.getId());

        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(new WsTicketResponse(ticket, wsTicketService.getExpirySeconds()));
    }
}
