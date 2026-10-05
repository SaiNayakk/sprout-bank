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

    /** A business that may collect from and pay customers: its account, API key and callback secret. */
    public record Partner(String name, String displayName, String vpa, String key, String webhookSecret) {}
}
