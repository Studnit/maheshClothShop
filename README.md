# Mahesh Clothes Shop - Inventory, Billing and Business Management

Web application for a clothing shop in Lohara, Dharashiv, Maharashtra: stock with size and colour variants, supplier
purchasing, a fast billing screen (POS), A4 and thermal bills, WhatsApp delivery of every bill, returns and exchanges,
expenses, a live dashboard and reports. Everything is available in **English and Marathi** (switch in the top bar).

**Stack:** Java 21, Spring Boot 3.5, Thymeleaf + Bootstrap 5 (self-hosted, works offline), PostgreSQL 16, Flyway,
Spring Security, OpenAPI. Roles: **Owner**, **Cashier**, **Stock Manager** (permissions editable by the owner).

## What is in it

| Area | Highlights |
|---|---|
| Billing (POS) | Barcode scan or search, size/colour pick, per-line and bill discount (cashier limit), cash/UPI/card/bank/split/credit (udhar), hold and resume bills, exchange credit, idempotent submit (no double bills), F2/F9 shortcuts |
| WhatsApp | After a bill: PDF sent from the owner WhatsApp Business account with a thank-you message (English or Marathi), copy saved on our side, full history with Sent/Delivered/Read/Failed and retry, plus a no-API "Share on WhatsApp" fallback |
| Inventory | Variant stock, immutable stock ledger, weighted-average cost, adjustments with approval for write-offs, stock counts, low-stock and reorder suggestions, dead/fast movers, size x colour matrix, barcode labels |
| Purchasing | Suppliers, draft/receive purchases with freight allocated into landed cost, supplier payments and statements, returns to supplier |
| Money | Customers with credit limits and dues, returns/exchanges/refunds, expenses with receipts, profit and loss, GST summary (optional - off by default) |
| Reports | 15 reports with CSV export, permission-checked |
| Admin | Users, roles and permissions, audit log, settings, backup scripts |
| API | Read-only REST API under `/api/v1` with Swagger UI (owner only, off in production by default) |

The shop address, phone and GSTIN are **not invented** - enter them in Settings -> Shop. GST is switched off until
the shop turns it on.

## Run it on your computer (development)

You need Java 21, Maven and PostgreSQL 16 or newer.

```bash
createdb mahesh_shop            # must be an EMPTY database
export DB_URL=jdbc:postgresql://localhost:5432/mahesh_shop DB_USERNAME=postgres DB_PASSWORD=dev
mvn spring-boot:run             # clean, empty shop (no demo data)
# optional trial with clearly-labelled [DEMO] products and bills, on a throw-away database only:
#   SPRING_PROFILES_ACTIVE=dev,demo mvn spring-boot:run
```

Open http://localhost:8080 - the first visit goes to `/setup` to create the owner account.

## Tests

```bash
mvn test
```

* Unit tests (pricing, tax, landed cost, costing, proration, formatting, phone numbers, WhatsApp signature, English/Marathi key parity).
* `WebSmokeTest` boots the whole app on an empty local PostgreSQL database, validates every JPA mapping against the
  Flyway schema and opens every screen in both languages. It runs only when `TEST_DB_URL` is set
  (e.g. `TEST_DB_URL=jdbc:postgresql://localhost:5432/mahesh_test TEST_DB_USERNAME=postgres TEST_DB_PASSWORD=... mvn test`).
* `scripts/i18n-keys.py` checks that every message key used by a template or Java class exists in both languages.
* `scripts/integrity-check.sql` proves stock = ledger, bill totals = lines, paid = payments, etc. Run it any time.

## Deploy

See [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) (plain Java + PostgreSQL, HTTPS proxy, nightly backups),
[docs/WHATSAPP_SETUP.md](docs/WHATSAPP_SETUP.md) (owner WhatsApp Business account) and
[docs/PRINTERS_AND_LABELS.md](docs/PRINTERS_AND_LABELS.md). Design decisions and known limits:
[docs/REVIEW_AND_DECISIONS.md](docs/REVIEW_AND_DECISIONS.md).
