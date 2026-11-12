package com.codealpha.meetapp.signaling.handler;

import com.codealpha.meetapp.signaling.message.MessageType;
import com.codealpha.meetapp.signaling.message.SignalMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * Strategy interface for processing specialized signaling messages.
 */
public interface MessageHandler {

    /**
     * Whether this handler supports the specified MessageType.
     */
    boolean supports(MessageType type);

    /**
     * Executes message processing for the given session.
     */
    void handle(WebSocketSession session, SignalMessage message) throws Exception;
}

