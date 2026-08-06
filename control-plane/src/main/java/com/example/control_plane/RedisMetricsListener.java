package com.example.control_plane;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;
import org.springframework.messaging.simp.SimpMessagingTemplate;

@Configuration
public class RedisMetricsListener {

    @Autowired
    private SimpMessagingTemplate template;

    @Bean
    RedisMessageListenerContainer container(RedisConnectionFactory connectionFactory,
                                            MessageListenerAdapter listenerAdapter) {

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(listenerAdapter, new ChannelTopic("locust_metrics"));
        return container;
    }

    @Bean
    MessageListenerAdapter listenerAdapter(MetricsReceiver receiver) {
        return new MessageListenerAdapter(receiver, "receiveMessage");
    }

    @Bean
    MetricsReceiver receiver() {
        return new MetricsReceiver(template);
    }

    public static class MetricsReceiver {
        private final SimpMessagingTemplate template;

        public MetricsReceiver(SimpMessagingTemplate template) {
            this.template = template;
        }

        public void receiveMessage(String message) {
            // Forward the raw JSON string from Redis directly to WebSocket clients
            template.convertAndSend("/topic/metrics", message);
        }
    }
}
