package jp.vinsight.api.admin;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AdminRepository {
  private final JdbcClient jdbc;
  public AdminRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

  private static final RowMapper<AdminUser> MAPPER = (rs, n) -> new AdminUser(rs.getLong("id"), rs.getString("login_id"),
      rs.getString("password_hash"), rs.getTimestamp("password_set_at").toInstant(), rs.getString("role"),
      rs.getString("totp_secret_enc"), rs.getString("totp_pending_enc"), rs.getLong("totp_last_step"), rs.getBoolean("disabled"));
  private static final String COLS = "id, login_id, password_hash, password_set_at, role, totp_secret_enc, totp_pending_enc, totp_last_step, disabled";

  public Optional<AdminUser> findById(long id) {
    return jdbc.sql("SELECT " + COLS + " FROM admin_users WHERE id = :id").param("id", id).query(MAPPER).optional();
  }

  public Optional<AdminUser> findByLoginId(String loginId) {
    return jdbc.sql("SELECT " + COLS + " FROM admin_users WHERE login_id = :l").param("l", loginId).query(MAPPER).optional();
  }

  public List<AdminUser> all() { return jdbc.sql("SELECT " + COLS + " FROM admin_users ORDER BY id").query(MAPPER).list(); }

  public long count() { return jdbc.sql("SELECT COUNT(*) FROM admin_users").query(Long.class).single(); }

  public void insert(String loginId, String hash, String role, Instant at) {
    jdbc.sql("INSERT INTO admin_users (login_id, password_hash, password_set_at, role) VALUES (:l, :h, :at, :r)")
        .param("l", loginId).param("h", hash).param("at", Timestamp.from(at)).param("r", role).update();
  }

  public void setPassword(long id, String hash, Instant at) {
    jdbc.sql("UPDATE admin_users SET password_hash = :h, password_set_at = :at WHERE id = :id")
        .param("h", hash).param("at", Timestamp.from(at)).param("id", id).update();
  }

  public void setPendingTotp(long id, String enc) {
    jdbc.sql("UPDATE admin_users SET totp_pending_enc = :e WHERE id = :id").param("e", enc).param("id", id).update();
  }

  public void enableTotp(long id, String enc, long step) {
    jdbc.sql("UPDATE admin_users SET totp_secret_enc = :e, totp_pending_enc = NULL, totp_last_step = :s WHERE id = :id")
        .param("e", enc).param("s", step).param("id", id).update();
  }

  /** Records the code's time step; returns false if another request used the same or a later step first (no replay). */
  public boolean useTotpStep(long id, long step) {
    return jdbc.sql("UPDATE admin_users SET totp_last_step = :s WHERE id = :id AND totp_last_step < :s")
        .param("s", step).param("id", id).update() == 1;
  }
}
