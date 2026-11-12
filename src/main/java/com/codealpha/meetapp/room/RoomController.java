package com.codealpha.meetapp.room;

import com.codealpha.meetapp.common.exception.UnauthorizedException;
import com.codealpha.meetapp.room.dto.*;
import com.codealpha.meetapp.security.UserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/rooms")
public class RoomController {

    private final RoomService roomService;

    public RoomController(RoomService roomService) {
        this.roomService = roomService;
    }

    @PostMapping
    public ResponseEntity<CreateRoomResponse> createRoom(@AuthenticationPrincipal UserPrincipal principal) {
        ensureAuthenticated(principal);
        CreateRoomResponse response = roomService.createRoom(principal.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{code}")
    public ResponseEntity<RoomResponse> getRoom(@PathVariable("code") String code,
                                                @AuthenticationPrincipal UserPrincipal principal) {
        ensureAuthenticated(principal);
        return ResponseEntity.ok(roomService.getRoom(code));
    }

    @PostMapping("/{code}/join")
    public ResponseEntity<RoomResponse> joinRoom(@PathVariable("code") String code,
                                                 @AuthenticationPrincipal UserPrincipal principal) {
        ensureAuthenticated(principal);
        return ResponseEntity.ok(roomService.joinRoom(code, principal.getId()));
    }

    @PostMapping("/{code}/leave")
    public ResponseEntity<MessageResponse> leaveRoom(@PathVariable("code") String code,
                                                     @AuthenticationPrincipal UserPrincipal principal) {
        ensureAuthenticated(principal);
        return ResponseEntity.ok(roomService.leaveRoom(code, principal.getId()));
    }

    @PostMapping("/{code}/end")
    public ResponseEntity<MessageResponse> endRoom(@PathVariable("code") String code,
                                                   @AuthenticationPrincipal UserPrincipal principal) {
        ensureAuthenticated(principal);
        return ResponseEntity.ok(roomService.endRoom(code, principal.getId()));
    }

    @GetMapping("/{code}/participants")
    public ResponseEntity<List<ParticipantResponse>> getParticipants(@PathVariable("code") String code,
                                                                     @AuthenticationPrincipal UserPrincipal principal) {
        ensureAuthenticated(principal);
        return ResponseEntity.ok(roomService.getParticipants(code, principal.getId()));
    }

    private void ensureAuthenticated(UserPrincipal principal) {
        if (principal == null || principal.getId() == null) {
            throw new UnauthorizedException("Authentication required to access meeting room resources.");
        }
    }
}
