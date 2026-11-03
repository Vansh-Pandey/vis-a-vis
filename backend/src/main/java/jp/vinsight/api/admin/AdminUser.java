package jp.vinsight.api.admin;

import java.time.Instant;

public record AdminUser(long id, String loginId, String passwordHash, Instant passwordSetAt, String role,
    String totpSecretEnc, String totpPendingEnc, long totpLastStep, boolean disabled) {
  public String passwordStamp() { return String.valueOf(passwordSetAt.toEpochMilli()) + (totpSecretEnc == null ? ":no2fa" : ":2fa"); }
  public boolean totpEnabled() { return totpSecretEnc != null; }
}
