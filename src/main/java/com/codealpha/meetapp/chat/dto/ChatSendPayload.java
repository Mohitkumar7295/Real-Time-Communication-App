package com.codealpha.meetapp.chat.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Inbound payload sent by client for 'chat' signaling message.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatSendPayload(
        String text,
        String clientId
) {
}
