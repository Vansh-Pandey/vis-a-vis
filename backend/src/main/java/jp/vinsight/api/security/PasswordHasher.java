package jp.vinsight.api.security;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Argon2id with the OWASP-recommended minimum (19 MiB memory, 2 iterations, 1 lane). A stolen copy of the members table
 * gives no usable passwords.
 */
@Component
public class PasswordHasher {
  private final Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, 1, 19 * 1024, 2);
  /** Compared against when the account does not exist, so a missing account takes as long as a wrong password. */
  private final String dummyHash = encoder.encode("dummy-password-for-timing");

  public String hash(String raw) { return encoder.encode(raw); }

  public boolean matches(String raw, String hash) {
    if (raw == null || raw.isEmpty()) return false;
    if (hash == null) {
      encoder.matches(raw, dummyHash);
      return false;
    }
    return encoder.matches(raw, hash);
  }

  public void burnTime(String raw) { encoder.matches(raw == null ? "" : raw, dummyHash); }
}
