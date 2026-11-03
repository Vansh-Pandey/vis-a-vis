package jp.vinsight.api.auth;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import jp.vinsight.api.security.SecretBox;

/**
 * Password reset links: 256-bit random token, valid 30 minutes, usable once. Only its SHA-256 is stored.
 * A member can get at most one new link per minute, so the form cannot flood an inbox.
 */
@Service
public class ResetTokenService {
  public static final Duration TTL = Duration.ofMinutes(30);
  public static final Duration COOLDOWN = Duration.ofMinutes(1);
  private final JdbcClient jdbc;
  private final Clock clock;

  public ResetTokenService(JdbcClient jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

  /** A new token for the member, or empty if one was issued less than a minute ago. */
  public Optional<String> issue(long memberId) {
    Instant now = clock.instant();
    Long recent = jdbc.sql("SELECT COUNT(*) FROM password_reset_tokens WHERE member_id = :m AND created_at > :since")
        .param("m", memberId).param("since", Timestamp.from(now.minus(COOLDOWN))).query(Long.class).single();
    if (recent > 0) return Optional.empty();
    String token = SecretBox.randomToken(32);
    jdbc.sql("INSERT INTO password_reset_tokens (token_hash, member_id, created_at, expires_at) VALUES (:h, :m, :c, :e)")
        .param("h", SecretBox.sha256Hex(token)).param("m", memberId).param("c", Timestamp.from(now))
        .param("e", Timestamp.from(now.plus(TTL))).update();
    return Optional.of(token);
  }

  /** The member id for a valid, unused, unexpired token; locks the row so it can be used only once. */
  public Optional<Long> consume(String token) {
    if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return Optional.empty();
    String h = SecretBox.sha256Hex(token);
    Optional<Long> member = jdbc.sql("SELECT member_id FROM password_reset_tokens WHERE token_hash = :h AND used_at IS NULL AND expires_at > :now FOR UPDATE")
        .param("h", h).param("now", Timestamp.from(clock.instant())).query(Long.class).optional();
    member.ifPresent(m -> {
      // this link and every other open link for the member stop working
      jdbc.sql("UPDATE password_reset_tokens SET used_at = :now WHERE member_id = :m AND used_at IS NULL")
          .param("now", Timestamp.from(clock.instant())).param("m", m).update();
    });
    return member;
  }

  /** The member a valid token belongs to, without using it up (lets a weak new password be corrected with the same link). */
  public Optional<Long> peek(String token) {
    if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return Optional.empty();
    return jdbc.sql("SELECT member_id FROM password_reset_tokens WHERE token_hash = :h AND used_at IS NULL AND expires_at > :now")
        .param("h", SecretBox.sha256Hex(token)).param("now", Timestamp.from(clock.instant())).query(Long.class).optional();
  }
}
