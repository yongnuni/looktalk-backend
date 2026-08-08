package com.looktalk.backend.global.config;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

// docs/LookTalk_Backend_API_Spec_v2.1.md 8. Request Header 및 Cookie - Refresh Token을 HttpOnly Cookie로 주고받으므로
// allowCredentials(true)가 필요하다. Frontend origin은 배포 환경마다 다르므로 설정값(looktalk.cors.allowed-origins)으로
// 분리한다. local 기본값은 http://localhost:5173이며, production은 CORS_ALLOWED_ORIGINS 환경변수로 재정의한다.
@Configuration
public class CorsConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${looktalk.cors.allowed-origins}") List<String> allowedOrigins
    ) {
        CorsConfiguration configuration = new CorsConfiguration();

        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);

        return source;
    }
}
