package com.codealpha.meetapp.webrtc.dto;

import java.util.List;

/**
 * Top-level response wrapper for WebRTC ICE server configurations.
 */
public class IceConfigResponse {

    private List<IceServerResponse> iceServers;

    public IceConfigResponse() {
    }

    public IceConfigResponse(List<IceServerResponse> iceServers) {
        this.iceServers = iceServers;
    }

    public List<IceServerResponse> getIceServers() {
        return iceServers;
    }

    public void setIceServers(List<IceServerResponse> iceServers) {
        this.iceServers = iceServers;
    }
}

