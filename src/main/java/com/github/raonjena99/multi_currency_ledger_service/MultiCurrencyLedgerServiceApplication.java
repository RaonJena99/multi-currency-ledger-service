package com.github.raonjena99.multi_currency_ledger_service;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import jakarta.annotation.PostConstruct;

@EnableAsync
@EnableScheduling
@SpringBootApplication(excludeName = {
    "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
})
public class MultiCurrencyLedgerServiceApplication {

	@PostConstruct
    public void init() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

	public static void main(String[] args) {
		SpringApplication.run(MultiCurrencyLedgerServiceApplication.class, args);
	}

}
