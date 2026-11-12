package com.codealpha.meetapp.file;

import com.codealpha.meetapp.common.config.AppProperties;
import com.codealpha.meetapp.common.exception.BadRequestException;
import com.codealpha.meetapp.common.exception.UnsupportedMediaTypeException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;

/**
 * Validates file uploads against size limits, extension allow-lists, blocklists,
 * multi-extension heuristics, magic bytes, and sanitizes filenames.
 */
@Component
public class FileValidator {

    private final AppProperties appProperties;

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "webp", "pdf", "txt", "csv", "docx", "xlsx", "pptx"
    );

    private static final Set<String> BLOCKED_EXTENSIONS = Set.of(
            "exe", "bat", "cmd", "sh", "ps1", "js", "mjs", "html", "htm",
            "svg", "jar", "msi", "dll", "apk", "zip", "tar", "gz", "7z"
    );

    private static final Map<String, String> EXTENSION_TO_CONTENT_TYPE = Map.ofEntries(
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("pdf", "application/pdf"),
            Map.entry("txt", "text/plain"),
            Map.entry("csv", "text/csv"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation")
    );

    public record ValidatedFileInfo(
            String originalName,
            String storedName,
            String contentType,
            long sizeBytes
    ) {}

    public FileValidator(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    public ValidatedFileInfo validate(String rawOriginalName, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new BadRequestException("Uploaded file is empty");
        }

        long maxBytes = (long) appProperties.getMaxFileSizeMb() * 1024 * 1024;
        if (bytes.length > maxBytes) {
            throw new BadRequestException("File size exceeds limit of " + appProperties.getMaxFileSizeMb() + " MB");
        }

        if (rawOriginalName == null || rawOriginalName.isBlank()) {
            throw new BadRequestException("File original name is missing");
        }

        // 1. Sanitize basename and normalize
        String baseName = extractBaseName(rawOriginalName);
        String normalized = Normalizer.normalize(baseName, Normalizer.Form.NFC);

        // 2. Multi-extension & blocked extension checks
        String[] dotSegments = normalized.split("\\.");
        if (dotSegments.length < 2) {
            throw new UnsupportedMediaTypeException("File must have a valid extension");
        }

        for (int i = 1; i < dotSegments.length; i++) {
            String seg = dotSegments[i].toLowerCase(Locale.ROOT).trim();
            if (BLOCKED_EXTENSIONS.contains(seg)) {
                throw new UnsupportedMediaTypeException("Forbidden file extension detected: ." + seg);
            }
        }

        String primaryExt = dotSegments[dotSegments.length - 1].toLowerCase(Locale.ROOT).trim();
        if (!ALLOWED_EXTENSIONS.contains(primaryExt)) {
            throw new UnsupportedMediaTypeException("Unsupported file type: ." + primaryExt);
        }

        // 3. Magic bytes inspection
        validateMagicBytes(primaryExt, bytes);

        // 4. Sanitize original name for display / header
        String sanitizedName = sanitizeDisplayName(normalized, primaryExt);

        // 5. Generate secure random storedName
        String storedName = UUID.randomUUID() + "." + primaryExt;
        String contentType = EXTENSION_TO_CONTENT_TYPE.getOrDefault(primaryExt, "application/octet-stream");

        return new ValidatedFileInfo(sanitizedName, storedName, contentType, bytes.length);
    }

    private String extractBaseName(String path) {
        int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        if (lastSlash >= 0) {
            path = path.substring(lastSlash + 1);
        }
        return path.trim();
    }

    private String sanitizeDisplayName(String name, String ext) {
        // Strip control characters and dangerous symbols
        String clean = name.replaceAll("[\\p{Cntrl}\"\\\\/|<>*?:;]", "_").trim();
        if (clean.isBlank() || clean.equals("." + ext)) {
            clean = "file." + ext;
        }
        if (clean.length() > 100) {
            String extensionWithDot = "." + ext;
            int maxBaseLen = 100 - extensionWithDot.length();
            if (maxBaseLen > 0 && clean.contains(".")) {
                clean = clean.substring(0, maxBaseLen) + extensionWithDot;
            } else {
                clean = clean.substring(0, 100);
            }
        }
        return clean;
    }

    private void validateMagicBytes(String ext, byte[] bytes) {
        switch (ext) {
            case "png" -> {
                // 89 50 4E 47 0D 0A 1A 0A
                if (bytes.length < 8 ||
                        (bytes[0] & 0xFF) != 0x89 ||
                        (bytes[1] & 0xFF) != 0x50 ||
                        (bytes[2] & 0xFF) != 0x4E ||
                        (bytes[3] & 0xFF) != 0x47 ||
                        (bytes[4] & 0xFF) != 0x0D ||
                        (bytes[5] & 0xFF) != 0x0A ||
                        (bytes[6] & 0xFF) != 0x1A ||
                        (bytes[7] & 0xFF) != 0x0A) {
                    throw new BadRequestException("File content does not match PNG signature");
                }
            }
            case "jpg", "jpeg" -> {
                // FF D8 FF
                if (bytes.length < 3 ||
                        (bytes[0] & 0xFF) != 0xFF ||
                        (bytes[1] & 0xFF) != 0xD8 ||
                        (bytes[2] & 0xFF) != 0xFF) {
                    throw new BadRequestException("File content does not match JPEG signature");
                }
            }
            case "gif" -> {
                // GIF87a or GIF89a
                if (bytes.length < 6) {
                    throw new BadRequestException("File content does not match GIF signature");
                }
                String header = new String(bytes, 0, 6, StandardCharsets.US_ASCII);
                if (!header.equals("GIF87a") && !header.equals("GIF89a")) {
                    throw new BadRequestException("File content does not match GIF signature");
                }
            }
            case "webp" -> {
                // RIFF....WEBP
                if (bytes.length < 12) {
                    throw new BadRequestException("File content does not match WebP signature");
                }
                String riff = new String(bytes, 0, 4, StandardCharsets.US_ASCII);
                String webp = new String(bytes, 8, 4, StandardCharsets.US_ASCII);
                if (!"RIFF".equals(riff) || !"WEBP".equals(webp)) {
                    throw new BadRequestException("File content does not match WebP signature");
                }
            }
            case "pdf" -> {
                // %PDF-
                if (bytes.length < 5) {
                    throw new BadRequestException("File content does not match PDF signature");
                }
                String pdf = new String(bytes, 0, 5, StandardCharsets.US_ASCII);
                if (!pdf.startsWith("%PDF-")) {
                    throw new BadRequestException("File content does not match PDF signature");
                }
            }
            case "docx", "xlsx", "pptx" -> {
                // PK\x03\x04
                if (bytes.length < 4 ||
                        (bytes[0] & 0xFF) != 0x50 ||
                        (bytes[1] & 0xFF) != 0x4B ||
                        (bytes[2] & 0xFF) != 0x03 ||
                        (bytes[3] & 0xFF) != 0x04) {
                    throw new BadRequestException("File content does not match OpenXML (ZIP container) signature");
                }
            }
            case "txt", "csv" -> {
                // Check first 8 KB for NULL bytes or non-text control characters
                int checkLen = Math.min(bytes.length, 8192);
                for (int i = 0; i < checkLen; i++) {
                    byte b = bytes[i];
                    if (b == 0x00) {
                        throw new BadRequestException("Text file contains prohibited null bytes");
                    }
                }
            }
            default -> throw new UnsupportedMediaTypeException("Unsupported file type: ." + ext);
        }
    }
}
