package jp.vinsight.api.admin;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import jp.vinsight.api.audit.AuditLog;
import jp.vinsight.api.security.PasswordHasher;
import jp.vinsight.api.security.PasswordPolicy;

/**
 * Creates the first administrator, once, from environment variables:
 * VINSIGHT_BOOTSTRAP_ADMIN_ID and VINSIGHT_BOOTSTRAP_ADMIN_PASSWORD. Does nothing if any admin exists.
 * Remove the variables after the first start. The password must pass the normal password rules.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);
  private final AdminRepository admins;
  private final PasswordHasher hasher;
  private final AuditLog audit;
  private final Clock clock;

  public AdminBootstrap(AdminRepository admins, PasswordHasher hasher, AuditLog audit, Clock clock) {
    this.admins = admins; this.hasher = hasher; this.audit = audit; this.clock = clock;
  }

  @Override
  public void run(ApplicationArguments args) {
    String id = System.getenv("VINSIGHT_BOOTSTRAP_ADMIN_ID");
    String pw = System.getenv("VINSIGHT_BOOTSTRAP_ADMIN_PASSWORD");
    if (id == null || pw == null || admins.count() > 0) return;
    Map<String, String> errors = new HashMap<>();
    PasswordPolicy.check(pw, null, errors, "password", id);
    if (!id.matches("[A-Za-z0-9_.-]{3,50}") || !errors.isEmpty())
      throw new IllegalStateException("Bootstrap admin not created: id must be 3-50 letters/digits and the password must follow the password rules " + errors.values());
    admins.insert(id, hasher.hash(pw), "ADMIN", clock.instant());
    audit.record("system", "初期管理者の作成", id, null, null);
    log.info("Created the first administrator '{}'. Remove VINSIGHT_BOOTSTRAP_ADMIN_* from the environment now.", id);
  }
}
