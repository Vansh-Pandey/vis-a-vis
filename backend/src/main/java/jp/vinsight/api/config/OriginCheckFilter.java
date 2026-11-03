package jp.vinsight.api.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Protection against requests sent from other websites (CSRF, Security Requirements #12), in three layers:
 * 1. every request that changes data must carry an Origin header equal to the site's own address;
 * 2. it must be JSON (or a file upload to the admin import), which a plain HTML form on another site cannot send;
 * 3. the session cookie is SameSite=Strict, so other sites' requests arrive without it anyway.
 */
public class OriginCheckFilter extends OncePerRequestFilter {
  private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");
  private final String origin;

  public OriginCheckFilter(String origin) { this.origin = origin; }

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
    if (!SAFE.contains(req.getMethod())) {
      String o = req.getHeader("Origin");
      if (o == null || !o.equals(origin)) { Json.write(res, 403, "このリクエストは受け付けられません。"); return; }
      String ct = req.getContentType() == null ? "" : req.getContentType().toLowerCase();
      boolean json = ct.startsWith("application/json");
      boolean upload = ct.startsWith("multipart/form-data") && req.getRequestURI().startsWith("/api/admin/import/");
      boolean empty = req.getContentLengthLong() <= 0 && ct.isEmpty();
      if (!json && !upload && !empty) { Json.write(res, 415, "リクエストの形式が正しくありません。"); return; }
    }
    chain.doFilter(req, res);
  }
}
