package com.looktalk.backend.global.security;

import com.looktalk.backend.global.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

// 403 처리. 인증은 되었으나 역할 권한이 없는 경우(COMMON_FORBIDDEN).
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final SecurityResponseWriter responseWriter;

    public RestAccessDeniedHandler(SecurityResponseWriter responseWriter) {
        this.responseWriter = responseWriter;
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException
    ) throws IOException {
        responseWriter.write(response, ErrorCode.COMMON_FORBIDDEN);
    }
}
