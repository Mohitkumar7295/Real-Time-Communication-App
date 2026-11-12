package com.codealpha.meetapp.file;

import com.codealpha.meetapp.common.exception.UnauthorizedException;
import com.codealpha.meetapp.file.dto.FileListResponse;
import com.codealpha.meetapp.file.dto.FileUploadResponse;
import com.codealpha.meetapp.security.UserPrincipal;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

/**
 * REST controller managing room file uploads, cursor listings,
 * authenticated streaming downloads, and deletions.
 */
@RestController
public class FileController {

    private final FileService fileService;
    private final FileOperationLimiter fileOperationLimiter;

    public FileController(FileService fileService, FileOperationLimiter fileOperationLimiter) {
        this.fileService = fileService;
        this.fileOperationLimiter = fileOperationLimiter;
    }

    @PostMapping(value = "/api/files/upload/{code}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<FileUploadResponse> uploadFile(
            @PathVariable("code") String code,
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal UserPrincipal principal) {

        assertAuthenticated(principal);

        FileUploadResponse response = fileOperationLimiter.execute(() ->
                fileService.uploadFile(code, principal.getId(), file)
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/api/rooms/{code}/files")
    public ResponseEntity<FileListResponse> listFiles(
            @PathVariable("code") String code,
            @RequestParam(value = "before", required = false) Long before,
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            @AuthenticationPrincipal UserPrincipal principal) {

        assertAuthenticated(principal);

        FileListResponse response = fileService.getRoomFiles(code, principal.getId(), before, limit);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/api/files/download/{id}")
    public ResponseEntity<Resource> downloadFile(
            @PathVariable("id") Long id,
            @AuthenticationPrincipal UserPrincipal principal) {

        assertAuthenticated(principal);

        FileService.DownloadedFile downloaded = fileOperationLimiter.execute(() ->
                fileService.loadFileForDownload(id, principal.getId())
        );

        ContentDisposition contentDisposition = ContentDisposition.attachment()
                .filename(downloaded.meta().getOriginalName(), StandardCharsets.UTF_8)
                .build();

        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(downloaded.meta().getContentType());
        } catch (Exception e) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }

        ByteArrayResource resource = new ByteArrayResource(downloaded.plaintextBytes());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-cache, no-store, must-revalidate")
                .contentType(mediaType)
                .contentLength(downloaded.plaintextBytes().length)
                .body(resource);
    }

    @DeleteMapping("/api/files/{id}")
    public ResponseEntity<Void> deleteFile(
            @PathVariable("id") Long id,
            @AuthenticationPrincipal UserPrincipal principal) {

        assertAuthenticated(principal);

        fileService.deleteFile(id, principal.getId());
        return ResponseEntity.noContent().build();
    }

    private void assertAuthenticated(UserPrincipal principal) {
        if (principal == null) {
            throw new UnauthorizedException("Full authentication is required to access file resources.");
        }
    }
}
