package com.codealpha.meetapp.signaling.dto;

public record WsTicketResponse(String ticket, int expiresInSeconds) {
}
