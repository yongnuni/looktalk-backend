package com.looktalk.backend.chat.websocket;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

// docs/LookTalk_Backend_API_Spec_v2.1.md 58. WebSocket 실시간 메시지
// /ws에 STOMP(SockJS 미사용)를 연결한다. allowed origin은 REST CORS와 동일한
// looktalk.cors.allowed-origins 설정값을 그대로 재사용해, REST/WebSocket origin 정책이 갈라지지 않게 한다.
// client SEND를 사용하지 않으므로 setApplicationDestinationPrefixes("/app")는 설정하지 않는다
// (@MessageMapping 기반 endpoint를 두지 않는다).
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final List<String> allowedOrigins;
    private final WebSocketAuthChannelInterceptor authChannelInterceptor;

    public WebSocketConfig(
            @Value("${looktalk.cors.allowed-origins}") List<String> allowedOrigins,
            WebSocketAuthChannelInterceptor authChannelInterceptor
    ) {
        this.allowedOrigins = allowedOrigins;
        this.authChannelInterceptor = authChannelInterceptor;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOrigins(allowedOrigins.toArray(new String[0]));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // 사용자별 목적지(/user/queue/chat) 전달에 필요한 최소 범위만 활성화한다. room 단위 /topic broadcast는
        // 사용하지 않는다(사용자마다 ciphertext가 다르므로).
        registry.enableSimpleBroker("/queue");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authChannelInterceptor);
    }
}
