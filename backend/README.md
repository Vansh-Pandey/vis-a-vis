# vinsight-api

Java back end for the renewed Vinsight monitor site (VIS-À-VIS × Switch), following the meeting of 2 November 2026:
the site is rebuilt in Java, tested first on a separate staging site with dummy data imported by CSV, and the old
member records are moved over without losing member numbers or points.

Spring Boot 4.1 · Java 21 · MariaDB/MySQL · Flyway · Spring Session (JDBC)

The API paths and JSON match the existing Next.js screens (Vansh's repository) for the reopening scope, so those
screens can call this back end directly.

## Scope (reopening, agreed 26 Oct / 2 Nov)

| Area | Endpoints |
|---|---|
| Member sign-in | `POST /api/auth/login`, `POST /api/auth/logout`, `GET /api/auth/me` |
| Password reset (and first sign-in of carried-over members) | `POST /api/password-reset/request`, `POST /api/password-reset/confirm` |
| My Page: balance, expiry, pending applications | `GET /api/mypage`, `GET /api/mypage/points/history` |
| Point redemption | `GET /api/mypage/trade`, `POST /api/mypage/trade` |
| Admin sign-in with two-step login | `POST /api/admin/login`, `POST /api/admin/logout`, `GET /api/admin/me`, `POST /api/admin/password` |
| Members | `GET /api/admin/members`, `GET /api/admin/members/{no}`, `POST /api/admin/members/{no}/points` |
| Gift applications | `GET /api/admin/redemptions`, `POST /api/admin/redemptions/{id}/status` |
| CSV import / export | `POST /api/admin/import/members?dryRun=true\|false`, `GET /api/admin/import/batches`, `GET /api/admin/export/members.csv` |
| Audit log | `GET /api/admin/audit-log` |
| Other | `GET /api/captcha`, `GET /api/health`, `GET /api/public/news` |

Not yet: surveys, news, registration of new members, profile editing, inquiries, withdrawal.

## Security design

| Risk | What this code does |
|---|---|
| SQL injection | Every statement uses bound parameters (`JdbcClient`); LIKE wildcards are escaped |
| Stolen password database | Argon2id (19 MiB, 2 iterations). Passwords from the old system are **never imported**: carried-over members must set a new one through an e-mailed link |
| Stolen admin password | Admin two-step login (RFC 6238 codes), required outside local development; codes cannot be reused |
| App account misused | Two database accounts: `vinsight_migrator` changes the schema at start-up; the running app (`vinsight_app`) can only read and write rows |
| Records quietly edited | `audit_log` and `point_ledger` refuse UPDATE/DELETE at database level (triggers); balances cannot go below zero (CHECK) |
| Points spent twice | Every movement is a ledger row; redemption locks the member row and is unique per (member, request id), so double clicks, resends and parallel requests spend once |
| Gifts redirected | Gifts always go to the account's registered e-mail |

Also:
- Sessions are server-side; the cookie is `HttpOnly`, `SameSite=Strict`, `Secure` (except local http). Session id changes at sign-in. Members time out after 30 minutes idle, admins after 15. Changing a password (or turning on two-step login) ends all older sessions.
- Requests that change data must come from the site's own Origin and be JSON (cross-site request protection).
- Login throttling per account (waits double from 2 s to 5 min after 3 failures) and per address (10 accounts in 15 minutes). The address comes from the socket, not from a header an attacker can set.
- Reset links: 256-bit random, 30 minutes, single use, stored only as SHA-256, at most one per minute per member. The reset request gives the same answer whether or not the address is registered.
- Admin two-step secrets are encrypted (AES-256-GCM) with a key that is not in the database.
- Sensitive admin actions (password change) ask for the password and code again.
- Optional admin IP allow-list; staging site password and `noindex` header.
- Strict security headers (CSP, frame denial, HSTS over https, no-referrer, no caching); deny-by-default access rules; errors never include stack traces or SQL.
- CSV exports neutralise spreadsheet formulas; the import refuses any file with a password column.
- Audit log: sign-ins and failures, two-step setup, member views, point adjustments, redemptions and status changes, CSV imports and personal-data exports.
- E-mail goes through an outbox table in the same transaction as the change it reports.

## Running locally

Requires JDK 21, Maven, MariaDB (`brew install openjdk@21 maven mariadb && brew services start mariadb`).

```bash
./scripts/dev-db-setup.sh            # databases + two accounts, random passwords in .env.local
VINSIGHT_BOOTSTRAP_ADMIN_ID=devadmin VINSIGHT_BOOTSTRAP_ADMIN_PASSWORD='choose-a-strong-one' ./scripts/run-dev.sh
```

The API listens on http://localhost:8080. In DEV mode reset links are returned in the API answer (there is no mailbox)
and two-step login is optional.

### Checks

```bash
mvn test                                                           # unit tests (TOTP vectors, password rules, crypto, CSV)
python3 scripts/make-dummy-csv.py /tmp/dummy.csv 200               # dummy members incl. blanks, duplicates, bad rows
ADMIN_ID=devadmin ADMIN_PASSWORD=... CSV=/tmp/dummy.csv node scripts/security-check.mjs   # 64 end-to-end checks, fresh DB
ADMIN_ID=... ADMIN_PASSWORD=... BASE_URL=http://localhost:8081 node scripts/admin-2fa-check.mjs  # 16 checks, STAGING mode, fresh DB
```

## Configuration (environment variables)

| Variable | Meaning |
|---|---|
| `VINSIGHT_ENV` | `DEV`, `STAGING` or `PRODUCTION` (default PRODUCTION) |
| `VINSIGHT_DB_URL` | e.g. `jdbc:mariadb://db-host:3306/vinsight` |
| `VINSIGHT_DB_APP_PASSWORD`, `VINSIGHT_DB_MIGRATOR_PASSWORD` | the two database accounts |
| `VINSIGHT_SECRET_KEY` | 32 random bytes, base64 (`openssl rand -base64 32`). Encrypts two-step secrets: keep it safe and the same across restarts |
| `VINSIGHT_SITE_ORIGIN` | the site's address, e.g. `https://new.vinsight.jp` |
| `VINSIGHT_SMTP_HOST/PORT/USER/PASSWORD`, `VINSIGHT_MAIL_FROM` | outgoing mail (STARTTLS required) |
| `VINSIGHT_ADMIN_ALLOWED_IPS` | optional, comma separated |
| `VINSIGHT_STAGING_USER/PASSWORD` | optional site-wide password for staging |
| `VINSIGHT_FORWARD_HEADERS` | `native` only behind a trusted reverse proxy |
| `VINSIGHT_BOOTSTRAP_ADMIN_ID/PASSWORD` | first start only: creates the first admin, then remove |

## Moving the old members over

1. Use the agreed clean backup of the old database (see the internal report).
2. `python3 -I scripts/legacy-to-csv.py vinsight_db.sql.gz OUT.csv` converts it. It carries over only member number,
   e-mail, date of birth, prefecture and municipality, points, registration date and status (data-minimisation list of
   26 Oct). It never carries passwords, street addresses, phone numbers, gender or notes. It prints how many records
   fall into each group (empty, invalid, duplicate, active, inactive).
3. Import with `dryRun=true` first, check the report, then `dryRun=false`. Repeating the import is safe: unchanged rows
   stay unchanged, and changed points are booked as ledger movements.
4. Carried-over members start as `LEGACY_PENDING`: whatever they type at sign-in, they are sent a reset link and get
   in only after choosing a new password.

Keep converted files outside this repository: they contain personal data.

## Open decisions

- **Hosting.** Java cannot run on the current Xserver shared plan. Staging and production need a VPS or cloud server
  (e.g. Xserver VPS) with MariaDB, behind nginx with TLS. The subdomain plan (`new.vinsight.jp`) still works.
- Which old records to keep (all usable ones, or only active ones / ones holding points) and whether old points expire.
- Real CAPTCHA service in place of the image CAPTCHA, before reopening.
- Login throttling and CAPTCHA replay protection are in memory: fine for one server, move to the database for two.
