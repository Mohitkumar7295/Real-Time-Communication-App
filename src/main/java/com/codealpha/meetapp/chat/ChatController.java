package com.codealpha.meetapp.chat;

import com.codealpha.meetapp.chat.dto.ChatHistoryResponse;
import com.codealpha.meetapp.common.exception.UnauthorizedException;
import com.codealpha.meetapp.security.UserPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/rooms/{code}/messages")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @GetMapping
    public ResponseEntity<List<ChatHistoryResponse>> getMessages(
            @PathVariable("code") String code,
            @RequestParam(value = "limit", required = false, defaultValue = "50") Integer limit,
            @RequestParam(value = "before", required = false) Long before,
            @AuthenticationPrincipal UserPrincipal principal) {

        if (principal == null) {
            throw new UnauthorizedException("Full authentication is required to access chat history.");
        }

        List<ChatHistoryResponse> history = chatService.getHistory(
                code,
                principal.getId(),
                limit,
                before
        );

        return ResponseEntity.ok(history);
    }
}
