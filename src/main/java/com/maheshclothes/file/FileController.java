package com.maheshclothes.file;

import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.UUID;

/** Serves stored images/receipts to signed-in users only. */
@RestController
@RequiredArgsConstructor
public class FileController {

    private final FileService files;

    @GetMapping("/files/{id}")
    public ResponseEntity<byte[]> get(@PathVariable UUID id) {
        return files.load(id)
                .map(f -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(f.contentType()))
                        .header("X-Content-Type-Options", "nosniff")
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                ContentDisposition.inline().filename(f.fileName()).build().toString())
                        .cacheControl(CacheControl.maxAge(Duration.ofHours(12)).cachePrivate())
                        .body(f.data()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
