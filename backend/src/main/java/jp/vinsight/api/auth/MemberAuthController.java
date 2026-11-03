package jp.vinsight.api.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jp.vinsight.api.audit.AuditLog;
import jp.vinsight.api.common.ApiException;
import jp.vinsight.api.common.Requests;
import jp.vinsight.api.config.AppProperties;
import jp.vinsight.api.mail.MailOutbox;
import jp.vinsight.api.member.Member;
import jp.vinsight.api.member.MemberRepository;
import jp.vinsight.api.security.LoginThrottle;
import jp.vinsight.api.security.PasswordHasher;
import jp.vinsight.api.security.PasswordPolicy;

/** Member sign-in, sign-out and password reset. Paths and JSON match the existing Next.js screens. */
@RestController
@RequestMapping("/api")
public class MemberAuthController {
  private static final String WRONG = "メールアドレス（またはモニター番号）かパスワードが正しくありません。";
  private final MemberRepository members;
  private final PasswordHasher hasher;
  private final LoginThrottle throttle;
  private final ResetTokenService resets;
  private final Sessions sessions;
  private final MailOutbox mail;
  private final AuditLog audit;
  private final AppProperties props;
  private final Clock clock;

  public MemberAuthController(MemberRepository members, PasswordHasher hasher, LoginThrottle throttle, ResetTokenService resets,
      Sessions sessions, MailOutbox mail, AuditLog audit, AppProperties props, Clock clock) {
    this.members = members; this.hasher = hasher; this.throttle = throttle; this.resets = resets; this.sessions = sessions;
    this.mail = mail; this.audit = audit; this.props = props; this.clock = clock;
  }

  public record LoginRequest(String email, String password) {}

  @PostMapping("/auth/login")
  @Transactional
  public Map<String, Object> login(@RequestBody LoginRequest body, HttpServletRequest req) {
    String ident = Requests.trim(body.email());
    String ip = Requests.clientIp(req);
    if (ident.isEmpty() || body.password() == null || body.password().isEmpty())
      throw new ApiException(HttpStatus.UNAUTHORIZED, WRONG);
    throttle.assertAllowed("member", ident, ip);
    Optional<Member> found = ident.matches("\\d{1,9}") ? members.findByMemberNo(Integer.parseInt(ident)).filter(m -> !"WITHDRAWN".equals(m.status()))
        : members.findLoginAccount(ident);
    Member m = found.orElse(null);

    // Carried over from the old site: there is no password to check (old passwords are never imported). Whatever was typed,
    // the only way in is a new password set through a link sent to the member's own mailbox.
    if (m != null && m.active() && m.needsPasswordReset()) {
      hasher.burnTime(body.password());
      String link = sendResetLink(m, "【Vinsight】サイトリニューアルに伴うパスワード再設定のお願い", req);
      audit.record("member:" + m.id(), "旧サイト会員のログイン（再設定メールを案内）", String.valueOf(m.memberNo()), null, ip);
      Map<String, Object> r = new LinkedHashMap<>();
      r.put("ok", false);
      r.put("next", "password-reset");
      r.put("resetUrl", props.testShortcuts() ? link : null);
      return r;
    }
    if (m == null || !m.active() || !hasher.matches(body.password(), m.passwordHash())) {
      if (m == null) hasher.burnTime(body.password());
      throttle.failed("member", ident, ip);
      throw new ApiException(HttpStatus.UNAUTHORIZED, WRONG);
    }
    throttle.succeeded("member", ident);
    members.recordLogin(m.id(), clock.instant());
    sessions.signIn(req, Sessions.MEMBER, m.id(), m.passwordStamp());
    return Map.of("ok", true);
  }

  @PostMapping("/auth/logout")
  public Map<String, Object> logout(HttpServletRequest req) {
    sessions.signOut(req, Sessions.MEMBER);
    return Map.of("ok", true);
  }

  @GetMapping("/auth/me")
  public Map<String, Object> me(HttpServletRequest req) {
    Map<String, Object> out = new LinkedHashMap<>();
    var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
    CurrentUser cu = auth != null && auth.getPrincipal() instanceof CurrentUser c ? c : null;
    Member m = cu != null && cu.memberId() != null ? members.findById(cu.memberId()).orElse(null) : null;
    out.put("monitor", m == null ? null : Map.of("id", m.memberNo(), "name", m.name() == null ? "" : m.name()));
    out.put("admin", cu != null && cu.adminId() != null);
    out.put("hasAdminCookie", cu != null && cu.adminId() != null);
    return out;
  }

  public record ResetRequest(String email) {}

  /** Same answer whether or not the address is registered, so the form cannot be used to find out who is a member. */
  @PostMapping("/password-reset/request")
  @Transactional
  public Map<String, Object> requestReset(@RequestBody ResetRequest body, HttpServletRequest req) {
    String email = Requests.trim(body.email());
    String link = null;
    if (!email.isEmpty()) {
      Member m = members.findLoginAccount(email).filter(Member::active).orElse(null);
      if (m != null) link = sendResetLink(m, "【Vinsight】パスワード再設定", req);
    }
    Map<String, Object> r = new LinkedHashMap<>();
    r.put("ok", true);
    r.put("resetUrl", props.testShortcuts() ? link : null);
    return r;
  }

  public record ResetConfirm(String token, String password, String passwordConfirm) {}

  @PostMapping("/password-reset/confirm")
  @Transactional
  public Map<String, Object> confirmReset(@RequestBody ResetConfirm body, HttpServletRequest req) {
    // check the new password before using up the link, so a weak password can be corrected with the same link
    Map<String, String> errors = new LinkedHashMap<>();
    Member m = resets.peek(body.token()).flatMap(members::findById).orElse(null);
    if (m == null || !m.active()) throw ApiException.notFound("再設定URLが無効、または有効期限が切れています。");
    PasswordPolicy.check(body.password(), body.passwordConfirm(), errors, "password", m.email(), String.valueOf(m.memberNo()));
    if (!errors.isEmpty()) throw ApiException.invalid(errors);
    Long memberId = resets.consume(body.token()).orElseThrow(() -> ApiException.notFound("再設定URLが無効、または有効期限が切れています。"));
    members.setPassword(memberId, hasher.hash(body.password()), clock.instant());
    audit.record("member:" + memberId, "パスワード再設定", String.valueOf(m.memberNo()), "パスワード再設定メールから", Requests.clientIp(req));
    mail.enqueue(m.email(), "【Vinsight】パスワードが変更されました",
        (m.name() == null ? "" : m.name() + " 様\n\n") + "Vinsightのパスワードが変更されました。\nお心当たりがない場合は、お問い合わせフォームからすぐにご連絡ください。\n",
        "password-changed");
    return Map.of("ok", true);
  }

  private String sendResetLink(Member m, String subject, HttpServletRequest req) {
    String token = resets.issue(m.id()).orElse(null);
    if (token == null) return null; // a link was sent less than a minute ago
    String url = props.siteOrigin() + "/password-reset/" + token;
    mail.enqueue(m.email(), subject,
        (m.name() == null ? "" : m.name() + " 様\n\n") + "Vinsightのパスワード再設定のご案内です。\n"
            + "下記のURLから、30分以内に新しいパスワードを設定してください。\n\n" + url + "\n\n"
            + "このメールにお心当たりがない場合は、このメールを破棄してください。パスワードは変更されません。\n",
        "password-reset");
    return url;
  }
}
