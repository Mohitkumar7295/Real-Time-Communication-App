package com.codealpha.meetapp.signaling.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PresenterInfo(Long userId, String streamId) {
}
