package jp.vinsight.api.audit;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Append-only record of who did what (the table refuses UPDATE and DELETE). No passwords or tokens are ever written here. */
@Component
public class AuditLog {
  private final JdbcClient jdbc;

  public AuditLog(JdbcClient jdbc) { this.jdbc = jdbc; }

  public void record(String actor, String action, String target, String detail, String ip) {
    jdbc.sql("INSERT INTO audit_log (actor, action, target, detail, ip) VALUES (:actor, :action, :target, :detail, :ip)")
        .param("actor", actor).param("action", action).param("target", target)
        .param("detail", detail == null ? null : detail.length() > 1000 ? detail.substring(0, 1000) : detail).param("ip", ip).update();
  }

  /**
   * For failures (wrong password, wrong code): saved in its own transaction, so the record survives when the request's
   * transaction is rolled back by the error that follows.
   */
  @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
  public void recordFailure(String actor, String action, String target, String detail, String ip) {
    record(actor, action, target, detail, ip);
  }

  public List<Map<String, Object>> latest(int limit, long beforeId) {
    return jdbc.sql("SELECT id, at, actor, action, target, detail, ip FROM audit_log WHERE id < :before ORDER BY id DESC LIMIT :limit")
        .param("before", beforeId <= 0 ? Long.MAX_VALUE : beforeId).param("limit", Math.min(500, Math.max(1, limit))).query().listOfRows();
  }
}
