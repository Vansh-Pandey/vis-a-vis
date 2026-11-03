package jp.vinsight.api.member;

import java.util.LinkedHashMap;
import java.util.Map;

/** What a member may see about their own account. Never includes the password hash or internal ids. */
public final class MemberView {
  private MemberView() {}

  public static Map<String, Object> of(Member m) {
    Map<String, Object> v = new LinkedHashMap<>();
    v.put("id", m.memberNo());
    v.put("email", m.email());
    v.put("name", nz(m.name()));
    v.put("postcode", nz(m.postcode()));
    v.put("prefecture", nz(m.prefecture()));
    v.put("city", nz(m.city()));
    v.put("street", nz(m.street()));
    v.put("tel", nz(m.tel()));
    v.put("birth", m.birth() == null ? "" : m.birth().toString());
    v.put("gender", nz(m.gender()));
    v.put("married", m.married() != null && m.married());
    v.put("children", m.children() == null ? 0 : m.children());
    v.put("points", m.pointBalance());
    v.put("registeredAt", m.registeredAt() == null ? null : m.registeredAt().toString());
    v.put("lastLoginAt", m.lastLoginAt() == null ? null : m.lastLoginAt().toString());
    v.put("prevLoginAt", m.prevLoginAt() == null ? null : m.prevLoginAt().toString());
    v.put("status", Member.statusLabel(m.status()));
    return v;
  }

  private static String nz(String s) { return s == null ? "" : s; }
}
