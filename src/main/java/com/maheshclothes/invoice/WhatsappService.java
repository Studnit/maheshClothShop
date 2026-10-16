package com.maheshclothes.invoice;

import com.fasterxml.jackson.databind.JsonNode;
import com.maheshclothes.common.AppProperties;
import com.maheshclothes.common.BusinessException;
import com.maheshclothes.common.IndianFormat;
import com.maheshclothes.common.NotFoundException;
import com.maheshclothes.common.PageResult;
import com.maheshclothes.common.PhoneNumbers;
import com.maheshclothes.common.SettingsService;
import com.maheshclothes.common.SqlUtil;
import com.maheshclothes.file.FileService;
import com.maheshclothes.sales.SaleCompletedEvent;
import com.maheshclothes.security.CurrentUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Sends the bill PDF to the customer from the OWNER'S WhatsApp Business account (WhatsApp Business Cloud API).
 *
 * Rules enforced here:
 *  - nothing is sent unless the cashier ticked "customer agreed to receive the bill on WhatsApp";
 *  - the first message to a customer is a Meta-approved TEMPLATE with the PDF as its header (free-form messages
 *    are only allowed inside a 24-hour customer-initiated window);
 *  - access token and app secret come from environment variables only;
 *  - a failure never affects the sale: it is recorded as FAILED and can be retried from the history screen;
 *  - the "Share via WhatsApp" link (wa.me) works without any API approval and is always available as a fallback.
 */
@Slf4j
@Service
public class WhatsappService {

    private final JdbcTemplate jdbc;
    private final SettingsService settings;
    private final AppProperties props;
    private final InvoiceArchiveService archive;
    private final FileService files;
    private final MessageSource messages;
    private final RestClient http;

    public WhatsappService(JdbcTemplate jdbc, SettingsService settings, AppProperties props,
                           InvoiceArchiveService archive, FileService files, MessageSource messages) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.props = props;
        this.archive = archive;
        this.files = files;
        this.messages = messages;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(30));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    // ------------------------------------------------------------------ status

    public boolean apiConfigured() {
        return props.whatsapp().hasAccessToken() && !settings.get("whatsapp.phone_number_id", "").isBlank();
    }

    public boolean apiEnabled() {
        return settings.bool("whatsapp.enabled") && apiConfigured();
    }

    // ------------------------------------------------------------------ automatic send after a sale

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSaleCompleted(SaleCompletedEvent e) {
        if (!e.sendWhatsApp() || !settings.bool("whatsapp.auto_send") || !apiEnabled()) {
            return;
        }
        try {
            sendByApi(e.invoiceId(), true);
        } catch (RuntimeException ex) {
            log.warn("WhatsApp send for invoice {} failed: {}", e.invoiceNo(), ex.getMessage());
        }
    }

    /** Sends (or re-sends) the invoice by the Cloud API and records the result. Never throws for delivery problems. */
    public UUID sendByApi(UUID invoiceId, boolean consent) {
        Inv inv = invoice(invoiceId);
        String to = PhoneNumbers.toWhatsApp(inv.phone(), settings.get("whatsapp.default_country_code", "91"));
        if (to == null) {
            throw new BusinessException("wa.error.phone");
        }
        String lang = language();
        UUID msgId = insertMessage(invoiceId, to, inv.customerName(), "API", lang,
                settings.get("whatsapp.template_name", "invoice_thank_you"), null, consent);
        try {
            if (!apiEnabled()) {
                throw new IllegalStateException("WhatsApp Cloud API is not configured");
            }
            InvoiceArchiveService.Archived doc = archive.ensure(invoiceId);
            jdbc.update("update whatsapp_messages set document_id = ? where id = ?", doc.documentId(), msgId);
            FileService.StoredFile pdf = files.load(doc.fileId()).orElseThrow();
            String mediaId = uploadMedia(pdf);
            String providerId = sendTemplate(to, lang, mediaId, pdf.fileName(), inv);
            jdbc.update("update whatsapp_messages set status = 'SENT', provider_message_id = ?, sent_at = now(), "
                    + "attempts = attempts + 1, error_message = null, updated_at = now() where id = ?", providerId, msgId);
        } catch (RuntimeException ex) {
            fail(msgId, ex);
        }
        return msgId;
    }

    public void resend(UUID messageId) {
        Map<String, Object> m = jdbc.queryForList("select invoice_id, mode from whatsapp_messages where id = ?", messageId)
                .stream().findFirst().orElseThrow(() -> new NotFoundException("Message"));
        UUID invoiceId = (UUID) m.get("invoice_id");
        sendByApi(invoiceId, true);
    }

    // ------------------------------------------------------------------ "Share on WhatsApp" link (no API needed)

    public record ShareLink(UUID messageId, String url, String text, String phone) {
    }

    /** Builds the click-to-chat link with a ready message, a download link for the PDF, and records it in history. */
    public ShareLink prepareShare(UUID invoiceId, String langOverride) {
        Inv inv = invoice(invoiceId);
        String lang = langOverride == null || langOverride.isBlank() ? language() : langOverride;
        InvoiceArchiveService.Archived doc = archive.ensure(invoiceId);
        String text = message(lang, inv, publicLink(doc.token()));
        String to = PhoneNumbers.toWhatsApp(inv.phone(), settings.get("whatsapp.default_country_code", "91"));
        String url = (to == null ? "https://wa.me/" : "https://wa.me/" + to) + "?text="
                + URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
        UUID id = insertMessage(invoiceId, to == null ? "" : to, inv.customerName(), "LINK", lang, null, text, true);
        jdbc.update("update whatsapp_messages set status = 'OPENED', document_id = ?, sent_at = now(), attempts = 1 where id = ?",
                doc.documentId(), id);
        return new ShareLink(id, url, text, to);
    }

    public String publicLink(String token) {
        String base = settings.get("app.public_base_url", "").trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return (base.isEmpty() ? "" : base) + "/i/" + token;
    }

    // ------------------------------------------------------------------ webhook status updates

    /** Called from the signed webhook: sent / delivered / read / failed for a message we sent. */
    public void applyStatus(String providerId, String status, String errorText) {
        String s = switch (status == null ? "" : status) {
            case "sent" -> "SENT";
            case "delivered" -> "DELIVERED";
            case "read" -> "READ";
            case "failed" -> "FAILED";
            default -> null;
        };
        if (s == null || providerId == null) {
            return;
        }
        jdbc.update("update whatsapp_messages set status = case "
                        + " when status = 'READ' then status "                       // never move backwards
                        + " when status = 'DELIVERED' and ? = 'SENT' then status "
                        + " else ? end, "
                        + "delivered_at = case when ? = 'DELIVERED' then now() else delivered_at end, "
                        + "read_at = case when ? = 'READ' then now() else read_at end, "
                        + "error_message = case when ? = 'FAILED' then ? else error_message end, updated_at = now() "
                        + "where provider_message_id = ?",
                s, s, s, s, s, trim(errorText, 600), providerId);
    }

    // ------------------------------------------------------------------ history (owner screens)

    public record MessageRow(UUID id, UUID invoiceId, String invoiceNo, String customerName, String phone, String mode,
                             String language, String status, String error, int attempts, Instant createdAt,
                             Instant sentAt, Instant deliveredAt, Instant readAt, String createdBy) {
    }

    public PageResult<MessageRow> history(String status, String q, int page, int size) {
        StringBuilder where = new StringBuilder(" where 1=1");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            where.append(" and w.status = ?");
            args.add(status);
        }
        for (String t : SqlUtil.tokens(q)) {
            where.append(" and (lower(i.invoice_no) like ? or lower(coalesce(w.customer_name,'')) like ? or w.to_phone like ?)");
            String like = SqlUtil.like(t);
            args.add(like);
            args.add(like);
            args.add(like);
        }
        Long total = jdbc.queryForObject("select count(*) from whatsapp_messages w join sales_invoices i on i.id = w.invoice_id"
                + where, Long.class, args.toArray());
        List<Object> pa = new ArrayList<>(args);
        pa.add(size);
        pa.add(page * size);
        List<MessageRow> rows = jdbc.query("select w.id, w.invoice_id, i.invoice_no, w.customer_name, w.to_phone, w.mode, "
                        + "w.language, w.status, w.error_message, w.attempts, w.created_at, w.sent_at, w.delivered_at, "
                        + "w.read_at, w.created_by from whatsapp_messages w join sales_invoices i on i.id = w.invoice_id"
                        + where + " order by w.created_at desc limit ? offset ?",
                (rs, n) -> new MessageRow(SqlUtil.uuid(rs, 1), SqlUtil.uuid(rs, 2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getInt(10),
                        SqlUtil.instant(rs, 11), SqlUtil.instant(rs, 12), SqlUtil.instant(rs, 13), SqlUtil.instant(rs, 14),
                        rs.getString(15)), pa.toArray());
        return new PageResult<>(rows, page, size, total == null ? 0 : total);
    }

    public List<MessageRow> historyOf(UUID invoiceId) {
        return jdbc.query("select w.id, w.invoice_id, i.invoice_no, w.customer_name, w.to_phone, w.mode, w.language, w.status, "
                        + "w.error_message, w.attempts, w.created_at, w.sent_at, w.delivered_at, w.read_at, w.created_by "
                        + "from whatsapp_messages w join sales_invoices i on i.id = w.invoice_id where w.invoice_id = ? "
                        + "order by w.created_at desc",
                (rs, n) -> new MessageRow(SqlUtil.uuid(rs, 1), SqlUtil.uuid(rs, 2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getInt(10),
                        SqlUtil.instant(rs, 11), SqlUtil.instant(rs, 12), SqlUtil.instant(rs, 13), SqlUtil.instant(rs, 14),
                        rs.getString(15)), invoiceId);
    }

    public Map<String, Long> statusCounts() {
        Map<String, Long> m = new java.util.LinkedHashMap<>();
        jdbc.query("select status, count(*) from whatsapp_messages group by status",
                rs -> {
                    m.put(rs.getString(1), rs.getLong(2));
                });
        return m;
    }

    // ------------------------------------------------------------------ internals

    private record Inv(String invoiceNo, String customerName, String phone, BigDecimal total, String shop) {
    }

    private Inv invoice(UUID id) {
        return jdbc.query("select invoice_no, customer_name, customer_phone, total from sales_invoices where id = ?",
                (rs, n) -> new Inv(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4),
                        settings.shop().name()), id).stream().findFirst().orElseThrow(() -> new NotFoundException("Invoice"));
    }

    private String language() {
        String l = settings.defaultLanguage();
        return "mr".equalsIgnoreCase(l) || "both".equalsIgnoreCase(l) ? "mr" : "en";
    }

    /** The thank-you text: used for the wa.me link, and as the human-readable record of what the customer got. */
    String message(String lang, Inv inv, String link) {
        Locale locale = Locale.forLanguageTag("mr".equals(lang) ? "mr" : "en");
        String name = inv.customerName() == null || inv.customerName().isBlank()
                ? messages.getMessage("wa.customer.default", null, locale) : inv.customerName();
        return messages.getMessage("wa.message", new Object[]{name, inv.shop(), inv.invoiceNo(),
                IndianFormat.amount(inv.total()), link}, locale);
    }

    private UUID insertMessage(UUID invoiceId, String to, String customerName, String mode, String lang, String template,
                               String text, boolean consent) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into whatsapp_messages (id, invoice_id, to_phone, customer_name, mode, language, template_name, "
                        + "message_text, status, customer_consent, created_by) values (?,?,?,?,?,?,?,?,'QUEUED',?,?)",
                id, invoiceId, to, customerName, mode, lang, template, text, consent, CurrentUser.username());
        return id;
    }

    private void fail(UUID msgId, RuntimeException ex) {
        log.warn("WhatsApp message {} failed: {}", msgId, ex.getMessage());
        jdbc.update("update whatsapp_messages set status = 'FAILED', attempts = attempts + 1, error_message = ?, "
                + "updated_at = now() where id = ?", trim(safeError(ex), 600), msgId);
    }

    /** Error text for the history screen; never contains the access token. */
    private String safeError(RuntimeException ex) {
        String m = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        String token = props.whatsapp().accessToken();
        if (token != null && !token.isBlank()) {
            m = m.replace(token, "***");
        }
        return m;
    }

    private String graph(String path) {
        return "https://graph.facebook.com/" + props.whatsapp().apiVersion() + "/"
                + settings.get("whatsapp.phone_number_id", "") + path;
    }

    private String uploadMedia(FileService.StoredFile pdf) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("messaging_product", "whatsapp");
        body.add("type", "application/pdf");
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.APPLICATION_PDF);
        ByteArrayResource res = new ByteArrayResource(pdf.data()) {
            @Override
            public String getFilename() {
                return pdf.fileName();
            }
        };
        body.add("file", new HttpEntity<>(res, partHeaders));
        JsonNode r = http.post().uri(graph("/media"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + props.whatsapp().accessToken())
                .contentType(MediaType.MULTIPART_FORM_DATA).body(body).retrieve().body(JsonNode.class);
        if (r == null || !r.hasNonNull("id")) {
            throw new IllegalStateException("WhatsApp media upload returned no id");
        }
        return r.get("id").asText();
    }

    private String sendTemplate(String to, String lang, String mediaId, String fileName, Inv inv) {
        String code = settings.get("mr".equals(lang) ? "whatsapp.template_lang_mr" : "whatsapp.template_lang_en", lang);
        String customer = inv.customerName() == null || inv.customerName().isBlank() ? "Customer" : inv.customerName();
        Map<String, Object> payload = Map.of(
                "messaging_product", "whatsapp",
                "to", to,
                "type", "template",
                "template", Map.of(
                        "name", settings.get("whatsapp.template_name", "invoice_thank_you"),
                        "language", Map.of("code", code),
                        "components", List.of(
                                Map.of("type", "header", "parameters", List.of(Map.of("type", "document",
                                        "document", Map.of("id", mediaId, "filename", fileName)))),
                                Map.of("type", "body", "parameters", List.of(
                                        text(customer), text(inv.shop()), text(inv.invoiceNo()),
                                        text("Rs. " + IndianFormat.amount(inv.total())))))));
        JsonNode r = http.post().uri(graph("/messages"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + props.whatsapp().accessToken())
                .contentType(MediaType.APPLICATION_JSON).body(payload).retrieve().body(JsonNode.class);
        if (r == null || !r.path("messages").has(0)) {
            throw new IllegalStateException("WhatsApp send returned no message id");
        }
        return r.path("messages").get(0).path("id").asText();
    }

    private static Map<String, String> text(String v) {
        return Map.of("type", "text", "text", v == null || v.isBlank() ? "-" : v.replace('\n', ' '));
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    public Optional<Instant> lastSent(UUID invoiceId) {
        return jdbc.query("select max(sent_at) from whatsapp_messages where invoice_id = ?",
                (rs, n) -> Optional.ofNullable(SqlUtil.instant(rs, 1)), invoiceId).stream().findFirst().flatMap(x -> x);
    }
}
