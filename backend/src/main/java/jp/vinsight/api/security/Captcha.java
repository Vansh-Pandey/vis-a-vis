package jp.vinsight.api.security;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Image CAPTCHA kept compatible with the current screens. The id carries the expiry and a keyed hash of the answer, so the
 * server keeps nothing until an id is used; each id is accepted once. To be replaced by a CAPTCHA service before reopening.
 */
@Component
public class Captcha {
  private static final long TTL_MS = 10 * 60_000L;
  private static final SecureRandom RANDOM = new SecureRandom();
  private final SecretBox box;
  private final Clock clock;
  private final Map<String, Long> used = new ConcurrentHashMap<>();

  public Captcha(SecretBox box, Clock clock) { this.box = box; this.clock = clock; }

  public record Challenge(String id, String svg) {}

  public Challenge create() {
    String text = String.valueOf(1000 + RANDOM.nextInt(9000));
    String nonce = SecretBox.randomToken(12);
    long exp = clock.millis() + TTL_MS;
    String payload = nonce + "." + exp;
    String id = payload + "." + box.sign(payload + "." + text);
    return new Challenge(id, svg(text));
  }

  public boolean check(String id, String answer) {
    if (id == null || answer == null) return false;
    String[] p = id.split("\\.");
    if (p.length != 3) return false;
    long exp;
    try { exp = Long.parseLong(p[1]); } catch (NumberFormatException e) { return false; }
    if (clock.millis() > exp) return false;
    if (!box.verify(p[0] + "." + p[1] + "." + answer.strip(), p[2])) return false;
    return used.putIfAbsent(p[0], exp) == null;
  }

  @Scheduled(fixedDelay = 600_000)
  void purge() { long now = clock.millis(); used.values().removeIf(exp -> exp < now); }

  private static String svg(String text) {
    StringBuilder chars = new StringBuilder();
    for (int i = 0; i < text.length(); i++) {
      int x = 12 + i * 24, y = 30 + (i % 2) * 4, size = 26 + (i % 3) * 2, rot = (i % 2 == 1 ? 1 : -1) * (6 + RANDOM.nextInt(6));
      chars.append("<text x=\"").append(x).append("\" y=\"").append(y).append("\" font-size=\"").append(size)
          .append("\" font-family=\"sans-serif\" font-weight=\"700\" fill=\"#002c83\" transform=\"rotate(").append(rot).append(' ')
          .append(x).append(" 28)\">").append(text.charAt(i)).append("</text>");
    }
    StringBuilder noise = new StringBuilder();
    for (int i = 0; i < 5; i++) {
      noise.append("<line x1=\"").append(RANDOM.nextInt(30)).append("\" y1=\"").append(RANDOM.nextInt(46)).append("\" x2=\"")
          .append(80 + RANDOM.nextInt(32)).append("\" y2=\"").append(RANDOM.nextInt(46)).append("\" stroke=\"#fcc800\" stroke-width=\"1.5\"/>");
    }
    return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"112\" height=\"46\" viewBox=\"0 0 112 46\"><rect width=\"112\" height=\"46\" fill=\"#f4f2f2\"/>"
        + noise + chars + "</svg>";
  }
}
