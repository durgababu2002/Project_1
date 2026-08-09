package com.example.control_plane;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the full "real SLA" latency path end-to-end against a real Redis
 * container: a json aggregate published by the load generator is consumed by
 * {@link RedisAggregateListener}, feeds the {@link AggregateWindow}, and the
 * resulting percentile math reflects the actual injected samples.
 */
@Testcontainers
class RedisAggregateListenerIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Test
    void ingestsAggregatesAndComputesRealPercentiles() throws Exception {
        LettuceConnectionFactory factory =
                new LettuceConnectionFactory("localhost", redis.getMappedPort(6379));
        factory.afterPropertiesSet();

        AggregateWindow window = new AggregateWindow(60_000L);
        RedisAggregateListener listener = new RedisAggregateListener();
        ReflectionTestUtils.setField(listener, "aggregateWindow", window);

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.addMessageListener(listener, new ChannelTopic("locust_aggregates"));
        container.afterPropertiesSet();
        container.start();

        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();

        // Batch 1: samples 1..100 via a single aggregate message (nearest-rank).
        String json = """
                {
                  "timestamp": %d,
                  "sample_count": 100,
                  "latencies": [%s],
                  "error_count": 0,
                  "total_requests": 100
                }
                """.formatted(System.currentTimeMillis(), joinRange(1, 100));
        template.convertAndSend("locust_aggregates", json);

        // Async pub/sub: poll until the window receives the samples.
        int attempts = 0;
        while (window.currentSampleCount() < 100 && attempts < 100) {
            Thread.sleep(100);
            attempts++;
        }

        AggregatedMetrics metrics = window.aggregate();
        assertThat(metrics.getSampleCount()).isEqualTo(100);
        assertThat(metrics.getP50()).isEqualTo(50.0);
        assertThat(metrics.getP95()).isEqualTo(95.0);
        assertThat(metrics.getP99()).isEqualTo(99.0);
        assertThat(metrics.getMax()).isEqualTo(100.0);

        container.stop();
        factory.destroy();
    }

    private String joinRange(int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i <= to; i++) {
            if (i > from) sb.append(",");
            sb.append(i);
        }
        return sb.toString();
    }
}