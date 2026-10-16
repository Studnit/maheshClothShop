# Deployment (no Docker)

You need: Java 21, Maven (to build), PostgreSQL 16 or newer, and an HTTPS reverse proxy (nginx, Caddy or Apache) in
front for internet use. For a shop-only network (LAN) you can skip the proxy and use http://server:8080 with
`COOKIE_SECURE=false`.

## 1. Database

```bash
sudo -u postgres psql -c "CREATE USER mahesh WITH PASSWORD 'choose-a-strong-password';"
sudo -u postgres psql -c "CREATE DATABASE mahesh_shop OWNER mahesh;"
```

The database must be **empty**. Flyway creates every table on the first start.

## 2. Build and run

```bash
mvn -DskipTests package
export SPRING_PROFILES_ACTIVE=prod
export DB_URL=jdbc:postgresql://localhost:5432/mahesh_shop
export DB_USERNAME=mahesh
export DB_PASSWORD='choose-a-strong-password'
export APP_SETUP_TOKEN='any-random-text'          # asked once on the first-run setup page
export COOKIE_SECURE=true                          # false only for plain http on a LAN
java -jar target/mahesh-clothes-shop-1.0.0.jar
```

Open `/setup`, enter the setup token, create the owner account, then fill **Settings** (shop name, address, phone,
GSTIN if registered, logo) and add users. Demo data is never loaded unless you add the `demo` profile
(`SPRING_PROFILES_ACTIVE=dev,demo`, throw-away database only).

### Removing demo data you already loaded

Demo rows were created by the optional demo migration. To get a clean shop, drop and recreate the database
(it must hold no real data), then start the app without the `demo` profile:
```bash
sudo -u postgres psql -c "DROP DATABASE mahesh_clothes;"
sudo -u postgres psql -c "CREATE DATABASE mahesh_clothes OWNER <your_db_user>;"
```
The `/setup` page then creates your owner account on the empty shop.

### Language

The top bar has three choices: **EN**, **मराठी** and **EN+म** (every label shown as `मराठी / English`).
The shop default is in Settings -> System -> Default language.

### Keep it running (Linux systemd)

`/etc/systemd/system/mahesh-shop.service`
```ini
[Unit]
Description=Mahesh Clothes Shop
After=network.target postgresql.service

[Service]
User=shop
WorkingDirectory=/opt/mahesh-shop
EnvironmentFile=/etc/mahesh-shop.env
ExecStart=/usr/bin/java -jar /opt/mahesh-shop/mahesh-clothes-shop.jar
Restart=on-failure

[Install]
WantedBy=multi-user.target
```
Put the `export` values above as `NAME=value` lines (no `export`) in `/etc/mahesh-shop.env`, `chmod 600` it, then
`systemctl enable --now mahesh-shop`. On Windows, run the same `java -jar` command from a service wrapper such as NSSM.

### HTTPS

Caddy example (automatic certificates):
```
billing.yourshop.in {
    reverse_proxy localhost:8080
}
```
The app honours `X-Forwarded-*`, so no other setting is needed.

## 3. Backups (do this on day one)

Needs the PostgreSQL client tools (`pg_dump`). Set `DB_USERNAME`/`DB_PASSWORD` (and `DB_HOST`, `DB_PORT`, `DB_NAME` if not
the defaults) in the environment, then:

```bash
crontab -e
30 1 * * *  /opt/mahesh-shop/deploy/backup.sh         >> /opt/mahesh-shop/backups/backup.log 2>&1
45 1 * * 0  /opt/mahesh-shop/deploy/verify-backup.sh  >> /opt/mahesh-shop/backups/backup.log 2>&1
```

* `backup.sh` writes a compressed dump to `backups/`, keeps 30 days (`RETENTION_DAYS`) and checks the dump is readable.
* `verify-backup.sh` restores the newest dump into a throw-away database and runs `scripts/integrity-check.sql`.
* Copy `backups/` to another machine or cloud storage regularly. Images and receipts are stored in the database, so they are in the dump.
* `restore.sh <dump>` replaces all data after you type YES (stop the app first). Practise once on a spare machine.

## Updating

Take a backup, stop the app, replace the jar, start it. Flyway applies new migrations automatically. Never edit an
already-applied migration; add a new `V<n>__name.sql`.

## If Flyway reports an error at start-up

| Message contains | Cause and fix |
|---|---|
| `Connection refused` / `password authentication failed` / `database "..." does not exist` | PostgreSQL not running or `DB_URL`/`DB_USERNAME`/`DB_PASSWORD` wrong. Create the database first (step 1). |
| `Found non-empty schema(s) "public" but no schema history table` | The database already has tables from something else. Use a new, empty database. |
| `Validate failed: Migration checksum mismatch` | A migration file was edited after it ran (or the database came from another version). In development drop and recreate the database. In production restore a backup and use the matching version. |
| `Detected failed migration` | An earlier start crashed half-way. Drop and recreate the database (development) or restore a backup, then start again. |
| `Unsupported Database: PostgreSQL xx` | Flyway is older than your PostgreSQL. Update Spring Boot, or use PostgreSQL 16/17. |
| `permission denied for schema public` / `must be owner` | The database user must own the database (`OWNER mahesh` in step 1). On PostgreSQL 15+ run `GRANT ALL ON SCHEMA public TO mahesh;` |

If it still fails, send the first `ERROR` line and the lines after it from the log.

## Security checklist

* HTTPS only on the internet; `COOKIE_SECURE=true`.
* Strong owner password; one account per person; disable accounts of people who leave.
* Accounts lock for a few minutes after repeated wrong passwords.
* Money and stock changes are recorded in the audit log and stock ledger; entries cannot be edited.
* WhatsApp token and secrets are environment variables only (`WHATSAPP_ACCESS_TOKEN`, `WHATSAPP_APP_SECRET`, `WHATSAPP_VERIFY_TOKEN`).
* Swagger is off in production; to use it temporarily set `SWAGGER_ENABLED=true` (owner login only).
