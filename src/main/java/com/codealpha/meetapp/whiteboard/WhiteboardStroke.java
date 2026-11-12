package com.codealpha.meetapp.whiteboard;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable/thread-safe representation of an in-memory whiteboard stroke.
 */
public class WhiteboardStroke {

    private final String strokeId;
    private final Long userId;
    private final String tool;
    private final String color;
    private final double width;
    private final List<double[]> points;

    @JsonProperty("final")
    private final boolean isFinal;

    public WhiteboardStroke(String strokeId, Long userId, String tool, String color, double width, List<double[]> points, boolean isFinal) {
        this.strokeId = strokeId;
        this.userId = userId;
        this.tool = tool;
        this.color = color;
        this.width = width;
        this.points = points != null ? new ArrayList<>(points) : new ArrayList<>();
        this.isFinal = isFinal;
    }

    public String getStrokeId() {
        return strokeId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getTool() {
        return tool;
    }

    public String getColor() {
        return color;
    }

    public double getWidth() {
        return width;
    }

    public List<double[]> getPoints() {
        return Collections.unmodifiableList(points);
    }

    @JsonProperty("final")
    public boolean isFinal() {
        return isFinal;
    }

    public synchronized void appendPoints(List<double[]> newPoints, boolean isFinalState) {
        if (newPoints != null) {
            this.points.addAll(newPoints);
        }
    }
}
