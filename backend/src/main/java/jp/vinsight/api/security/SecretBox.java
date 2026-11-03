package jp.vinsight.api.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import jp.vinsight.api.config.AppProperties;

/** Encryption (AES-256-GCM) and signing (HMAC-SHA256) with keys derived from VINSIGHT_SECRET_KEY. */
@Component
public class SecretBox {
  private static final SecureRandom RANDOM = new SecureRandom();
  private final SecretKeySpec encKey;
  private final SecretKeySpec macKey;

  public SecretBox(AppProperties props) {
    byte[] master = props.secretKeyBytes();
    this.encKey = new SecretKeySpec(derive(master, "vinsight-encryption"), "AES");
    this.macKey = new SecretKeySpec(derive(master, "vinsight-signing"), "HmacSHA256");
  }

  private static byte[] derive(byte[] master, String label) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(master, "HmacSHA256"));
      return mac.doFinal(label.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
  }

  public String encrypt(String plain) {
    try {
      byte[] iv = new byte[12];
      RANDOM.nextBytes(iv);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, encKey, new GCMParameterSpec(128, iv));
      byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder().encodeToString(ByteBuffer.allocate(12 + ct.length).put(iv).put(ct).array());
    } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
  }

  public String decrypt(String box) {
    try {
      byte[] all = Base64.getDecoder().decode(box);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.DECRYPT_MODE, encKey, new GCMParameterSpec(128, all, 0, 12));
      return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException e) { throw new IllegalStateException("cannot decrypt", e); }
  }

  public String sign(String data) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(macKey);
      return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
  }

  public boolean verify(String data, String signature) {
    return signature != null && MessageDigest.isEqual(sign(data).getBytes(StandardCharsets.US_ASCII), signature.getBytes(StandardCharsets.US_ASCII));
  }

  public static String randomToken(int bytes) {
    byte[] b = new byte[bytes];
    RANDOM.nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  public static String sha256Hex(String s) {
    try {
      byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(64);
      for (byte x : d) sb.append(String.format("%02x", x));
      return sb.toString();
    } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
  }
}
