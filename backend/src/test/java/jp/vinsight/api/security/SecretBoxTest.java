package jp.vinsight.api.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import org.junit.jupiter.api.Test;
import jp.vinsight.api.config.AppProperties;

class SecretBoxTest {
  private static SecretBox box(String key) {
    return new SecretBox(new AppProperties("http://localhost:3000", key, "DEV", "info@example.com",
        new AppProperties.Admin("", 15), new AppProperties.Member(30), new AppProperties.StagingGate("", "")));
  }

  private final String key = Base64.getEncoder().encodeToString(new byte[32]);

  @Test
  void encryptsWithAFreshNonceAndDecrypts() {
    SecretBox b = box(key);
    String a = b.encrypt("JBSWY3DPEHPK3PXP"), c = b.encrypt("JBSWY3DPEHPK3PXP");
    assertNotEquals(a, c);
    assertEquals("JBSWY3DPEHPK3PXP", b.decrypt(a));
  }

  @Test
  void tamperedOrForeignCiphertextIsRejected() {
    SecretBox b = box(key);
    byte[] raw = Base64.getDecoder().decode(b.encrypt("secret"));
    raw[raw.length - 1] ^= 1;
    assertThrows(IllegalStateException.class, () -> b.decrypt(Base64.getEncoder().encodeToString(raw)));
    byte[] other = new byte[32]; other[0] = 1;
    assertThrows(IllegalStateException.class, () -> box(Base64.getEncoder().encodeToString(other)).decrypt(b.encrypt("secret")));
  }

  @Test
  void signaturesVerifyOnlyForTheSameData() {
    SecretBox b = box(key);
    String sig = b.sign("abc");
    assertTrue(b.verify("abc", sig));
    assertFalse(b.verify("abd", sig));
    assertFalse(b.verify("abc", null));
  }

  @Test
  void refusesAShortKey() {
    assertThrows(IllegalStateException.class, () -> box(Base64.getEncoder().encodeToString(new byte[16])));
  }
}
