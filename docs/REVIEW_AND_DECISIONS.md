# Review notes, decisions and known limits

## Decisions where the brief was open

| Topic | Decision | Why |
|---|---|---|
| Shop identity | Name "Mahesh Clothes Shop", village Lohara, district Dharashiv, state Maharashtra pre-filled. Address, PIN, phone, GSTIN left empty | The brief says not to invent them; enter in Settings |
| GST | Off by default; when on, prices may be tax-inclusive (default) or exclusive; per-product rate; GST summary report | Many small shops are unregistered; registered ones can switch it on |
| Costing | Weighted average over sellable units; cost frozen on each bill line | Profit reports stay right when buying prices change |
| Landed cost | Freight and extra charges are spread over purchase lines by value; supplier GST counts as cost unless "claims input credit" is set | Matches small-shop practice |
| Returns | Returned value first reduces what the customer still owes; any remainder is refunded (return) or kept as credit (exchange). Refund + credit can never exceed what was paid | Stops cash leaking out on credit bills |
| Round-off | Optional; returned with the last full return | Keeps bill totals reconcilable |
| Cashier discount | Capped by a setting (default 10 percent); more needs a user with the discount permission | Common abuse point |
| Stock never negative | DB constraint plus row locks in a fixed order | Two cashiers cannot sell the last piece twice |
| Double submit | Every bill has a request id; a retry returns the same bill | Slow networks and double taps |
| Ledgers | Stock ledger, audit log and payments are append-only; cancellations create reversing entries | Auditability |
| Language | English and Marathi on every screen, bill (screen and print), error message and WhatsApp text; cookie remembers the choice, shop default in Settings | Requested |
| Dates and money | Stored in UTC / numeric(14,2); shown in Asia/Kolkata with Indian digit grouping | |
| API | Read-only on purpose | Writes carry approval and idempotency rules that live in the screens |
| Secrets | WhatsApp token/app secret/verify token only from environment variables | Keeps them out of backups and the Settings screen |

## Things changed after review of the first draft

* Payment and refund methods come from the `payment_methods` table everywhere (the purchase payment form had its own list).
* Customer-facing PDF link uses an unguessable token, expires per setting, and shows only that one bill.
* Cashiers see only their own bills unless they hold the sales-report permission.
* WhatsApp sending runs after the bill transaction commits, so a WhatsApp outage can never undo or delay a sale.
* The webhook accepts only calls with a valid HMAC signature.
* Dashboard money tiles need the financial permission; the API dashboard does too.

## Known limits (be aware)

1. **PDF font.** The bill PDF uses the built-in Helvetica, so Marathi (Devanagari) characters in it print as "?".
   Product names in Marathi therefore appear degraded in the PDF only; the on-screen bill, the print page and the
   WhatsApp message are full Marathi. To get Marathi in the PDF, add a Devanagari TTF (for example Noto Sans
   Devanagari, open licence) to `src/main/resources/fonts/` and load it in `InvoicePdfService` with `BaseFont.IDENTITY_H`;
   Devanagari also needs text shaping, which OpenPDF does not do for conjuncts, so for perfect Marathi PDFs use the
   print page ("Save as PDF" from the browser) or switch the PDF engine to one with shaping (for example OpenHTMLtoPDF with Noto fonts).
2. **WhatsApp Business API** needs Meta approval of the template and the shop number registered on the Cloud API.
   Until then use the share button. See WHATSAPP_SETUP.md.
3. **Single shop, single till location.** No multi-branch stock transfer.
4. **Offline billing.** The app needs the server; static files are cached by a service worker, but bills are not queued offline.
5. **Payment gateway / UPI collection** is not integrated: UPI is recorded as a payment method with a reference.
6. **E-invoicing / GST filing export** is not included; the GST summary report gives the numbers.

## What has and has not been executed

Executed during development:
* All SQL on a real PostgreSQL 16: schema, constraints, demo data, integrity-check (including a negative control),
  dashboard and all 15 report queries.
* 40 plain-Java unit tests with a tiny JUnit stand-in: pricing, tax, landed cost, costing, proration, formatting,
  phone numbers, WhatsApp signature, English/Marathi bundle parity.
* Code128 encoder tests, `node --check` on every JavaScript file.
* `scripts/i18n-keys.py` (every message key exists in both languages), `scripts/template-lint.py`,
  `scripts/template-vars.py`, and a javac syntax parse of all Java files.

**Not executed** (the build sandbox could not reach Maven Central):
* `mvn compile / test / package` - Spring and JPA code has not been compiled against the real libraries.
* Spring Boot start-up, JPA mapping validation, Thymeleaf rendering of the screens. `WebSmokeTest` does exactly this
  and should be the first thing run: `mvn test` with `TEST_DB_URL` pointing at an empty local PostgreSQL database.
* The backup/restore scripts (syntax-checked only).
* A live WhatsApp send.

Expect to fix a few small compile or template errors on the first Maven run. They will be quick: the smoke test
names the page and the expression.
