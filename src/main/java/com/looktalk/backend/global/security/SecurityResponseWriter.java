package com.looktalk.backend.global.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.looktalk.backend.global.exception.ErrorCode;
import com.looktalk.backend.global.response.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

// docs/LookTalk_Backend_API_Spec_v2.1.md 65. Exception 구현
// "Spring Security의 401, 403도 가능한 한 동일한 JSON Error Response 형식으로 반환한다."
// DispatcherServlet 밖(Security Filter 단계)에서 동작하므로 MVC의 ObjectMapper Bean에 의존하지 않고 직접 사용한다.
@Component
public class SecurityResponseWriter {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public void write(HttpServletResponse response, ErrorCode errorCode) throws IOException {
        response.setStatus(errorCode.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.fail(errorCode)));
    }
}
