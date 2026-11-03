package jp.vinsight.api.common;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** An error the user should see. The message is shown on screen as is; fields map form inputs to their messages. */
public class ApiException extends RuntimeException {
  private final HttpStatus status;
  private final Map<String, String> fields;
  private final String code;

  public ApiException(HttpStatus status, String message) { this(status, message, Map.of(), null); }

  public ApiException(HttpStatus status, String message, Map<String, String> fields) { this(status, message, fields, null); }

  public ApiException(HttpStatus status, String message, Map<String, String> fields, String code) {
    super(message);
    this.status = status;
    this.fields = fields == null ? Map.of() : Map.copyOf(fields);
    this.code = code;
  }

  public static ApiException invalid(Map<String, String> fields) {
    return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "入力内容に誤りがあります。", fields);
  }

  public static ApiException notFound(String message) { return new ApiException(HttpStatus.NOT_FOUND, message); }

  public HttpStatus status() { return status; }
  public Map<String, String> fields() { return fields; }
  public String code() { return code; }
}
