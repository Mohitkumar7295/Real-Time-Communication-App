package com.codealpha.meetapp.file;

import com.codealpha.meetapp.file.dto.FileItemDto;
import com.codealpha.meetapp.file.dto.FileListResponse;
import com.codealpha.meetapp.file.dto.FileUploadResponse;
import com.codealpha.meetapp.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FileControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockBean
    private FileService fileService;

    private String validToken;

    @BeforeEach
    void setUp() {
        validToken = jwtService.generateToken(1L, "aarav@example.com");
    }

    @Test
    @DisplayName("POST /api/files/upload/{code} without token returns 401 Unauthorized")
    void uploadFile_unauthorized_returns401() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "doc.pdf", "application/pdf", "data".getBytes());
        mockMvc.perform(multipart("/api/files/upload/ROOM123").file(file))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/files/upload/{code} with valid token returns 201 Created and payload")
    void uploadFile_success_returns201() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "doc.pdf", "application/pdf", "%PDF-1.7".getBytes());

        FileUploadResponse response = new FileUploadResponse(
                42L, "doc.pdf", 8L, "application/pdf", 1L, "Aarav Sharma", Instant.now()
        );
        when(fileService.uploadFile(eq("ROOM123"), eq(1L), any())).thenReturn(response);

        mockMvc.perform(multipart("/api/files/upload/ROOM123")
                        .file(file)
                        .header("Authorization", "Bearer " + validToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", is(42)))
                .andExpect(jsonPath("$.originalName", is("doc.pdf")))
                .andExpect(jsonPath("$.uploaderId", is(1)));
    }

    @Test
    @DisplayName("GET /api/rooms/{code}/files returns 200 OK and file list")
    void listFiles_success_returns200() throws Exception {
        FileItemDto item = new FileItemDto(
                10L, "sheet.xlsx", 500L, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                1L, "Aarav Sharma", Instant.now()
        );
        FileListResponse response = new FileListResponse(List.of(item), false);

        when(fileService.getRoomFiles(eq("ROOM123"), eq(1L), isNull(), eq(50))).thenReturn(response);

        mockMvc.perform(get("/api/rooms/ROOM123/files")
                        .header("Authorization", "Bearer " + validToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files", hasSize(1)))
                .andExpect(jsonPath("$.files[0].originalName", is("sheet.xlsx")))
                .andExpect(jsonPath("$.hasMore", is(false)));
    }

    @Test
    @DisplayName("GET /api/files/download/{id} returns 200 with attachment headers, nosniff, and private no-store cache")
    void downloadFile_success_returnsAttachmentHeaders() throws Exception {
        FileMeta meta = new FileMeta(null, null, "report.pdf", "uuid.pdf", "application/pdf", 12L);
        byte[] payload = "%PDF-content".getBytes(StandardCharsets.US_ASCII);
        FileService.DownloadedFile downloaded = new FileService.DownloadedFile(meta, payload);

        when(fileService.loadFileForDownload(eq(100L), eq(1L))).thenReturn(downloaded);

        mockMvc.perform(get("/api/files/download/100")
                        .header("Authorization", "Bearer " + validToken))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "private, no-cache, no-store, must-revalidate"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment;")))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("filename*=UTF-8''report.pdf")))
                .andExpect(content().bytes(payload));
    }

    @Test
    @DisplayName("DELETE /api/files/{id} returns 204 No Content")
    void deleteFile_success_returns204() throws Exception {
        doNothing().when(fileService).deleteFile(eq(100L), eq(1L));

        mockMvc.perform(delete("/api/files/100")
                        .header("Authorization", "Bearer " + validToken))
                .andExpect(status().isNoContent());

        verify(fileService).deleteFile(100L, 1L);
    }
}
