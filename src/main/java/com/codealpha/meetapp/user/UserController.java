package com.codealpha.meetapp.user;

import com.codealpha.meetapp.auth.dto.UserResponse;
import com.codealpha.meetapp.common.exception.NotFoundException;
import com.codealpha.meetapp.common.exception.UnauthorizedException;
import com.codealpha.meetapp.security.UserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserRepository userRepository;

    public UserController(UserRepository userRepository) {
        this.userRepository = userRepository;
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
}
