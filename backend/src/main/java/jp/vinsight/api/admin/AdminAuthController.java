package jp.vinsight.api.admin;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jp.vinsight.api.audit.AuditLog;
import jp.vinsight.api.auth.CurrentUser;
import jp.vinsight.api.auth.Sessions;
import jp.vinsight.api.common.ApiException;
import jp.vinsight.api.common.Requests;
import jp.vinsight.api.config.AppProperties;
import jp.vinsight.api.security.LoginThrottle;
import jp.vinsight.api.security.PasswordHasher;
import jp.vinsight.api.security.PasswordPolicy;
import jp.vinsight.api.security.SecretBox;
import jp.vinsight.api.security.Totp;

/**
 * Administrator sign-in. Password, then a 6-digit code from an authenticator app (Security Requirements #16).
 * Two-step login is required outside local development; an admin without it is shown a setup key right after the password.
 * Answers use the same {ok, next} shape as the current admin login screen.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminAuthController {
  private static final String WRONG = "IDまたはパスワードが正しくありません。";
  private static final long PENDING_MS = 5 * 60_000L;
  private final AdminRepository admins;
  private final PasswordHasher hasher;
  private final LoginThrottle throttle;
  private final Sessions sessions;
  private final SecretBox box;
  private final AuditLog audit;
  private final AppProperties props;
  private final Clock clock;

  public AdminAuthController(AdminRepository admins, PasswordHasher hasher, LoginThrottle throttle, Sessions sessions, SecretBox box,
      AuditLog audit, AppProperties props, Clock clock) {
    this.admins = admins; this.hasher = hasher; this.throttle = throttle; this.sessions = sessions; this.box = box;
    this.audit = audit; this.props = props; this.clock = clock;
  }

  /** Two-step login is optional only on a developer's machine. */
  private boolean twoStepRequired() { return !props.environment().equals("DEV"); }

  public record LoginRequest(String id, String password, String code) {}

  @PostMapping("/login")
  @Transactional
  public Map<String, Object> login(@RequestBody LoginRequest body, HttpServletRequest req) {
    String loginId = Requests.trim(body.id());
    String ip = Requests.clientIp(req);
    throttle.assertAllowed("admin", loginId, ip);
    AdminUser a = loginId.isEmpty() ? null : admins.findByLoginId(loginId).orElse(null);
    if (a == null || a.disabled() || !hasher.matches(body.password(), a == null ? null : a.passwordHash())) {
      throttle.failed("admin", loginId, ip);
      audit.recordFailure("admin:" + loginId, "管理者ログイン失敗", loginId, null, ip);
      throw new ApiException(HttpStatus.UNAUTHORIZED, WRONG);
    }
    String code = Requests.trim(body.code());
    if (a.totpEnabled()) {
      if (code.isEmpty()) return Map.of("ok", false, "next", "totp");
      long step = Totp.verify(box.decrypt(a.totpSecretEnc()), code, a.totpLastStep(), clock.millis());
      if (step < 0 || !admins.useTotpStep(a.id(), step)) {
        throttle.failed("admin", loginId, ip);
        audit.recordFailure("admin:" + loginId, "管理者ログイン失敗（確認コード）", loginId, null, ip);
        throw new ApiException(HttpStatus.UNAUTHORIZED, "確認コードが正しくありません。", Map.of("code", "確認コードが正しくありません。"));
      }
    } else if (twoStepRequired()) {
      // first sign-in: show a new key, and finish when the first code from the app is entered
      if (code.isEmpty() || a.totpPendingEnc() == null) {
        String secret = Totp.newSecret();
        admins.setPendingTotp(a.id(), box.encrypt(secret));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", false);
        r.put("next", "totp-setup");
        r.put("secret", secret);
        r.put("uri", Totp.uri(secret, a.loginId()));
        return r;
      }
      long step = Totp.verify(box.decrypt(a.totpPendingEnc()), code, -1, clock.millis());
      if (step < 0) {
        throttle.failed("admin", loginId, ip);
        audit.recordFailure("admin:" + loginId, "管理者ログイン失敗（確認コード）", loginId, "2段階認証の設定中", ip);
        throw new ApiException(HttpStatus.UNAUTHORIZED, "確認コードが正しくありません。", Map.of("code", "確認コードが正しくありません。"));
      }
      admins.enableTotp(a.id(), a.totpPendingEnc(), step);
      audit.record("admin:" + loginId, "2段階認証の設定", loginId, "ログイン時に設定", ip);
      a = admins.findById(a.id()).orElseThrow();
    }
    throttle.succeeded("admin", loginId);
    sessions.signIn(req, Sessions.ADMIN, a.id(), a.passwordStamp());
    audit.record("admin:" + loginId, "管理者ログイン", loginId, a.totpEnabled() ? "2段階認証" : "パスワードのみ（開発環境）", ip);
    return Map.of("ok", true);
  }

  @PostMapping("/logout")
  public Map<String, Object> logout(HttpServletRequest req) {
    sessions.signOut(req, Sessions.ADMIN);
    return Map.of("ok", true);
  }

  @GetMapping("/me")
  public Map<String, Object> me(@AuthenticationPrincipal CurrentUser u) {
    AdminUser a = admins.findById(u.adminId()).orElseThrow();
    return Map.of("id", a.loginId(), "role", a.role(), "totpEnabled", a.totpEnabled(), "totpRequired", twoStepRequired());
  }

  public record PasswordChange(String currentPassword, String newPassword, String code) {}

  /** Changing the password signs out every other admin session (their password stamp no longer matches). */
  @PostMapping("/password")
  @Transactional
  public Map<String, Object> changePassword(@AuthenticationPrincipal CurrentUser u, @RequestBody PasswordChange body, HttpServletRequest req) {
    AdminUser a = admins.findById(u.adminId()).orElseThrow();
    reauth(a, body.currentPassword(), body.code(), Requests.clientIp(req));
    Map<String, String> errors = new LinkedHashMap<>();
    PasswordPolicy.check(body.newPassword(), null, errors, "newPassword", a.loginId());
    if (!errors.isEmpty()) throw ApiException.invalid(errors);
    admins.setPassword(a.id(), hasher.hash(body.newPassword()), clock.instant());
    audit.record(u.actor(), "管理者パスワード変更", a.loginId(), null, Requests.clientIp(req));
    sessions.signIn(req, Sessions.ADMIN, a.id(), admins.findById(a.id()).orElseThrow().passwordStamp());
    return Map.of("ok", true);
  }

  /** Sensitive changes ask for the password (and code) again, so an unattended signed-in browser is not enough. */
  void reauth(AdminUser a, String password, String code, String ip) {
    throttle.assertAllowed("admin-reauth", a.loginId(), ip);
    if (!hasher.matches(password, a.passwordHash())) {
      throttle.failed("admin-reauth", a.loginId(), ip);
      audit.recordFailure("admin:" + a.loginId(), "本人確認の失敗", a.loginId(), "パスワード", ip);
      throw ApiException.invalid(Map.of("currentPassword", "現在のパスワードが正しくありません。"));
    }
    if (a.totpEnabled()) {
      long step = Totp.verify(box.decrypt(a.totpSecretEnc()), code, a.totpLastStep(), clock.millis());
      if (step < 0 || !admins.useTotpStep(a.id(), step)) {
        audit.recordFailure("admin:" + a.loginId(), "本人確認の失敗", a.loginId(), "確認コード", ip);
        throw ApiException.invalid(Map.of("code", "確認コードが正しくありません。"));
      }
    }
  }
}
