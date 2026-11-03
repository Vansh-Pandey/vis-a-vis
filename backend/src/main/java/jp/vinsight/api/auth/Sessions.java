package jp.vinsight.api.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import org.springframework.stereotype.Component;

/**
 * Who is signed in, kept on the server (Spring Session, MariaDB); the browser only holds a random session id.
 * Members and administrators are separate "realms" in the same session: signing out of one leaves the other.
 * Each realm stores the password stamp at sign-in, so changing a password ends every older session (see SessionAuthFilter).
 */
@Component
public class Sessions {
  public static final String MEMBER = "member", ADMIN = "admin";
  private final Clock clock;

  public Sessions(Clock clock) { this.clock = clock; }

  /** Starts a realm. The session id is changed first, so an id planted before sign-in is useless afterwards (session fixation). */
  public void signIn(HttpServletRequest req, String realm, long id, String passwordStamp) {
    HttpSession s = req.getSession(false);
    if (s == null) s = req.getSession(true);
    else req.changeSessionId();
    s = req.getSession(false);
    s.setAttribute(realm + ".id", id);
    s.setAttribute(realm + ".stamp", passwordStamp);
    s.setAttribute(realm + ".seen", clock.millis());
    s.setAttribute(realm + ".since", clock.millis());
  }

  public void signOut(HttpServletRequest req, String realm) {
    HttpSession s = req.getSession(false);
    if (s == null) return;
    for (String k : new String[] {".id", ".stamp", ".seen", ".since", ".pending"}) s.removeAttribute(realm + k);
    if (s.getAttribute(MEMBER + ".id") == null && s.getAttribute(ADMIN + ".id") == null) s.invalidate();
  }

  /** Signed-in id for the realm, or null. Does not create a session. */
  public static Long id(HttpServletRequest req, String realm) {
    HttpSession s = req.getSession(false);
    return s == null ? null : (Long) s.getAttribute(realm + ".id");
  }

  /** Admin sign-in waiting for its two-step code: password already checked, nothing else allowed yet. */
  public void setPending(HttpServletRequest req, long adminId) {
    HttpSession s = req.getSession(true);
    s.setAttribute(ADMIN + ".pending", adminId + ":" + clock.millis());
  }

  public Long pending(HttpServletRequest req, long maxAgeMs) {
    HttpSession s = req.getSession(false);
    if (s == null || s.getAttribute(ADMIN + ".pending") == null) return null;
    String[] p = ((String) s.getAttribute(ADMIN + ".pending")).split(":");
    if (clock.millis() - Long.parseLong(p[1]) > maxAgeMs) { s.removeAttribute(ADMIN + ".pending"); return null; }
    return Long.parseLong(p[0]);
  }

  public void clearPending(HttpServletRequest req) {
    HttpSession s = req.getSession(false);
    if (s != null) s.removeAttribute(ADMIN + ".pending");
  }
}
