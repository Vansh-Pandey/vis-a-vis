package jp.vinsight.api.points;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jp.vinsight.api.auth.CurrentUser;
import jp.vinsight.api.common.ApiException;
import jp.vinsight.api.common.Requests;
import jp.vinsight.api.member.Member;
import jp.vinsight.api.member.MemberRepository;
import jp.vinsight.api.member.MemberView;
import jp.vinsight.api.security.Captcha;

/** My Page: balance, expiry and redemption. Reopening scope agreed on 26 Oct / 2 Nov. */
@RestController
@RequestMapping("/api/mypage")
public class MypageController {
  private static final Duration SOON = Duration.ofDays(60);
  private final MemberRepository members;
  private final PointLedger ledger;
  private final RedemptionService redemptions;
  private final Captcha captcha;
  private final Clock clock;
  private final int expiryMonths;

  public MypageController(MemberRepository members, PointLedger ledger, RedemptionService redemptions, Captcha captcha, Clock clock,
      @Value("${vinsight.points.expiry-months:12}") int expiryMonths) {
    this.members = members; this.ledger = ledger; this.redemptions = redemptions; this.captcha = captcha; this.clock = clock;
    this.expiryMonths = expiryMonths;
  }

  private Member me(CurrentUser u) {
    return members.findById(u.memberId()).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "ログインが必要です。"));
  }

  @GetMapping
  public Map<String, Object> home(@AuthenticationPrincipal CurrentUser u) {
    Member m = me(u);
    Instant now = clock.instant();
    List<PointLedger.Lot> lots = ledger.lots(m.id(), now);
    List<Map<String, Object>> lotViews = new ArrayList<>(), soon = new ArrayList<>();
    int soonTotal = 0;
    Map<String, Object> next = null;
    for (PointLedger.Lot l : lots) {
      Map<String, Object> v = new LinkedHashMap<>();
      v.put("points", l.points());
      v.put("expiresAt", l.expiresAt() == null ? null : l.expiresAt().toString());
      lotViews.add(v);
      if (l.expiresAt() != null) {
        if (next == null) next = v;
        if (Duration.between(now, l.expiresAt()).compareTo(SOON) <= 0) { soon.add(v); soonTotal += l.points(); }
      }
    }
    Map<String, Object> expiry = new LinkedHashMap<>();
    expiry.put("months", expiryMonths);
    expiry.put("lots", lotViews);
    expiry.put("soon", soon);
    expiry.put("soonTotal", soonTotal);
    expiry.put("nextExpiry", next);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("monitor", MemberView.of(m));
    out.put("news", List.of());
    out.put("surveys", List.of());
    out.put("trades", redemptions.pendingFor(m.id()));
    out.put("expiry", expiry);
    out.put("active", true);
    return out;
  }

  @GetMapping("/points/history")
  public Map<String, Object> history(@AuthenticationPrincipal CurrentUser u) {
    Member m = me(u);
    return Map.of("balance", m.pointBalance(), "items", ledger.history(m.id(), 200));
  }

  @GetMapping("/trade")
  public Map<String, Object> tradeForm(@AuthenticationPrincipal CurrentUser u) {
    List<Map<String, String>> items = GiftCatalog.ITEMS.stream().map(i -> Map.of("key", i.key(), "note", i.note())).toList();
    return Map.of("monitor", MemberView.of(me(u)), "items", items, "unit", GiftCatalog.POINTS_PER_UNIT);
  }

  public record TradeItem(String item, Integer units, String cardNo) {}
  public record TradeRequest(List<TradeItem> items, String name, String postcode, String prefecture, String address1, String address2,
      String tel, String requestId, String captchaId, String captcha) {}

  @PostMapping("/trade")
  public Map<String, Object> trade(@AuthenticationPrincipal CurrentUser u, @RequestBody TradeRequest body, HttpServletRequest req) {
    if (body.requestId() == null || !body.requestId().matches("[A-Za-z0-9_-]{16,64}"))
      throw new ApiException(HttpStatus.BAD_REQUEST, "画面を再読み込みしてから、もう一度お申し込みください。");
    var again = redemptions.existing(u.memberId(), body.requestId());
    if (again.isPresent()) return Map.of("ok", true, "id", again.get().id(), "used", again.get().used(), "balance", again.get().balance());
    Map<String, String> errors = new LinkedHashMap<>();
    List<RedemptionService.Line> lines = new ArrayList<>();
    if (body.items() != null) {
      for (TradeItem t : body.items()) {
        if (t == null || t.units() == null || t.units() <= 0) continue;
        var item = GiftCatalog.byKey(t.item()).orElse(null);
        if (item == null || t.units() > GiftCatalog.MAX_UNITS_PER_ITEM || lines.stream().anyMatch(l -> l.item() == item)) {
          errors.put("items", "交換する商品の内容が正しくありません。");
          continue;
        }
        String card = null;
        if (item.code().equals("TAMARUN")) {
          card = Requests.trim(t.cardNo());
          if (!card.matches("0526\\d{9}")) errors.put("cardNo", "たまルンカード番号は「0526」から始まる13桁の半角数字で入力してください。");
        }
        lines.add(new RedemptionService.Line(item, t.units(), card));
      }
    }
    if (lines.isEmpty()) errors.putIfAbsent("items", "交換する商品の口数を選択してください。");
    var ship = new RedemptionService.Shipping(Requests.trim(body.name()), Requests.trim(body.postcode()), Requests.trim(body.prefecture()),
        Requests.trim(body.address1()), Requests.trim(body.address2()), Requests.trim(body.tel()));
    if (ship.name().isEmpty() || ship.name().length() > 100) errors.put("name", "氏名を入力してください。");
    if (!ship.postcode().matches("\\d{3}-?\\d{4}")) errors.put("postcode", "郵便番号を正しく入力してください（例：700-0001）。");
    if (ship.prefecture().isEmpty() || ship.prefecture().length() > 10) errors.put("prefecture", "都道府県を選択してください。");
    if (ship.city().isEmpty() || ship.city().length() > 200) errors.put("city", "市区町村を入力してください。");
    if (ship.street().length() > 200) errors.put("street", "住所が長すぎます。");
    if (!ship.tel().matches("0\\d{1,4}-?\\d{1,4}-?\\d{3,4}")) errors.put("tel", "電話番号を正しく入力してください（例：090-1234-5678）。");
    if (!errors.isEmpty()) throw ApiException.invalid(errors);
    if (!captcha.check(body.captchaId(), body.captcha())) throw ApiException.invalid(Map.of("captcha", "画像認証の文字が正しくありません。"));
    var r = redemptions.apply(u.memberId(), body.requestId(), lines, ship, Requests.clientIp(req));
    return Map.of("ok", true, "id", r.id(), "used", r.used(), "balance", r.balance());
  }
}
