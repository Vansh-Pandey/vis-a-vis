package jp.vinsight.api.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PasswordPolicyTest {
  private static Map<String, String> check(String pw, String... ids) {
    Map<String, String> e = new HashMap<>();
    PasswordPolicy.check(pw, pw, e, "password", ids);
    return e;
  }

  @Test
  void acceptsAReasonablePassword() { assertTrue(check("Kp7-renewal-2026", "someone@example.com").isEmpty()); }

  @Test
  void refusesShortOneKindCommonOrPersonal() {
    assertFalse(check("Ab1-short").isEmpty(), "too short");
    assertFalse(check("onlylowercaseletters").isEmpty(), "one kind of character");
    assertFalse(check("password123").isEmpty(), "common");
    assertFalse(check("aaaaaaaaaa1").isEmpty(), "too few distinct characters");
    assertFalse(check("hanako.yamada2026", "hanako.yamada@example.com").isEmpty(), "contains the e-mail name");
    assertFalse(check("パスワード１２３４５６").isEmpty(), "full-width characters");
  }

  @Test
  void refusesMismatchedConfirmation() {
    Map<String, String> e = new HashMap<>();
    PasswordPolicy.check("Kp7-renewal-2026", "Kp7-renewal-2025", e, "password");
    assertTrue(e.containsKey("passwordConfirm"));
  }
}
