package app.sprout.bank.domain;

import app.sprout.bank.config.BankProperties;
import app.sprout.bank.config.BankProperties.Partner;
import app.sprout.bank.domain.Bank.Account;
import app.sprout.bank.domain.Bank.Created;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * UPI AutoPay mandates: a partner asks, the customer approves once with their PIN, and the partner may
 * then debit up to the limit each time, without asking, until the customer revokes it. Every outcome
 * is told to the partner through the outbox, in the transaction that made it.
 */
@Service
public class Mandates {

    public record Mandate(UUID id, String partner, String reference, String payerVpa, String payeeVpa, String payeeName,
                          long maxAmount, String purpose, boolean shareSpends, String status, Instant createdAt, Instant expiresAt,
                          Instant decidedAt, Instant revokedAt) {}

    public record Debit(UUID id, UUID mandateId, long amount, String reference, String status, Instant createdAt) {}

    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final BankProperties props;
    private final Bank bank;

    public Mandates(JdbcClient db, TransactionTemplate tx, Clock clock, BankProperties props, Bank bank) {
        this.db = db;
        this.tx = tx;
        this.clock = clock;
        this.props = props;
        this.bank = bank;
    }

    // ── partners ─────────────────────────────────────────────────────────────

    public Created<Mandate> request(Partner partner, String payerVpa, long maxAmount, String purpose, String reference, boolean shareSpends,
                                    String callbackUrl) {
        if (maxAmount <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The limit must be above zero.");
        }
        Optional<Mandate> existing = byReference(partner, reference);
        if (existing.isPresent()) {
            return new Created<>(existing.get(), false);
        }
        Account payer = bank.byVpa(payerVpa).filter(a -> a.userId() != null)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No customer has the UPI address " + payerVpa + "."));
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        try {
            db.sql("INSERT INTO mandates (id, partner, reference, payer_vpa, payee_vpa, max_amount_paise, purpose, share_spends, callback_url, "
                            + "status, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)")
                    .params(id, partner.name(), reference, payer.vpa(), partner.vpa(), maxAmount, purpose, shareSpends, callbackUrl,
                            Bank.ts(now), Bank.ts(now.plus(props.mandateTtl())))
                    .update();
        } catch (DuplicateKeyException e) {
            return new Created<>(byReference(partner, reference).orElseThrow(), false);
        }
        return new Created<>(mandate(id), true);
    }

    public Mandate forPartner(Partner partner, UUID id) {
        return db.sql(SQL + " WHERE m.id = ? AND m.partner = ?").params(id, partner.name()).query(Mandates::mandate).optional()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No such mandate of yours."));
    }

    /** Takes money under an active mandate, within its limit. Unique per (partner, reference). */
    public Created<Debit> debit(Partner partner, UUID mandateId, long amount, String reference) {
        if (amount <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The amount must be above zero.");
        }
        Optional<Debit> earlier = debitByReference(partner, reference);
        if (earlier.isPresent()) {
            return new Created<>(earlier.get(), false);
        }
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        try {
            tx.executeWithoutResult(s -> {
                Mandate m = db.sql(SQL + " WHERE m.id = ? AND m.partner = ? FOR UPDATE OF m").params(mandateId, partner.name())
                        .query(Mandates::mandate).optional()
                        .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No such mandate of yours."));
                if (!m.status().equals("ACTIVE")) {
                    throw new ApiException(ErrorCode.REQUEST_NOT_PENDING, "This mandate is " + m.status().toLowerCase(Locale.ROOT)
                            + ", not active.", null, Map.of("status", m.status()));
                }
                if (amount > m.maxAmount()) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "That's above the mandate's limit of ₹" + Money.rupees(m.maxAmount()) + ".");
                }
                long balance = db.sql("SELECT balance_paise FROM accounts WHERE vpa = ? FOR UPDATE").param(m.payerVpa())
                        .query(Long.class).single();
                if (balance < amount) {
                    throw new ApiException(ErrorCode.INSUFFICIENT_BALANCE, "The customer doesn't have enough money for this debit.");
                }
                db.sql("INSERT INTO mandate_debits (id, mandate_id, partner, reference, amount_paise, status, created_at) "
                                + "VALUES (?, ?, ?, ?, ?, 'COMPLETED', ?)")
                        .params(id, m.id(), partner.name(), reference, amount, Bank.ts(now)).update();
                bank.transfer(m.payerVpa(), m.payeeVpa(), amount, "AutoPay to " + m.payeeName() + ": " + m.purpose(),
                        "AutoPay from " + m.payerVpa(), reference, now);
            });
        } catch (DuplicateKeyException e) {
            return new Created<>(debitByReference(partner, reference).orElseThrow(), false);
        }
        return new Created<>(debitByReference(partner, reference).orElseThrow(), true);
    }

    // ── customers ────────────────────────────────────────────────────────────

    public List<Mandate> mine(UUID userId, String status) {
        Account me = bank.mine(userId);
        return db.sql(SQL + " WHERE m.payer_vpa = ? AND (CAST(? AS text) IS NULL OR m.status = ?) ORDER BY m.created_at DESC LIMIT 100")
                .params(me.vpa(), status, status).query(Mandates::mandate).list();
    }

    public Mandate approve(UUID userId, UUID id, String pin) {
        Instant now = clock.instant();
        // wrong PINs are counted even though the approval fails: decide inside, raise after commit
        ApiException[] refusal = new ApiException[1];
        Mandate result = tx.execute(s -> {
            Mandate m = lock(userId, id);
            if (!m.status().equals("PENDING")) {
                throw notPending(m.status());
            }
            if (!m.expiresAt().isAfter(now)) {
                decide(m, "EXPIRED", "MANDATE_EXPIRED", now);
                refusal[0] = notPending("EXPIRED");
                return null;
            }
            refusal[0] = bank.verifyPin(m.payerVpa(), pin, now);
            if (refusal[0] != null) {
                return null;
            }
            decide(m, "ACTIVE", "MANDATE_ACTIVE", now);
            return mandate(id);
        });
        if (refusal[0] != null) {
            throw refusal[0];
        }
        return result;
    }

    public Mandate decline(UUID userId, UUID id) {
        Instant now = clock.instant();
        return tx.execute(s -> {
            Mandate m = lock(userId, id);
            if (!m.status().equals("PENDING")) {
                throw notPending(m.status());
            }
            decide(m, "DECLINED", "MANDATE_DECLINED", now);
            return mandate(id);
        });
    }

    public Mandate revoke(UUID userId, UUID id) {
        Instant now = clock.instant();
        return tx.execute(s -> {
            Mandate m = lock(userId, id);
            if (!m.status().equals("ACTIVE")) {
                throw new ApiException(ErrorCode.REQUEST_NOT_PENDING, "Only an active mandate can be revoked; this one is "
                        + m.status().toLowerCase(Locale.ROOT) + ".", null, Map.of("status", m.status()));
            }
            db.sql("UPDATE mandates SET status = 'REVOKED', revoked_at = ? WHERE id = ?").params(Bank.ts(now), id).update();
            tell(m, "MANDATE_REVOKED", now);
            return mandate(id);
        });
    }

    /** Requests nobody answered in time. Called on a schedule. */
    public int expireDue() {
        Instant now = clock.instant();
        return tx.execute(s -> {
            List<Mandate> due = db.sql(SQL + " WHERE m.status = 'PENDING' AND m.expires_at <= ? FOR UPDATE OF m SKIP LOCKED")
                    .param(Bank.ts(now)).query(Mandates::mandate).list();
            due.forEach(m -> decide(m, "EXPIRED", "MANDATE_EXPIRED", now));
            return due.size();
        });
    }

    // ── internals ────────────────────────────────────────────────────────────

    private Mandate lock(UUID userId, UUID id) {
        Account me = bank.mine(userId);
        return db.sql(SQL + " WHERE m.id = ? AND m.payer_vpa = ? FOR UPDATE OF m").params(id, me.vpa()).query(Mandates::mandate).optional()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No such mandate asked of you."));
    }

    private void decide(Mandate m, String status, String event, Instant now) {
        db.sql("UPDATE mandates SET status = ?, decided_at = ? WHERE id = ? AND status = 'PENDING'").params(status, Bank.ts(now), m.id())
                .update();
        tell(m, event, now);
    }

    private void tell(Mandate m, String type, Instant now) {
        String callback = db.sql("SELECT callback_url FROM mandates WHERE id = ?").param(m.id()).query(String.class).single();
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", UUID.randomUUID().toString());
        event.put("type", type);
        event.put("mandateId", m.id().toString());
        event.put("reference", m.reference());
        event.put("occurredAt", now.toString());
        bank.outbox(m.partner(), callback, event, now);
    }

    private static ApiException notPending(String status) {
        return new ApiException(ErrorCode.REQUEST_NOT_PENDING, "This mandate is already " + status.toLowerCase(Locale.ROOT) + ".", null,
                Map.of("status", status));
    }

    private Optional<Mandate> byReference(Partner partner, String reference) {
        return db.sql(SQL + " WHERE m.partner = ? AND m.reference = ?").params(partner.name(), reference).query(Mandates::mandate).optional();
    }

    private Optional<Debit> debitByReference(Partner partner, String reference) {
        return db.sql("SELECT id, mandate_id, amount_paise, reference, status, created_at FROM mandate_debits WHERE partner = ? AND reference = ?")
                .params(partner.name(), reference)
                .query((rs, n) -> new Debit(rs.getObject("id", UUID.class), rs.getObject("mandate_id", UUID.class), rs.getLong("amount_paise"),
                        rs.getString("reference"), rs.getString("status"), rs.getTimestamp("created_at").toInstant()))
                .optional();
    }

    Mandate mandate(UUID id) {
        return db.sql(SQL + " WHERE m.id = ?").param(id).query(Mandates::mandate).single();
    }

    private static final String SQL = """
            SELECT m.id, m.partner, m.reference, m.payer_vpa, m.payee_vpa, p.holder_name AS payee_name, m.max_amount_paise, m.purpose,
                   m.share_spends, m.status, m.created_at, m.expires_at, m.decided_at, m.revoked_at
            FROM mandates m JOIN accounts p ON p.vpa = m.payee_vpa""";

    private static Mandate mandate(ResultSet rs, int n) throws SQLException {
        return new Mandate(rs.getObject("id", UUID.class), rs.getString("partner"), rs.getString("reference"), rs.getString("payer_vpa"),
                rs.getString("payee_vpa"), rs.getString("payee_name"), rs.getLong("max_amount_paise"), rs.getString("purpose"),
                rs.getBoolean("share_spends"), rs.getString("status"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(), instant(rs.getTimestamp("decided_at")), instant(rs.getTimestamp("revoked_at")));
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
