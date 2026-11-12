package com.codealpha.meetapp.signaling.dto;

/**
 * Payload carrying audio and video mute states.
 */
public class MuteStatusPayload {

    private boolean audioMuted;
    private boolean videoMuted;

    public MuteStatusPayload() {
    }

    public MuteStatusPayload(boolean audioMuted, boolean videoMuted) {
        this.audioMuted = audioMuted;
        this.videoMuted = videoMuted;
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

