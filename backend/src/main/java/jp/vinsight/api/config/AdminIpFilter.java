package jp.vinsight.api.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.web.filter.OncePerRequestFilter;
import jp.vinsight.api.common.Requests;

/** Optional: the admin API answers only from listed addresses (e.g. the office). Empty list = no restriction. */
public class AdminIpFilter extends OncePerRequestFilter {
  private final List<String> allowed;

  public AdminIpFilter(List<String> allowed) { this.allowed = allowed; }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest req) {
    return allowed.isEmpty() || !req.getRequestURI().startsWith("/api/admin");
  }

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
    if (!allowed.contains(Requests.clientIp(req))) { Json.write(res, 403, "このネットワークからは管理画面を利用できません。"); return; }
    chain.doFilter(req, res);
  }
}
