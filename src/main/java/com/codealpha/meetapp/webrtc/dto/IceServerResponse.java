package com.codealpha.meetapp.webrtc.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Representation of an RTCIceServer entry provided to the client.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class IceServerResponse {

    private List<String> urls;
    private String username;
    private String credential;

    public IceServerResponse() {
    }

    public IceServerResponse(List<String> urls) {
        this.urls = urls;
    }

    public IceServerResponse(List<String> urls, String username, String credential) {
        this.urls = urls;
        this.username = username;
        this.credential = credential;
    }

    public List<String> getUrls() {
        return urls;
    }

    public void setUrls(List<String> urls) {
        this.urls = urls;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getCredential() {
        return credential;
    }

    public void setCredential(String credential) {
        this.credential = credential;
    }
}

