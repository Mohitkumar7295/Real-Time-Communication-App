package com.codealpha.meetapp.room;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Periodically scans for abandoned or empty active rooms and marks them ended
 * after 10 minutes of complete inactivity (no active participants).
 */
@Component
public class RoomInactivityCleaner {

    private static final Logger log = LoggerFactory.getLogger(RoomInactivityCleaner.class);
    private static final long INACTIVITY_THRESHOLD_MINUTES = 10;

    private final RoomRepository roomRepository;
    private final RoomParticipantRepository roomParticipantRepository;
    private final ApplicationEventPublisher eventPublisher;

    public RoomInactivityCleaner(RoomRepository roomRepository,
                                 RoomParticipantRepository roomParticipantRepository,
                                 ApplicationEventPublisher eventPublisher) {
        this.roomRepository = roomRepository;
        this.roomParticipantRepository = roomParticipantRepository;
        this.eventPublisher = eventPublisher;
    }

    @Scheduled(fixedDelay = 60000) // Runs every minute
    @Transactional
    public void cleanupInactiveRooms() {
        List<Room> emptyActiveRooms = roomRepository.findActiveRoomsWithNoActiveParticipants();
        if (emptyActiveRooms.isEmpty()) {
            return;
        }

        Instant cutoff = Instant.now().minus(INACTIVITY_THRESHOLD_MINUTES, ChronoUnit.MINUTES);

        for (Room room : emptyActiveRooms) {
            var lastParticipant = roomParticipantRepository.findTopByRoomIdOrderByLeftAtDesc(room.getId());

            boolean shouldEnd;
            if (lastParticipant.isPresent() && lastParticipant.get().getLeftAt() != null) {
                shouldEnd = lastParticipant.get().getLeftAt().isBefore(cutoff);
            } else {
                // No participants ever joined; check creation time
                shouldEnd = room.getCreatedAt().isBefore(cutoff);
            }

            if (shouldEnd) {
                log.info("Auto-ending abandoned room {} due to 10+ minutes of inactivity", room.getRoomCode());
                room.setActive(false);
                room.setEndedAt(Instant.now());
                roomRepository.save(room);

                eventPublisher.publishEvent(new RoomEndedEvent(this, room.getRoomCode(), room.getHost().getId()));
            }
        }
    }
}
