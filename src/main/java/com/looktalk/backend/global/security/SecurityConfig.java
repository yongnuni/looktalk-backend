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
                                "/v3/api-docs/**"
                        ).permitAll()
                        // docs/LookTalk_Backend_API_Spec_v2.1.md 10. 권한 구분 - PATIENT/STAFF 전용 API
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/users/me/phone-verifications",
                                "/api/users/me/phone-verifications/confirm"
                        ).hasRole("PATIENT")
                        .requestMatchers("/api/users/me/settings").hasRole("PATIENT")
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
