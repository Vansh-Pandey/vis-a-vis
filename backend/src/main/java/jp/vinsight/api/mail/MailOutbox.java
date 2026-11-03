package jp.vinsight.api.mail;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import jp.vinsight.api.config.AppProperties;

/**
 * E-mail is written to mail_outbox in the same transaction as the change it reports and sent afterwards by {@link #send()}.
 * A failed send is retried, and nothing is mailed for a change that was rolled back.
 * Without SMTP settings (local development) messages stay in the table and only their recipient and subject are logged.
 */
@Component
public class MailOutbox {
  private static final Logger log = LoggerFactory.getLogger(MailOutbox.class);
  private final JdbcClient jdbc;
  private final ObjectProvider<JavaMailSender> sender;
  private final AppProperties props;
  private final Clock clock;
  private final String smtpHost;

  public MailOutbox(JdbcClient jdbc, ObjectProvider<JavaMailSender> sender, AppProperties props, Clock clock,
      @org.springframework.beans.factory.annotation.Value("${spring.mail.host:}") String smtpHost) {
    this.jdbc = jdbc; this.sender = sender; this.props = props; this.clock = clock; this.smtpHost = smtpHost;
  }

  public void enqueue(String to, String subject, String body, String kind) {
    jdbc.sql("INSERT INTO mail_outbox (to_addr, subject, body, kind) VALUES (:to, :subject, :body, :kind)")
        .param("to", to).param("subject", subject).param("body", body).param("kind", kind).update();
  }

  record Pending(long id, String to, String subject, String body, int attempts) {}

  @Scheduled(fixedDelay = 15_000, initialDelay = 10_000)
  public void send() {
    JavaMailSender mail = smtpHost == null || smtpHost.isBlank() ? null : sender.getIfAvailable();
    List<Pending> batch = jdbc.sql("SELECT id, to_addr, subject, body, attempts FROM mail_outbox WHERE sent_at IS NULL AND attempts < 8 ORDER BY id LIMIT 20")
        .query((rs, n) -> new Pending(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5))).list();
    for (Pending p : batch) {
      if (mail == null) {
        log.info("[mail not sent: no SMTP configured] #{} to={} subject={}", p.id(), mask(p.to()), p.subject());
        jdbc.sql("UPDATE mail_outbox SET attempts = 8, last_error = 'no SMTP configured' WHERE id = :id").param("id", p.id()).update();
        continue;
      }
      try {
        SimpleMailMessage m = new SimpleMailMessage();
        m.setFrom(props.mailFrom());
        m.setTo(p.to());
        m.setSubject(p.subject());
        m.setText(p.body());
        mail.send(m);
        jdbc.sql("UPDATE mail_outbox SET sent_at = :at, attempts = attempts + 1 WHERE id = :id").param("at", Timestamp.from(clock.instant())).param("id", p.id()).update();
      } catch (RuntimeException e) {
        String err = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
        jdbc.sql("UPDATE mail_outbox SET attempts = attempts + 1, last_error = :err WHERE id = :id")
            .param("err", err.length() > 500 ? err.substring(0, 500) : err).param("id", p.id()).update();
        log.warn("mail #{} failed: {}", p.id(), err);
      }
    }
  }

  static String mask(String email) {
    int at = email.indexOf('@');
    return at <= 1 ? "***" : email.charAt(0) + "***" + email.substring(at);
  }
}
