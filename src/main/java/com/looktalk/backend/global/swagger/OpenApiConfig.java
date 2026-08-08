package com.looktalk.backend.global.swagger;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// docs/LookTalk_Backend_API_Spec_v2.1.md 9. 인증 구조 - Authorization: Bearer {accessToken}
// bearerAuth Scheme만 공통으로 등록한다. 전역 SecurityRequirement는 적용하지 않는다.
// 인증이 필요한 API는 각 Controller/Method에서 개별적으로 @SecurityRequirement(name = "bearerAuth")를 붙인다.
@Configuration
public class OpenApiConfig {

    public static final String BEARER_AUTH_SCHEME_NAME = "bearerAuth";

    @Bean
    public OpenAPI lookTalkOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Look Talk API")
                        .description("얼굴 신호 기반 AAC 시스템 Look Talk 백엔드 API 문서")
                        .version("v1.0.0"))
                .components(new Components()
                        .addSecuritySchemes(BEARER_AUTH_SCHEME_NAME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("로그인(AUTH-006) 또는 토큰 재발급(AUTH-007) 응답의 accessToken을 사용한다.")
                        ));
    }
}
