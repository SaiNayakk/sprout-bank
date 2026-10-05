package app.sprout.bank.domain;

import app.sprout.bank.config.BankProperties;
import app.sprout.bank.config.BankProperties.Partner;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Delivers partner callbacks from the outbox, signed, until each is acknowledged with a 2xx: 1 s,
 * 2 s, 4 s ... up to a minute apart. A partner that was down gets every event when it comes back.
 * Also expires collect requests nobody answered.
 */
@Component
public class Callbacks {

    private static final Logger log = LoggerFactory.getLogger(Callbacks.class);

    private record Due(UUID id, String partner, String url, String body, int attempts) {}

    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final BankProperties props;
    private final Bank bank;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public Callbacks(JdbcClient db, TransactionTemplate tx, Clock clock, BankProperties props, Bank bank) {
        this.db = db;
        this.tx = tx;
        this.clock = clock;
        this.props = props;
        this.bank = bank;
    }

    @Scheduled(fixedDelayString = "${sprout.bank.expiry-check:5s}")
    public void expire() {
        try {
            int n = bank.expireDue();
            if (n > 0) {
                log.info("Expired {} collect request(s) nobody answered", n);
            }
        } catch (RuntimeException e) {
            log.warn("Couldn't expire requests this time: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${sprout.bank.delivery-check:1s}")
    public void deliver() {
        try {
            deliverDue();
        } catch (RuntimeException e) {
            log.warn("Couldn't deliver callbacks this time: {}", e.getMessage());
        }
    }

    /** Sends every callback that's due. Returns how many were acknowledged. */
    public int deliverDue() {
        List<Due> due = tx.execute(s -> db.sql("""
                        SELECT id, partner, callback_url, body, attempts FROM outbox
                        WHERE delivered_at IS NULL AND next_attempt_at <= ?
                        ORDER BY created_at LIMIT 20 FOR UPDATE SKIP LOCKED""")
                .param(Timestamp.from(clock.instant()))
                .query((rs, n) -> new Due(rs.getObject("id", UUID.class), rs.getString("partner"), rs.getString("callback_url"),
                        rs.getString("body"), rs.getInt("attempts")))
                .list());
        int delivered = 0;
        for (Due d : due) {
            String error = send(d);
            Instant now = clock.instant();
            if (error == null) {
                db.sql("UPDATE outbox SET delivered_at = ?, attempts = attempts + 1, last_error = NULL WHERE id = ?")
                        .params(Timestamp.from(now), d.id()).update();
                delivered++;
            } else {
                long backoff = Math.min(60, 1L << Math.min(d.attempts(), 6));
                db.sql("UPDATE outbox SET attempts = attempts + 1, next_attempt_at = ?, last_error = ? WHERE id = ?")
                        .params(Timestamp.from(now.plusSeconds(backoff)), error, d.id()).update();
                log.warn("Callback to {} failed (attempt {}): {}; retrying in {} s", d.partner(), d.attempts() + 1, error, backoff);
            }
        }
        return delivered;
    }

    private String send(Due d) {
        Partner partner = props.partners().stream().filter(p -> p.name().equals(d.partner())).findFirst().orElse(null);
        if (partner == null) {
            return "unknown partner " + d.partner();
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(d.url())).timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("X-Bank-Signature", "sha256=" + sign(partner.webhookSecret(), d.body()))
                    .POST(HttpRequest.BodyPublishers.ofString(d.body())).build();
            int status = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
            return status / 100 == 2 ? null : "HTTP " + status;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        }
    }

    /** HMAC-SHA256 of the body, hex: what the partner recomputes to know the bank sent it. */
    public static String sign(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
