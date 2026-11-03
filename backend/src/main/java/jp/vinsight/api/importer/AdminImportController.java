package jp.vinsight.api.importer;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import jp.vinsight.api.audit.AuditLog;
import jp.vinsight.api.auth.CurrentUser;
import jp.vinsight.api.common.ApiException;
import jp.vinsight.api.common.Requests;

@RestController
public class AdminImportController {
  private final MemberCsvImporter importer;
  private final AuditLog audit;

  public AdminImportController(MemberCsvImporter importer, AuditLog audit) { this.importer = importer; this.audit = audit; }

  @PostMapping(value = "/api/admin/import/members", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public MemberCsvImporter.Report importMembers(@RequestParam("file") MultipartFile file, @RequestParam(defaultValue = "true") boolean dryRun,
      @AuthenticationPrincipal CurrentUser u, HttpServletRequest req) throws IOException {
    if (file.isEmpty()) throw ApiException.invalid(Map.of("file", "ファイルを選択してください。"));
    String name = file.getOriginalFilename() == null ? "members.csv" : file.getOriginalFilename().replaceAll("[\\r\\n\\\\/]", "_");
    var report = importer.run(name, file.getBytes(), dryRun, u.actor());
    audit.record(u.actor(), dryRun ? "モニターCSV取り込み（確認のみ）" : "モニターCSV取り込み", "batch#" + report.batchId(),
        String.format("%s 行数%d 追加%d 更新%d 変更なし%d エラー%d", name, report.rowsTotal(), report.inserted(), report.updated(), report.unchanged(), report.rejected()),
        Requests.clientIp(req));
    return report;
  }

  @GetMapping("/api/admin/import/batches")
  public Map<String, Object> batches() { return Map.of("items", importer.batches()); }

  @GetMapping("/api/admin/export/members.csv")
  public ResponseEntity<byte[]> export(@AuthenticationPrincipal CurrentUser u, HttpServletRequest req) {
    String csv = importer.export();
    long rows = csv.chars().filter(c -> c == '\n').count() - 1;
    audit.record(u.actor(), "CSV出力（モニター・個人情報）", null, rows + "件", Requests.clientIp(req));
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("monitors_" + LocalDate.now() + ".csv").build().toString())
        .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
        .body(csv.getBytes(StandardCharsets.UTF_8));
  }
}
