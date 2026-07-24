package com.looktalk.backend.global.swagger;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI lookTalkOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Look Talk API")
                        .description("얼굴 신호 기반 AAC 시스템 Look Talk 백엔드 API 문서")
                        .version("v1.0.0"));
    }
}