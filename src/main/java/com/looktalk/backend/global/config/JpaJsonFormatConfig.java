package com.looktalk.backend.global.config;

import org.hibernate.cfg.MappingSettings;
import org.hibernate.type.format.jackson.Jackson3JsonFormatMapper;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Spring Boot 4.1(spring-boot-starter-jackson)은 Jackson 3(tools.jackson.*)를 기본 JSON 엔진으로 사용하지만,
// classpath에 Jackson 2(com.fasterxml.jackson.*)도 함께 존재해(다른 dependency의 전이 의존성) Hibernate의
// JacksonIntegration 자동 감지가 Jackson 2 기반 JacksonJsonFormatMapper를 기본 선택한다. 그 결과
// @JdbcTypeCode(SqlTypes.JSON)로 매핑한 tools.jackson.databind.JsonNode 필드(Calibration.calibrationData)를
// Hibernate가 역직렬화하지 못해 InvalidDataAccessApiUsageException이 발생한다. 이를 명시적으로
// Jackson3JsonFormatMapper로 고정한다(hibernate.type.json_format_mapper).
@Configuration
public class JpaJsonFormatConfig {

    @Bean
    public HibernatePropertiesCustomizer jackson3JsonFormatMapperCustomizer() {
        return hibernateProperties ->
                hibernateProperties.put(MappingSettings.JSON_FORMAT_MAPPER, new Jackson3JsonFormatMapper());
    }
}
