package com.looktalk.backend.chat.websocket;

import com.looktalk.backend.global.security.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

// docs/LookTalk_Backend_API_Spec_v2.1.md 58. WebSocket 실시간 메시지
// `/ws` HTTP handshake는 SecurityConfig에서 permitAll로 열어두지만, 실질적인 인증 경계는 여기(STOMP CONNECT)다.
// 기존 JwtAuthenticationFilter와 동일하게 JwtTokenProvider로 검증하고, userId를 canonical Principal.getName()으로
// 사용한다(loginId/email/displayName을 routing key로 쓰지 않는다). 새 JWT parser를 만들지 않고 재사용한다.
@Component
public class WebSocketAuthChannelInterceptor implements ChannelInterceptor {

    // docs/LookTalk_Backend_API_Spec_v2.1.md 58. + docs/LookTalk_Backend_Phase2_Implementation_Plan.md 7.
    // client가 구독할 수 있는 destination은 이 두 개뿐이다.
    private static final Set<String> ALLOWED_SUBSCRIBE_DESTINATIONS = Set.of("/user/queue/chat", "/user/queue/emergency");
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String ROLE_CLAIM = "role";

    private final JwtTokenProvider jwtTokenProvider;

    public WebSocketAuthChannelInterceptor(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        // StompHeaderAccessor.wrap(message)로 새로 감싸면 setUser() 등의 변경이 세션에 반영되지 않는다.
        // 디코더가 붙여둔, 아직 mutable한 원본 accessor를 그대로 가져와야 CONNECT에서 설정한 Principal이
        // 이후 SUBSCRIBE 등 같은 세션의 다른 frame에서도 accessor.getUser()로 조회된다.
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        StompCommand command = accessor.getCommand();

        if (StompCommand.CONNECT.equals(command)) {
            authenticateConnect(accessor);
        } else if (StompCommand.SUBSCRIBE.equals(command)) {
            requireAuthenticatedSubscription(accessor);
        } else if (StompCommand.SEND.equals(command)) {
            // REST CHAT-006만 메시지 생성 Source of Truth다. client SEND frame은 이번 단계에서 지원하지 않는다.
            throw new MessagingException("STOMP SEND는 지원하지 않는다. 메시지 생성은 REST CHAT-006만 사용한다.");
        }

        return message;
    }

    private void authenticateConnect(StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            throw new MessagingException("STOMP CONNECT에 Authorization: Bearer {accessToken} header가 필요하다.");
        }

        String token = header.substring(BEARER_PREFIX.length());
        Claims claims;
        try {
            claims = jwtTokenProvider.parseClaims(token);
        } catch (JwtException | IllegalArgumentException exception) {
            // 원문 token/malformed 값을 로그에 남기지 않는다.
            throw new MessagingException("STOMP CONNECT의 Access Token이 유효하지 않다.");
        }

        String userId = claims.getSubject();
        String role = claims.get(ROLE_CLAIM, String.class);

        Authentication authentication = new UsernamePasswordAuthenticationToken(
                userId, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        accessor.setUser(authentication);
    }

    private void requireAuthenticatedSubscription(StompHeaderAccessor accessor) {
        if (accessor.getUser() == null) {
            throw new MessagingException("인증되지 않은 세션은 구독할 수 없다.");
        }
        if (!ALLOWED_SUBSCRIBE_DESTINATIONS.contains(accessor.getDestination())) {
            throw new MessagingException("허용되지 않은 구독 destination이다.");
        }
    }
}
