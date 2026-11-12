package com.codealpha.meetapp.signaling.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Payload sent in the join confirmation back to the connecting client.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JoinAckPayload {

    private Long userId;
    private String roomCode;
    private List<PeerParticipantDto> peers;
    private PresenterInfo presenter;

    public JoinAckPayload() {
    }

    public JoinAckPayload(Long userId, String roomCode, List<PeerParticipantDto> peers) {
        this(userId, roomCode, peers, null);
    }

    public JoinAckPayload(Long userId, String roomCode, List<PeerParticipantDto> peers, PresenterInfo presenter) {
        this.userId = userId;
        this.roomCode = roomCode;
        this.peers = peers;
        this.presenter = presenter;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getRoomCode() {
        return roomCode;
    }

    public void setRoomCode(String roomCode) {
        this.roomCode = roomCode;
    }

    public List<PeerParticipantDto> getPeers() {
        return peers;
    }

    public void setPeers(List<PeerParticipantDto> peers) {
        this.peers = peers;
    }

    public PresenterInfo getPresenter() {
        return presenter;
    }

    public void setPresenter(PresenterInfo presenter) {
        this.presenter = presenter;
    }
}
