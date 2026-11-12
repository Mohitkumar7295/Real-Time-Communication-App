package com.codealpha.meetapp.room;

import com.codealpha.meetapp.common.exception.GlobalExceptionHandler;
import com.codealpha.meetapp.common.exception.RoomFullException;
import com.codealpha.meetapp.room.dto.*;
import com.codealpha.meetapp.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class RoomControllerTest {

    private MockMvc mockMvc;

    @Mock
    private RoomService roomService;

    @InjectMocks
    private RoomController roomController;

    private UserPrincipal testPrincipal;

    @BeforeEach
    void setUp() {
        testPrincipal = new UserPrincipal(1L, "test@example.com");

        mockMvc = MockMvcBuilders
                .standaloneSetup(roomController)
                .setControllerAdvice(new GlobalExceptionHandler(java.time.Clock.systemUTC()))
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    @Override
                    public boolean supportsParameter(MethodParameter parameter) {
                        return parameter.hasParameterAnnotation(AuthenticationPrincipal.class);
                    }

                    @Override
                    public Object resolveArgument(MethodParameter parameter,
                                                  ModelAndViewContainer mavContainer,
                                                  NativeWebRequest webRequest,
                                                  WebDataBinderFactory binderFactory) {
                        return testPrincipal;
                    }
                })
                .build();
    }

    @Test
    @DisplayName("POST /api/rooms creates room and returns 201 Created")
    void createRoom_returns201() throws Exception {
        CreateRoomResponse response = new CreateRoomResponse("ROOM1234", 1L, true, 0L, Instant.now());
        when(roomService.createRoom(1L)).thenReturn(response);

        mockMvc.perform(post("/api/rooms"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roomCode").value("ROOM1234"))
                .andExpect(jsonPath("$.hostId").value(1))
                .andExpect(jsonPath("$.participantCount").value(0));
    }

    @Test
    @DisplayName("GET /api/rooms/{code} returns room details")
    void getRoom_returns200() throws Exception {
        RoomResponse response = new RoomResponse("ROOM1234", 1L, true, 2L, 5, Instant.now());
        when(roomService.getRoom("ROOM1234")).thenReturn(response);

        mockMvc.perform(get("/api/rooms/ROOM1234"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomCode").value("ROOM1234"))
                .andExpect(jsonPath("$.participantCount").value(2));
    }

    @Test
    @DisplayName("POST /api/rooms/{code}/join joins room successfully")
    void joinRoom_returns200() throws Exception {
        RoomResponse response = new RoomResponse("ROOM1234", 1L, true, 3L, 5, Instant.now());
        when(roomService.joinRoom("ROOM1234", 1L)).thenReturn(response);

        mockMvc.perform(post("/api/rooms/ROOM1234/join"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.participantCount").value(3));
    }

    @Test
    @DisplayName("POST /api/rooms/{code}/join returns 409 RoomFullException when room is at capacity")
    void joinRoom_whenFull_returns409() throws Exception {
        when(roomService.joinRoom("ROOM1234", 1L))
                .thenThrow(new RoomFullException("Meeting room is full (maximum 5 participants)."));

        mockMvc.perform(post("/api/rooms/ROOM1234/join"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("Meeting room is full (maximum 5 participants)."));
    }

    @Test
    @DisplayName("POST /api/rooms/{code}/leave leaves room successfully")
    void leaveRoom_returns200() throws Exception {
        when(roomService.leaveRoom("ROOM1234", 1L)).thenReturn(new MessageResponse("Left room ROOM1234"));

        mockMvc.perform(post("/api/rooms/ROOM1234/leave"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Left room ROOM1234"));
    }

    @Test
    @DisplayName("POST /api/rooms/{code}/end ends room successfully")
    void endRoom_returns200() throws Exception {
        when(roomService.endRoom("ROOM1234", 1L)).thenReturn(new MessageResponse("Room ROOM1234 ended successfully."));

        mockMvc.perform(post("/api/rooms/ROOM1234/end"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Room ROOM1234 ended successfully."));
    }

    @Test
    @DisplayName("GET /api/rooms/{code}/participants returns participant list")
    void getParticipants_returns200() throws Exception {
        List<ParticipantResponse> list = List.of(new ParticipantResponse(1L, "Host User", Instant.now()));
        when(roomService.getParticipants("ROOM1234", 1L)).thenReturn(list);

        mockMvc.perform(get("/api/rooms/ROOM1234/participants"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value(1))
                .andExpect(jsonPath("$[0].name").value("Host User"));
    }
}
