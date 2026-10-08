package app.sprout.bank.domain;

import app.sprout.bank.domain.Bank.Account;
import app.sprout.bank.domain.Bank.Created;
import app.sprout.bank.domain.Bank.Txn;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Customers paying by UPI: to a demo merchant or anyone else with an account here. A partner whose
 * active mandate shares the customer's spends hears about each payment, in the same transaction.
 */
@Service
public class UpiPayments {

    public record Merchant(String vpa, String name, String category) {}

    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Bank bank;

    public UpiPayments(JdbcClient db, TransactionTemplate tx, Clock clock, Bank bank) {
        this.db = db;
        this.tx = tx;
        this.clock = clock;
        this.bank = bank;
    }

    public List<Merchant> merchants() {
        return db.sql("SELECT vpa, holder_name, merchant_category FROM accounts WHERE merchant_category IS NOT NULL ORDER BY holder_name")
                .query((rs, n) -> new Merchant(rs.getString(1), rs.getString(2), rs.getString(3))).list();
    }

    /**
     * Pays with the UPI PIN. Returns the debit from the customer's account. With an idempotency key, a payment the
     * customer already made under that key is returned as it was (created = false) and no money moves again.
     */
    public Created<Txn> pay(UUID userId, String payeeVpa, long amount, String pin, String note, String idempotencyKey) {
        if (amount <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The amount must be above zero.");
        }
        if (idempotencyKey != null && (idempotencyKey.length() < 8 || idempotencyKey.length() > 100)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The Idempotency-Key must be 8 to 100 characters.");
        }
        Account me = bank.mine(userId);
        if (idempotencyKey != null) {
            Optional<Txn> earlier = paymentWithKey(me.vpa(), idempotencyKey);
            if (earlier.isPresent()) {
                return new Created<>(earlier.get(), false);
            }
        }
        Account payee = bank.byVpa(payeeVpa).filter(a -> !a.vpa().equals(me.vpa()))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No account has the UPI address " + payeeVpa + "."));
        Instant now = clock.instant();
        // wrong PINs are counted even though the payment fails: decide inside, raise after commit
        ApiException[] refusal = new ApiException[1];
        UUID out;
        try {
            out = tx.execute(s -> {
                refusal[0] = bank.verifyPin(me.vpa(), pin, now);
                if (refusal[0] != null) {
                    return null;
                }
                long balance = db.sql("SELECT balance_paise FROM accounts WHERE vpa = ?").param(me.vpa()).query(Long.class).single();
                if (balance < amount) {
                    refusal[0] = new ApiException(ErrorCode.INSUFFICIENT_BALANCE, "Not enough money in your account for this payment.");
                    return null;
                }
                String what = note == null || note.isBlank() ? "" : " (" + note.trim() + ")";
                UUID id = bank.transfer(me.vpa(), payee.vpa(), amount, "UPI to " + payee.holderName() + what, "UPI from " + me.holderName() + what,
                        now);
                if (idempotencyKey != null) {
                    // stored with the debit, so the key exists exactly when the money moved
                    db.sql("UPDATE transactions SET idempotency_key = ? WHERE id = ?").params(idempotencyKey, id).update();
                }
                shareSpend(me.vpa(), id, amount, payee.holderName(), now);
                return id;
            });
        } catch (DuplicateKeyException e) {
            // the same key arrived twice at once and the other payment won: return that one
            return new Created<>(paymentWithKey(me.vpa(), idempotencyKey).orElseThrow(), false);
        }
        if (refusal[0] != null) {
            throw refusal[0];
        }
        return new Created<>(bank.txn(out), true);
    }

    private Optional<Txn> paymentWithKey(String vpa, String idempotencyKey) {
        return db.sql("SELECT id FROM transactions WHERE vpa = ? AND idempotency_key = ?").params(vpa, idempotencyKey)
                .query(UUID.class).optional().map(bank::txn);
    }

    /** Tells every partner whose active mandate shares this customer's spends. */
    private void shareSpend(String payerVpa, UUID spendId, long amount, String payeeName, Instant now) {
        db.sql("SELECT id, partner, reference, callback_url FROM mandates WHERE payer_vpa = ? AND status = 'ACTIVE' AND share_spends")
                .param(payerVpa)
                .query((rs, n) -> new Object[] {rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4)})
                .list()
                .forEach(m -> {
                    Map<String, Object> event = new LinkedHashMap<>();
                    event.put("eventId", UUID.randomUUID().toString());
                    event.put("type", "SPEND");
                    event.put("mandateId", m[0].toString());
                    event.put("reference", m[2]);
                    event.put("spendId", spendId.toString());
                    event.put("amount", Money.rupees(amount));
                    event.put("payeeName", payeeName);
                    event.put("occurredAt", now.toString());
                    bank.outbox((String) m[1], (String) m[3], event, now);
                });
    }
}
