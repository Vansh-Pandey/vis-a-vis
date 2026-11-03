package jp.vinsight.api.security;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import jp.vinsight.api.common.ApiException;

/**
 * Slows down password guessing (Security Requirements #8).
 * Per account: 3 free failures in 15 minutes, then a wait that doubles from 2 s up to 5 min.
 * Per address: an address that fails on 10 different accounts in 15 minutes is blocked for 15 minutes (credential stuffing,
 * trying a list of known e-mail/password pairs).
 * Kept in memory: correct for the single staging/production instance; move to the database before running two instances.
 */
@Component
public class LoginThrottle {
  private static final long WINDOW_MS = 15 * 60_000L;
  private static final int FREE = 3;
  private static final long BASE_MS = 2_000L, MAX_MS = 5 * 60_000L;
  private static final int MAX_ACCOUNTS_PER_IP = 10;

  private record Failures(Deque<Long> times) {}
  private final Map<String, Failures> byAccount = new ConcurrentHashMap<>();
  private final Map<String, Map<String, Long>> accountsByIp = new ConcurrentHashMap<>();
  private final Clock clock;

  public LoginThrottle(Clock clock) { this.clock = clock; }

  public void assertAllowed(String realm, String account, String ip) {
    long now = clock.millis();
    Map<String, Long> accts = ip == null ? null : accountsByIp.get(realm + "|" + ip);
    if (accts != null) {
      accts.values().removeIf(t -> now - t > WINDOW_MS);
      if (accts.size() >= MAX_ACCOUNTS_PER_IP) throw tooMany(WINDOW_MS);
    }
    Failures f = byAccount.get(key(realm, account));
    if (f == null) return;
    synchronized (f) {
      f.times().removeIf(t -> now - t > WINDOW_MS);
      int n = f.times().size();
      if (n < FREE) return;
      long wait = Math.min(MAX_MS, BASE_MS << Math.min(20, n - FREE));
      long until = f.times().peekLast() + wait;
      if (now < until) throw tooMany(until - now);
    }
  }

  public void failed(String realm, String account, String ip) {
    long now = clock.millis();
    Failures f = byAccount.computeIfAbsent(key(realm, account), k -> new Failures(new ArrayDeque<>()));
    synchronized (f) { f.times().addLast(now); }
    if (ip != null) accountsByIp.computeIfAbsent(realm + "|" + ip, k -> new ConcurrentHashMap<>()).put(key(realm, account), now);
  }

  public void succeeded(String realm, String account) { byAccount.remove(key(realm, account)); }

  @Scheduled(fixedDelay = 300_000)
  void purge() {
    long now = clock.millis();
    byAccount.values().removeIf(f -> { synchronized (f) { f.times().removeIf(t -> now - t > WINDOW_MS); return f.times().isEmpty(); } });
    accountsByIp.values().forEach(m -> m.values().removeIf(t -> now - t > WINDOW_MS));
    accountsByIp.values().removeIf(Map::isEmpty);
  }

  private static String key(String realm, String account) { return realm + "|" + (account == null ? "" : account.strip().toLowerCase()); }

  private static ApiException tooMany(long waitMs) {
    long sec = Math.max(1, (waitMs + 999) / 1000);
    String wait = sec >= 60 ? (sec + 59) / 60 + "分" : sec + "秒";
    return new ApiException(HttpStatus.TOO_MANY_REQUESTS,
        "ログインの失敗が続いたため、一時的にログインできません。" + wait + "ほど待ってからお試しください。", Map.of(), "TOO_MANY_ATTEMPTS");
  }

  /** For tests. */
  public void reset() { byAccount.clear(); accountsByIp.clear(); }

  Set<String> keys() { return new HashSet<>(byAccount.keySet()); }
}
