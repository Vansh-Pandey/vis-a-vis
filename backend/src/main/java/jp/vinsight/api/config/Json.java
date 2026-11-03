package jp.vinsight.api.config;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Minimal JSON error writer for filters, which run before Spring MVC's message converters. */
final class Json {
  private Json() {}

  static void write(HttpServletResponse res, int status, String message) throws IOException {
    res.setStatus(status);
    res.setContentType("application/json;charset=UTF-8");
    String escaped = message.replace("\\", "\\\\").replace("\"", "\\\"");
    res.getOutputStream().write(("{\"error\":\"" + escaped + "\"}").getBytes(StandardCharsets.UTF_8));
  }
}
