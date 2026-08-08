package com.looktalk.backend.global.security;

import com.looktalk.backend.global.exception.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

// docs/LookTalk_Backend_API_Spec_v2.1.md 9. 인증 구조
// HTTP Request -> JwtAuthenticationFilter -> Access Token 검증 -> userId/role 추출 -> SecurityContext 등록
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    // AuthenticationEntryPoint가 401 세부 Error Code(COMMON_TOKEN_INVALID/COMMON_TOKEN_EXPIRED)를 구분하기 위해 사용하는 request attribute.
    public static final String AUTH_ERROR_ATTRIBUTE = "looktalk.auth.error";

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length());

            try {
                Claims claims = jwtTokenProvider.parseClaims(token);
                String userId = claims.getSubject();
                String role = claims.get("role", String.class);

                Authentication authentication = new UsernamePasswordAuthenticationToken(
                        userId,
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))
                );
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (ExpiredJwtException exception) {
                request.setAttribute(AUTH_ERROR_ATTRIBUTE, ErrorCode.COMMON_TOKEN_EXPIRED);
            } catch (JwtException | IllegalArgumentException exception) {
                request.setAttribute(AUTH_ERROR_ATTRIBUTE, ErrorCode.COMMON_TOKEN_INVALID);
            }
        }

        filterChain.doFilter(request, response);
    }
}
