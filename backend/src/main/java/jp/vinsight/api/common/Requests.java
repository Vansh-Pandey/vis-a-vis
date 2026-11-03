package jp.vinsight.api.common;

import jakarta.servlet.http.HttpServletRequest;

public final class Requests {
  private Requests() {}

  /**
   * The caller's address. Uses the socket address only; when the app runs behind a reverse proxy, Tomcat's RemoteIpValve
   * (server.forward-headers-strategy=native with trusted proxies) has already replaced it with the real client address,
   * so a forged X-Forwarded-For from the internet is never trusted here.
   */
  public static String clientIp(HttpServletRequest req) {
    return req.getRemoteAddr();
  }

  public static String trim(String s) { return s == null ? "" : s.strip(); }
}
