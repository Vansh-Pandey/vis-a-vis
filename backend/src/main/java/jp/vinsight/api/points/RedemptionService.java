package jp.vinsight.api.points;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import jp.vinsight.api.audit.AuditLog;
import jp.vinsight.api.common.ApiException;
import jp.vinsight.api.mail.MailOutbox;
import jp.vinsight.api.member.Member;
import jp.vinsight.api.member.MemberRepository;

/**
 * Point redemption. In one transaction: lock the member row, check the balance, store the application and its items,
 * write the ledger row and update the balance. The (member, request id) pair is unique in the database, so a double click,
 * a resend after a timeout or two parallel requests all end with one application and one deduction.
 */
@Service
public class RedemptionService {
  private final JdbcClient jdbc;
  private final MemberRepository members;
  private final PointLedger ledger;
  private final MailOutbox mail;
  private final AuditLog audit;
  private final TransactionTemplate tx;
  private final Clock clock;

  public RedemptionService(JdbcClient jdbc, MemberRepository members, PointLedger ledger, MailOutbox mail, AuditLog audit,
      TransactionTemplate tx, Clock clock) {
    this.jdbc = jdbc; this.members = members; this.ledger = ledger; this.mail = mail; this.audit = audit; this.tx = tx; this.clock = clock;
  }

  public record Line(GiftCatalog.Item item, int units, String cardNo) {}
  public record Shipping(String name, String postcode, String prefecture, String city, String street, String tel) {}
  public record Result(long id, int used, int balance) {}

  public Result apply(long memberId, String requestId, List<Line> lines, Shipping ship, String ip) {
    try {
      return tx.execute(s -> applyOnce(memberId, requestId, lines, ship, ip));
    } catch (DuplicateKeyException race) {
      // a parallel request with the same id committed first: answer with its result
      return existing(memberId, requestId).orElseThrow(() -> race);
    }
  }

  private Result applyOnce(long memberId, String requestId, List<Line> lines, Shipping ship, String ip) {
    Member m = members.findByIdForUpdate(memberId).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "ログインが必要です。"));
    Optional<Result> again = existing(memberId, requestId);
    if (again.isPresent()) return again.get();
    int used = lines.stream().mapToInt(l -> l.units() * GiftCatalog.POINTS_PER_UNIT).sum();
    if (used > m.pointBalance())
      throw ApiException.invalid(Map.of("items", "保有ポイント（" + m.pointBalance() + "）を超えています。"));
    Instant now = clock.instant();
    jdbc.sql("""
        INSERT INTO redemptions (member_id, request_id, points_used, ship_name, ship_postcode, ship_prefecture, ship_city, ship_street,
                                 ship_tel, email, applied_at)
        VALUES (:m, :req, :used, :name, :pc, :pref, :city, :street, :tel, :email, :at)""")
        .param("m", memberId).param("req", requestId).param("used", used).param("name", ship.name()).param("pc", ship.postcode())
        .param("pref", ship.prefecture()).param("city", ship.city()).param("street", ship.street()).param("tel", ship.tel())
        .param("email", m.email()).param("at", Timestamp.from(now)).update();
    long id = jdbc.sql("SELECT id FROM redemptions WHERE member_id = :m AND request_id = :req").param("m", memberId).param("req", requestId)
        .query(Long.class).single();
    for (Line l : lines) {
      jdbc.sql("INSERT INTO redemption_items (redemption_id, item_code, units, card_no) VALUES (:r, :c, :u, :card)")
          .param("r", id).param("c", l.item().code()).param("u", l.units()).param("card", l.cardNo()).update();
    }
    int balance = ledger.post(memberId, m.pointBalance(), -used, "REDEMPTION", "redemption:" + id, null, null, "member:" + memberId);
    audit.record("member:" + memberId, "ポイント交換申込み", "申請#" + id, used + "pt（残高 " + balance + "pt）", ip);
    String items = String.join(" / ", lines.stream().map(l -> l.item().key() + " " + l.units() + "口").toList());
    mail.enqueue(m.email(), "【Vinsight】ポイント交換のお申し込みを受け付けました",
        (ship.name() + " 様\n\nポイント交換のお申し込みを受け付けました。\n\n申請番号：" + id + "\n商品：" + items + "\n使用ポイント："
            + used + "pt\n残りのポイント：" + balance + "pt\n\nデジタルギフトは、ご登録のメールアドレスにお送りします。\n"),
        "redemption-received");
    return new Result(id, used, balance);
  }

  /** The application already made with this request id, if any (a resend returns it instead of applying again). */
  public Optional<Result> existing(long memberId, String requestId) {
    return jdbc.sql("""
        SELECT r.id, r.points_used, m.point_balance FROM redemptions r JOIN members m ON m.id = r.member_id
        WHERE r.member_id = :m AND r.request_id = :req""")
        .param("m", memberId).param("req", requestId)
        .query((rs, n) -> new Result(rs.getLong(1), rs.getInt(2), rs.getInt(3))).optional();
  }

  public List<Map<String, Object>> pendingFor(long memberId) {
    List<Map<String, Object>> out = new ArrayList<>();
    jdbc.sql("SELECT id, applied_at, points_used, status FROM redemptions WHERE member_id = :m AND status = 'PENDING' ORDER BY id DESC")
        .param("m", memberId).query((rs, n) -> {
          Map<String, Object> t = new LinkedHashMap<>();
          t.put("id", rs.getLong("id"));
          t.put("appliedAt", rs.getTimestamp("applied_at").toInstant().toString());
          t.put("pointsUsed", rs.getInt("points_used"));
          t.put("status", "未発送");
          t.put("items", items(rs.getLong("id")));
          return t;
        }).list().forEach(out::add);
    return out;
  }

  public List<Map<String, Object>> items(long redemptionId) {
    return jdbc.sql("SELECT item_code, units, card_no FROM redemption_items WHERE redemption_id = :r ORDER BY id").param("r", redemptionId)
        .query((rs, n) -> {
          Map<String, Object> i = new LinkedHashMap<>();
          i.put("item", GiftCatalog.byCode(rs.getString(1)).map(GiftCatalog.Item::key).orElse(rs.getString(1)));
          i.put("units", rs.getInt(2));
          i.put("cardNo", rs.getString(3) == null ? "" : rs.getString(3));
          return i;
        }).list();
  }
}
