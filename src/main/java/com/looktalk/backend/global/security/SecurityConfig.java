package com.looktalk.backend.global.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

@Configuration
public class SecurityConfig {

    // docs/LookTalk_Backend_API_Spec_v2.1.md 5.1 - 비밀번호는 BCrypt로 해시한다.
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // docs/LookTalk_Backend_API_Spec_v2.1.md 10. 권한 구분 - PUBLIC 목록은 §16 전체 Endpoint 표를 기준으로 한다.
    // JwtAuthenticationFilter는 Bean으로 등록하지 않고 여기서 직접 생성해 Servlet Filter로 이중 등록되지 않도록 한다.
    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            CorsConfigurationSource corsConfigurationSource,
            JwtTokenProvider jwtTokenProvider,
            RestAuthenticationEntryPoint restAuthenticationEntryPoint,
            RestAccessDeniedHandler restAccessDeniedHandler
    ) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/health",
                                "/api/auth/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs/**",
                                // docs/LookTalk_Backend_API_Spec_v2.1.md 58. WebSocket - HTTP upgrade handshake만
                                // permit한다. 이것이 "WebSocket이 인증 없이 사용 가능하다"는 뜻은 아니다: 실질적인
                                // 인증 경계는 WebSocketAuthChannelInterceptor의 STOMP CONNECT 검증이며, JWT가 없거나
                                // 유효하지 않은 세션은 CONNECTED를 받지 못해 메시징을 전혀 사용할 수 없다.
                                "/ws"
                        ).permitAll()
                        // docs/LookTalk_Backend_API_Spec_v2.1.md 10. 권한 구분 - PATIENT/STAFF 전용 API
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/users/me/phone-verifications",
                                "/api/users/me/phone-verifications/confirm"
                        ).hasRole("PATIENT")
                        .requestMatchers("/api/users/me/settings").hasRole("PATIENT")
                        .requestMatchers("/api/memos/**").hasRole("PATIENT")
                        .requestMatchers("/api/phrases/**").hasRole("PATIENT")
                        .requestMatchers("/api/friends/**").hasRole("PATIENT")
                        // docs/LookTalk_Backend_API_Spec_v2.1.md 55. CHAT-004는 PATIENT 전용이다.
                        // CHAT-001~003(/api/chat-contacts, /api/chat-rooms, /api/chat-rooms/hospital)은
                        // PATIENT/STAFF 모두 가능(AUTH)하므로 별도 매처 없이 아래 anyRequest().authenticated()로 처리한다.
                        .requestMatchers(HttpMethod.POST, "/api/chat-rooms/sms").hasRole("PATIENT")
                        .requestMatchers("/api/staff/**").hasRole("STAFF")
                        .anyRequest().authenticated()
                )
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(restAuthenticationEntryPoint)
                        .accessDeniedHandler(restAccessDeniedHandler)
                )
                .addFilterBefore(
                        new JwtAuthenticationFilter(jwtTokenProvider),
                        UsernamePasswordAuthenticationFilter.class
                );

        return http.build();
    }
}
