package jp.vinsight.api.security;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Six-digit, 30-second codes (RFC 6238) as shown by Google Authenticator, Microsoft Authenticator and similar apps. */
public final class Totp {
  private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  private Totp() {}

  public static String newSecret() {
    byte[] b = new byte[20];
    new SecureRandom().nextBytes(b);
    StringBuilder out = new StringBuilder();
    int bits = 0, value = 0;
    for (byte x : b) {
      value = (value << 8) | (x & 0xff);
      bits += 8;
      while (bits >= 5) { out.append(ALPHABET.charAt((value >>> (bits - 5)) & 31)); bits -= 5; }
    }
    if (bits > 0) out.append(ALPHABET.charAt((value << (5 - bits)) & 31));
    return out.toString();
  }

  static byte[] decode(String secret) {
    String s = secret.replaceAll("[\\s=]", "").toUpperCase();
    byte[] out = new byte[s.length() * 5 / 8];
    int bits = 0, value = 0, i = 0;
    for (char ch : s.toCharArray()) {
      int idx = ALPHABET.indexOf(ch);
      if (idx < 0) throw new IllegalArgumentException("invalid base32");
      value = (value << 5) | idx;
      bits += 5;
      if (bits >= 8) { out[i++] = (byte) ((value >>> (bits - 8)) & 0xff); bits -= 8; }
    }
    return out;
  }

  public static long step(long epochMillis) { return epochMillis / 1000 / 30; }

  public static String code(String secret, long step) {
    try {
      Mac mac = Mac.getInstance("HmacSHA1");
      mac.init(new SecretKeySpec(decode(secret), "HmacSHA1"));
      byte[] h = mac.doFinal(java.nio.ByteBuffer.allocate(8).putLong(step).array());
      int o = h[h.length - 1] & 0xf;
      int n = ((h[o] & 0x7f) << 24) | ((h[o + 1] & 0xff) << 16) | ((h[o + 2] & 0xff) << 8) | (h[o + 3] & 0xff);
      return String.format("%06d", n % 1_000_000);
    } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
  }

  /** The step the code belongs to (one step of clock drift either way), or -1. Steps up to lastUsedStep are refused (no replay). */
  public static long verify(String secret, String code, long lastUsedStep, long nowMillis) {
    if (code == null) return -1;
    String given = code.replaceAll("\\s", "");
    if (!given.matches("\\d{6}")) return -1;
    long now = step(nowMillis);
    for (long s = now - 1; s <= now + 1; s++) {
      if (s <= lastUsedStep) continue;
      if (MessageDigest.isEqual(code(secret, s).getBytes(StandardCharsets.US_ASCII), given.getBytes(StandardCharsets.US_ASCII))) return s;
    }
    return -1;
  }

  public static String uri(String secret, String account) {
    String label = URLEncoder.encode("Vinsight 管理画面:" + account, StandardCharsets.UTF_8).replace("+", "%20");
    return "otpauth://totp/" + label + "?secret=" + secret + "&issuer=Vinsight&digits=6&period=30";
  }
}
