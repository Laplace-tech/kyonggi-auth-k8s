package com.kyonggi.backend.infra;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class TestClockConfig {

    public static final ZoneId TEST_ZONE = ZoneId.of("Asia/Seoul");
    public static final Instant TEST_START = LocalDateTime.of(2026, 1, 1, 0, 0).atZone(TEST_ZONE).toInstant();
    public static final MutableClock TEST_CLOCK = new MutableClock(TEST_START, TEST_ZONE);

    public static void reset() {
        TEST_CLOCK.set(TEST_START);
    }

    @Bean
    @Primary // 앱에 Clock Bean이 있어도 테스트에선 이게 우선
    Clock clock() {
        return TEST_CLOCK;
    }

    public static final class MutableClock extends Clock {
        private final ZoneId zone;
        private final AtomicReference<Instant> now;

        public MutableClock(Instant initialInstant, ZoneId zone) {
            this.zone = zone;
            this.now = new AtomicReference<>(initialInstant);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(now.get(), zone);
        }

        @Override
        public Instant instant() {
            return now.get();
        }

        public void set(Instant instant) {
            now.set(instant);
        }

        public void advance(Duration d) {
            now.updateAndGet(i -> i.plus(d));
        }
    }
}