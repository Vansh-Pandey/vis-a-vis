package jp.vinsight.api.admin;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
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
import jp.vinsight.api.member.Member;
import jp.vinsight.api.member.MemberRepository;
import jp.vinsight.api.member.MemberView;
import jp.vinsight.api.points.PointLedger;

@RestController
@RequestMapping("/api/admin/members")
public class AdminMembersController {
  private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
  private final MemberRepository members;
  private final PointLedger ledger;
  private final AuditLog audit;
  private final Clock clock;

  public AdminMembersController(MemberRepository members, PointLedger ledger, AuditLog audit, Clock clock) {
    this.members = members; this.ledger = ledger; this.audit = audit; this.clock = clock;
  }

  static Map<String, Object> adminView(Member m) {
    Map<String, Object> v = MemberView.of(m);
    v.put("legacyMonitorPk", m.legacyMonitorPk());
    v.put("migrationStatus", m.migrationStatus().equals("LEGACY_PENDING") ? "旧サイトから移行・パスワード未設定" : "移行済み");
    v.put("remark", m.remark() == null ? "" : m.remark());
    return v;
  }

  @GetMapping
  public Map<String, Object> search(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "") String status,
      @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "50") int size) {
    if (!status.isEmpty() && !List.of("ACTIVE", "PROVISIONAL", "WITHDRAWN", "SUSPENDED").contains(status)) throw ApiException.invalid(Map.of("status", "状態の指定が正しくありません。"));
    var p = members.search(q, status, Math.max(1, page), Math.min(200, Math.max(1, size)));
    return Map.of("items", p.items().stream().map(AdminMembersController::adminView).toList(), "total", p.total());
  }

  @GetMapping("/{memberNo}")
  public Map<String, Object> detail(@PathVariable int memberNo, @AuthenticationPrincipal CurrentUser u, HttpServletRequest req) {
    Member m = members.findByMemberNo(memberNo).orElseThrow(() -> ApiException.notFound("モニターが見つかりません。"));
    audit.record(u.actor(), "モニター情報の閲覧", String.valueOf(memberNo), null, Requests.clientIp(req));
    return Map.of("monitor", adminView(m), "points", ledger.history(m.id(), 200));
  }

  public record Adjustment(Integer points, String reason, String expiresOn) {}

  /** Manual point grant or deduction. Always written to the ledger with the admin's name and reason. */
  @PostMapping("/{memberNo}/points")
  @Transactional
  public Map<String, Object> adjust(@PathVariable int memberNo, @RequestBody Adjustment body, @AuthenticationPrincipal CurrentUser u, HttpServletRequest req) {
    Map<String, String> errors = new LinkedHashMap<>();
    if (body.points() == null || body.points() == 0 || Math.abs(body.points()) > 100_000) errors.put("points", "ポイントは±100,000以内で、0以外を入力してください。");
    String reason = Requests.trim(body.reason());
    if (reason.isEmpty() || reason.length() > 200) errors.put("reason", "理由を200文字以内で入力してください。");
    LocalDate exp = null;
    if (body.expiresOn() != null && !body.expiresOn().isBlank()) {
      try { exp = LocalDate.parse(body.expiresOn()); } catch (RuntimeException e) { errors.put("expiresOn", "有効期限はYYYY-MM-DDで入力してください。"); }
    }
    if (!errors.isEmpty()) throw ApiException.invalid(errors);
    Member m = members.findByMemberNo(memberNo).orElseThrow(() -> ApiException.notFound("モニターが見つかりません。"));
    m = members.findByIdForUpdate(m.id()).orElseThrow();
    if (m.pointBalance() + body.points() < 0) throw ApiException.invalid(Map.of("points", "保有ポイント（" + m.pointBalance() + "）より多くは減らせません。"));
    int balance = ledger.post(m.id(), m.pointBalance(), body.points(), "ADJUSTMENT", null,
        exp == null ? null : exp.plusDays(1).atStartOfDay(JST).toInstant(), reason, u.actor());
    audit.record(u.actor(), "ポイント手動調整", String.valueOf(memberNo), (body.points() > 0 ? "+" : "") + body.points() + "pt 理由：" + reason, Requests.clientIp(req));
    return Map.of("ok", true, "balance", balance);
  }
}
