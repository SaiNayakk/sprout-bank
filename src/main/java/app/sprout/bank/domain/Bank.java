package app.sprout.bank.domain;

import app.sprout.bank.config.BankProperties;
import app.sprout.bank.config.BankProperties.Partner;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The bank's rules: accounts, UPI PINs, collect requests and payouts. Every change of money moves it
 * between two accounts in one transaction, and every outcome a partner must hear about is written to
 * the outbox in that same transaction.
 */
@Service
public class Bank {

    public record Account(String vpa, UUID userId, String holderName, long balance, Instant openedAt) {}

    public record Request(UUID id, String partner, String reference, String payerVpa, String payeeVpa, String payeeName,
                          long amount, String note, String status, Instant createdAt, Instant expiresAt, Instant decidedAt) {}

    public record Payout(UUID id, String payeeVpa, long amount, String reference, String status, Instant createdAt) {}

    public record Txn(UUID id, long amount, String direction, String description, String counterparty, String reference, long balanceAfter,
                      Instant at) {}

    /** Partner calls return whether they created something new (201) or found it from before (200). */
    public record Created<T>(T value, boolean created) {}

    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final BankProperties props;
    private final BCryptPasswordEncoder bcrypt;
    private final ObjectMapper json;

    public Bank(JdbcClient db, TransactionTemplate tx, Clock clock, BankProperties props, ObjectMapper json) {
        this.db = db;
        this.tx = tx;
        this.clock = clock;
        this.props = props;
        this.bcrypt = new BCryptPasswordEncoder(props.bcryptStrength());
        this.json = json;
    }

    // ── partners ─────────────────────────────────────────────────────────────

    /** Each partner business has an account here, created once with its opening balance (usually none). */
    public void ensurePartnerAccounts() {
        for (Partner p : props.partners()) {
            long opening = p.openingBalance() == null ? 0 : Money.paise(p.openingBalance());
            db.sql("INSERT INTO accounts (vpa, partner, holder_name, balance_paise, opened_at) VALUES (?, ?, ?, ?, ?) "
                            + "ON CONFLICT (vpa) DO NOTHING")
                    .params(p.vpa(), p.name(), p.displayName(), opening, ts(clock.instant())).update();
        }
    }

    public Partner partner(String key) {
        if (key != null) {
            for (Partner p : props.partners()) {
                if (java.security.MessageDigest.isEqual(p.key().getBytes(), key.getBytes())) {
                    return p;
                }
            }
        }
        throw new ApiException(ErrorCode.INVALID_PARTNER_KEY, "Send a valid X-Partner-Key.");
    }

    // ── customers ────────────────────────────────────────────────────────────

    public Account open(UUID userId, String holderName, String pin) {
        String name = holderName == null ? "" : holderName.trim();
        if (name.length() < 2 || name.length() > 100) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Give the account holder's name (2 to 100 characters).");
        }
        checkPin(pin);
        if (account(userId).isPresent()) {
            throw new ApiException(ErrorCode.ACCOUNT_EXISTS, "You already have a Sprout Bank account.");
        }
        long opening = Money.paise(props.openingBalance());
        Instant now = clock.instant();
        String vpa = freeVpa(name);
        String hash = bcrypt.encode(pin);
        try {
            tx.executeWithoutResult(s -> {
                db.sql("INSERT INTO accounts (vpa, user_id, holder_name, balance_paise, pin_hash, opened_at) VALUES (?, ?, ?, ?, ?, ?)")
                        .params(vpa, userId, name, opening, hash, ts(now)).update();
                record(vpa, "IN", opening, "Opening balance (not real money)", null, null, opening, now);
            });
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ApiException(ErrorCode.ACCOUNT_EXISTS, "You already have a Sprout Bank account.");
        }
        return account(userId).orElseThrow();
    }

    /** 4 or 6 digits, not all the same and not a run like 1234 or 654321. */
    static void checkPin(String pin) {
        if (pin == null || !pin.matches("^([0-9]{4}|[0-9]{6})$")) {
            throw new ApiException(ErrorCode.WEAK_PIN, "A UPI PIN is 4 or 6 digits.");
        }
        boolean same = pin.chars().distinct().count() == 1;
        boolean up = true;
        boolean down = true;
        for (int i = 1; i < pin.length(); i++) {
            up &= pin.charAt(i) - pin.charAt(i - 1) == 1;
            down &= pin.charAt(i) - pin.charAt(i - 1) == -1;
        }
        if (same || up || down) {
            throw new ApiException(ErrorCode.WEAK_PIN, "That PIN is too easy to guess. Avoid repeated digits and runs like 1234.");
        }
    }

    private String freeVpa(String holderName) {
        String base = holderName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", ".").replaceAll("^\\.|\\.$", "");
        if (base.isEmpty()) {
            base = "customer";
        }
        if (base.length() > 30) {
            base = base.substring(0, 30);
        }
        for (int n = 0; ; n++) {
            String vpa = base + (n == 0 ? "" : String.valueOf(n)) + "@sproutbank";
            boolean taken = db.sql("SELECT 1 FROM accounts WHERE vpa = ?").param(vpa).query(Integer.class).optional().isPresent();
            if (!taken) {
                return vpa;
            }
        }
    }

    public Optional<Account> account(UUID userId) {
        return db.sql("SELECT vpa, user_id, holder_name, balance_paise, opened_at FROM accounts WHERE user_id = ?")
                .param(userId).query(Bank::account).optional();
    }

    public Account mine(UUID userId) {
        return account(userId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Open a Sprout Bank account first."));
    }

    public Optional<Account> byVpa(String vpa) {
        return db.sql("SELECT vpa, user_id, holder_name, balance_paise, opened_at FROM accounts WHERE vpa = ?")
                .param(vpa == null ? "" : vpa.trim().toLowerCase(Locale.ROOT)).query(Bank::account).optional();
    }

    public List<Request> requests(UUID userId, String status) {
        Account me = mine(userId);
        return db.sql(REQUEST_SQL + " WHERE r.payer_vpa = ? AND (? IS NULL OR r.status = ?) ORDER BY r.created_at DESC LIMIT 100")
                .params(me.vpa(), status, status).query(Bank::request).list();
    }

    public List<Txn> transactions(UUID userId) {
        return statement(mine(userId).vpa(), null);
    }

    /** A partner's own statement, newest first; with a reference, only the movements that carried it. */
    public List<Txn> partnerTransactions(Partner partner, String reference) {
        return statement(partner.vpa(), reference);
    }

    private List<Txn> statement(String vpa, String reference) {
        return db.sql("SELECT id, direction, amount_paise, description, counterparty, reference, balance_after_paise, at FROM transactions "
                        + "WHERE vpa = ? AND (CAST(? AS text) IS NULL OR reference = ?) ORDER BY at DESC, id DESC LIMIT 100")
                .params(vpa, reference, reference)
                .query((rs, n) -> new Txn(rs.getObject("id", UUID.class), rs.getLong("amount_paise"), rs.getString("direction"),
                        rs.getString("description"), rs.getString("counterparty"), rs.getString("reference"),
                        rs.getLong("balance_after_paise"), rs.getTimestamp("at").toInstant()))
                .list();
    }

    /** Approve a collect request with the UPI PIN: money moves from the customer to the partner. */
    public Request approve(UUID userId, UUID requestId, String pin) {
        Instant now = clock.instant();
        // wrong PINs must be counted even though the approval fails, so the outcome is decided inside
        // the transaction and the error raised after it commits
        ApiException[] refusal = new ApiException[1];
        Request result = tx.execute(s -> {
            Account me = mine(userId);
            Request r = lockRequest(requestId, me.vpa());
            if (!r.status().equals("PENDING")) {
                throw notPending(r);
            }
            if (!r.expiresAt().isAfter(now)) {
                expire(r, now);
                refusal[0] = notPending(new Request(r.id(), r.partner(), r.reference(), r.payerVpa(), r.payeeVpa(), r.payeeName(),
                        r.amount(), r.note(), "EXPIRED", r.createdAt(), r.expiresAt(), now));
                return null;
            }
            var pinRow = db.sql("SELECT pin_hash, pin_failures, pin_locked_until, balance_paise FROM accounts WHERE vpa = ? FOR UPDATE")
                    .param(me.vpa())
                    .query((rs, n) -> new Object[] {rs.getString(1), rs.getInt(2), rs.getTimestamp(3), rs.getLong(4)}).single();
            Timestamp lockedUntil = (Timestamp) pinRow[2];
            if (lockedUntil != null && lockedUntil.toInstant().isAfter(now)) {
                long secs = Math.max(1, lockedUntil.toInstant().getEpochSecond() - now.getEpochSecond());
                refusal[0] = new ApiException(ErrorCode.PIN_LOCKED, "Too many wrong PINs. Try again later.", (int) secs, Map.of());
                return null;
            }
            if (!bcrypt.matches(pin == null ? "" : pin, (String) pinRow[0])) {
                int failures = (int) pinRow[1] + 1;
                if (failures >= props.pinAttempts()) {
                    db.sql("UPDATE accounts SET pin_failures = 0, pin_locked_until = ? WHERE vpa = ?")
                            .params(ts(now.plus(props.pinLockout())), me.vpa()).update();
                    refusal[0] = new ApiException(ErrorCode.PIN_LOCKED, "Too many wrong PINs. Approvals are locked for "
                            + props.pinLockout().toMinutes() + " minutes.", (int) props.pinLockout().toSeconds(), Map.of());
                } else {
                    db.sql("UPDATE accounts SET pin_failures = ? WHERE vpa = ?").params(failures, me.vpa()).update();
                    refusal[0] = new ApiException(ErrorCode.INVALID_PIN, "That UPI PIN is wrong.", null,
                            Map.of("attemptsLeft", props.pinAttempts() - failures));
                }
                return null;
            }
            db.sql("UPDATE accounts SET pin_failures = 0, pin_locked_until = NULL WHERE vpa = ?").param(me.vpa()).update();
            if ((long) pinRow[3] < r.amount()) {
                refusal[0] = new ApiException(ErrorCode.INSUFFICIENT_BALANCE, "Not enough money in your account for this payment.");
                return null;
            }
            transfer(r.payerVpa(), r.payeeVpa(), r.amount(), "UPI to " + r.payeeName(), "UPI from " + me.holderName(), now);
            db.sql("UPDATE collect_requests SET status = 'APPROVED', decided_at = ? WHERE id = ?").params(ts(now), r.id()).update();
            notify(r, "COLLECT_APPROVED", now);
            return request(r.id());
        });
        if (refusal[0] != null) {
            throw refusal[0];
        }
        return result;
    }

    public Request decline(UUID userId, UUID requestId) {
        Instant now = clock.instant();
        return tx.execute(s -> {
            Account me = mine(userId);
            Request r = lockRequest(requestId, me.vpa());
            if (!r.status().equals("PENDING")) {
                throw notPending(r);
            }
            db.sql("UPDATE collect_requests SET status = 'DECLINED', decided_at = ? WHERE id = ?").params(ts(now), r.id()).update();
            notify(r, "COLLECT_DECLINED", now);
            return request(r.id());
        });
    }

    /** Requests nobody answered in time. Called on a schedule. */
    public int expireDue() {
        Instant now = clock.instant();
        return tx.execute(s -> {
            List<Request> due = db.sql(REQUEST_SQL + " WHERE r.status = 'PENDING' AND r.expires_at <= ? FOR UPDATE OF r SKIP LOCKED")
                    .param(ts(now)).query(Bank::request).list();
            due.forEach(r -> expire(r, now));
            return due.size();
        });
    }

    private void expire(Request r, Instant now) {
        db.sql("UPDATE collect_requests SET status = 'EXPIRED', decided_at = ? WHERE id = ? AND status = 'PENDING'")
                .params(ts(now), r.id()).update();
        notify(r, "COLLECT_EXPIRED", now);
    }

    private Request lockRequest(UUID id, String payerVpa) {
        return db.sql(REQUEST_SQL + " WHERE r.id = ? AND r.payer_vpa = ? FOR UPDATE OF r").params(id, payerVpa)
                .query(Bank::request).optional()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No such request to you."));
    }

    private static ApiException notPending(Request r) {
        return new ApiException(ErrorCode.REQUEST_NOT_PENDING, "This request is already " + r.status().toLowerCase(Locale.ROOT) + ".",
                null, Map.of("status", r.status()));
    }

    // ── partner operations ───────────────────────────────────────────────────

    public Created<Request> collect(Partner partner, String payerVpa, long amount, String reference, String note, String callbackUrl) {
        if (amount <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The amount must be above zero.");
        }
        Optional<Request> existing = db.sql(REQUEST_SQL + " WHERE r.partner = ? AND r.reference = ?").params(partner.name(), reference)
                .query(Bank::request).optional();
        if (existing.isPresent()) {
            return new Created<>(existing.get(), false);
        }
        Account payer = byVpa(payerVpa).filter(a -> a.userId() != null)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No customer has the UPI address " + payerVpa + "."));
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        try {
            db.sql("INSERT INTO collect_requests (id, partner, reference, payer_vpa, payee_vpa, amount_paise, note, callback_url, status, "
                            + "created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)")
                    .params(id, partner.name(), reference, payer.vpa(), partner.vpa(), amount, note, callbackUrl, ts(now),
                            ts(now.plus(props.collectTtl())))
                    .update();
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return new Created<>(db.sql(REQUEST_SQL + " WHERE r.partner = ? AND r.reference = ?").params(partner.name(), reference)
                    .query(Bank::request).single(), false);
        }
        return new Created<>(request(id), true);
    }

    public Request partnerRequest(Partner partner, UUID id) {
        return db.sql(REQUEST_SQL + " WHERE r.id = ? AND r.partner = ?").params(id, partner.name()).query(Bank::request).optional()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No such collect request of yours."));
    }

    public Created<Payout> payout(Partner partner, String payeeVpa, long amount, String reference) {
        if (amount <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The amount must be above zero.");
        }
        Optional<Payout> existing = payoutByReference(partner, reference);
        if (existing.isPresent()) {
            return new Created<>(existing.get(), false);
        }
        // customers and other businesses alike: a clearing corporation and its members pay each other this way
        Account payee = byVpa(payeeVpa).filter(a -> !a.vpa().equals(partner.vpa()))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No account has the UPI address " + payeeVpa + "."));
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        try {
            tx.executeWithoutResult(s -> {
                long merchant = db.sql("SELECT balance_paise FROM accounts WHERE vpa = ? FOR UPDATE").param(partner.vpa())
                        .query(Long.class).single();
                if (merchant < amount) {
                    throw new ApiException(ErrorCode.INSUFFICIENT_BALANCE, partner.displayName() + " doesn't have enough money here.");
                }
                db.sql("INSERT INTO payouts (id, partner, reference, payee_vpa, amount_paise, status, created_at) "
                                + "VALUES (?, ?, ?, ?, ?, 'COMPLETED', ?)")
                        .params(id, partner.name(), reference, payee.vpa(), amount, ts(now)).update();
                transfer(partner.vpa(), payee.vpa(), amount, "Payout to " + payee.holderName(), "From " + partner.displayName(), reference, now);
            });
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return new Created<>(payoutByReference(partner, reference).orElseThrow(), false);
        }
        return new Created<>(payoutByReference(partner, reference).orElseThrow(), true);
    }

    public Optional<Payout> payoutByReference(Partner partner, String reference) {
        return db.sql("SELECT id, payee_vpa, amount_paise, reference, status, created_at FROM payouts WHERE partner = ? AND reference = ?")
                .params(partner.name(), reference)
                .query((rs, n) -> new Payout(rs.getObject("id", UUID.class), rs.getString("payee_vpa"), rs.getLong("amount_paise"),
                        rs.getString("reference"), rs.getString("status"), rs.getTimestamp("created_at").toInstant()))
                .optional();
    }

    // ── internals ────────────────────────────────────────────────────────────

    /** Moves money between two accounts (locked in name order, so transfers can't deadlock). */
    private void transfer(String from, String to, long amount, String fromText, String toText, Instant now) {
        transfer(from, to, amount, fromText, toText, null, now);
    }

    private void transfer(String from, String to, long amount, String fromText, String toText, String reference, Instant now) {
        for (String vpa : from.compareTo(to) < 0 ? List.of(from, to) : List.of(to, from)) {
            db.sql("SELECT 1 FROM accounts WHERE vpa = ? FOR UPDATE").param(vpa).query(Integer.class).single();
        }
        long fromAfter = db.sql("UPDATE accounts SET balance_paise = balance_paise - ? WHERE vpa = ? RETURNING balance_paise")
                .params(amount, from).query(Long.class).single();
        long toAfter = db.sql("UPDATE accounts SET balance_paise = balance_paise + ? WHERE vpa = ? RETURNING balance_paise")
                .params(amount, to).query(Long.class).single();
        record(from, "OUT", amount, fromText, to, reference, fromAfter, now);
        record(to, "IN", amount, toText, from, reference, toAfter, now);
    }

    private void record(String vpa, String direction, long amount, String text, String counterparty, String reference, long after,
                        Instant now) {
        db.sql("INSERT INTO transactions (id, vpa, direction, amount_paise, description, counterparty, reference, balance_after_paise, at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(UUID.randomUUID(), vpa, direction, amount, text, counterparty, reference, after, ts(now)).update();
    }

    /** Writes the partner's callback into the outbox, in the caller's transaction. */
    private void notify(Request r, String type, Instant now) {
        String callback = db.sql("SELECT callback_url FROM collect_requests WHERE id = ?").param(r.id()).query(String.class).single();
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", UUID.randomUUID().toString());
        event.put("type", type);
        event.put("requestId", r.id().toString());
        event.put("reference", r.reference());
        event.put("amount", Money.rupees(r.amount()));
        event.put("occurredAt", now.toString());
        try {
            db.sql("INSERT INTO outbox (id, partner, callback_url, body, created_at, next_attempt_at) VALUES (?, ?, ?, ?, ?, ?)")
                    .params(UUID.randomUUID(), r.partner(), callback, json.writeValueAsString(event), ts(now), ts(now)).update();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public Request request(UUID id) {
        return db.sql(REQUEST_SQL + " WHERE r.id = ?").param(id).query(Bank::request).single();
    }

    private static final String REQUEST_SQL = """
            SELECT r.id, r.partner, r.reference, r.payer_vpa, r.payee_vpa, p.holder_name AS payee_name, r.amount_paise, r.note,
                   r.status, r.created_at, r.expires_at, r.decided_at
            FROM collect_requests r JOIN accounts p ON p.vpa = r.payee_vpa""";

    private static Request request(ResultSet rs, int n) throws SQLException {
        Timestamp decided = rs.getTimestamp("decided_at");
        return new Request(rs.getObject("id", UUID.class), rs.getString("partner"), rs.getString("reference"), rs.getString("payer_vpa"),
                rs.getString("payee_vpa"), rs.getString("payee_name"), rs.getLong("amount_paise"), rs.getString("note"),
                rs.getString("status"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("expires_at").toInstant(),
                decided == null ? null : decided.toInstant());
    }

    private static Account account(ResultSet rs, int n) throws SQLException {
        return new Account(rs.getString("vpa"), rs.getObject("user_id", UUID.class), rs.getString("holder_name"),
                rs.getLong("balance_paise"), rs.getTimestamp("opened_at").toInstant());
    }

    private static Timestamp ts(Instant i) {
        return Timestamp.from(i);
    }
}
