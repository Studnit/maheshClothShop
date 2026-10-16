package com.maheshclothes.invoice;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WhatsappSignatureTest {

    private static String sign(String body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void acceptsGenuineSignature() throws Exception {
        String body = "{\"entry\":[]}";
        assertTrue(WhatsappWebhookController.validSignature(body.getBytes(StandardCharsets.UTF_8), sign(body, "s3cret"), "s3cret"));
    }

    @Test
    void rejectsTamperedBodyWrongSecretAndMalformedHeaders() throws Exception {
        String body = "{\"entry\":[]}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        assertFalse(WhatsappWebhookController.validSignature("{\"entry\":[1]}".getBytes(StandardCharsets.UTF_8), sign(body, "s3cret"), "s3cret"));
        assertFalse(WhatsappWebhookController.validSignature(bytes, sign(body, "other"), "s3cret"));
        assertFalse(WhatsappWebhookController.validSignature(bytes, null, "s3cret"));
        assertFalse(WhatsappWebhookController.validSignature(bytes, "sha1=abc", "s3cret"));
        assertFalse(WhatsappWebhookController.validSignature(bytes, "sha256=", "s3cret"));
    }
}
