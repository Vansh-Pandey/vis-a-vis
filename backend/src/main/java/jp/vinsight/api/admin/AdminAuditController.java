package jp.vinsight.api.admin;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import jp.vinsight.api.audit.AuditLog;

@RestController
@RequestMapping("/api/admin/audit-log")
public class AdminAuditController {
  private final AuditLog audit;
  public AdminAuditController(AuditLog audit) { this.audit = audit; }

  @GetMapping
  public Map<String, Object> list(@RequestParam(defaultValue = "100") int limit, @RequestParam(defaultValue = "0") long before) {
    return Map.of("items", audit.latest(limit, before));
  }
}
