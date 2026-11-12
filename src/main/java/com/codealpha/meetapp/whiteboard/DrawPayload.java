package com.codealpha.meetapp.whiteboard;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Incoming and outgoing payload for drawing stroke segments.
 */
public class DrawPayload {

    private final String strokeId;
    private final String tool;
    private final String color;
    private final Double width;
    private final List<double[]> points;

    @JsonProperty("final")
    private final Boolean isFinal;

    @JsonCreator
    public DrawPayload(@JsonProperty("strokeId") String strokeId,
                       @JsonProperty("tool") String tool,
                       @JsonProperty("color") String color,
                       @JsonProperty("width") Double width,
                       @JsonProperty("points") List<double[]> points,
                       @JsonProperty("final") Boolean isFinal) {
        this.strokeId = strokeId;
        this.tool = tool;
        this.color = color;
        this.width = width;
        this.points = points;
        this.isFinal = isFinal;
    }

    public String getStrokeId() {
        return strokeId;
    }

    public String getTool() {
        return tool;
    }

    public String getColor() {
        return color;
    }

    public Double getWidth() {
        return width;
    }

    public List<double[]> getPoints() {
        return points;
    }

    @JsonProperty("final")
    public Boolean getIsFinal() {
        return isFinal;
    }
}
