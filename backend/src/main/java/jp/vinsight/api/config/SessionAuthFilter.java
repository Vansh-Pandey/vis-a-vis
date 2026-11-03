package jp.vinsight.api.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import jp.vinsight.api.admin.AdminRepository;
import jp.vinsight.api.admin.AdminUser;
import jp.vinsight.api.auth.CurrentUser;
import jp.vinsight.api.auth.Sessions;
import jp.vinsight.api.member.Member;
import jp.vinsight.api.member.MemberRepository;

/**
 * Rebuilds who is signed in on every request from the server-side session, and drops a realm when
 * it has been idle too long (members 30 min, admins 15 min), the account is no longer active, or the password
 * (or two-step setting) changed since sign-in.
 */
public class SessionAuthFilter extends OncePerRequestFilter {
  private final MemberRepository members;
  private final AdminRepository admins;
  private final Clock clock;
  private final long memberIdleMs, adminIdleMs;

  public SessionAuthFilter(MemberRepository members, AdminRepository admins, Clock clock, AppProperties props) {
    this.members = members; this.admins = admins; this.clock = clock;
    this.memberIdleMs = props.member().sessionIdleMinutes() * 60_000L;
    this.adminIdleMs = props.admin().sessionIdleMinutes() * 60_000L;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
    HttpSession s = req.getSession(false);
    Long memberId = null, adminId = null;
    String role = null, loginId = null;
    if (s != null) {
      long now = clock.millis();
      Long mid = (Long) s.getAttribute(Sessions.MEMBER + ".id");
      if (mid != null) {
        Member m = fresh(s, Sessions.MEMBER, now, memberIdleMs) ? members.findById(mid).orElse(null) : null;
        if (m != null && m.active() && !m.needsPasswordReset() && m.passwordStamp().equals(s.getAttribute(Sessions.MEMBER + ".stamp"))) {
          memberId = mid;
          s.setAttribute(Sessions.MEMBER + ".seen", now);
        } else drop(s, Sessions.MEMBER);
      }
      Long aid = (Long) s.getAttribute(Sessions.ADMIN + ".id");
      if (aid != null) {
        AdminUser a = fresh(s, Sessions.ADMIN, now, adminIdleMs) ? admins.findById(aid).orElse(null) : null;
        if (a != null && !a.disabled() && a.passwordStamp().equals(s.getAttribute(Sessions.ADMIN + ".stamp"))) {
          adminId = aid; role = a.role(); loginId = a.loginId();
          s.setAttribute(Sessions.ADMIN + ".seen", now);
        } else drop(s, Sessions.ADMIN);
      }
    }
    if (memberId != null || adminId != null) {
      List<SimpleGrantedAuthority> auth = new ArrayList<>();
      if (memberId != null) auth.add(new SimpleGrantedAuthority("ROLE_MEMBER"));
      if (adminId != null) auth.add(new SimpleGrantedAuthority("ROLE_" + role));
      var principal = new CurrentUser(memberId, adminId, role, loginId);
      var token = new UsernamePasswordAuthenticationToken(principal, null, auth);
      var ctx = SecurityContextHolder.createEmptyContext();
      ctx.setAuthentication(token);
      SecurityContextHolder.setContext(ctx);
    }
    // SecurityContextHolderFilter clears the context after the response; clearing it here would hide the caller
    // from ExceptionTranslationFilter, which must still see an anonymous user to answer 401.
    chain.doFilter(req, res);
  }

  private static boolean fresh(HttpSession s, String realm, long now, long idleMs) {
    Long seen = (Long) s.getAttribute(realm + ".seen");
    return seen != null && now - seen <= idleMs;
  }

  private static void drop(HttpSession s, String realm) {
    for (String k : new String[] {".id", ".stamp", ".seen", ".since"}) s.removeAttribute(realm + k);
  }
}
