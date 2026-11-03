package jp.vinsight.api.auth;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jp.vinsight.api.security.Captcha;

@RestController
@RequestMapping("/api")
public class PublicController {
  private final Captcha captcha;

  public PublicController(Captcha captcha) { this.captcha = captcha; }

  @GetMapping("/health")
  public Map<String, Object> health() { return Map.of("ok", true); }

  @GetMapping("/captcha")
  public Captcha.Challenge captcha() { return captcha.create(); }

  @GetMapping("/public/news")
  public Map<String, Object> news() { return Map.of("items", List.of()); }
}
