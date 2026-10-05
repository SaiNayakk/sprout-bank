package app.sprout.bank.web;

import app.sprout.bank.config.BankProperties.Partner;
import app.sprout.bank.domain.ApiException;
import app.sprout.bank.domain.Bank;
import app.sprout.bank.domain.Bank.Account;
import app.sprout.bank.domain.Bank.Created;
import app.sprout.bank.domain.Bank.Payout;
import app.sprout.bank.domain.Bank.Request;
import app.sprout.bank.domain.Bank.Txn;
import app.sprout.bank.domain.ErrorCode;
import app.sprout.bank.domain.Mandates;
import app.sprout.bank.domain.Mandates.Debit;
import app.sprout.bank.domain.Mandates.Mandate;
import app.sprout.bank.domain.UpiPayments;
import app.sprout.bank.domain.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sprout Bank's API (bank-v1.yaml): customers through the gateway, identified by the gateway's
 * X-User-Id, and partners on /partner with their X-Partner-Key.
 */
@RestController
public class BankController {

    public record OpenRequest(@NotBlank @Size(max = 100) String holderName, @NotBlank String upiPin) {}

    public record PinRequest(String upiPin) {}

    public record CollectRequestBody(@NotBlank String payerVpa, @NotBlank String amount, @NotBlank @Size(max = 120) String reference,
                                     @Size(max = 100) String note, @NotBlank String callbackUrl) {}

    public record PayBody(@NotBlank String payeeVpa, @NotBlank String amount, String upiPin, @Size(max = 100) String note) {}

    public record MandateBody(@NotBlank String payerVpa, @NotBlank String maxAmount, @NotBlank @Size(max = 100) String purpose,
                              @NotBlank @Size(max = 120) String reference, Boolean shareSpends, @NotBlank String callbackUrl) {}

    public record DebitBody(@NotBlank String amount, @NotBlank @Size(max = 120) String reference) {}

    public record PayoutBody(@NotBlank String payeeVpa, @NotBlank String amount, @NotBlank @Size(max = 120) String reference) {}

    private static final Set<String> STATUSES = Set.of("PENDING", "APPROVED", "DECLINED", "EXPIRED");
    private static final Set<String> MANDATE_STATUSES = Set.of("PENDING", "ACTIVE", "DECLINED", "EXPIRED", "REVOKED");

    private final Bank bank;
    private final UpiPayments upi;
    private final Mandates mandates;
    private final String page;

    public BankController(Bank bank, UpiPayments upi, Mandates mandates) throws IOException {
        this.bank = bank;
        this.upi = upi;
        this.mandates = mandates;
        this.page = new ClassPathResource("bank/app.html").getContentAsString(StandardCharsets.UTF_8);
    }

    // ── customers ────────────────────────────────────────────────────────────

    @PostMapping("/v1/accounts")
    public ResponseEntity<Map<String, Object>> open(@RequestHeader(value = "X-User-Id", required = false) String user,
                                                    @Valid @RequestBody OpenRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(account(bank.open(userId(user), req.holderName(), req.upiPin())));
    }

    @GetMapping("/v1/accounts/me")
    public Map<String, Object> me(@RequestHeader(value = "X-User-Id", required = false) String user) {
        return account(bank.mine(userId(user)));
    }

    @GetMapping("/v1/requests")
    public Map<String, Object> requests(@RequestHeader(value = "X-User-Id", required = false) String user,
                                        @RequestParam(required = false) String status) {
        if (status != null && !STATUSES.contains(status)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "status must be one of " + STATUSES + ".");
        }
        return Map.of("requests", bank.requests(userId(user), status).stream().map(BankController::request).toList());
    }

    @PostMapping("/v1/requests/{id}/approve")
    public Map<String, Object> approve(@RequestHeader(value = "X-User-Id", required = false) String user, @PathVariable UUID id,
                                       @RequestBody PinRequest req) {
        return request(bank.approve(userId(user), id, req.upiPin()));
    }

    @PostMapping("/v1/requests/{id}/decline")
    public Map<String, Object> decline(@RequestHeader(value = "X-User-Id", required = false) String user, @PathVariable UUID id) {
        return request(bank.decline(userId(user), id));
    }

    @GetMapping("/v1/merchants")
    public Map<String, Object> merchants() {
        return Map.of("merchants", upi.merchants().stream()
                .map(m -> Map.of("vpa", m.vpa(), "name", m.name(), "category", m.category())).toList());
    }

    @PostMapping("/v1/payments")
    public ResponseEntity<Map<String, Object>> pay(@RequestHeader(value = "X-User-Id", required = false) String user,
                                                   @Valid @RequestBody PayBody body) {
        Txn t = upi.pay(userId(user), body.payeeVpa(), Money.paise(body.amount()), body.upiPin(), body.note());
        return ResponseEntity.status(HttpStatus.CREATED).body(txn(t));
    }

    @GetMapping("/v1/mandates")
    public Map<String, Object> mandates(@RequestHeader(value = "X-User-Id", required = false) String user,
                                        @RequestParam(required = false) String status) {
        if (status != null && !MANDATE_STATUSES.contains(status)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "status must be one of " + MANDATE_STATUSES + ".");
        }
        return Map.of("mandates", mandates.mine(userId(user), status).stream().map(BankController::mandate).toList());
    }

    @PostMapping("/v1/mandates/{id}/approve")
    public Map<String, Object> approveMandate(@RequestHeader(value = "X-User-Id", required = false) String user, @PathVariable UUID id,
                                              @RequestBody PinRequest req) {
        return mandate(mandates.approve(userId(user), id, req.upiPin()));
    }

    @PostMapping("/v1/mandates/{id}/decline")
    public Map<String, Object> declineMandate(@RequestHeader(value = "X-User-Id", required = false) String user, @PathVariable UUID id) {
        return mandate(mandates.decline(userId(user), id));
    }

    @PostMapping("/v1/mandates/{id}/revoke")
    public Map<String, Object> revokeMandate(@RequestHeader(value = "X-User-Id", required = false) String user, @PathVariable UUID id) {
        return mandate(mandates.revoke(userId(user), id));
    }

    @GetMapping("/v1/transactions")
    public Map<String, Object> transactions(@RequestHeader(value = "X-User-Id", required = false) String user) {
        return Map.of("transactions", bank.transactions(userId(user)).stream().map(BankController::txn).toList());
    }

    static Map<String, Object> txn(Txn t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id().toString());
        m.put("amount", Money.rupees(t.amount()));
        m.put("direction", t.direction());
        m.put("description", t.description());
        if (t.counterparty() != null) {
            m.put("counterparty", t.counterparty());
        }
        if (t.reference() != null) {
            m.put("reference", t.reference());
        }
        m.put("balanceAfter", Money.rupees(t.balanceAfter()));
        m.put("at", t.at().toString());
        return m;
    }

    @GetMapping(value = "/app", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> app() {
        return ResponseEntity.ok().header("Content-Security-Policy",
                "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:")
                .body(page);
    }

    // ── partners ─────────────────────────────────────────────────────────────

    @GetMapping("/partner/v1/vpas/{vpa}")
    public Map<String, Object> vpa(@RequestHeader(value = "X-Partner-Key", required = false) String key, @PathVariable String vpa) {
        bank.partner(key);
        Account a = bank.byVpa(vpa).filter(x -> x.userId() != null)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No customer has the UPI address " + vpa + "."));
        return Map.of("vpa", a.vpa(), "holderName", a.holderName());
    }

    @PostMapping("/partner/v1/collect-requests")
    public ResponseEntity<Map<String, Object>> collect(@RequestHeader(value = "X-Partner-Key", required = false) String key,
                                                       @Valid @RequestBody CollectRequestBody body) {
        Partner partner = bank.partner(key);
        Created<Request> r = bank.collect(partner, body.payerVpa(), Money.paise(body.amount()), body.reference(), body.note(),
                body.callbackUrl());
        return ResponseEntity.status(r.created() ? HttpStatus.CREATED : HttpStatus.OK).body(request(r.value()));
    }

    @GetMapping("/partner/v1/collect-requests/{id}")
    public Map<String, Object> collectStatus(@RequestHeader(value = "X-Partner-Key", required = false) String key,
                                             @PathVariable UUID id) {
        return request(bank.partnerRequest(bank.partner(key), id));
    }

    @PostMapping("/partner/v1/mandates")
    public ResponseEntity<Map<String, Object>> requestMandate(@RequestHeader(value = "X-Partner-Key", required = false) String key,
                                                              @Valid @RequestBody MandateBody body) {
        Partner partner = bank.partner(key);
        Created<Mandate> m = mandates.request(partner, body.payerVpa(), Money.paise(body.maxAmount()), body.purpose(), body.reference(),
                Boolean.TRUE.equals(body.shareSpends()), body.callbackUrl());
        return ResponseEntity.status(m.created() ? HttpStatus.CREATED : HttpStatus.OK).body(mandate(m.value()));
    }

    @GetMapping("/partner/v1/mandates/{id}")
    public Map<String, Object> partnerMandate(@RequestHeader(value = "X-Partner-Key", required = false) String key,
                                              @PathVariable UUID id) {
        return mandate(mandates.forPartner(bank.partner(key), id));
    }

    @PostMapping("/partner/v1/mandates/{id}/debits")
    public ResponseEntity<Map<String, Object>> debit(@RequestHeader(value = "X-Partner-Key", required = false) String key,
                                                     @PathVariable UUID id, @Valid @RequestBody DebitBody body) {
        Created<Debit> d = mandates.debit(bank.partner(key), id, Money.paise(body.amount()), body.reference());
        Debit v = d.value();
        return ResponseEntity.status(d.created() ? HttpStatus.CREATED : HttpStatus.OK).body(Map.of("id", v.id().toString(),
                "mandateId", v.mandateId().toString(), "amount", Money.rupees(v.amount()), "reference", v.reference(),
                "status", v.status(), "createdAt", v.createdAt().toString()));
    }

    @PostMapping("/partner/v1/payouts")
    public ResponseEntity<Map<String, Object>> payout(@RequestHeader(value = "X-Partner-Key", required = false) String key,
                                                      @Valid @RequestBody PayoutBody body) {
        Created<Payout> p = bank.payout(bank.partner(key), body.payeeVpa(), Money.paise(body.amount()), body.reference());
        return ResponseEntity.status(p.created() ? HttpStatus.CREATED : HttpStatus.OK).body(payout(p.value()));
    }

    /** The partner's own account and balance, for reconciling its books with the bank. */
    @GetMapping("/partner/v1/account")
    public Map<String, Object> partnerAccount(@RequestHeader(value = "X-Partner-Key", required = false) String key) {
        return account(bank.partnerAccount(bank.partner(key)));
    }

    /** The partner's own statement; with ?reference=, only the money that carried that reference. */
    @GetMapping("/partner/v1/transactions")
    public Map<String, Object> partnerTransactions(@RequestHeader(value = "X-Partner-Key", required = false) String key,
                                                   @RequestParam(required = false) String reference) {
        return Map.of("transactions", bank.partnerTransactions(bank.partner(key), reference).stream().map(BankController::txn).toList());
    }

    @GetMapping("/partner/v1/payouts/{reference}")
    public Map<String, Object> payoutStatus(@RequestHeader(value = "X-Partner-Key", required = false) String key,
                                            @PathVariable String reference) {
        Partner partner = bank.partner(key);
        return payout(bank.payoutByReference(partner, reference)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No payout with that reference.")));
    }

    // ── shapes ───────────────────────────────────────────────────────────────

    private static UUID userId(String header) {
        try {
            return UUID.fromString(header);
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "Sign in to continue.");
        }
    }

    static Map<String, Object> account(Account a) {
        return Map.of("vpa", a.vpa(), "holderName", a.holderName(), "balance", Money.rupees(a.balance()),
                "openedAt", a.openedAt().toString());
    }

    static Map<String, Object> request(Request r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id().toString());
        m.put("payerVpa", r.payerVpa());
        m.put("payeeVpa", r.payeeVpa());
        m.put("payeeName", r.payeeName());
        m.put("amount", Money.rupees(r.amount()));
        if (r.note() != null) {
            m.put("note", r.note());
        }
        m.put("reference", r.reference());
        m.put("status", r.status());
        m.put("createdAt", r.createdAt().toString());
        m.put("expiresAt", r.expiresAt().toString());
        if (r.decidedAt() != null) {
            m.put("decidedAt", r.decidedAt().toString());
        }
        return m;
    }

    static Map<String, Object> mandate(Mandate m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", m.id().toString());
        out.put("payerVpa", m.payerVpa());
        out.put("payeeVpa", m.payeeVpa());
        out.put("payeeName", m.payeeName());
        out.put("maxAmount", Money.rupees(m.maxAmount()));
        out.put("purpose", m.purpose());
        out.put("reference", m.reference());
        out.put("shareSpends", m.shareSpends());
        out.put("status", m.status());
        out.put("createdAt", m.createdAt().toString());
        out.put("expiresAt", m.expiresAt().toString());
        if (m.decidedAt() != null) {
            out.put("decidedAt", m.decidedAt().toString());
        }
        if (m.revokedAt() != null) {
            out.put("revokedAt", m.revokedAt().toString());
        }
        return out;
    }

    static Map<String, Object> payout(Payout p) {
        return Map.of("id", p.id().toString(), "payeeVpa", p.payeeVpa(), "amount", Money.rupees(p.amount()),
                "reference", p.reference(), "status", p.status(), "createdAt", p.createdAt().toString());
    }

}
