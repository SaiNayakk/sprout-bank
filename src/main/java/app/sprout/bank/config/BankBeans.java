package app.sprout.bank.config;

import app.sprout.bank.domain.Bank;
import java.time.Clock;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class BankBeans {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Every partner business has its account before the first request arrives. */
    @Bean
    ApplicationRunner partnerAccounts(Bank bank) {
        return args -> bank.ensurePartnerAccounts();
    }
}
