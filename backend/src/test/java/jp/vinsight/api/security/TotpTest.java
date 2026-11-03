package jp.vinsight.api.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TotpTest {
  // RFC 6238 Appendix B, SHA-1, secret "12345678901234567890" (base32 below), last 6 digits of the 8-digit values
  private static final String SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

  @Test
  void matchesRfc6238Vectors() {
    assertEquals("287082", Totp.code(SECRET, 59 / 30));
    assertEquals("081804", Totp.code(SECRET, 1111111109L / 30));
    assertEquals("050471", Totp.code(SECRET, 1111111111L / 30));
    assertEquals("005924", Totp.code(SECRET, 1234567890L / 30));
    assertEquals("279037", Totp.code(SECRET, 2000000000L / 30));
  }

  @Test
  void acceptsOneStepOfDriftAndRefusesReplay() {
    long now = 1_700_000_000_000L, step = Totp.step(now);
    assertEquals(step, Totp.verify(SECRET, Totp.code(SECRET, step), -1, now));
    assertEquals(step - 1, Totp.verify(SECRET, Totp.code(SECRET, step - 1), -1, now));
    assertEquals(-1, Totp.verify(SECRET, Totp.code(SECRET, step - 2), -1, now));
    assertEquals(-1, Totp.verify(SECRET, Totp.code(SECRET, step), step, now), "a used step must not be accepted again");
    assertEquals(-1, Totp.verify(SECRET, "12345", -1, now));
    assertEquals(-1, Totp.verify(SECRET, "abcdef", -1, now));
  }

  @Test
  void newSecretsAre160Bits() {
    String s = Totp.newSecret();
    assertTrue(s.matches("[A-Z2-7]{32}"), s);
  }
}
