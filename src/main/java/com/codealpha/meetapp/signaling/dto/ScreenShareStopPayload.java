package com.codealpha.meetapp.signaling.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScreenShareStopPayload(Long userId, String reason) {
}
