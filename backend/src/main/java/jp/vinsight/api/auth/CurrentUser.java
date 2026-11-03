package jp.vinsight.api.auth;

/** The signed-in member and/or administrator for this request (set by SessionAuthFilter). */
public record CurrentUser(Long memberId, Long adminId, String adminRole, String adminLoginId) {
  public String actor() {
    if (adminId != null) return "admin:" + adminLoginId;
    if (memberId != null) return "member:" + memberId;
    return "anonymous";
  }
}
