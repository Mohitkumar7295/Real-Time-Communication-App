package com.codealpha.meetapp.signaling.dto;

/**
 * Summary DTO of a peer delivered in the join confirmation.
 * Never includes sensitive fields like email or password.
 */
public class PeerParticipantDto {

    private Long userId;
    private String name;
    private boolean audioMuted;
    private boolean videoMuted;

    public PeerParticipantDto() {
    }

    public PeerParticipantDto(Long userId, String name, boolean audioMuted, boolean videoMuted) {
        this.userId = userId;
        this.name = name;
        this.audioMuted = audioMuted;
        this.videoMuted = videoMuted;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isAudioMuted() {
        return audioMuted;
    }

    public void setAudioMuted(boolean audioMuted) {
        this.audioMuted = audioMuted;
    }

    public boolean isVideoMuted() {
        return videoMuted;
    }

    public void setVideoMuted(boolean videoMuted) {
        this.videoMuted = videoMuted;
    }
}

