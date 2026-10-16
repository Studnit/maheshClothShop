-- =====================================================================
-- Invoice PDF archive + WhatsApp delivery history
-- =====================================================================

-- One saved PDF per sales invoice (invoices are immutable, so the PDF is generated once).
CREATE TABLE invoice_documents (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    invoice_id       uuid         NOT NULL REFERENCES sales_invoices (id),
    file_id          uuid         NOT NULL REFERENCES stored_files (id),
    language         varchar(5)   NOT NULL DEFAULT 'en',
    sha256           varchar(64)  NOT NULL,
    -- unguessable token for the customer download link (/i/{token}); can expire
    share_token      varchar(64)  NOT NULL,
    token_expires_at timestamptz,
    download_count   integer      NOT NULL DEFAULT 0,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz,
    created_by       varchar(60),
    updated_by       varchar(60)
);
CREATE UNIQUE INDEX uq_invoice_documents_invoice ON invoice_documents (invoice_id);
CREATE UNIQUE INDEX uq_invoice_documents_token   ON invoice_documents (share_token);

-- Every attempt to send a bill to a customer on WhatsApp, with its delivery status.
--   mode API  = sent automatically through the WhatsApp Business Cloud API
--   mode LINK = cashier tapped "Share on WhatsApp" (opens WhatsApp with a ready message)
CREATE TABLE whatsapp_messages (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    invoice_id           uuid         NOT NULL REFERENCES sales_invoices (id),
    document_id          uuid         REFERENCES invoice_documents (id),
    to_phone             varchar(20)  NOT NULL,
    customer_name        varchar(160),
    mode                 varchar(6)   NOT NULL,
    language             varchar(5)   NOT NULL DEFAULT 'en',
    template_name        varchar(100),
    message_text         text,
    status               varchar(12)  NOT NULL DEFAULT 'QUEUED',
    provider_message_id  varchar(120),
    error_message        varchar(600),
    attempts             integer      NOT NULL DEFAULT 0,
    customer_consent     boolean      NOT NULL DEFAULT false,
    sent_at              timestamptz,
    delivered_at         timestamptz,
    read_at              timestamptz,
    created_at           timestamptz  NOT NULL DEFAULT now(),
    updated_at           timestamptz,
    created_by           varchar(60),
    updated_by           varchar(60),
    CONSTRAINT ck_wa_mode   CHECK (mode IN ('API','LINK')),
    CONSTRAINT ck_wa_status CHECK (status IN ('QUEUED','SENT','DELIVERED','READ','FAILED','OPENED'))
);
CREATE INDEX ix_wa_invoice  ON whatsapp_messages (invoice_id);
CREATE INDEX ix_wa_time     ON whatsapp_messages (created_at DESC);
CREATE INDEX ix_wa_status   ON whatsapp_messages (status, created_at DESC);
CREATE INDEX ix_wa_provider ON whatsapp_messages (provider_message_id) WHERE provider_message_id IS NOT NULL;

INSERT INTO permissions (code, group_name, description) VALUES
 ('WHATSAPP_SEND',    'WhatsApp', 'Send or share bills with customers on WhatsApp'),
 ('WHATSAPP_HISTORY', 'WhatsApp', 'View the WhatsApp message history and invoice archive, and resend');

INSERT INTO role_permissions (role_id, permission_code) VALUES
 ('00000000-0000-0000-0000-0000000000a1', 'WHATSAPP_SEND'),
 ('00000000-0000-0000-0000-0000000000a1', 'WHATSAPP_HISTORY'),
 ('00000000-0000-0000-0000-0000000000a2', 'WHATSAPP_SEND');

-- Non-secret WhatsApp settings. The access token and app secret are NEVER stored in the database:
-- they come from environment variables (WHATSAPP_ACCESS_TOKEN, WHATSAPP_APP_SECRET).
INSERT INTO app_settings (setting_key, setting_value) VALUES
 ('whatsapp.enabled',            'false'),   -- master switch for automatic sending through the Cloud API
 ('whatsapp.auto_send',          'true'),    -- send automatically when a bill is completed (if a phone number is present)
 ('whatsapp.phone_number_id',    ''),        -- from Meta: WhatsApp Manager > API setup
 ('whatsapp.template_name',      'invoice_thank_you'),
 ('whatsapp.template_lang_en',   'en'),
 ('whatsapp.template_lang_mr',   'mr'),
 ('whatsapp.link_validity_days', '90'),
 ('whatsapp.default_country_code','91'),
 ('app.public_base_url',         '');        -- e.g. https://billing.yourshop.in, used in customer download links
