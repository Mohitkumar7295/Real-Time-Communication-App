package com.codealpha.meetapp.room;

import org.springframework.context.ApplicationEvent;

/**
 * Event published when a room is explicitly terminated by its host.
 */
public class RoomEndedEvent extends ApplicationEvent {

    private final String roomCode;
    private final Long hostId;

    public RoomEndedEvent(Object source, String roomCode, Long hostId) {
        super(source);
        this.roomCode = roomCode;
        this.hostId = hostId;
    }

    public String getRoomCode() {
        return roomCode;
    }

    public Long getHostId() {
        return hostId;
    }
}

