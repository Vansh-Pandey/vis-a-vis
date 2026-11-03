package jp.vinsight.api.admin;

import jakarta.servlet.http.HttpServletRequest;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import jp.vinsight.api.audit.AuditLog;
import jp.vinsight.api.auth.CurrentUser;
import jp.vinsight.api.common.ApiException;
import jp.vinsight.api.common.Requests;
import jp.vinsight.api.mail.MailOutbox;
import jp.vinsight.api.member.MemberRepository;
import jp.vinsight.api.points.PointLedger;
import jp.vinsight.api.points.RedemptionService;

/** Gift applications: list, mark as sent, or cancel (cancelling returns the points through the ledger). */
@RestController
@RequestMapping("/api/admin/redemptions")
public class AdminRedemptionsController {
  private final JdbcClient jdbc;
  private final RedemptionService redemptions;
  private final MemberRepository members;
  private final PointLedger ledger;
  private final MailOutbox mail;
  private final AuditLog audit;
  private final Clock clock;

  public AdminRedemptionsController(JdbcClient jdbc, RedemptionService redemptions, MemberRepository members, PointLedger ledger,
      MailOutbox mail, AuditLog audit, Clock clock) {
    this.jdbc = jdbc; this.redemptions = redemptions; this.members = members; this.ledger = ledger; this.mail = mail;
    this.audit = audit; this.clock = clock;
  }

  @GetMapping
  public Map<String, Object> list(@RequestParam(defaultValue = "PENDING") String status, @RequestParam(defaultValue = "1") int page) {
    if (!List.of("PENDING", "SHIPPED", "CANCELLED").contains(status)) throw ApiException.invalid(Map.of("status", "状態の指定が正しくありません。"));
    var rows = jdbc.sql("""
        SELECT r.id, m.member_no, r.points_used, r.ship_name, r.ship_postcode, r.ship_prefecture, r.ship_city, r.ship_street, r.ship_tel,
               r.email, r.status, r.applied_at, r.shipped_at
        FROM redemptions r JOIN members m ON m.id = r.member_id WHERE r.status = :s ORDER BY r.id DESC LIMIT 100 OFFSET :o""")
        .param("s", status).param("o", (Math.max(1, page) - 1) * 100).query().listOfRows();
    rows.forEach(r -> r.put("items", redemptions.items(((Number) r.get("id")).longValue())));
    long total = jdbc.sql("SELECT COUNT(*) FROM redemptions WHERE status = :s").param("s", status).query(Long.class).single();
    return Map.of("items", rows, "total", total);
  }

  public record StatusChange(String status, String remark) {}

  @PostMapping("/{id}/status")
  @Transactional
  public Map<String, Object> setStatus(@PathVariable long id, @RequestBody StatusChange body, @AuthenticationPrincipal CurrentUser u, HttpServletRequest req) {
    record Row(long memberId, String status, int points, String email, String name) {}
    Row r = jdbc.sql("SELECT member_id, status, points_used, email, ship_name FROM redemptions WHERE id = :id FOR UPDATE").param("id", id)
        .query((rs, n) -> new Row(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getString(5))).optional()
        .orElseThrow(() -> ApiException.notFound("申請が見つかりません。"));
    String to = body.status();
    if (!"PENDING".equals(r.status())) throw new ApiException(HttpStatus.CONFLICT, "この申請はすでに処理済みです。");
    if ("SHIPPED".equals(to)) {
      jdbc.sql("UPDATE redemptions SET status = 'SHIPPED', shipped_at = :at, remark = :rm WHERE id = :id")
          .param("at", Timestamp.from(clock.instant())).param("rm", Requests.trim(body.remark())).param("id", id).update();
      mail.enqueue(r.email(), "【Vinsight】ポイント交換の商品を発送しました", r.name() + " 様\n\nお申し込みいただいたポイント交換（申請番号：" + id + "）の商品を発送しました。\n", "redemption-shipped");
    } else if ("CANCELLED".equals(to)) {
      String reason = Requests.trim(body.remark());
      if (reason.isEmpty()) throw ApiException.invalid(Map.of("remark", "取り消しの理由を入力してください。"));
      var m = members.findByIdForUpdate(r.memberId()).orElseThrow();
      ledger.post(m.id(), m.pointBalance(), r.points(), "REDEMPTION_CANCEL", "redemption:" + id, null, reason, u.actor());
      jdbc.sql("UPDATE redemptions SET status = 'CANCELLED', remark = :rm WHERE id = :id").param("rm", reason).param("id", id).update();
    } else {
      throw ApiException.invalid(Map.of("status", "状態の指定が正しくありません。"));
    }
    audit.record(u.actor(), "交換申請の状態変更", "申請#" + id, r.status() + " → " + to, Requests.clientIp(req));
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("ok", true);
    return out;
  }
}
