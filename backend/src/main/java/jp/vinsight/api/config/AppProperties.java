package jp.vinsight.api.config;

import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings under {@code vinsight.*}. Checked once at start-up so a missing secret stops the app instead of weakening it. */
@ConfigurationProperties(prefix = "vinsight")
public record AppProperties(
    String siteOrigin,
    String secretKey,
    String environment,
    String mailFrom,
    Admin admin,
    Member member,
    StagingGate stagingGate) {

  public record Admin(String allowedIps, int sessionIdleMinutes) {
    public List<String> allowedIpList() {
      return allowedIps == null || allowedIps.isBlank() ? List.of()
          : Arrays.stream(allowedIps.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
  }

  public record Member(int sessionIdleMinutes) {}

  public record StagingGate(String user, String password) {
    public boolean enabled() {
      return user != null && !user.isBlank() && password != null && !password.isBlank();
    }
  }

  public AppProperties {
    if (secretKey == null || secretKey.isBlank()) throw new IllegalStateException("VINSIGHT_SECRET_KEY must be set (32 random bytes, base64)");
    if (Base64.getDecoder().decode(secretKey).length < 32) throw new IllegalStateException("VINSIGHT_SECRET_KEY must decode to at least 32 bytes");
    if (siteOrigin == null || !siteOrigin.matches("https?://[^/]+")) throw new IllegalStateException("VINSIGHT_SITE_ORIGIN must look like https://example.com (no path)");
    if (environment == null) environment = "PRODUCTION";
    environment = environment.toUpperCase();
    if (!List.of("DEV", "STAGING", "PRODUCTION").contains(environment)) throw new IllegalStateException("VINSIGHT_ENV must be DEV, STAGING or PRODUCTION");
    if (environment.equals("PRODUCTION") && !siteOrigin.startsWith("https://")) throw new IllegalStateException("Production must be served over https");
  }

  public boolean production() { return environment.equals("PRODUCTION"); }

  /** Shortcuts for testing without a mailbox (reset links in API responses). Never in production. */
  public boolean testShortcuts() { return !production(); }

  public byte[] secretKeyBytes() { return Base64.getDecoder().decode(secretKey); }
}
