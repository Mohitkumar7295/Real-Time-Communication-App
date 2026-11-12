package com.codealpha.meetapp.signaling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service managing single-use, short-lived WebSocket connection tickets.
 * Tickets expire after 30 seconds and are immediately invalidated upon first use,
 * preventing replay attacks and eliminating JWT tokens from WebSocket URL query strings.
 */
@Service
public class WsTicketService {

    private static final Logger log = LoggerFactory.getLogger(WsTicketService.class);
    private static final long TICKET_EXPIRY_SECONDS = 30L;

    private record TicketEntry(Long userId, Instant expiresAt) {}

    private final Map<String, TicketEntry> ticketStore = new ConcurrentHashMap<>();
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Issues a single-use random ticket for the given authenticated user.
     */
    public String createTicket(Long userId) {
        cleanExpiredTickets();

        byte[] randomBytes = new byte[24];
        secureRandom.nextBytes(randomBytes);
        String ticket = HexFormat.of().formatHex(randomBytes);

        Instant expiresAt = Instant.now().plusSeconds(TICKET_EXPIRY_SECONDS);
        ticketStore.put(ticket, new TicketEntry(userId, expiresAt));
        return ticket;
    }

    /**
     * Atomically consumes a ticket. Returns userId if valid and unexpired; empty otherwise.
     */
    public Optional<Long> consumeTicket(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return Optional.empty();
        }

        TicketEntry entry = ticketStore.remove(ticket.trim());
        if (entry == null) {
            log.warn("WebSocket ticket rejected: Ticket not found or already consumed");
            return Optional.empty();
        }

        if (Instant.now().isAfter(entry.expiresAt())) {
            log.warn("WebSocket ticket rejected: Ticket expired for user {}", entry.userId());
            return Optional.empty();
        }

        return Optional.of(entry.userId());
    }

    public int getExpirySeconds() {
        return (int) TICKET_EXPIRY_SECONDS;
    }

    private void cleanExpiredTickets() {
        Instant now = Instant.now();
        ticketStore.entrySet().removeIf(e -> now.isAfter(e.getValue().expiresAt()));
    }
}
