package com.github.raonjena99.multi_currency_ledger_service;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import jakarta.annotation.PostConstruct;

/**
 * MultiCurrencyLedgerServiceApplication(다중 통화 원장 서비스 애플리케이션)의 루트 실행 클래스입니다.
 * 비동기 처리 및 스케줄링 기능을 활성화합니다.
 */
@EnableRetry 
@EnableAsync
@EnableScheduling
@SpringBootApplication(excludeName = {
    "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
})
public class MultiCurrencyLedgerServiceApplication {

	/**
	 * 애플리케이션 초기화 시 기본 시간대(TimeZone)를 UTC로 설정합니다.
	 */
	@PostConstruct
    public void init() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

	/**
	 * Spring Boot 애플리케이션의 메인 진입점(Entry Point)입니다.
	 *
	 * @param args 애플리케이션 실행 시 전달되는 명령줄 인수(Command-line arguments)
	 */
	public static void main(String[] args) {
		SpringApplication.run(MultiCurrencyLedgerServiceApplication.class, args);
	}

}
