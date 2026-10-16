-- =====================================================================
-- Mahesh Clothes Shop - core schema (PostgreSQL 13+)
-- Conventions
--   * UUID primary keys (gen_random_uuid is built in from PostgreSQL 13)
--   * money = numeric(14,2); unit costs = numeric(14,4); tax rates = numeric(5,2)
--   * timestamps are timestamptz (stored as UTC, displayed in the shop timezone)
--   * every business table carries created_at/updated_at/created_by/updated_by
-- =====================================================================

-- ---------- identity & access ----------
CREATE TABLE permissions (
    code         varchar(60)  PRIMARY KEY,
    group_name   varchar(40)  NOT NULL,
    description  varchar(200) NOT NULL
);

CREATE TABLE roles (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name         varchar(40)  NOT NULL,
    description  varchar(200),
    system_role  boolean      NOT NULL DEFAULT false,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    updated_at   timestamptz,
    created_by   varchar(60),
    updated_by   varchar(60)
);
CREATE UNIQUE INDEX uq_roles_name ON roles (lower(name));

CREATE TABLE role_permissions (
    role_id          uuid        NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    permission_code  varchar(60) NOT NULL REFERENCES permissions (code) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_code)
);

CREATE TABLE users (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    username         varchar(60)  NOT NULL,
    full_name        varchar(120) NOT NULL,
    email            varchar(160),
    phone            varchar(20),
    password_hash    varchar(100) NOT NULL,
    role_id          uuid         NOT NULL REFERENCES roles (id),
    enabled          boolean      NOT NULL DEFAULT true,
    failed_attempts  integer      NOT NULL DEFAULT 0,
    locked_until     timestamptz,
    last_login_at    timestamptz,
    version          bigint       NOT NULL DEFAULT 0,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz,
    created_by       varchar(60),
    updated_by       varchar(60),
    CONSTRAINT ck_users_username CHECK (username ~ '^[a-z0-9.]{3,60}$')
);
CREATE UNIQUE INDEX uq_users_username ON users (lower(username));

-- ---------- catalogue ----------
CREATE TABLE categories (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name        varchar(80) NOT NULL,
    sort_order  integer     NOT NULL DEFAULT 0,
    active      boolean     NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz,
    created_by  varchar(60),
    updated_by  varchar(60)
);
CREATE UNIQUE INDEX uq_categories_name ON categories (lower(name));

CREATE TABLE brands (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name        varchar(80) NOT NULL,
    sort_order  integer     NOT NULL DEFAULT 0,
    active      boolean     NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz,
    created_by  varchar(60),
    updated_by  varchar(60)
);
CREATE UNIQUE INDEX uq_brands_name ON brands (lower(name));

CREATE TABLE sizes (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name        varchar(30) NOT NULL,
    sort_order  integer     NOT NULL DEFAULT 0,
    active      boolean     NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz,
    created_by  varchar(60),
    updated_by  varchar(60)
);
CREATE UNIQUE INDEX uq_sizes_name ON sizes (lower(name));

CREATE TABLE colours (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name        varchar(40) NOT NULL,
    hex_code    varchar(7),
    sort_order  integer     NOT NULL DEFAULT 0,
    active      boolean     NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz,
    created_by  varchar(60),
    updated_by  varchar(60),
    CONSTRAINT ck_colours_hex CHECK (hex_code IS NULL OR hex_code ~ '^#[0-9A-Fa-f]{6}$')
);
CREATE UNIQUE INDEX uq_colours_name ON colours (lower(name));

-- uploaded images / receipts / attachments live in the database so they survive
-- container redeploys and are included in every backup
CREATE TABLE stored_files (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    file_name     varchar(200) NOT NULL,
    content_type  varchar(100) NOT NULL,
    size_bytes    integer      NOT NULL,
    data          bytea        NOT NULL,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz,
    created_by    varchar(60),
    updated_by    varchar(60)
);

CREATE TABLE products (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    product_code    varchar(20)   NOT NULL,
    name            varchar(200)  NOT NULL,
    sku             varchar(40)   NOT NULL,
    category_id     uuid          NOT NULL REFERENCES categories (id),
    brand_id        uuid          REFERENCES brands (id),
    description     text,
    fabric          varchar(80),
    design          varchar(80),
    purchase_cost   numeric(14,2) NOT NULL DEFAULT 0 CHECK (purchase_cost >= 0),
    selling_price   numeric(14,2) NOT NULL CHECK (selling_price >= 0),
    mrp             numeric(14,2) CHECK (mrp IS NULL OR mrp >= 0),
    tax_rate        numeric(5,2)  NOT NULL DEFAULT 0 CHECK (tax_rate >= 0 AND tax_rate <= 100),
    hsn_code        varchar(12),
    min_stock       integer       CHECK (min_stock IS NULL OR min_stock >= 0),
    barcode         varchar(60),
    image_file_id   uuid          REFERENCES stored_files (id),
    active          boolean       NOT NULL DEFAULT true,
    version         bigint        NOT NULL DEFAULT 0,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    updated_at      timestamptz,
    created_by      varchar(60),
    updated_by      varchar(60)
);
CREATE UNIQUE INDEX uq_products_code ON products (product_code);
CREATE UNIQUE INDEX uq_products_sku  ON products (upper(sku));
CREATE UNIQUE INDEX uq_products_barcode ON products (barcode) WHERE barcode IS NOT NULL;
CREATE INDEX ix_products_category ON products (category_id);
CREATE INDEX ix_products_name ON products (lower(name) text_pattern_ops);

CREATE TABLE product_variants (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id    uuid         NOT NULL REFERENCES products (id),
    sku           varchar(60)  NOT NULL,
    size_id       uuid         REFERENCES sizes (id),
    colour_id     uuid         REFERENCES colours (id),
    barcode       varchar(60),
    selling_price numeric(14,2) CHECK (selling_price IS NULL OR selling_price >= 0),  -- optional override
    active        boolean      NOT NULL DEFAULT true,
    version       bigint       NOT NULL DEFAULT 0,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz,
    created_by    varchar(60),
    updated_by    varchar(60)
);
CREATE UNIQUE INDEX uq_variants_sku ON product_variants (upper(sku));
CREATE UNIQUE INDEX uq_variants_barcode ON product_variants (barcode) WHERE barcode IS NOT NULL;
-- one variant per product/size/colour combination (NULL size or colour counts as "none")
CREATE UNIQUE INDEX uq_variants_combo ON product_variants (
    product_id,
    COALESCE(size_id,   '00000000-0000-0000-0000-000000000000'::uuid),
    COALESCE(colour_id, '00000000-0000-0000-0000-000000000000'::uuid)
);
CREATE INDEX ix_variants_product ON product_variants (product_id);

-- ---------- inventory ----------
-- One row per variant. CHECK constraints are the last line of defence against negative stock.
CREATE TABLE stock_balances (
    variant_id     uuid PRIMARY KEY REFERENCES product_variants (id),
    available_qty  integer       NOT NULL DEFAULT 0 CHECK (available_qty >= 0),
    damaged_qty    integer       NOT NULL DEFAULT 0 CHECK (damaged_qty >= 0),
    -- weighted-average cost of SELLABLE units
    avg_cost       numeric(14,4) NOT NULL DEFAULT 0 CHECK (avg_cost >= 0),
    version        bigint        NOT NULL DEFAULT 0,
    updated_at     timestamptz
);

-- Append-only ledger of every stock change.
CREATE TABLE stock_movements (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    seq              bigint GENERATED ALWAYS AS IDENTITY,   -- strict ledger order (timestamps can tie)
    variant_id       uuid          NOT NULL REFERENCES product_variants (id),
    movement_type    varchar(30)   NOT NULL,
    available_delta  integer       NOT NULL DEFAULT 0,
    damaged_delta    integer       NOT NULL DEFAULT 0,
    unit_cost        numeric(14,4) NOT NULL DEFAULT 0,
    available_after  integer       NOT NULL,
    damaged_after    integer       NOT NULL,
    ref_type         varchar(30),
    ref_id           uuid,
    ref_no           varchar(60),
    reason           varchar(300),
    created_at       timestamptz   NOT NULL DEFAULT now(),
    updated_at       timestamptz,
    created_by       varchar(60),
    updated_by       varchar(60),
    CONSTRAINT ck_movement_type CHECK (movement_type IN (
        'OPENING','PURCHASE_RECEIPT','SALE','SALE_CANCEL','SALE_RETURN','RETURN_DAMAGED',
        'SUPPLIER_RETURN','ADJUST_IN','ADJUST_OUT','DAMAGE','WRITE_OFF','COUNT'))
);
CREATE INDEX ix_movements_variant_time ON stock_movements (variant_id, seq DESC);
CREATE INDEX ix_movements_ref ON stock_movements (ref_type, ref_id);
CREATE INDEX ix_movements_time ON stock_movements (created_at DESC);

CREATE TABLE stock_adjustments (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    adjustment_no varchar(40)  NOT NULL,
    variant_id   uuid          NOT NULL REFERENCES product_variants (id),
    adjust_type  varchar(20)   NOT NULL,
    quantity     integer       NOT NULL CHECK (quantity > 0),
    unit_cost    numeric(14,4),
    reason       varchar(300)  NOT NULL,
    created_at   timestamptz   NOT NULL DEFAULT now(),
    updated_at   timestamptz,
    created_by   varchar(60),
    updated_by   varchar(60),
    CONSTRAINT ck_adjust_type CHECK (adjust_type IN
        ('OPENING','ADJUST_IN','ADJUST_OUT','DAMAGE','WRITE_OFF','COUNT_GAIN','COUNT_LOSS'))
);
CREATE UNIQUE INDEX uq_adjustments_no ON stock_adjustments (adjustment_no);
CREATE INDEX ix_adjustments_variant ON stock_adjustments (variant_id);
CREATE INDEX ix_adjustments_time ON stock_adjustments (created_at DESC);

-- ---------- suppliers & purchasing ----------
CREATE TABLE suppliers (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    supplier_code    varchar(20)  NOT NULL,
    name             varchar(160) NOT NULL,
    phone            varchar(20),
    address          varchar(300),
    gstin            varchar(15),
    opening_balance  numeric(14,2) NOT NULL DEFAULT 0,   -- amount we owe at start (+) or advance (-)
    notes            varchar(500),
    active           boolean      NOT NULL DEFAULT true,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz,
    created_by       varchar(60),
    updated_by       varchar(60)
);
CREATE UNIQUE INDEX uq_suppliers_code ON suppliers (supplier_code);
CREATE INDEX ix_suppliers_name ON suppliers (lower(name) text_pattern_ops);

CREATE TABLE purchase_invoices (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    purchase_no         varchar(40)  NOT NULL,
    supplier_id         uuid         NOT NULL REFERENCES suppliers (id),
    supplier_invoice_no varchar(60)  NOT NULL,
    purchase_date       date         NOT NULL,
    status              varchar(12)  NOT NULL DEFAULT 'DRAFT',
    subtotal            numeric(14,2) NOT NULL DEFAULT 0,
    discount_total      numeric(14,2) NOT NULL DEFAULT 0,
    tax_total           numeric(14,2) NOT NULL DEFAULT 0,
    additional_costs    numeric(14,2) NOT NULL DEFAULT 0,
    total               numeric(14,2) NOT NULL DEFAULT 0,
    amount_paid         numeric(14,2) NOT NULL DEFAULT 0,
    returned_total      numeric(14,2) NOT NULL DEFAULT 0,
    notes               varchar(500),
    attachment_file_id  uuid         REFERENCES stored_files (id),
    received_at         timestamptz,
    received_by         varchar(60),
    version             bigint       NOT NULL DEFAULT 0,
    created_at          timestamptz  NOT NULL DEFAULT now(),
    updated_at          timestamptz,
    created_by          varchar(60),
    updated_by          varchar(60),
    CONSTRAINT ck_purchase_status CHECK (status IN ('DRAFT','RECEIVED','CANCELLED')),
    CONSTRAINT ck_purchase_amounts CHECK (amount_paid >= 0 AND returned_total >= 0)
);
CREATE UNIQUE INDEX uq_purchase_no ON purchase_invoices (purchase_no);
-- the same supplier invoice number cannot be entered twice (cancelled drafts excluded)
CREATE UNIQUE INDEX uq_purchase_supplier_invoice
    ON purchase_invoices (supplier_id, upper(supplier_invoice_no)) WHERE status <> 'CANCELLED';
CREATE INDEX ix_purchase_supplier_date ON purchase_invoices (supplier_id, purchase_date DESC);
CREATE INDEX ix_purchase_date ON purchase_invoices (purchase_date DESC);

CREATE TABLE purchase_items (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    purchase_id      uuid          NOT NULL REFERENCES purchase_invoices (id) ON DELETE CASCADE,
    variant_id       uuid          NOT NULL REFERENCES product_variants (id),
    quantity         integer       NOT NULL CHECK (quantity > 0),
    unit_price       numeric(14,2) NOT NULL CHECK (unit_price >= 0),
    discount         numeric(14,2) NOT NULL DEFAULT 0 CHECK (discount >= 0),
    tax_rate         numeric(5,2)  NOT NULL DEFAULT 0 CHECK (tax_rate >= 0),
    tax_amount       numeric(14,2) NOT NULL DEFAULT 0,
    line_total       numeric(14,2) NOT NULL DEFAULT 0,       -- net of discount, plus tax
    landed_unit_cost numeric(14,4) NOT NULL DEFAULT 0,       -- cost used for inventory valuation
    returned_qty     integer       NOT NULL DEFAULT 0,
    returned_amount  numeric(14,2) NOT NULL DEFAULT 0,
    created_at       timestamptz   NOT NULL DEFAULT now(),
    updated_at       timestamptz,
    created_by       varchar(60),
    updated_by       varchar(60),
    CONSTRAINT ck_purchase_item_returns CHECK (returned_qty >= 0 AND returned_qty <= quantity)
);
CREATE INDEX ix_purchase_items_purchase ON purchase_items (purchase_id);
CREATE INDEX ix_purchase_items_variant ON purchase_items (variant_id);

CREATE TABLE supplier_payments (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_no  varchar(40)   NOT NULL,
    supplier_id uuid          NOT NULL REFERENCES suppliers (id),
    purchase_id uuid          REFERENCES purchase_invoices (id),
    amount      numeric(14,2) NOT NULL CHECK (amount > 0),
    method      varchar(30)   NOT NULL,
    reference   varchar(100),
    paid_on     date          NOT NULL,
    notes       varchar(300),
    created_at  timestamptz   NOT NULL DEFAULT now(),
    updated_at  timestamptz,
    created_by  varchar(60),
    updated_by  varchar(60)
);
CREATE UNIQUE INDEX uq_supplier_payment_no ON supplier_payments (payment_no);
CREATE INDEX ix_supplier_payments_supplier ON supplier_payments (supplier_id, paid_on DESC);

CREATE TABLE supplier_returns (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    return_no   varchar(40)   NOT NULL,
    supplier_id uuid          NOT NULL REFERENCES suppliers (id),
    purchase_id uuid          NOT NULL REFERENCES purchase_invoices (id),
    return_date date          NOT NULL,
    total       numeric(14,2) NOT NULL DEFAULT 0,
    reason      varchar(300)  NOT NULL,
    created_at  timestamptz   NOT NULL DEFAULT now(),
    updated_at  timestamptz,
    created_by  varchar(60),
    updated_by  varchar(60)
);
CREATE UNIQUE INDEX uq_supplier_return_no ON supplier_returns (return_no);
CREATE INDEX ix_supplier_returns_supplier ON supplier_returns (supplier_id, return_date DESC);

CREATE TABLE supplier_return_items (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    return_id        uuid          NOT NULL REFERENCES supplier_returns (id) ON DELETE CASCADE,
    purchase_item_id uuid          NOT NULL REFERENCES purchase_items (id),
    variant_id       uuid          NOT NULL REFERENCES product_variants (id),
    quantity         integer       NOT NULL CHECK (quantity > 0),
    amount           numeric(14,2) NOT NULL,         -- credit received from the supplier
    stock_unit_cost  numeric(14,4) NOT NULL,         -- cost at which stock left the shelf
    created_at       timestamptz   NOT NULL DEFAULT now(),
    updated_at       timestamptz,
    created_by       varchar(60),
    updated_by       varchar(60)
);
CREATE INDEX ix_supplier_return_items_return ON supplier_return_items (return_id);

-- ---------- customers ----------
CREATE TABLE customers (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_code  varchar(20)  NOT NULL,
    name           varchar(160) NOT NULL,
    phone          varchar(20),
    address        varchar(300),
    notes          varchar(500),
    credit_limit   numeric(14,2) CHECK (credit_limit IS NULL OR credit_limit >= 0),
    active         boolean      NOT NULL DEFAULT true,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    updated_at     timestamptz,
    created_by     varchar(60),
    updated_by     varchar(60)
);
CREATE UNIQUE INDEX uq_customers_code ON customers (customer_code);
CREATE UNIQUE INDEX uq_customers_phone ON customers (phone) WHERE phone IS NOT NULL AND phone <> '';
CREATE INDEX ix_customers_name ON customers (lower(name) text_pattern_ops);

-- ---------- payment methods (configurable) ----------
CREATE TABLE payment_methods (
    code            varchar(30) PRIMARY KEY,
    name            varchar(60) NOT NULL,
    requires_reference boolean  NOT NULL DEFAULT false,
    selectable      boolean     NOT NULL DEFAULT true,   -- false = system-only (e.g. exchange credit)
    active          boolean     NOT NULL DEFAULT true,
    sort_order      integer     NOT NULL DEFAULT 0
);

-- ---------- sales ----------
CREATE TABLE sales_invoices (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    invoice_no            varchar(40)   NOT NULL,
    sale_time             timestamptz   NOT NULL DEFAULT now(),
    customer_id           uuid          REFERENCES customers (id),
    customer_name         varchar(160),
    customer_phone        varchar(20),
    status                varchar(12)   NOT NULL DEFAULT 'COMPLETED',
    prices_include_tax    boolean       NOT NULL DEFAULT true,
    subtotal              numeric(14,2) NOT NULL,   -- sum of unit price x qty before any discount
    line_discount_total   numeric(14,2) NOT NULL DEFAULT 0,
    invoice_discount      numeric(14,2) NOT NULL DEFAULT 0,
    taxable_total         numeric(14,2) NOT NULL,
    tax_total             numeric(14,2) NOT NULL DEFAULT 0,
    round_off             numeric(14,2) NOT NULL DEFAULT 0,
    total                 numeric(14,2) NOT NULL,
    amount_paid           numeric(14,2) NOT NULL DEFAULT 0,
    balance_due           numeric(14,2) NOT NULL DEFAULT 0,
    total_cost            numeric(14,2) NOT NULL DEFAULT 0,   -- cost of goods sold snapshot
    client_request_id     uuid          NOT NULL,           -- idempotency key
    exchange_return_id    uuid,
    notes                 varchar(500),
    cancelled_at          timestamptz,
    cancelled_by          varchar(60),
    cancel_reason         varchar(300),
    version               bigint        NOT NULL DEFAULT 0,
    created_at            timestamptz   NOT NULL DEFAULT now(),
    updated_at            timestamptz,
    created_by            varchar(60),
    updated_by            varchar(60),
    CONSTRAINT ck_sales_status CHECK (status IN ('COMPLETED','CANCELLED')),
    CONSTRAINT ck_sales_amounts CHECK (total >= 0 AND amount_paid >= 0 AND balance_due >= 0)
);
CREATE UNIQUE INDEX uq_sales_invoice_no ON sales_invoices (invoice_no);
CREATE UNIQUE INDEX uq_sales_client_request ON sales_invoices (client_request_id);
CREATE INDEX ix_sales_time ON sales_invoices (sale_time DESC);
CREATE INDEX ix_sales_customer ON sales_invoices (customer_id);
CREATE INDEX ix_sales_phone ON sales_invoices (customer_phone);
CREATE INDEX ix_sales_created_by ON sales_invoices (created_by, sale_time DESC);
CREATE INDEX ix_sales_due ON sales_invoices (customer_id) WHERE balance_due > 0 AND status = 'COMPLETED';

CREATE TABLE sales_items (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    invoice_id              uuid          NOT NULL REFERENCES sales_invoices (id),
    variant_id              uuid          NOT NULL REFERENCES product_variants (id),
    product_name            varchar(200)  NOT NULL,
    sku                     varchar(60)   NOT NULL,
    size_name               varchar(30),
    colour_name             varchar(40),
    hsn_code                varchar(12),
    quantity                integer       NOT NULL CHECK (quantity > 0),
    unit_price              numeric(14,2) NOT NULL,
    line_discount           numeric(14,2) NOT NULL DEFAULT 0,
    invoice_discount_share  numeric(14,2) NOT NULL DEFAULT 0,
    taxable_value           numeric(14,2) NOT NULL,
    tax_rate                numeric(5,2)  NOT NULL DEFAULT 0,
    tax_amount              numeric(14,2) NOT NULL DEFAULT 0,
    line_total              numeric(14,2) NOT NULL,
    unit_cost               numeric(14,4) NOT NULL DEFAULT 0,   -- weighted-average cost at time of sale
    returned_qty            integer       NOT NULL DEFAULT 0,
    returned_taxable        numeric(14,2) NOT NULL DEFAULT 0,
    returned_tax            numeric(14,2) NOT NULL DEFAULT 0,
    returned_total          numeric(14,2) NOT NULL DEFAULT 0,
    created_at              timestamptz   NOT NULL DEFAULT now(),
    updated_at              timestamptz,
    created_by              varchar(60),
    updated_by              varchar(60),
    CONSTRAINT ck_sales_item_returns CHECK (returned_qty >= 0 AND returned_qty <= quantity)
);
CREATE INDEX ix_sales_items_invoice ON sales_items (invoice_id);
CREATE INDEX ix_sales_items_variant ON sales_items (variant_id);

CREATE TABLE sales_payments (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    invoice_id      uuid          NOT NULL REFERENCES sales_invoices (id),
    payment_kind    varchar(20)   NOT NULL DEFAULT 'SALE',
    method          varchar(30)   NOT NULL REFERENCES payment_methods (code),
    amount          numeric(14,2) NOT NULL CHECK (amount > 0),
    reference       varchar(100),
    received_at     timestamptz   NOT NULL DEFAULT now(),
    created_at      timestamptz   NOT NULL DEFAULT now(),
    updated_at      timestamptz,
    created_by      varchar(60),
    updated_by      varchar(60),
    CONSTRAINT ck_payment_kind CHECK (payment_kind IN ('SALE','BALANCE','EXCHANGE_CREDIT'))
);
CREATE INDEX ix_sales_payments_invoice ON sales_payments (invoice_id);
CREATE INDEX ix_sales_payments_time ON sales_payments (received_at DESC);

-- ---------- returns, exchanges, refunds ----------
CREATE TABLE sales_returns (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    return_no           varchar(40)   NOT NULL,
    invoice_id          uuid          NOT NULL REFERENCES sales_invoices (id),
    return_time         timestamptz   NOT NULL DEFAULT now(),
    return_type         varchar(10)   NOT NULL,                 -- RETURN | EXCHANGE
    reason              varchar(300)  NOT NULL,
    taxable_total       numeric(14,2) NOT NULL DEFAULT 0,
    tax_total           numeric(14,2) NOT NULL DEFAULT 0,
    round_off_adjust    numeric(14,2) NOT NULL DEFAULT 0,
    total               numeric(14,2) NOT NULL DEFAULT 0,
    balance_adjusted    numeric(14,2) NOT NULL DEFAULT 0,       -- reduced the customer's unpaid balance
    refund_total        numeric(14,2) NOT NULL DEFAULT 0,       -- money actually handed back
    credit_total        numeric(14,2) NOT NULL DEFAULT 0,       -- exchange credit created
    credit_used         numeric(14,2) NOT NULL DEFAULT 0,
    credit_status       varchar(10)   NOT NULL DEFAULT 'NONE',  -- NONE | PENDING | USED | REFUNDED
    client_request_id   uuid          NOT NULL,
    version             bigint        NOT NULL DEFAULT 0,
    created_at          timestamptz   NOT NULL DEFAULT now(),
    updated_at          timestamptz,
    created_by          varchar(60),
    updated_by          varchar(60),
    CONSTRAINT ck_return_type CHECK (return_type IN ('RETURN','EXCHANGE')),
    CONSTRAINT ck_return_credit_status CHECK (credit_status IN ('NONE','PENDING','USED','REFUNDED')),
    CONSTRAINT ck_return_credit CHECK (credit_used >= 0 AND credit_used <= credit_total)
);
CREATE UNIQUE INDEX uq_return_no ON sales_returns (return_no);
CREATE UNIQUE INDEX uq_return_client_request ON sales_returns (client_request_id);
CREATE INDEX ix_returns_invoice ON sales_returns (invoice_id);
CREATE INDEX ix_returns_time ON sales_returns (return_time DESC);

ALTER TABLE sales_invoices
    ADD CONSTRAINT fk_sales_exchange_return FOREIGN KEY (exchange_return_id) REFERENCES sales_returns (id);

CREATE TABLE sales_return_items (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    return_id       uuid          NOT NULL REFERENCES sales_returns (id) ON DELETE CASCADE,
    sales_item_id   uuid          NOT NULL REFERENCES sales_items (id),
    variant_id      uuid          NOT NULL REFERENCES product_variants (id),
    quantity        integer       NOT NULL CHECK (quantity > 0),
    taxable_value   numeric(14,2) NOT NULL,
    tax_amount      numeric(14,2) NOT NULL DEFAULT 0,
    amount          numeric(14,2) NOT NULL,
    unit_cost       numeric(14,4) NOT NULL DEFAULT 0,
    item_condition  varchar(12)   NOT NULL DEFAULT 'RESELLABLE',
    created_at      timestamptz   NOT NULL DEFAULT now(),
    updated_at      timestamptz,
    created_by      varchar(60),
    updated_by      varchar(60),
    CONSTRAINT ck_return_item_condition CHECK (item_condition IN ('RESELLABLE','DAMAGED'))
);
CREATE INDEX ix_return_items_return ON sales_return_items (return_id);
CREATE INDEX ix_return_items_sales_item ON sales_return_items (sales_item_id);

CREATE TABLE refunds (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    refund_no     varchar(40)   NOT NULL,
    refund_type   varchar(20)   NOT NULL,
    invoice_id    uuid          NOT NULL REFERENCES sales_invoices (id),
    return_id     uuid          REFERENCES sales_returns (id),
    amount        numeric(14,2) NOT NULL CHECK (amount > 0),
    method        varchar(30)   NOT NULL REFERENCES payment_methods (code),
    reference     varchar(100),
    refunded_at   timestamptz   NOT NULL DEFAULT now(),
    created_at    timestamptz   NOT NULL DEFAULT now(),
    updated_at    timestamptz,
    created_by    varchar(60),
    updated_by    varchar(60),
    CONSTRAINT ck_refund_type CHECK (refund_type IN ('RETURN','CANCELLATION','EXCHANGE_BALANCE'))
);
CREATE UNIQUE INDEX uq_refund_no ON refunds (refund_no);
CREATE INDEX ix_refunds_invoice ON refunds (invoice_id);
CREATE INDEX ix_refunds_time ON refunds (refunded_at DESC);

-- ---------- expenses ----------
CREATE TABLE expense_categories (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name        varchar(80) NOT NULL,
    sort_order  integer     NOT NULL DEFAULT 0,
    active      boolean     NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz,
    created_by  varchar(60),
    updated_by  varchar(60)
);
CREATE UNIQUE INDEX uq_expense_categories_name ON expense_categories (lower(name));

CREATE TABLE expenses (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    category_id      uuid          NOT NULL REFERENCES expense_categories (id),
    amount           numeric(14,2) NOT NULL CHECK (amount > 0),
    expense_date     date          NOT NULL,
    payment_method   varchar(30)   NOT NULL,
    description      varchar(300),
    receipt_file_id  uuid          REFERENCES stored_files (id),
    created_at       timestamptz   NOT NULL DEFAULT now(),
    updated_at       timestamptz,
    created_by       varchar(60),
    updated_by       varchar(60)
);
CREATE INDEX ix_expenses_date ON expenses (expense_date DESC);
CREATE INDEX ix_expenses_category ON expenses (category_id);

-- ---------- settings, numbering, audit ----------
CREATE TABLE app_settings (
    setting_key    varchar(80) PRIMARY KEY,
    setting_value  text        NOT NULL DEFAULT '',
    updated_at     timestamptz NOT NULL DEFAULT now(),
    updated_by     varchar(60)
);

-- Gap-free document numbers. The upsert row-locks until commit, so two billers can never get the same number.
CREATE TABLE number_sequences (
    seq_name    varchar(40) NOT NULL,
    period_key  varchar(20) NOT NULL,
    last_value  bigint      NOT NULL DEFAULT 0,
    PRIMARY KEY (seq_name, period_key)
);

CREATE TABLE audit_logs (
    id           bigserial PRIMARY KEY,
    occurred_at  timestamptz  NOT NULL DEFAULT now(),
    username     varchar(60),
    action       varchar(60)  NOT NULL,
    entity_type  varchar(40),
    entity_id    varchar(64),
    details      text,
    ip_address   varchar(45)
);
CREATE INDEX ix_audit_time ON audit_logs (occurred_at DESC);
CREATE INDEX ix_audit_entity ON audit_logs (entity_type, entity_id);
CREATE INDEX ix_audit_user ON audit_logs (username, occurred_at DESC);

-- ---------- tamper protection ----------
-- The audit trail and the stock ledger are append-only. Corrections are made with new rows
-- (reversals, adjustments), never by rewriting history.
CREATE FUNCTION forbid_modification() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% on % is not allowed: this table is append-only', TG_OP, TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_logs_append_only
    BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION forbid_modification();

CREATE TRIGGER trg_stock_movements_append_only
    BEFORE UPDATE OR DELETE ON stock_movements
    FOR EACH ROW EXECUTE FUNCTION forbid_modification();
