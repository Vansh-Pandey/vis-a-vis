package jp.vinsight.api.security;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Password rules (Security Requirements: 10+ characters, 2+ kinds of character, not easy to guess, not the e-mail address). */
public final class PasswordPolicy {
  public static final int MIN = 10;
  public static final int MAX = 128;

  private static final Set<String> COMMON = Set.of(
      "password123", "password1234", "qwerty12345", "1234567890", "12345678910", "abcdefghij", "abc1234567", "iloveyou12",
      "vinsight123", "vinsight2026", "monitor1234", "passw0rd123", "p@ssw0rd123", "11111111111", "0000000000", "asdfghjkl1",
      "zaq12wsxcde", "1qaz2wsx3edc", "qwertyuiop", "a1234567890", "admin12345", "welcome123");

  private PasswordPolicy() {}

  /** Adds a message under {@code field} when the password is not acceptable. */
  public static void check(String password, String confirm, Map<String, String> errors, String field, String... identifiers) {
    if (password == null || password.isEmpty()) { errors.put(field, "パスワードを入力してください。"); return; }
    if (password.length() < MIN || password.length() > MAX) { errors.put(field, "パスワードは" + MIN + "文字以上" + MAX + "文字以内で入力してください。"); return; }
    if (!password.chars().allMatch(c -> c >= 0x21 && c <= 0x7e)) { errors.put(field, "パスワードは半角の英字・数字・記号で入力してください。"); return; }
    int kinds = 0;
    if (password.chars().anyMatch(Character::isLowerCase)) kinds++;
    if (password.chars().anyMatch(Character::isUpperCase)) kinds++;
    if (password.chars().anyMatch(Character::isDigit)) kinds++;
    if (password.chars().anyMatch(c -> !Character.isLetterOrDigit(c))) kinds++;
    if (kinds < 2) { errors.put(field, "英大文字・英小文字・数字・記号のうち2種類以上を組み合わせてください。"); return; }
    String lower = password.toLowerCase(Locale.ROOT);
    if (COMMON.contains(lower) || lower.chars().distinct().count() <= 3) { errors.put(field, "推測されやすいパスワードは使用できません。"); return; }
    for (String id : identifiers) {
      if (id == null || id.length() < 4) continue;
      String part = id.toLowerCase(Locale.ROOT);
      int at = part.indexOf('@');
      if (at > 0) part = part.substring(0, at);
      if (part.length() >= 4 && lower.contains(part)) { errors.put(field, "メールアドレスやモニター番号を含むパスワードは使用できません。"); return; }
    }
    if (confirm != null && !password.equals(confirm)) errors.put(field + "Confirm", "確認用パスワードが一致しません。");
  }
}
