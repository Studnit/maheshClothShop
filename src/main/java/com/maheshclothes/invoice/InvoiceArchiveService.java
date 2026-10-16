package com.maheshclothes.invoice;

import com.maheshclothes.common.NotFoundException;
import com.maheshclothes.common.SettingsService;
import com.maheshclothes.file.FileService;
import com.maheshclothes.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Keeps one PDF per invoice on our side (in the database, so it is backed up with everything else).
 * Invoices never change after they are created, so the PDF is generated once and reused.
 */
@Service
@RequiredArgsConstructor
public class InvoiceArchiveService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final InvoicePdfService pdf;
    private final FileService files;
    private final SettingsService settings;

    public record Archived(UUID documentId, UUID invoiceId, UUID fileId, String invoiceNo, String token, String sha256) {
    }

    @Transactional
    public Archived ensure(UUID invoiceId) {
        Optional<Archived> found = find(invoiceId);
        if (found.isPresent()) {
            return found.get();
        }
        String invoiceNo = jdbc.query("select invoice_no from sales_invoices where id = ?",
                (rs, n) -> rs.getString(1), invoiceId).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Invoice"));
        byte[] bytes = pdf.render(invoiceId);
        UUID fileId = files.save("Invoice-" + invoiceNo.replace('/', '-') + ".pdf", "application/pdf", bytes);
        String token = newToken();
        int days = settings.integer("whatsapp.link_validity_days", 90);
        Instant expires = days > 0 ? Instant.now().plus(days, ChronoUnit.DAYS) : null;
        String sha = sha256(bytes);
        jdbc.update("insert into invoice_documents (invoice_id, file_id, language, sha256, share_token, token_expires_at, created_by) "
                        + "values (?, ?, 'en', ?, ?, ?, ?) on conflict (invoice_id) do nothing",
                invoiceId, fileId, sha, token, expires == null ? null : java.sql.Timestamp.from(expires),
                CurrentUser.username());
        return find(invoiceId).orElseThrow();
    }

    public Optional<Archived> find(UUID invoiceId) {
        return jdbc.query("select d.id, d.invoice_id, d.file_id, i.invoice_no, d.share_token, d.sha256 "
                        + "from invoice_documents d join sales_invoices i on i.id = d.invoice_id where d.invoice_id = ?",
                (rs, n) -> new Archived(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5), rs.getString(6)), invoiceId)
                .stream().findFirst();
    }

    /** Resolves a customer download link; empty when unknown or expired. Counts the download. */
    @Transactional
    public Optional<FileService.StoredFile> openByToken(String token) {
        if (token == null || token.length() < 20 || token.length() > 64) {
            return Optional.empty();
        }
        Optional<UUID> fileId = jdbc.query("select file_id from invoice_documents where share_token = ? "
                        + "and (token_expires_at is null or token_expires_at > now())",
                (rs, n) -> rs.getObject(1, UUID.class), token).stream().findFirst();
        fileId.ifPresent(f -> jdbc.update("update invoice_documents set download_count = download_count + 1 "
                + "where share_token = ?", token));
        return fileId.flatMap(files::load);
    }

    private static String newToken() {
        byte[] b = new byte[24];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
