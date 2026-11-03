package jp.vinsight.api.member;

import java.time.Instant;
import java.time.LocalDate;

public record Member(
    long id, int memberNo, Integer legacyMonitorPk, String email, String passwordHash, Instant passwordSetAt,
    String migrationStatus, String status, String name, String postcode, String prefecture, String city, String street,
    String tel, LocalDate birth, String gender, Boolean married, Integer children, String occupation, int pointBalance,
    Instant registeredAt, Instant lastLoginAt, Instant prevLoginAt, Instant infoChangedAt, String remark) {

  public boolean active() { return "ACTIVE".equals(status); }
  public boolean needsPasswordReset() { return "LEGACY_PENDING".equals(migrationStatus) || passwordHash == null; }
  /** Changes whenever the password changes; sessions and reset links made before that stop working. */
  public String passwordStamp() { return passwordSetAt == null ? "none" : String.valueOf(passwordSetAt.toEpochMilli()); }

  public static String statusLabel(String status) {
    return switch (status) {
      case "ACTIVE" -> "有効";
      case "PROVISIONAL" -> "仮登録";
      case "WITHDRAWN" -> "退会";
      case "SUSPENDED" -> "停止";
      default -> status;
    };
  }
}
