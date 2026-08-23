package com.github.raonjena99.multi_currency_ledger_service.portfolio.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import com.github.raonjena99.multi_currency_ledger_service.portfolio.application.dto.PortfolioCacheDto;

@ExtendWith(MockitoExtension.class)
class RedisPortfolioCacheAdapterTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @InjectMocks
    private RedisPortfolioCacheAdapter adapter;

    @BeforeEach
    void setUp() {
        // leniency for methods not using valueOperations
    }

    @Test
    void getPortfolioCache_should_return_cached_dto() {
        UUID accountId = UUID.randomUUID();
        String key = "portfolio:account:" + accountId;
        
        PortfolioCacheDto dto = mock(PortfolioCacheDto.class);
        
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(key)).thenReturn(dto);

        Optional<PortfolioCacheDto> result = adapter.getPortfolioCache(accountId);

        assertThat(result).isPresent().contains(dto);
    }

    @Test
    void getPortfolioCache_should_return_empty_if_not_found() {
        UUID accountId = UUID.randomUUID();
        String key = "portfolio:account:" + accountId;
        
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(key)).thenReturn(null);

        Optional<PortfolioCacheDto> result = adapter.getPortfolioCache(accountId);

        assertThat(result).isEmpty();
    }

    @Test
    void savePortfolioCache_should_save_dto() {
        UUID accountId = UUID.randomUUID();
        String key = "portfolio:account:" + accountId;
        PortfolioCacheDto dto = mock(PortfolioCacheDto.class);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        adapter.savePortfolioCache(accountId, dto);

        verify(valueOperations).set(eq(key), eq(dto), any(Duration.class));
    }

    @Test
    void evictPortfolioCache_should_delete_key() {
        UUID accountId = UUID.randomUUID();
        String key = "portfolio:account:" + accountId;

        adapter.evictPortfolioCache(accountId);

        verify(redisTemplate).delete(key);
    }

    @Test
    void tryAcquireLock_should_return_true_if_acquired() {
        String lockKey = "lockKey";
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq(lockKey), anyString(), any(Duration.class))).thenReturn(true);

        boolean acquired = adapter.tryAcquireLock(lockKey, 5);

        assertThat(acquired).isTrue();
    }

    @Test
    void tryAcquireLock_should_return_false_if_not_acquired() {
        String lockKey = "lockKey";
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq(lockKey), anyString(), any(Duration.class))).thenReturn(false);

        boolean acquired = adapter.tryAcquireLock(lockKey, 5);

        assertThat(acquired).isFalse();
    }

    @Test
    void releaseLock_should_execute_script_if_token_exists() {
        String lockKey = "lockKey";
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq(lockKey), anyString(), any(Duration.class))).thenReturn(true);

        adapter.tryAcquireLock(lockKey, 5);
        adapter.releaseLock(lockKey);

        verify(redisTemplate).execute(any(RedisScript.class), any(java.util.List.class), anyString());
    }

    @Test
    void releaseLock_should_not_execute_script_if_no_token() {
        String lockKey = "lockKey";
        
        adapter.releaseLock(lockKey);

        verify(redisTemplate, never()).execute(any(RedisScript.class), any(java.util.List.class), anyString());
    }
}
