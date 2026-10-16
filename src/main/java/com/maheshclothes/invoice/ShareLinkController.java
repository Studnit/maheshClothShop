package com.maheshclothes.invoice;

import com.maheshclothes.file.FileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Public download link in the customer's WhatsApp message: /i/{token}. Unguessable token, can expire. */
@RestController
@RequiredArgsConstructor
public class ShareLinkController {

    private final InvoiceArchiveService archive;

    @GetMapping("/i/{token}")
    public ResponseEntity<byte[]> download(@PathVariable String token) {
        return archive.openByToken(token)
                .map(f -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_PDF)
                        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(f.fileName()).build().toString())
                        .header("X-Robots-Tag", "noindex, nofollow")
                        .cacheControl(CacheControl.noStore())
                        .body(f.data()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
