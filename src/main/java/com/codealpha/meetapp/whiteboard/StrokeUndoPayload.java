package com.codealpha.meetapp.whiteboard;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Payload for undoing a previously drawn stroke.
 */
public class StrokeUndoPayload {

    private final String strokeId;

    @JsonCreator
    public StrokeUndoPayload(@JsonProperty("strokeId") String strokeId) {
        this.strokeId = strokeId;
    }

    public String getStrokeId() {
        return strokeId;
    }
}
