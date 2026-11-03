package jp.vinsight.api.points;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import jp.vinsight.api.member.MemberRepository;

/**
 * Every point movement is a row in point_ledger (append-only). The member's cached balance is updated in the same
 * transaction, and only through {@link #post}, with the member row locked by the caller.
 */
@Component
public class PointLedger {
  private final JdbcClient jdbc;
  private final MemberRepository members;

  public PointLedger(JdbcClient jdbc, MemberRepository members) { this.jdbc = jdbc; this.members = members; }

  /** Adds a movement and returns the new balance. Caller must hold the member row lock (findByIdForUpdate). */
  public int post(long memberId, int currentBalance, int delta, String reason, String ref, Instant expiresAt, String note, String by) {
    int balance = currentBalance + delta;
    if (balance < 0) throw new IllegalStateException("balance would become negative");
    jdbc.sql("""
        INSERT INTO point_ledger (member_id, delta, reason, ref, expires_at, note, created_by)
        VALUES (:m, :d, :r, :ref, :exp, :note, :by)""")
        .param("m", memberId).param("d", delta).param("r", reason).param("ref", ref)
        .param("exp", expiresAt == null ? null : Timestamp.from(expiresAt)).param("note", note).param("by", by).update();
    members.setBalance(memberId, balance);
    return balance;
  }

  public int sum(long memberId) {
    return jdbc.sql("SELECT COALESCE(SUM(delta), 0) FROM point_ledger WHERE member_id = :m").param("m", memberId).query(Integer.class).single();
  }

  public List<Map<String, Object>> history(long memberId, int limit) {
    return jdbc.sql("SELECT id, delta, reason, ref, expires_at, note, created_at FROM point_ledger WHERE member_id = :m ORDER BY id DESC LIMIT :l")
        .param("m", memberId).param("l", limit).query().listOfRows();
  }

  public record Lot(int points, Instant expiresAt) {}

  /**
   * Points still held, grouped by expiry date. Spending uses the points that expire first, so the oldest grants are
   * consumed before newer ones.
   */
  public List<Lot> lots(long memberId, Instant now) {
    record Row(int delta, Instant exp) {}
    List<Row> rows = jdbc.sql("SELECT delta, expires_at FROM point_ledger WHERE member_id = :m ORDER BY id")
        .param("m", memberId).query((rs, n) -> new Row(rs.getInt(1), rs.getTimestamp(2) == null ? null : rs.getTimestamp(2).toInstant())).list();
    int spent = rows.stream().filter(r -> r.delta() < 0).mapToInt(r -> -r.delta()).sum();
    List<Row> grants = new ArrayList<>(rows.stream().filter(r -> r.delta() > 0).toList());
    grants.sort((a, b) -> a.exp() == null ? (b.exp() == null ? 0 : 1) : b.exp() == null ? -1 : a.exp().compareTo(b.exp()));
    List<Lot> out = new ArrayList<>();
    for (Row g : grants) {
      int use = Math.min(spent, g.delta());
      spent -= use;
      int remain = g.delta() - use;
      if (remain > 0 && (g.exp() == null || g.exp().isAfter(now))) out.add(new Lot(remain, g.exp()));
    }
    return out;
  }
}
