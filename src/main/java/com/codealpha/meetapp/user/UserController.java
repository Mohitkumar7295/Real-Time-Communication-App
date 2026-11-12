package com.codealpha.meetapp.user;

import com.codealpha.meetapp.auth.dto.UserResponse;
import com.codealpha.meetapp.common.exception.NotFoundException;
import com.codealpha.meetapp.common.exception.UnauthorizedException;
import com.codealpha.meetapp.room.RoomService;
import com.codealpha.meetapp.room.dto.UserRoomHistoryItem;
import com.codealpha.meetapp.security.UserPrincipal;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserRepository userRepository;
    private final RoomService roomService;

    public UserController(UserRepository userRepository, RoomService roomService) {
        this.userRepository = userRepository;
        this.roomService = roomService;
    }

    @GetMapping("/me")
    public UserResponse getCurrentUser(@AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) {
            throw new UnauthorizedException("Full authentication is required to access this resource.");
        }
        User user = userRepository.findById(principal.getId())
                .orElseThrow(() -> new NotFoundException("User not found"));

        return new UserResponse(user.getId(), user.getName(), user.getEmail());
    }

    @GetMapping("/me/rooms")
    public List<UserRoomHistoryItem> getUserRooms(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        if (principal == null) {
            throw new UnauthorizedException("Full authentication is required to access this resource.");
        }
        return roomService.getUserRoomHistory(principal.getId(), from, to);
    }
}
