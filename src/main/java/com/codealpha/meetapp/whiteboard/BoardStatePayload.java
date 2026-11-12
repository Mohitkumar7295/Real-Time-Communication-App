package com.codealpha.meetapp.whiteboard;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Payload for chunked board state synchronization to late joiners.
 */
public class BoardStatePayload {

    private final int chunkIndex;
    private final int totalChunks;
    private final List<WhiteboardStroke> strokes;

    @JsonCreator
    public BoardStatePayload(@JsonProperty("chunkIndex") int chunkIndex,
                             @JsonProperty("totalChunks") int totalChunks,
                             @JsonProperty("strokes") List<WhiteboardStroke> strokes) {
        this.chunkIndex = chunkIndex;
        this.totalChunks = totalChunks;
        this.strokes = strokes;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public int getTotalChunks() {
        return totalChunks;
    }

    public List<WhiteboardStroke> getStrokes() {
        return strokes;
    }
}
