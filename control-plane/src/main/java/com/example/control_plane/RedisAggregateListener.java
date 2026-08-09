package com.example.control_plane;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Consumes periodic latency <em>aggregates</em> published by the load generator
 * on the {@code locust_aggregates} channel. Each message carries a batch of raw
 * response-time samples; those samples feed the {@link AggregateWindow} so the
 * SLA engine performs real percentile math instead of using mock values.
 */
@Configuration
public class RedisAggregateListener implements MessageListener {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private AggregateWindow aggregateWindow;

    @Bean
    RedisMessageListenerContainer aggregateContainer(RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(this, new ChannelTopic("locust_aggregates"));
        return container;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String payload = new String(message.getBody(), StandardCharsets.UTF_8);
            JsonNode root = objectMapper.readTree(payload);

            List<Long> latencies = new ArrayList<>();
            JsonNode latencyArray = root.path("latencies");
            if (latencyArray.isArray()) {
                for (JsonNode node : latencyArray) {
                    latencies.add(node.asLong());
                }
            }

            long errorCount = root.path("error_count").asLong(0);
            long totalRequests = root.path("total_requests").asLong(0);
            long timestamp = root.path("timestamp").asLong(System.currentTimeMillis());

            aggregateWindow.addBatch(latencies, errorCount, totalRequests, timestamp);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}