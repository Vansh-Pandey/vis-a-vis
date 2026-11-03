package jp.vinsight.api.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Staging only (meeting 2 Nov): a site-wide password so the test site is not open to the public, and a noindex header so
 * search engines never list it. Off in production. Uses a constant-time comparison.
 */
public class StagingGateFilter extends OncePerRequestFilter {
  private final AppProperties props;
  private final byte[] expected;

  public StagingGateFilter(AppProperties props) {
    this.props = props;
    this.expected = props.stagingGate().enabled()
        ? ("Basic " + Base64.getEncoder().encodeToString((props.stagingGate().user() + ":" + props.stagingGate().password()).getBytes(StandardCharsets.UTF_8)))
            .getBytes(StandardCharsets.UTF_8)
        : null;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
    if (!props.production()) res.setHeader("X-Robots-Tag", "noindex, nofollow, noarchive");
    if (expected != null) {
      String got = req.getHeader("Authorization");
      if (got == null || !MessageDigest.isEqual(expected, got.getBytes(StandardCharsets.UTF_8))) {
        res.setHeader("WWW-Authenticate", "Basic realm=\"Vinsight staging\", charset=\"UTF-8\"");
        Json.write(res, 401, "テスト環境のため、認証が必要です。");
        return;
      }
    }
    chain.doFilter(req, res);
  }
}
