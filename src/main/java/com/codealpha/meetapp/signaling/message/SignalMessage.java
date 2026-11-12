package com.codealpha.meetapp.signaling.message;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Standard WebSocket signaling message envelope.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SignalMessage {

    private MessageType type;
    private String roomCode;
    private Long from;
    private Long to;
    private Object payload;

    public SignalMessage() {
    }

    public SignalMessage(MessageType type, String roomCode, Long from, Long to, Object payload) {
        this.type = type;
        this.roomCode = roomCode;
        this.from = from;
        this.to = to;
        this.payload = payload;
    }

    public static SignalMessage of(MessageType type, String roomCode, Object payload) {
        return new SignalMessage(type, roomCode, null, null, payload);
    }

    public static SignalMessage error(String roomCode, String code, String message) {
        return new SignalMessage(MessageType.ERROR, roomCode, null, null,
                new ErrorPayload(code, message));
    }

    public MessageType getType() {
        return type;
    }

    public void setType(MessageType type) {
        this.type = type;
    }

    public String getRoomCode() {
        return roomCode;
    }

    public void setRoomCode(String roomCode) {
        this.roomCode = roomCode;
    }

    public Long getFrom() {
        return from;
    }

    public void setFrom(Long from) {
        this.from = from;
    }

    public Long getTo() {
        return to;
    }

    public void setTo(Long to) {
        this.to = to;
    }

    public Object getPayload() {
        return payload;
    }

    public void setPayload(Object payload) {
        this.payload = payload;
    }

    public record ErrorPayload(String code, String message) {
    }
}

