-- Vinsight renewal: initial schema.
-- Runs as vinsight_migrator. The app connects as vinsight_app, which has only SELECT/INSERT/UPDATE/DELETE.
-- No password from the old site is ever stored here: members carried over must set a new one by e-mail.

CREATE TABLE members (
  id                BIGINT       NOT NULL AUTO_INCREMENT,
  member_no         INT          NOT NULL,              -- "monitor number" shown to members; the old pk for carried-over members
  legacy_monitor_pk INT          NULL,                  -- link to monitor.pk on the old site, kept for point history and claims
  email             VARCHAR(254) NOT NULL,
  email_norm        VARCHAR(254) AS (LOWER(email)) PERSISTENT,
  password_hash     VARCHAR(255) NULL,                  -- Argon2id; NULL until the member sets a password
  password_set_at   DATETIME(3)  NULL,
  migration_status  VARCHAR(20)  NOT NULL,              -- LEGACY_PENDING (carried over, no password yet) / ACTIVE
  status            VARCHAR(20)  NOT NULL,              -- PROVISIONAL / ACTIVE / WITHDRAWN / SUSPENDED
  name              VARCHAR(100) NULL,
  postcode          VARCHAR(8)   NULL,
  prefecture        VARCHAR(10)  NULL,
  city              VARCHAR(200) NULL,
  street            VARCHAR(200) NULL,
  tel               VARCHAR(20)  NULL,
  birth             DATE         NULL,
  gender            VARCHAR(10)  NULL,
  married           TINYINT(1)   NULL,
  children          INT          NULL,
  occupation        VARCHAR(100) NULL,
  point_balance     INT          NOT NULL DEFAULT 0,     -- cache of SUM(point_ledger.delta); changed only with a ledger row in the same transaction
  registered_at     DATETIME(3)  NOT NULL,
  last_login_at     DATETIME(3)  NULL,
  prev_login_at     DATETIME(3)  NULL,
  info_changed_at   DATETIME(3)  NULL,
  remark            TEXT         NULL,
  created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uq_members_member_no (member_no),
  UNIQUE KEY uq_members_legacy_pk (legacy_monitor_pk),
  KEY ix_members_email (email_norm),
  CONSTRAINT ck_members_balance CHECK (point_balance >= 0),
  CONSTRAINT ck_members_migration CHECK (migration_status IN ('LEGACY_PENDING', 'ACTIVE')),
  CONSTRAINT ck_members_status CHECK (status IN ('PROVISIONAL', 'ACTIVE', 'WITHDRAWN', 'SUSPENDED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- New members get numbers above every old-site number.
CREATE SEQUENCE member_no_seq START WITH 10001 INCREMENT BY 1 NOCACHE;

-- Every point movement, append-only. A balance is the sum of its rows.
CREATE TABLE point_ledger (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  member_id   BIGINT       NOT NULL,
  delta       INT          NOT NULL,
  reason      VARCHAR(30)  NOT NULL,   -- LEGACY_BALANCE / SURVEY / REDEMPTION / REDEMPTION_CANCEL / ADJUSTMENT / EXPIRY
  ref         VARCHAR(100) NULL,       -- e.g. "redemption:42", "import:7"
  expires_at  DATETIME(3)  NULL,
  note        VARCHAR(500) NULL,
  created_by  VARCHAR(100) NOT NULL,   -- "member:10001", "admin:3", "system"
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY ix_ledger_member (member_id, created_at),
  CONSTRAINT fk_ledger_member FOREIGN KEY (member_id) REFERENCES members (id),
  CONSTRAINT ck_ledger_reason CHECK (reason IN ('LEGACY_BALANCE', 'SURVEY', 'REDEMPTION', 'REDEMPTION_CANCEL', 'ADJUSTMENT', 'EXPIRY'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE redemptions (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  member_id    BIGINT       NOT NULL,
  request_id   VARCHAR(64)  NOT NULL,   -- sent by the form; a resend returns the first application instead of spending twice
  points_used  INT          NOT NULL,
  ship_name    VARCHAR(100) NOT NULL,
  ship_postcode VARCHAR(8)  NOT NULL,
  ship_prefecture VARCHAR(10) NOT NULL,
  ship_city    VARCHAR(200) NOT NULL,
  ship_street  VARCHAR(200) NULL,
  ship_tel     VARCHAR(20)  NOT NULL,
  email        VARCHAR(254) NOT NULL,   -- always the account's address at the time of the application
  status       VARCHAR(20)  NOT NULL DEFAULT 'PENDING',  -- PENDING / SHIPPED / CANCELLED
  applied_at   DATETIME(3)  NOT NULL,
  shipped_at   DATETIME(3)  NULL,
  remark       TEXT         NULL,
  legacy_trade_pk INT       NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_redemptions_request (member_id, request_id),
  UNIQUE KEY uq_redemptions_legacy (legacy_trade_pk),
  KEY ix_redemptions_status (status, applied_at),
  CONSTRAINT fk_redemptions_member FOREIGN KEY (member_id) REFERENCES members (id),
  CONSTRAINT ck_redemptions_points CHECK (points_used > 0),
  CONSTRAINT ck_redemptions_status CHECK (status IN ('PENDING', 'SHIPPED', 'CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE redemption_items (
  id            BIGINT      NOT NULL AUTO_INCREMENT,
  redemption_id BIGINT      NOT NULL,
  item_code     VARCHAR(20) NOT NULL,   -- QUO / AMAZON / TAMARUN
  units         INT         NOT NULL,
  card_no       VARCHAR(20) NULL,       -- Tamarun card number, only for TAMARUN
  PRIMARY KEY (id),
  KEY ix_items_redemption (redemption_id),
  CONSTRAINT fk_items_redemption FOREIGN KEY (redemption_id) REFERENCES redemptions (id),
  CONSTRAINT ck_items_units CHECK (units > 0),
  CONSTRAINT ck_items_code CHECK (item_code IN ('QUO', 'AMAZON', 'TAMARUN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Reset links. Only a SHA-256 of the token is stored, so a copy of this table cannot be used to reset anyone's password.
CREATE TABLE password_reset_tokens (
  token_hash  CHAR(64)    NOT NULL,
  member_id   BIGINT      NOT NULL,
  created_at  DATETIME(3) NOT NULL,
  expires_at  DATETIME(3) NOT NULL,
  used_at     DATETIME(3) NULL,
  PRIMARY KEY (token_hash),
  KEY ix_reset_member (member_id, created_at),
  CONSTRAINT fk_reset_member FOREIGN KEY (member_id) REFERENCES members (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE admin_users (
  id               BIGINT       NOT NULL AUTO_INCREMENT,
  login_id         VARCHAR(50)  NOT NULL,
  password_hash    VARCHAR(255) NOT NULL,
  password_set_at  DATETIME(3)  NOT NULL,
  role             VARCHAR(20)  NOT NULL,   -- ADMIN (everything) / OPERATOR (no settings, no admin accounts)
  totp_secret_enc  VARCHAR(255) NULL,       -- AES-GCM encrypted with the app key; NULL until two-step login is set up
  totp_pending_enc VARCHAR(255) NULL,
  totp_last_step   BIGINT       NOT NULL DEFAULT -1,
  disabled         TINYINT(1)   NOT NULL DEFAULT 0,
  created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uq_admin_login (login_id),
  CONSTRAINT ck_admin_role CHECK (role IN ('ADMIN', 'OPERATOR'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Who did what. Append-only: the triggers below refuse UPDATE and DELETE for every account.
CREATE TABLE audit_log (
  id      BIGINT       NOT NULL AUTO_INCREMENT,
  at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  actor   VARCHAR(100) NOT NULL,
  action  VARCHAR(100) NOT NULL,
  target  VARCHAR(100) NULL,
  detail  VARCHAR(1000) NULL,
  ip      VARCHAR(45)  NULL,
  PRIMARY KEY (id),
  KEY ix_audit_at (at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Outgoing e-mail is written here in the same transaction as the change it reports, then sent by a worker.
CREATE TABLE mail_outbox (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  to_addr     VARCHAR(254) NOT NULL,
  subject     VARCHAR(200) NOT NULL,
  body        TEXT         NOT NULL,
  kind        VARCHAR(50)  NOT NULL,
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  sent_at     DATETIME(3)  NULL,
  attempts    INT          NOT NULL DEFAULT 0,
  last_error  VARCHAR(500) NULL,
  PRIMARY KEY (id),
  KEY ix_outbox_pending (sent_at, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE import_batches (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  kind        VARCHAR(30)  NOT NULL,   -- MEMBERS_CSV
  file_name   VARCHAR(200) NOT NULL,
  file_sha256 CHAR(64)     NOT NULL,
  dry_run     TINYINT(1)   NOT NULL,
  rows_total  INT          NOT NULL,
  inserted    INT          NOT NULL,
  updated     INT          NOT NULL,
  unchanged   INT          NOT NULL,
  rejected    INT          NOT NULL,
  created_by  VARCHAR(100) NOT NULL,
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE import_rejections (
  id        BIGINT       NOT NULL AUTO_INCREMENT,
  batch_id  BIGINT       NOT NULL,
  row_no    INT          NOT NULL,
  field     VARCHAR(50)  NULL,
  message   VARCHAR(300) NOT NULL,
  PRIMARY KEY (id),
  KEY ix_rejections_batch (batch_id),
  CONSTRAINT fk_rejections_batch FOREIGN KEY (batch_id) REFERENCES import_batches (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

DELIMITER //
CREATE TRIGGER audit_log_no_update BEFORE UPDATE ON audit_log FOR EACH ROW
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_log is append-only'//
CREATE TRIGGER audit_log_no_delete BEFORE DELETE ON audit_log FOR EACH ROW
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_log is append-only'//
CREATE TRIGGER point_ledger_no_update BEFORE UPDATE ON point_ledger FOR EACH ROW
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'point_ledger is append-only'//
CREATE TRIGGER point_ledger_no_delete BEFORE DELETE ON point_ledger FOR EACH ROW
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'point_ledger is append-only'//
DELIMITER ;

-- Spring Session (server-side sessions; the browser only holds a random session id)
CREATE TABLE SPRING_SESSION (
  PRIMARY_ID            CHAR(36)     NOT NULL,
  SESSION_ID            CHAR(36)     NOT NULL,
  CREATION_TIME         BIGINT       NOT NULL,
  LAST_ACCESS_TIME      BIGINT       NOT NULL,
  MAX_INACTIVE_INTERVAL INT          NOT NULL,
  EXPIRY_TIME           BIGINT       NOT NULL,
  PRINCIPAL_NAME        VARCHAR(100),
  CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC;
CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
  SESSION_PRIMARY_ID CHAR(36)     NOT NULL,
  ATTRIBUTE_NAME     VARCHAR(200) NOT NULL,
  ATTRIBUTE_BYTES    BLOB         NOT NULL,
  CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
  CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID) REFERENCES SPRING_SESSION (PRIMARY_ID) ON DELETE CASCADE
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC;
