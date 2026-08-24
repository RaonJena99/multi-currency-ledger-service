package com.github.raonjena99.multi_currency_ledger_service;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.Ordered;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import jakarta.annotation.PostConstruct;

/**
 * MultiCurrencyLedgerServiceApplication(다중 통화 원장 서비스 애플리케이션)의 루트 실행 클래스입니다.
 * 비동기 처리 및 스케줄링 기능을 활성화합니다.
 */
// 재시도 어드바이스가 트랜잭션 어드바이스보다 반드시 "바깥"에 있어야 한다.
// 그래야 커밋 시점에 터지는 낙관적 락 예외를 재시도가 관측하고, 재시도마다 새 트랜잭션이 시작된다.
// 순서가 뒤집히면 하나의 트랜잭션 안에서 재시도하게 되어(이미 rollback-only) 재시도가 무의미해진다.
//
// 낮은 값이 바깥이다. @EnableTransactionManagement 의 기본 order 는 LOWEST_PRECEDENCE 이므로
// 그보다 한 단계 낮은 값을 명시해 기본값에 우연히 의존하지 않게 고정한다.
@EnableRetry(order = Ordered.LOWEST_PRECEDENCE - 1)
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
