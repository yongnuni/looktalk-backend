package com.looktalk.backend.global.security;

import com.looktalk.backend.global.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

// 401 처리.
// JwtAuthenticationFilter가 남긴 request attribute가 있으면 COMMON_TOKEN_INVALID/COMMON_TOKEN_EXPIRED를 사용하고,
// 없으면(=Authorization Header 자체가 없는 경우) COMMON_UNAUTHORIZED를 사용한다.
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final SecurityResponseWriter responseWriter;

    public RestAuthenticationEntryPoint(SecurityResponseWriter responseWriter) {
        this.responseWriter = responseWriter;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException
    ) throws IOException {
        ErrorCode errorCode = (ErrorCode) request.getAttribute(JwtAuthenticationFilter.AUTH_ERROR_ATTRIBUTE);

        if (errorCode == null) {
            errorCode = ErrorCode.COMMON_UNAUTHORIZED;
        }

        responseWriter.write(response, errorCode);
    }
}
