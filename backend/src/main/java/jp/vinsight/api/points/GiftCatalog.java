package jp.vinsight.api.points;

import java.util.List;
import java.util.Optional;

/** What points can be exchanged for. Keys are the labels the current screens send. */
public final class GiftCatalog {
  public static final int POINTS_PER_UNIT = 500;
  public static final int MAX_UNITS_PER_ITEM = 20;

  public record Item(String key, String code, String note) {}

  public static final List<Item> ITEMS = List.of(
      new Item("QUOカードPay", "QUO", "デジタルギフトとして登録メールアドレスに送付されます。"),
      new Item("Amazonギフト券（eメールタイプ）", "AMAZON", "デジタルギフトとして登録メールアドレスに送付されます。"),
      new Item("たまルンポイント", "TAMARUN", "「たまルンカード会員」のみ利用できます。カード番号の入力が必要です。"));

  private GiftCatalog() {}

  public static Optional<Item> byKey(String key) { return ITEMS.stream().filter(i -> i.key().equals(key)).findFirst(); }
  public static Optional<Item> byCode(String code) { return ITEMS.stream().filter(i -> i.code().equals(code)).findFirst(); }
}
