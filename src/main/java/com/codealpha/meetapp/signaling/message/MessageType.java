package com.codealpha.meetapp.signaling.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Enumeration of all signaling event types.
 */
public enum MessageType {

    // Foundation signaling & presence
    JOIN("join"),
    PEER_JOINED("peer-joined"),
    PEER_LEFT("peer-left"),
    PING("ping"),
    PONG("pong"),
    ROOM_ENDED("room-ended"),
    ERROR("error"),

    // WebRTC Media relays (Phase 7)
    OFFER("offer"),
    ANSWER("answer"),
    ICE_CANDIDATE("ice-candidate"),
    MUTE_STATUS("mute-status"),

    // Whiteboard collaboration (Phase 8)
    DRAW("draw"),
    STROKE_UNDO("stroke-undo"),
    CLEAR_BOARD("clear-board"),
    BOARD_STATE("board-state"),
    SCREEN_SHARE_START("screen-share-start"),
    SCREEN_SHARE_STOP("screen-share-stop"),
    CHAT("chat"),
    FILE_SHARED("file-shared"),
    FILE_DELETED("file-deleted");

    private final String value;

    MessageType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static MessageType fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (MessageType type : values()) {
            if (type.value.equalsIgnoreCase(value.trim())) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown message type: " + value);
    }
}

