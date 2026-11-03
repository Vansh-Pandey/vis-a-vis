package jp.vinsight.api.importer;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jp.vinsight.api.common.ApiException;
import jp.vinsight.api.member.Member;
import jp.vinsight.api.member.MemberRepository;
import jp.vinsight.api.points.PointLedger;
import jp.vinsight.api.security.SecretBox;

/**
 * Member import from CSV (meeting 2 Nov: start with dummy data, check NULLs, duplicates, overwrites and repeated imports,
 * and that uploaded, processed and exported counts agree).
 *
 * Rules:
 *  - monitor_no is the member number and the key: an existing number is updated, a new one is inserted.
 *    The old site's number is kept as is, so point history stays traceable.
 *  - Empty cells (or the text NULL) leave the existing value unchanged on update and store nothing on insert.
 *  - A file with a password column is refused as a whole: passwords are never imported. Imported members are
 *    LEGACY_PENDING and must set a new password through an e-mailed link.
 *  - A different points value is booked as a ledger movement (LEGACY_BALANCE on insert, ADJUSTMENT on update), never overwritten.
 *  - Dry run checks everything and reports the counts without changing any member.
 */
@Service
public class MemberCsvImporter {
  public static final List<String> COLUMNS = List.of("monitor_no", "email", "name", "birth", "gender", "postcode", "prefecture", "city",
      "street", "tel", "points", "registered_at", "status", "remark");
  private static final Set<String> REQUIRED = Set.of("monitor_no", "email");
  private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
  private static final int MAX_ROWS = 50_000;

  private final JdbcClient jdbc;
  private final MemberRepository members;
  private final PointLedger ledger;
  private final Clock clock;

  public MemberCsvImporter(JdbcClient jdbc, MemberRepository members, PointLedger ledger, Clock clock) {
    this.jdbc = jdbc; this.members = members; this.ledger = ledger; this.clock = clock;
  }

  public record Rejection(int row, String field, String message) {}
  public record Report(long batchId, boolean dryRun, int rowsTotal, int inserted, int updated, int unchanged, int rejected,
      List<Rejection> rejections) {}

  record Row(int rowNo, Map<String, String> v) {}

  @Transactional
  public Report run(String fileName, byte[] bytes, boolean dryRun, String actor) {
    List<List<String>> rows;
    try { rows = Csv.parse(Csv.decode(bytes), MAX_ROWS + 1); }
    catch (IllegalArgumentException e) { throw ApiException.invalid(Map.of("file", e.getMessage())); }
    if (rows.isEmpty()) throw ApiException.invalid(Map.of("file", "CSVが空です。"));
    List<String> header = rows.get(0).stream().map(h -> h.strip().toLowerCase(Locale.ROOT)).toList();
    if (header.stream().anyMatch(h -> h.contains("pass") || h.equals("pw") || h.contains("パスワード")))
      throw ApiException.invalid(Map.of("file", "パスワードの列を含むファイルは取り込めません。旧サイトのパスワードは新サイトに移しません。"));
    List<String> unknown = header.stream().filter(h -> !COLUMNS.contains(h)).toList();
    if (!unknown.isEmpty()) throw ApiException.invalid(Map.of("file", "不明な列があります：" + String.join(", ", unknown)));
    for (String r : REQUIRED) if (!header.contains(r)) throw ApiException.invalid(Map.of("file", "必須の列がありません：" + r));
    if (new HashSet<>(header).size() != header.size()) throw ApiException.invalid(Map.of("file", "同じ名前の列が複数あります。"));

    List<Rejection> rejections = new ArrayList<>();
    Set<Integer> seenNo = new HashSet<>();
    Set<String> seenEmail = new HashSet<>();
    int inserted = 0, updated = 0, unchanged = 0;
    int dataRows = rows.size() - 1;
    if (dataRows > MAX_ROWS) throw ApiException.invalid(Map.of("file", "行数が多すぎます（" + MAX_ROWS + "行まで）。"));
    long batchId = jdbc.sql("""
        INSERT INTO import_batches (kind, file_name, file_sha256, dry_run, rows_total, inserted, updated, unchanged, rejected, created_by)
        VALUES ('MEMBERS_CSV', :f, :sha, :dry, :total, 0, 0, 0, 0, :by)""")
        .param("f", fileName.length() > 200 ? fileName.substring(0, 200) : fileName)
        .param("sha", SecretBox.sha256Hex(new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1)))
        .param("dry", dryRun).param("total", dataRows).param("by", actor).update() == 1
        ? jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single() : -1;

    for (int i = 1; i < rows.size(); i++) {
      int rowNo = i + 1; // line number as Excel shows it (header is line 1)
      List<String> cells = rows.get(i);
      if (cells.size() != header.size()) { rejections.add(new Rejection(rowNo, null, "列の数が見出しと合いません（" + cells.size() + "列）。")); continue; }
      Map<String, String> v = new HashMap<>();
      for (int c = 0; c < header.size(); c++) {
        String s = cells.get(c).strip();
        v.put(header.get(c), s.isEmpty() || s.equalsIgnoreCase("NULL") ? null : s);
      }
      List<Rejection> rowErrors = new ArrayList<>();
      Parsed p = parse(rowNo, v, rowErrors);
      if (p != null && !seenNo.add(p.memberNo)) rowErrors.add(new Rejection(rowNo, "monitor_no", "同じモニター番号がファイル内で重複しています。"));
      if (p != null && p.email != null && !seenEmail.add(p.email.toLowerCase(Locale.ROOT))) rowErrors.add(new Rejection(rowNo, "email", "同じメールアドレスがファイル内で重複しています。"));
      Member existing = p == null ? null : members.findByMemberNo(p.memberNo).orElse(null);
      if (p != null && p.email != null && members.emailTaken(p.email, existing == null ? null : existing.id()))
        rowErrors.add(new Rejection(rowNo, "email", "このメールアドレスは別のモニターが使用しています。"));
      if (p != null && existing == null && p.memberNo >= 10001) rowErrors.add(new Rejection(rowNo, "monitor_no", "10001以上の番号は新サイトの登録用のため、取り込めません。"));
      if (!rowErrors.isEmpty()) { rejections.addAll(rowErrors); continue; }

      if (existing == null) {
        if (!dryRun) insert(p, batchId, actor);
        inserted++;
      } else if (differs(existing, p)) {
        if (!dryRun) update(existing, p, batchId, actor);
        updated++;
      } else unchanged++;
    }
    for (Rejection r : rejections) {
      jdbc.sql("INSERT INTO import_rejections (batch_id, row_no, field, message) VALUES (:b, :r, :f, :m)")
          .param("b", batchId).param("r", r.row()).param("f", r.field()).param("m", r.message()).update();
    }
    jdbc.sql("UPDATE import_batches SET inserted = :i, updated = :u, unchanged = :n, rejected = :r WHERE id = :id")
        .param("i", inserted).param("u", updated).param("n", unchanged).param("r", rejections.stream().map(Rejection::row).distinct().count())
        .param("id", batchId).update();
    int rejectedRows = (int) rejections.stream().map(Rejection::row).distinct().count();
    return new Report(batchId, dryRun, dataRows, inserted, updated, unchanged, rejectedRows, rejections.size() > 500 ? rejections.subList(0, 500) : rejections);
  }

  static final class Parsed {
    int memberNo; String email, name, gender, postcode, prefecture, city, street, tel, status, remark;
    LocalDate birth; Integer points; Instant registeredAt;
  }

  private Parsed parse(int rowNo, Map<String, String> v, List<Rejection> errors) {
    Parsed p = new Parsed();
    String no = v.get("monitor_no");
    if (no == null || !no.matches("\\d{1,7}") || Integer.parseInt(no) == 0) { errors.add(new Rejection(rowNo, "monitor_no", "モニター番号は1〜9999999の数字で入力してください。")); return null; }
    p.memberNo = Integer.parseInt(no);
    p.email = v.get("email");
    if (p.email == null || p.email.length() > 254 || !p.email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) errors.add(new Rejection(rowNo, "email", "メールアドレスが正しくありません。"));
    p.name = limit(v.get("name"), 100, "name", rowNo, errors);
    p.gender = v.get("gender");
    if (p.gender != null && !List.of("男性", "女性", "その他", "回答しない").contains(p.gender)) errors.add(new Rejection(rowNo, "gender", "性別は 男性/女性/その他/回答しない のいずれかです。"));
    p.postcode = v.get("postcode");
    if (p.postcode != null && !p.postcode.matches("\\d{3}-?\\d{4}")) errors.add(new Rejection(rowNo, "postcode", "郵便番号が正しくありません。"));
    p.prefecture = limit(v.get("prefecture"), 10, "prefecture", rowNo, errors);
    p.city = limit(v.get("city"), 200, "city", rowNo, errors);
    p.street = limit(v.get("street"), 200, "street", rowNo, errors);
    p.tel = v.get("tel");
    if (p.tel != null && !p.tel.matches("[0-9-]{10,13}")) errors.add(new Rejection(rowNo, "tel", "電話番号が正しくありません。"));
    p.remark = limit(v.get("remark"), 2000, "remark", rowNo, errors);
    if (v.get("birth") != null) {
      try { p.birth = LocalDate.parse(v.get("birth").replace('/', '-')); if (p.birth.isAfter(LocalDate.now(clock)) || p.birth.getYear() < 1900) throw new DateTimeParseException("range", v.get("birth"), 0); }
      catch (DateTimeParseException e) { errors.add(new Rejection(rowNo, "birth", "生年月日はYYYY-MM-DDで入力してください。")); }
    }
    if (v.get("points") != null) {
      if (!v.get("points").matches("\\d{1,7}")) errors.add(new Rejection(rowNo, "points", "ポイントは0以上の整数で入力してください。"));
      else p.points = Integer.parseInt(v.get("points"));
    }
    if (v.get("registered_at") != null) {
      String s = v.get("registered_at").replace('/', '-');
      try {
        p.registeredAt = (s.length() <= 10 ? LocalDate.parse(s).atStartOfDay() : LocalDateTime.parse(s.replace(' ', 'T'))).atZone(JST).toInstant();
      } catch (DateTimeParseException e) { errors.add(new Rejection(rowNo, "registered_at", "登録日時はYYYY-MM-DD HH:MM:SSで入力してください。")); }
    }
    String st = v.get("status");
    p.status = st == null ? null : switch (st) {
      case "有効", "ACTIVE", "1" -> "ACTIVE";
      case "退会", "WITHDRAWN", "0" -> "WITHDRAWN";
      case "停止", "SUSPENDED" -> "SUSPENDED";
      default -> { errors.add(new Rejection(rowNo, "status", "状態は 有効/退会/停止 のいずれかです。")); yield null; }
    };
    return p;
  }

  private static String limit(String s, int max, String field, int rowNo, List<Rejection> errors) {
    if (s != null && s.length() > max) errors.add(new Rejection(rowNo, field, max + "文字以内で入力してください。"));
    return s;
  }

  private static boolean differs(Member m, Parsed p) {
    return (p.email != null && !p.email.equals(m.email())) || ne(p.name, m.name()) || ne(p.gender, m.gender()) || ne(p.postcode, m.postcode())
        || ne(p.prefecture, m.prefecture()) || ne(p.city, m.city()) || ne(p.street, m.street()) || ne(p.tel, m.tel())
        || (p.birth != null && !p.birth.equals(m.birth())) || (p.points != null && p.points != m.pointBalance())
        || ne(p.status, m.status()) || ne(p.remark, m.remark());
  }

  private static boolean ne(String incoming, String current) { return incoming != null && !Objects.equals(incoming, current); }

  private void insert(Parsed p, long batchId, String actor) {
    Instant now = clock.instant();
    jdbc.sql("""
        INSERT INTO members (member_no, legacy_monitor_pk, email, migration_status, status, name, postcode, prefecture, city, street, tel,
                             birth, gender, registered_at, remark)
        VALUES (:no, :no, :email, 'LEGACY_PENDING', :status, :name, :pc, :pref, :city, :street, :tel, :birth, :gender, :reg, :remark)""")
        .param("no", p.memberNo).param("email", p.email).param("status", p.status == null ? "ACTIVE" : p.status).param("name", p.name)
        .param("pc", p.postcode).param("pref", p.prefecture).param("city", p.city).param("street", p.street).param("tel", p.tel)
        .param("birth", p.birth).param("gender", p.gender).param("reg", Timestamp.from(p.registeredAt == null ? now : p.registeredAt))
        .param("remark", p.remark).update();
    if (p.points != null && p.points > 0) {
      Member m = members.findByMemberNo(p.memberNo).orElseThrow();
      ledger.post(m.id(), 0, p.points, "LEGACY_BALANCE", "import:" + batchId, null, "旧サイトからの引き継ぎ", actor);
    }
  }

  private void update(Member m, Parsed p, long batchId, String actor) {
    jdbc.sql("""
        UPDATE members SET email = COALESCE(:email, email), name = COALESCE(:name, name), postcode = COALESCE(:pc, postcode),
          prefecture = COALESCE(:pref, prefecture), city = COALESCE(:city, city), street = COALESCE(:street, street),
          tel = COALESCE(:tel, tel), birth = COALESCE(:birth, birth), gender = COALESCE(:gender, gender),
          status = COALESCE(:status, status), remark = COALESCE(:remark, remark)
        WHERE id = :id""")
        .param("email", p.email).param("name", p.name).param("pc", p.postcode).param("pref", p.prefecture).param("city", p.city)
        .param("street", p.street).param("tel", p.tel).param("birth", p.birth).param("gender", p.gender).param("status", p.status)
        .param("remark", p.remark).param("id", m.id()).update();
    if (p.points != null && p.points != m.pointBalance()) {
      Member locked = members.findByIdForUpdate(m.id()).orElseThrow();
      ledger.post(locked.id(), locked.pointBalance(), p.points - locked.pointBalance(), "ADJUSTMENT", "import:" + batchId, null, "CSV取り込みによる残高の修正", actor);
    }
  }

  public String export() {
    StringBuilder sb = new StringBuilder("﻿");
    sb.append(String.join(",", COLUMNS)).append("\r\n");
    DateTimeFormatter f = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(JST);
    Map<String, String> statusOut = new LinkedHashMap<>(Map.of("ACTIVE", "有効", "WITHDRAWN", "退会", "SUSPENDED", "停止", "PROVISIONAL", "仮登録"));
    for (Member m : members.all()) {
      List<Object> cells = List.of(m.memberNo(), m.email(), nz(m.name()), m.birth() == null ? "" : m.birth().toString(), nz(m.gender()),
          nz(m.postcode()), nz(m.prefecture()), nz(m.city()), nz(m.street()), nz(m.tel()), m.pointBalance(),
          m.registeredAt() == null ? "" : f.format(m.registeredAt()), statusOut.getOrDefault(m.status(), m.status()), nz(m.remark()));
      sb.append(String.join(",", cells.stream().map(Csv::cell).toList())).append("\r\n");
    }
    return sb.toString();
  }

  private static String nz(String s) { return s == null ? "" : s; }

  public List<Map<String, Object>> batches() {
    return jdbc.sql("SELECT id, kind, file_name, dry_run, rows_total, inserted, updated, unchanged, rejected, created_by, created_at FROM import_batches ORDER BY id DESC LIMIT 50")
        .query().listOfRows();
  }
}
