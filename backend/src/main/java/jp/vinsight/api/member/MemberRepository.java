package jp.vinsight.api.member;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** All member SQL. Every value goes in as a bound parameter; no statement is built from request text. */
@Repository
public class MemberRepository {
  private final JdbcClient jdbc;

  public MemberRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

  static final String COLUMNS = """
      id, member_no, legacy_monitor_pk, email, password_hash, password_set_at, migration_status, status, name, postcode,
      prefecture, city, street, tel, birth, gender, married, children, occupation, point_balance, registered_at,
      last_login_at, prev_login_at, info_changed_at, remark""";

  static final RowMapper<Member> MAPPER = (ResultSet rs, int n) -> new Member(
      rs.getLong("id"), rs.getInt("member_no"), (Integer) rs.getObject("legacy_monitor_pk", Integer.class), rs.getString("email"),
      rs.getString("password_hash"), instant(rs, "password_set_at"), rs.getString("migration_status"), rs.getString("status"),
      rs.getString("name"), rs.getString("postcode"), rs.getString("prefecture"), rs.getString("city"), rs.getString("street"),
      rs.getString("tel"), rs.getObject("birth", java.time.LocalDate.class), rs.getString("gender"),
      rs.getObject("married") == null ? null : rs.getBoolean("married"), (Integer) rs.getObject("children", Integer.class),
      rs.getString("occupation"), rs.getInt("point_balance"), instant(rs, "registered_at"), instant(rs, "last_login_at"),
      instant(rs, "prev_login_at"), instant(rs, "info_changed_at"), rs.getString("remark"));

  static Instant instant(ResultSet rs, String col) throws SQLException {
    Timestamp t = rs.getTimestamp(col);
    return t == null ? null : t.toInstant();
  }

  public Optional<Member> findById(long id) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM members WHERE id = :id").param("id", id).query(MAPPER).optional();
  }

  /** Locks the row until the transaction ends, so two redemptions cannot both spend the same points. */
  public Optional<Member> findByIdForUpdate(long id) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM members WHERE id = :id FOR UPDATE").param("id", id).query(MAPPER).optional();
  }

  public Optional<Member> findByMemberNo(int no) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM members WHERE member_no = :no").param("no", no).query(MAPPER).optional();
  }

  /** The account that signs in with this address: not withdrawn. */
  public Optional<Member> findLoginAccount(String email) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM members WHERE email_norm = LOWER(:email) AND status <> 'WITHDRAWN' ORDER BY id LIMIT 1")
        .param("email", email).query(MAPPER).optional();
  }

  public boolean emailTaken(String email, Long exceptId) {
    return jdbc.sql("SELECT COUNT(*) FROM members WHERE email_norm = LOWER(:email) AND status <> 'WITHDRAWN' AND id <> :except")
        .param("email", email).param("except", exceptId == null ? -1L : exceptId).query(Long.class).single() > 0;
  }

  public void setPassword(long id, String hash, Instant at) {
    jdbc.sql("UPDATE members SET password_hash = :h, password_set_at = :at, migration_status = 'ACTIVE' WHERE id = :id")
        .param("h", hash).param("at", Timestamp.from(at)).param("id", id).update();
  }

  public void recordLogin(long id, Instant at) {
    jdbc.sql("UPDATE members SET prev_login_at = last_login_at, last_login_at = :at WHERE id = :id")
        .param("at", Timestamp.from(at)).param("id", id).update();
  }

  public void setBalance(long id, int balance) {
    jdbc.sql("UPDATE members SET point_balance = :b WHERE id = :id").param("b", balance).param("id", id).update();
  }

  public int nextMemberNo() {
    return jdbc.sql("SELECT NEXT VALUE FOR member_no_seq").query(Integer.class).single();
  }

  public record Page(List<Member> items, long total) {}

  /** Admin search. Free text matches the member number exactly, or the start of the e-mail address or name. */
  public Page search(String q, String status, int page, int size) {
    String where = " WHERE 1=1";
    var params = new java.util.HashMap<String, Object>();
    if (q != null && !q.isBlank()) {
      String t = q.strip();
      where += " AND (email_norm LIKE :prefix OR name LIKE :prefix" + (t.matches("\\d{1,9}") ? " OR member_no = :no" : "") + ")";
      params.put("prefix", escapeLike(t.toLowerCase()) + "%");
      if (t.matches("\\d{1,9}")) params.put("no", Integer.parseInt(t));
    }
    if (status != null && !status.isBlank()) { where += " AND status = :status"; params.put("status", status); }
    long total = jdbc.sql("SELECT COUNT(*) FROM members" + where).params(params).query(Long.class).single();
    params.put("limit", size);
    params.put("offset", Math.max(0, page - 1) * size);
    List<Member> items = jdbc.sql("SELECT " + COLUMNS + " FROM members" + where + " ORDER BY member_no LIMIT :limit OFFSET :offset")
        .params(params).query(MAPPER).list();
    return new Page(items, total);
  }

  public List<Member> all() {
    return jdbc.sql("SELECT " + COLUMNS + " FROM members ORDER BY member_no").query(MAPPER).list();
  }

  static String escapeLike(String s) { return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_"); }
}
