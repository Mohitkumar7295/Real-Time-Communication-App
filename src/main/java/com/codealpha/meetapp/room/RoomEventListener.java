package com.codealpha.meetapp.room;

import com.codealpha.meetapp.signaling.RoomSessionRegistry;
import com.codealpha.meetapp.whiteboard.WhiteboardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;

@Component
public class RoomEventListener {

    private static final Logger log = LoggerFactory.getLogger(RoomEventListener.class);

    private final RoomSessionRegistry roomSessionRegistry;
    private final WhiteboardService whiteboardService;

    public RoomEventListener(RoomSessionRegistry roomSessionRegistry, WhiteboardService whiteboardService) {
        this.roomSessionRegistry = roomSessionRegistry;
        this.whiteboardService = whiteboardService;
    }

    @EventListener
    public void onRoomEnded(RoomEndedEvent event) {
        log.info("Handling RoomEndedEvent for room {}. Disconnecting all active WebSocket sessions.", event.getRoomCode());
        whiteboardService.clearRoom(event.getRoomCode());
        roomSessionRegistry.closeRoom(
                event.getRoomCode(),
                new CloseStatus(4010, "Room ended by host"),
                null
        );
    }
}
