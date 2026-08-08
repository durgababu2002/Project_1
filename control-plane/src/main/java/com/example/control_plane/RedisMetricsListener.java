package com.example.control_plane;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.nio.charset.StandardCharsets;

@Configuration
public class RedisMetricsListener implements MessageListener {

    @Autowired
    private SimpMessagingTemplate template;

    @Bean
    RedisMessageListenerContainer container(RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(this, new ChannelTopic("locust_metrics"));
        return container;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String jsonPayload = new String(message.getBody(), StandardCharsets.UTF_8);
            template.convertAndSend("/topic/metrics", jsonPayload);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
