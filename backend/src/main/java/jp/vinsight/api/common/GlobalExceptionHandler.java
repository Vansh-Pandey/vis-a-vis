package jp.vinsight.api.common;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Turns every error into {@code {error, fields, code}}, the shape the Next.js screens already read.
 * Unexpected errors are logged with an id and answered with a generic message: no stack trace or SQL reaches the browser.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  public static Map<String, Object> body(String message, Map<String, String> fields, String code) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("error", message);
    if (fields != null && !fields.isEmpty()) m.put("fields", fields);
    if (code != null) m.put("code", code);
    return m;
  }

  @ExceptionHandler(ApiException.class)
  ResponseEntity<Map<String, Object>> api(ApiException e) {
    return ResponseEntity.status(e.status()).body(body(e.getMessage(), e.fields(), e.code()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException e) {
    Map<String, String> fields = new LinkedHashMap<>();
    e.getBindingResult().getFieldErrors().forEach(f -> fields.putIfAbsent(f.getField(), f.getDefaultMessage()));
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(body("入力内容に誤りがあります。", fields, null));
  }

  @ExceptionHandler({HttpMessageNotReadableException.class, HttpMediaTypeNotSupportedException.class,
      org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
      org.springframework.web.bind.MissingServletRequestParameterException.class,
      org.springframework.web.multipart.support.MissingServletRequestPartException.class})
  ResponseEntity<Map<String, Object>> unreadable(Exception e) {
    return ResponseEntity.badRequest().body(body("リクエストの形式が正しくありません。", null, null));
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  ResponseEntity<Map<String, Object>> method(HttpRequestMethodNotSupportedException e) {
    return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(body("許可されていないメソッドです。", null, null));
  }

  @ExceptionHandler(NoResourceFoundException.class)
  ResponseEntity<Map<String, Object>> notFound(NoResourceFoundException e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body("APIが見つかりません。", null, null));
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ResponseEntity<Map<String, Object>> tooLarge(MaxUploadSizeExceededException e) {
    return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).body(body("ファイルが大きすぎます（10MBまで）。", null, null));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Map<String, Object>> unexpected(Exception e) {
    String id = java.util.UUID.randomUUID().toString().substring(0, 8);
    log.error("Unexpected error {}", id, e);
    return ResponseEntity.internalServerError().body(body("サーバーエラーが発生しました。（エラーID: " + id + "）", null, null));
  }
}
