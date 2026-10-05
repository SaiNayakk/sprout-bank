package app.sprout.bank;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.sprout.bank.domain.Callbacks;
import app.sprout.contracts.Contracts;
import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Sprout Bank on a real Postgres, every JSON response checked against bank-v1.yaml. */
@Testcontainers
@SpringBootTest(properties = {
        "spring.config.name=bank",
        "sprout.bank.bcrypt-strength=4",
        "sprout.bank.expiry-check=1h",     // tests drive expiry and delivery by hand
        "sprout.bank.delivery-check=1h"})
@AutoConfigureMockMvc
class BankApiTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    static final List<Map<String, String>> RECEIVED = new CopyOnWriteArrayList<>();
    static final AtomicInteger FAIL_NEXT = new AtomicInteger();
    static final HttpServer PARTNER = partner();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "&currentSchema=bank");
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @TestConfiguration
    static class TestClock {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(Instant.parse("2026-10-05T04:00:00Z"));
        }
    }

    static final OpenApiInteractionValidator CONTRACT = OpenApiInteractionValidator
            .createForInlineApiSpecification(Contracts.read(Contracts.BANK_V1))
            .withBasePathOverride("/")
            .withLevelResolver(LevelResolver.create().withLevel("validation.request", ValidationReport.Level.IGNORE).build())
            .build();
    static final ResultMatcher MATCHES_CONTRACT = openApi().isValid(CONTRACT);
    static final String KEY = "dev-only-partner-key";
    static final String SECRET = "dev-only-webhook-secret";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired MutableClock clock;
    @Autowired Callbacks callbacks;

    UUID user;
    String vpa;

    @BeforeEach
    void customer() throws Exception {
        user = UUID.randomUUID();
        vpa = body(customerPost("/v1/accounts", user, Map.of("holderName", "Asha Rao", "upiPin", "2580"))
                .andExpect(status().isCreated())).path("vpa").asText();
        // tests share one database: deliver anything earlier tests left in the outbox, then start clean
        FAIL_NEXT.set(0);
        clock.advance(Duration.ofMinutes(2));
        for (int i = 0; i < 10 && callbacks.deliverDue() > 0; i++) {
            clock.advance(Duration.ofMinutes(2));
        }
        RECEIVED.clear();
    }

    JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    ResultActions customerPost(String path, UUID who, Object body) throws Exception {
        return mvc.perform(post(path).header("X-User-Id", who.toString()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    ResultActions customerGet(String path, UUID who) throws Exception {
        return mvc.perform(get(path).header("X-User-Id", who.toString()));
    }

    ResultActions partnerPost(String path, Object body) throws Exception {
        return mvc.perform(post(path).header("X-Partner-Key", KEY).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    String collect(String amount) throws Exception {
        return body(partnerPost("/partner/v1/collect-requests", Map.of("payerVpa", vpa, "amount", amount,
                "reference", "dep-" + UUID.randomUUID(), "note", "Add money to Sprout",
                "callbackUrl", "http://127.0.0.1:" + PARTNER.getAddress().getPort() + "/events"))
                .andExpect(status().isCreated())).path("id").asText();
    }

    ResultActions approve(String id, String pin) throws Exception {
        return customerPost("/v1/requests/" + id + "/approve", user, Map.of("upiPin", pin));
    }

    String balance(UUID who) throws Exception {
        return body(customerGet("/v1/accounts/me", who)).path("balance").asText();
    }

    // ── accounts and PINs ────────────────────────────────────────────────────

    @Test
    void anAccountOpensWithPretendMoneyAndAReadableUpiAddress() throws Exception {
        UUID other = UUID.randomUUID();
        customerPost("/v1/accounts", other, Map.of("holderName", "Asha Rao", "upiPin", "739153"))
                .andExpect(status().isCreated()).andExpect(MATCHES_CONTRACT)
                .andExpect(jsonPath("$.balance").value("100000.00"))
                .andExpect(jsonPath("$.vpa").value(org.hamcrest.Matchers.matchesPattern("asha\\.rao\\d*@sproutbank")));
        customerPost("/v1/accounts", other, Map.of("holderName", "Asha Rao", "upiPin", "739153"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ACCOUNT_EXISTS"));
        customerGet("/v1/transactions", other).andExpect(status().isOk()).andExpect(MATCHES_CONTRACT)
                .andExpect(jsonPath("$.transactions[0].direction").value("IN"));
    }

    @Test
    void easyPinsAreRefused() throws Exception {
        for (String pin : new String[] {"1111", "1234", "654321", "12a4", "12345", ""}) {
            customerPost("/v1/accounts", UUID.randomUUID(), Map.of("holderName", "Ravi", "upiPin", pin))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/v1/accounts/me")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    // ── collect requests ─────────────────────────────────────────────────────

    @Test
    void approvingWithThePinMovesTheMoneyAndTellsThePartner() throws Exception {
        String id = collect("500.25");
        customerGet("/v1/requests?status=PENDING", user).andExpect(status().isOk()).andExpect(MATCHES_CONTRACT)
                .andExpect(jsonPath("$.requests[0].payeeName").value("Sprout Investments"));
        approve(id, "2580").andExpect(status().isOk()).andExpect(MATCHES_CONTRACT).andExpect(jsonPath("$.status").value("APPROVED"));
        assertThat(balance(user)).isEqualTo("99499.75");
        approve(id, "2580").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REQUEST_NOT_PENDING"));

        assertThat(callbacks.deliverDue()).isEqualTo(1);
        Map<String, String> got = RECEIVED.get(0);
        assertThat(got.get("signature")).isEqualTo("sha256=" + Callbacks.sign(SECRET, got.get("body")));
        JsonNode event = json.readTree(got.get("body"));
        assertThat(event.path("type").asText()).isEqualTo("COLLECT_APPROVED");
        assertThat(event.path("requestId").asText()).isEqualTo(id);
        assertThat(event.path("amount").asText()).isEqualTo("500.25");
    }

    @Test
    void theSameReferenceIsTheSameRequest() throws Exception {
        Map<String, String> req = Map.of("payerVpa", vpa, "amount", "10", "reference", "dep-fixed-" + user,
                "callbackUrl", "http://127.0.0.1:1/x");
        String first = body(partnerPost("/partner/v1/collect-requests", req).andExpect(status().isCreated())).path("id").asText();
        partnerPost("/partner/v1/collect-requests", req).andExpect(status().isOk()).andExpect(MATCHES_CONTRACT)
                .andExpect(jsonPath("$.id").value(first));
    }

    @Test
    void wrongPinsCountDownThenLockForFifteenMinutes() throws Exception {
        String id = collect("100");
        approve(id, "1357").andExpect(status().isUnprocessableEntity()).andExpect(MATCHES_CONTRACT)
                .andExpect(jsonPath("$.code").value("INVALID_PIN")).andExpect(jsonPath("$.attemptsLeft").value(2));
        approve(id, "1357").andExpect(jsonPath("$.attemptsLeft").value(1));
        approve(id, "1357").andExpect(status().isLocked()).andExpect(jsonPath("$.code").value("PIN_LOCKED"))
                .andExpect(header().exists("Retry-After"));
        approve(id, "2580").andExpect(status().isLocked());
        assertThat(balance(user)).isEqualTo("100000.00");
        clock.advance(Duration.ofMinutes(15).plusSeconds(1));
        approve(collect("100"), "2580").andExpect(status().isOk());
    }

    @Test
    void notEnoughMoneyLeavesTheRequestWaiting() throws Exception {
        String id = collect("100000.01");
        approve(id, "2580").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"));
        customerGet("/v1/requests?status=PENDING", user).andExpect(jsonPath("$.requests[0].id").value(id));
    }

    @Test
    void declinedAndExpiredRequestsAreReportedToo() throws Exception {
        String declined = collect("50");
        customerPost("/v1/requests/" + declined + "/decline", user, Map.of()).andExpect(status().isOk()).andExpect(MATCHES_CONTRACT)
                .andExpect(jsonPath("$.status").value("DECLINED"));
        String expires = collect("60");
        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        mvc.perform(post("/v1/requests/" + expires + "/approve").header("X-User-Id", user.toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"upiPin\":\"2580\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value("EXPIRED"));
        callbacks.deliverDue();
        assertThat(RECEIVED).extracting(r -> json.readTree(r.get("body")).path("type").asText())
                .containsExactlyInAnyOrder("COLLECT_DECLINED", "COLLECT_EXPIRED");
    }

    @Test
    void someoneElseCantApproveMyRequest() throws Exception {
        String id = collect("10");
        UUID stranger = UUID.randomUUID();
        customerPost("/v1/accounts", stranger, Map.of("holderName", "Stranger", "upiPin", "8642")).andExpect(status().isCreated());
        customerPost("/v1/requests/" + id + "/approve", stranger, Map.of("upiPin", "8642"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void callbacksAreRetriedUntilThePartnerAcknowledges() throws Exception {
        approve(collect("20"), "2580").andExpect(status().isOk());
        FAIL_NEXT.set(2);
        assertThat(callbacks.deliverDue()).isZero();       // partner answers 500
        clock.advance(Duration.ofSeconds(1));
        assertThat(callbacks.deliverDue()).isZero();       // 500 again
        assertThat(callbacks.deliverDue()).as("not due yet: backoff").isZero();
        clock.advance(Duration.ofSeconds(2));
        assertThat(callbacks.deliverDue()).isEqualTo(1);   // acknowledged
        clock.advance(Duration.ofMinutes(5));
        assertThat(callbacks.deliverDue()).as("never sent twice once acknowledged").isZero();
    }

    // ── partners ─────────────────────────────────────────────────────────────

    @Test
    void partnersNeedTheirKey() throws Exception {
        mvc.perform(get("/partner/v1/vpas/" + vpa).header("X-Partner-Key", "wrong")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_PARTNER_KEY"));
        mvc.perform(get("/partner/v1/vpas/" + vpa).header("X-Partner-Key", KEY)).andExpect(status().isOk()).andExpect(MATCHES_CONTRACT)
                .andExpect(jsonPath("$.holderName").value("Asha Rao"));
        mvc.perform(get("/partner/v1/vpas/nobody@sproutbank").header("X-Partner-Key", KEY)).andExpect(status().isNotFound());
        mvc.perform(get("/partner/v1/vpas/sprout@sproutbank").header("X-Partner-Key", KEY))
                .andExpect(status().isNotFound()); // a business isn't a customer
    }

    @Test
    void payoutsAreIdempotentAndLimitedToWhatThePartnerHas() throws Exception {
        approve(collect("300"), "2580").andExpect(status().isOk());   // Sprout now holds at least ₹300 here
        String ref = "wd-" + UUID.randomUUID();
        Map<String, String> p = Map.of("payeeVpa", vpa, "amount", "120.50", "reference", ref);
        String id = body(partnerPost("/partner/v1/payouts", p).andExpect(status().isCreated()).andExpect(MATCHES_CONTRACT)).path("id").asText();
        partnerPost("/partner/v1/payouts", p).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        assertThat(balance(user)).isEqualTo("99820.50");
        mvc.perform(get("/partner/v1/payouts/" + ref).header("X-Partner-Key", KEY)).andExpect(status().isOk()).andExpect(MATCHES_CONTRACT);
        partnerPost("/partner/v1/payouts", Map.of("payeeVpa", vpa, "amount", "99999999", "reference", "wd-" + UUID.randomUUID()))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"));
    }

    @Test
    void businessesPayEachOtherAndEachSeesTheMoneyByItsReference() throws Exception {
        String clearingKey = "dev-only-clearing-partner-key";
        // the clearing corporation opens with its float and pays Sprout a settlement
        String ref = "scc:" + UUID.randomUUID();
        mvc.perform(post("/partner/v1/payouts").header("X-Partner-Key", clearingKey).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("payeeVpa", "sprout@sproutbank", "amount", "2500.75", "reference", ref))))
                .andExpect(status().isCreated()).andExpect(MATCHES_CONTRACT);
        JsonNode in = body(mvc.perform(get("/partner/v1/transactions").param("reference", ref).header("X-Partner-Key", KEY))
                .andExpect(status().isOk()).andExpect(MATCHES_CONTRACT)).path("transactions");
        assertThat(in.size()).isEqualTo(1);
        assertThat(in.get(0).path("direction").asText()).isEqualTo("IN");
        assertThat(in.get(0).path("amount").asText()).isEqualTo("2500.75");
        assertThat(in.get(0).path("counterparty").asText()).isEqualTo("clearing@sproutbank");
        JsonNode out = body(mvc.perform(get("/partner/v1/transactions").param("reference", ref).header("X-Partner-Key", clearingKey)))
                .path("transactions");
        assertThat(out.get(0).path("direction").asText()).isEqualTo("OUT");
        // and Sprout pays the clearing corporation back the same way; nobody pays themselves
        partnerPost("/partner/v1/payouts", Map.of("payeeVpa", "clearing@sproutbank", "amount", "500.00", "reference", "pay-" + UUID.randomUUID()))
                .andExpect(status().isCreated());
        partnerPost("/partner/v1/payouts", Map.of("payeeVpa", "sprout@sproutbank", "amount", "1.00", "reference", "self-" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
        assertThat(body(mvc.perform(get("/partner/v1/transactions").param("reference", "nothing-like-this").header("X-Partner-Key", KEY)))
                .path("transactions").size()).isZero();
        mvc.perform(get("/partner/v1/transactions").header("X-Partner-Key", "wrong")).andExpect(status().isUnauthorized());
    }

    @Test
    void theBankPageIsServed() throws Exception {
        mvc.perform(get("/app")).andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Sprout Bank")));
    }

    /** A stand-in for Sprout's payments service, receiving callbacks. */
    static HttpServer partner() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/events", ex -> {
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (FAIL_NEXT.get() > 0) {
                    FAIL_NEXT.decrementAndGet();
                    ex.sendResponseHeaders(500, -1);
                } else {
                    RECEIVED.add(Map.of("body", body, "signature", ex.getRequestHeaders().getFirst("X-Bank-Signature")));
                    ex.sendResponseHeaders(204, -1);
                }
                ex.close();
            });
            s.start();
            return s;
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
