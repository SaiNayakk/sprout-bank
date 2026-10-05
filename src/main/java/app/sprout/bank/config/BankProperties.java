package app.sprout.bank.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings under {@code sprout.bank} in bank.yml. */
@ConfigurationProperties("sprout.bank")
public record BankProperties(
        String openingBalance,
        Duration collectTtl,
        int pinAttempts,
        Duration pinLockout,
        int bcryptStrength,
        List<Partner> partners) {

    /**
     * A business with an account here that may collect from customers and pay anyone: its account, API
     * key, callback secret, and the money its account opens with (e.g. a clearing corporation's float).
     */
    public record Partner(String name, String displayName, String vpa, String key, String webhookSecret, String openingBalance) {}
}
