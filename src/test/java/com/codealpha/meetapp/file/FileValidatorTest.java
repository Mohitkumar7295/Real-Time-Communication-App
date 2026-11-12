package com.codealpha.meetapp.file;

import com.codealpha.meetapp.common.config.AppProperties;
import com.codealpha.meetapp.common.exception.BadRequestException;
import com.codealpha.meetapp.common.exception.UnsupportedMediaTypeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class FileValidatorTest {

    private FileValidator validator;
    private AppProperties appProperties;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        appProperties.setMaxFileSizeMb(10);
        validator = new FileValidator(appProperties);
    }

    @Test
    @DisplayName("Should accept valid PNG with correct magic bytes")
    void shouldAcceptValidPng() {
        byte[] pngHeader = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00};
        FileValidator.ValidatedFileInfo result = validator.validate("test_image.png", pngHeader);

        assertEquals("test_image.png", result.originalName());
        assertEquals("image/png", result.contentType());
        assertTrue(result.storedName().endsWith(".png"));
        assertFalse(result.storedName().contains("test_image"));
    }

    @Test
    @DisplayName("Should accept valid JPEG with correct magic bytes")
    void shouldAcceptValidJpeg() {
        byte[] jpegHeader = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10};
        FileValidator.ValidatedFileInfo result = validator.validate("photo.jpeg", jpegHeader);

        assertEquals("photo.jpeg", result.originalName());
        assertEquals("image/jpeg", result.contentType());
        assertTrue(result.storedName().endsWith(".jpeg"));
    }

    @Test
    @DisplayName("Should accept valid PDF with correct magic bytes")
    void shouldAcceptValidPdf() {
        byte[] pdfHeader = "%PDF-1.7\n%some-content".getBytes(StandardCharsets.US_ASCII);
        FileValidator.ValidatedFileInfo result = validator.validate("document.pdf", pdfHeader);

        assertEquals("document.pdf", result.originalName());
        assertEquals("application/pdf", result.contentType());
        assertTrue(result.storedName().endsWith(".pdf"));
    }

    @Test
    @DisplayName("Should accept valid DOCX with PK signature")
    void shouldAcceptValidDocx() {
        byte[] zipHeader = new byte[]{0x50, 0x4B, 0x03, 0x04, 0x14, 0x00};
        FileValidator.ValidatedFileInfo result = validator.validate("proposal.docx", zipHeader);

        assertEquals("proposal.docx", result.originalName());
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", result.contentType());
        assertTrue(result.storedName().endsWith(".docx"));
    }

    @Test
    @DisplayName("Should accept valid UTF-8 text file without null bytes")
    void shouldAcceptValidTxt() {
        byte[] txt = "Hello world! This is a plain text file.".getBytes(StandardCharsets.UTF_8);
        FileValidator.ValidatedFileInfo result = validator.validate("notes.txt", txt);

        assertEquals("notes.txt", result.originalName());
        assertEquals("text/plain", result.contentType());
        assertTrue(result.storedName().endsWith(".txt"));
    }

    @Test
    @DisplayName("Should reject text file containing null byte")
    void shouldRejectTxtWithNullByte() {
        byte[] binary = new byte[]{'H', 'e', 'l', 'l', 'o', 0x00, 'w', 'o', 'r', 'l', 'd'};
        assertThrows(BadRequestException.class, () -> validator.validate("bad.txt", binary));
    }

    @Test
    @DisplayName("Should reject mismatched magic bytes (e.g. plain text disguised as PNG)")
    void shouldRejectMismatchedMagicBytes() {
        byte[] fakePng = "This is not a png file".getBytes(StandardCharsets.UTF_8);
        assertThrows(BadRequestException.class, () -> validator.validate("fake.png", fakePng));
    }

    @Test
    @DisplayName("Should reject blocked file extensions (e.g. .exe, .sh, .bat)")
    void shouldRejectBlockedExtensions() {
        byte[] bytes = new byte[]{0x01, 0x02, 0x03, 0x04};
        assertThrows(UnsupportedMediaTypeException.class, () -> validator.validate("malware.exe", bytes));
        assertThrows(UnsupportedMediaTypeException.class, () -> validator.validate("script.sh", bytes));
        assertThrows(UnsupportedMediaTypeException.class, () -> validator.validate("batch.bat", bytes));
    }

    @Test
    @DisplayName("Should reject .zip files explicitly per specification")
    void shouldRejectZipFiles() {
        byte[] zipHeader = new byte[]{0x50, 0x4B, 0x03, 0x04, 0x14, 0x00};
        assertThrows(UnsupportedMediaTypeException.class, () -> validator.validate("archive.zip", zipHeader));
    }

    @Test
    @DisplayName("Should reject double extension attacks (e.g. payload.exe.pdf, malware.sh.docx)")
    void shouldRejectDoubleExtensionAttacks() {
        byte[] pdfHeader = "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII);
        assertThrows(UnsupportedMediaTypeException.class, () -> validator.validate("payload.exe.pdf", pdfHeader));

        byte[] zipHeader = new byte[]{0x50, 0x4B, 0x03, 0x04};
        assertThrows(UnsupportedMediaTypeException.class, () -> validator.validate("invoice.sh.docx", zipHeader));
    }

    @Test
    @DisplayName("Should reject empty file (0 bytes)")
    void shouldRejectEmptyFile() {
        assertThrows(BadRequestException.class, () -> validator.validate("empty.txt", new byte[0]));
    }

    @Test
    @DisplayName("Should reject file exceeding size limit")
    void shouldRejectOversizedFile() {
        appProperties.setMaxFileSizeMb(1); // 1 MB limit
        byte[] large = new byte[(1024 * 1024) + 1];
        assertThrows(BadRequestException.class, () -> validator.validate("large.txt", large));
    }

    @Test
    @DisplayName("Should sanitize path traversal and illegal control characters in filename")
    void shouldSanitizeDangerousFilename() {
        byte[] txt = "clean content".getBytes(StandardCharsets.UTF_8);
        FileValidator.ValidatedFileInfo result = validator.validate("../../../evil\nname<script>.txt", txt);

        assertFalse(result.originalName().contains("../"));
        assertFalse(result.originalName().contains("\n"));
        assertFalse(result.originalName().contains("<"));
        assertTrue(result.originalName().endsWith(".txt"));
    }
}
