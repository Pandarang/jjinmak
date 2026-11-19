package com.jjinmak.boundedcontext.market.auction.app.support;

import com.jjinmak.boundedcontext.market.auction.app.support.AuctionRetrySupport.Operation;
import com.jjinmak.boundedcontext.market.auction.domain.EnglishAuction;
import com.jjinmak.boundedcontext.market.auction.exception.AuctionErrorCode;
import com.jjinmak.common.exception.BusinessException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuctionRetrySupportTest {

    private SimpleMeterRegistry registry;
    private List<Long> sleeps;
    private AuctionRetrySupport retrySupport;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        sleeps = new ArrayList<>();
        retrySupport = new AuctionRetrySupport(registry, sleeps::add);
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        Thread.interrupted();
    }

    @Test
    void 첫_시도에_성공하면_재시도하지_않는다() {
        AtomicInteger calls = new AtomicInteger();

        String result = retrySupport.execute(Operation.BID, () -> {
            calls.incrementAndGet();
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(1);
        assertThat(sleeps).isEmpty();
        assertThat(attempts("bid")).isEqualTo(1);
        assertThat(requests("bid", "success")).isEqualTo(1);
    }

    @Test
    void 충돌_후_다시_시도해_성공하면_결과를_돌려준다() {
        Supplier<String> action = failTimes(1, "ok");

        String result = retrySupport.execute(Operation.BID, action);

        assertThat(result).isEqualTo("ok");
        assertThat(sleeps).hasSize(1);
        assertThat(sleeps.get(0)).isBetween(AuctionRetrySupport.MIN_BACKOFF_MS, AuctionRetrySupport.MAX_BACKOFF_MS);
        assertThat(attempts("bid")).isEqualTo(2);
        assertThat(requests("bid", "success")).isEqualTo(1);
    }

    @Test
    void 세_번_모두_충돌하면_AUCTION008로_바꿔_던진다() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> retrySupport.execute(Operation.BID, () -> {
            calls.incrementAndGet();
            throw conflict();
        }))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AuctionErrorCode.BID_CONFLICT);

        assertThat(calls).hasValue(AuctionRetrySupport.MAX_ATTEMPTS);
        assertThat(sleeps).hasSize(AuctionRetrySupport.MAX_ATTEMPTS - 1);
        assertThat(sleeps.get(1)).isBetween(AuctionRetrySupport.MIN_BACKOFF_MS * 2, AuctionRetrySupport.MAX_BACKOFF_MS * 2);
        assertThat(requests("bid", "exhausted")).isEqualTo(1);
    }

    @Test
    void 업무_예외는_재시도하지_않고_그대로_던진다() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> retrySupport.execute(Operation.BID, () -> {
            calls.incrementAndGet();
            throw new BusinessException(AuctionErrorCode.BID_PRICE_TOO_LOW);
        }))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AuctionErrorCode.BID_PRICE_TOO_LOW);

        assertThat(calls).hasValue(1);
        assertThat(sleeps).isEmpty();
        assertThat(requests("bid", "rejected")).isEqualTo(1);
    }

    @Test
    void 충돌_뒤_재검증에서_업무_예외가_나면_그_예외로_끝난다() {
        // 더 높은 입찰이 먼저 커밋됐으면 재검증에서 금액 부족으로 끝난다 (재시도 사슬이 끊기는 지점)
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> retrySupport.execute(Operation.BID, () -> {
            if (calls.incrementAndGet() == 1) {
                throw conflict();
            }
            throw new BusinessException(AuctionErrorCode.BID_PRICE_TOO_LOW);
        }))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AuctionErrorCode.BID_PRICE_TOO_LOW);

        assertThat(calls).hasValue(2);
        assertThat(attempts("bid")).isEqualTo(2);
    }

    @Test
    void 지표는_작업별로_나눠_기록한다() {
        retrySupport.execute(Operation.BID, failTimes(1, "ok"));
        retrySupport.execute(Operation.INSTANT_WIN, () -> "ok");

        assertThat(attempts("bid")).isEqualTo(2);
        assertThat(attempts("instant_win")).isEqualTo(1);
    }

    @Test
    void 트랜잭션_안에서_호출하면_막는다() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThatThrownBy(() -> retrySupport.execute(Operation.BID, () -> "ok"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 대기_중_인터럽트되면_AUCTION008로_끝내고_인터럽트_상태를_복원한다() {
        AuctionRetrySupport interrupted = new AuctionRetrySupport(registry, millis -> {
            throw new InterruptedException();
        });

        assertThatThrownBy(() -> interrupted.execute(Operation.BID, failTimes(1, "ok")))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AuctionErrorCode.BID_CONFLICT);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    private Supplier<String> failTimes(int failures, String result) {
        AtomicInteger calls = new AtomicInteger();
        return () -> {
            if (calls.incrementAndGet() <= failures) {
                throw conflict();
            }
            return result;
        };
    }

    private ObjectOptimisticLockingFailureException conflict() {
        return new ObjectOptimisticLockingFailureException(EnglishAuction.class, 1L);
    }

    private double attempts(String operation) {
        return registry.counter("auction.retry.attempts", "operation", operation).count();
    }

    private double requests(String operation, String result) {
        return registry.counter("auction.retry.requests", "operation", operation, "result", result).count();
    }
}
