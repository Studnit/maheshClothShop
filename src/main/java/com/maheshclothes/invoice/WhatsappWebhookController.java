package com.maheshclothes.invoice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maheshclothes.common.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Receives delivery receipts (sent / delivered / read / failed) from Meta.
 * Every POST is authenticated by checking Meta's HMAC-SHA256 signature of the raw body with the app secret;
 * without a configured secret all POSTs are rejected.
 */
@Slf4j
@RestController
@RequestMapping("/webhooks/whatsapp")
@RequiredArgsConstructor
public class WhatsappWebhookController {

    private final AppProperties props;
    private final WhatsappService whatsapp;
    private final ObjectMapper mapper;

    /** One-time subscription handshake from the Meta dashboard. */
    @GetMapping
    public ResponseEntity<String> verify(@RequestParam(name = "hub.mode", required = false) String mode,
                                         @RequestParam(name = "hub.verify_token", required = false) String token,
                                         @RequestParam(name = "hub.challenge", required = false) String challenge) {
        String expected = props.whatsapp().verifyToken();
        if ("subscribe".equals(mode) && expected != null && !expected.isBlank() && token != null
                && constantTimeEquals(expected, token) && challenge != null) {
            return ResponseEntity.ok(challenge.replaceAll("[^A-Za-z0-9_\\-]", ""));
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    @PostMapping(consumes = "application/json")
    public ResponseEntity<Void> receive(@RequestBody byte[] raw,
                                        @RequestHeader(name = "X-Hub-Signature-256", required = false) String signature) {
        String secret = props.whatsapp().appSecret();
        if (secret == null || secret.isBlank() || !validSignature(raw, signature, secret)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            JsonNode root = mapper.readTree(raw);
            for (JsonNode entry : root.path("entry")) {
                for (JsonNode change : entry.path("changes")) {
                    for (JsonNode st : change.path("value").path("statuses")) {
                        String error = st.path("errors").path(0).path("title").asText(null);
                        String detail = st.path("errors").path(0).path("message").asText(null);
                        whatsapp.applyStatus(st.path("id").asText(null), st.path("status").asText(null),
                                error == null ? detail : error + (detail == null ? "" : ": " + detail));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Could not process WhatsApp webhook: {}", e.getMessage());
        }
        return ResponseEntity.ok().build();       // always 200 once the signature is valid, so Meta does not retry forever
    }

    static boolean validSignature(byte[] body, String header, String secret) {
        if (header == null || !header.startsWith("sha256=")) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = java.util.HexFormat.of().formatHex(mac.doFinal(body));
            return constantTimeEquals(expected, header.substring(7).toLowerCase());
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
