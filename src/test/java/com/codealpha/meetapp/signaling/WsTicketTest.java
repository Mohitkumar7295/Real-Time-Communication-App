package com.codealpha.meetapp.signaling;

import com.codealpha.meetapp.security.RateLimiter;
import com.codealpha.meetapp.security.UserPrincipal;
import com.codealpha.meetapp.signaling.dto.WsTicketResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class WsTicketTest {

    private WsTicketService ticketService;
    private RateLimiter rateLimiter;
    private WsTicketController ticketController;

    @BeforeEach
    void setUp() {
        ticketService = new WsTicketService();
        rateLimiter = new RateLimiter();
        ticketController = new WsTicketController(ticketService, rateLimiter);
    }

    @Test
    @DisplayName("ticket can be created and consumed exactly once")
    void ticketLifecycle_singleUse() {
        String ticket = ticketService.createTicket(42L);
        assertNotNull(ticket);
        assertFalse(ticket.isBlank());

        // First consumption succeeds
        Optional<Long> userIdOpt1 = ticketService.consumeTicket(ticket);
        assertTrue(userIdOpt1.isPresent());
        assertEquals(42L, userIdOpt1.get());

        // Second consumption fails (single use)
        Optional<Long> userIdOpt2 = ticketService.consumeTicket(ticket);
        assertTrue(userIdOpt2.isEmpty());
    }

    @Test
    @DisplayName("consuming unknown ticket returns empty")
    void consumeTicket_unknown() {
        Optional<Long> res = ticketService.consumeTicket("non-existent-ticket");
        assertTrue(res.isEmpty());
    }

    @Test
    @DisplayName("controller issues ticket with 200 OK and no-store header")
    void controller_issuesTicket() {
        UserPrincipal principal = new UserPrincipal(10L, "test@example.com");
        ResponseEntity<WsTicketResponse> response = ticketController.issueTicket(principal);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertNotNull(response.getBody().ticket());
        assertEquals(30, response.getBody().expiresInSeconds());
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
    }
}
