package com.looktalk.backend.auth.controller;

import com.looktalk.backend.auth.dto.EmailVerificationConfirmRequest;
import com.looktalk.backend.auth.dto.EmailVerificationRequest;
import com.looktalk.backend.auth.dto.EmailVerificationResponse;
import com.looktalk.backend.auth.dto.LoginIdAvailabilityResponse;
import com.looktalk.backend.auth.dto.LoginRequest;
import com.looktalk.backend.auth.dto.LoginResponse;
import com.looktalk.backend.auth.dto.PasswordResetRequest;
import com.looktalk.backend.auth.dto.PatientSignupRequest;
import com.looktalk.backend.auth.dto.PatientSignupResponse;
import com.looktalk.backend.auth.dto.StaffSignupRequest;
import com.looktalk.backend.auth.dto.StaffSignupResponse;
import com.looktalk.backend.auth.dto.TokenRefreshResponse;
import com.looktalk.backend.auth.service.AuthService;
import com.looktalk.backend.global.exception.BusinessException;
import com.looktalk.backend.global.exception.ErrorCode;
import com.looktalk.backend.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// docs/LookTalk_Backend_API_Spec_v2.1.md 17~25. AUTH-001~009
// 모두 PUBLIC API이므로 @SecurityRequirement(bearerAuth)를 붙이지 않는다.
@Tag(name = "Auth", description = "이메일 인증, 회원가입, 로그인, 토큰 재발급, 로그아웃, 비밀번호 재설정 (AUTH-001~009)")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final String REFRESH_COOKIE_NAME = "LOOKTALK_REFRESH";
    private static final String REFRESH_COOKIE_PATH = "/api/auth";
    private static final int LOGIN_ID_MAX_LENGTH = 50;

    private final AuthService authService;
    private final long refreshTokenExpiryDays;

    public AuthController(
            AuthService authService,
            @Value("${looktalk.refresh-token.expiry-days}") long refreshTokenExpiryDays
    ) {
        this.authService = authService;
        this.refreshTokenExpiryDays = refreshTokenExpiryDays;
    }

    @Operation(
            summary = "이메일 인증번호 발송 (AUTH-001)",
            description = "회원가입 또는 비밀번호 재설정을 위한 6자리 이메일 인증번호를 발송한다. "
                    + "purpose=PASSWORD_RESET인 경우 가입 여부와 관계없이 동일한 성공 응답을 반환한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "인증번호 발송 요청 처리 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "AUTH_DUPLICATE_EMAIL - purpose=SIGNUP인데 이미 가입된 이메일"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "502", description = "EMAIL_SEND_FAILED - 이메일 발송 실패")
    })
    @PostMapping("/email-verifications")
    public ResponseEntity<ApiResponse<EmailVerificationResponse>> sendEmailVerification(
            @Valid @RequestBody EmailVerificationRequest request
    ) {
        EmailVerificationResponse response = authService.sendEmailVerification(request.email(), request.purpose());

        return ResponseEntity.ok(ApiResponse.success("인증번호 발송 요청이 처리되었습니다.", response));
    }

    @Operation(
            summary = "이메일 인증번호 확인 (AUTH-002)",
            description = "purpose=SIGNUP이면 { verified: true }를, purpose=PASSWORD_RESET이면 { resetToken } "
                    + "(AUTH-009에서 사용)을 반환한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "이메일 인증 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "AUTH_INVALID_VERIFICATION_CODE / AUTH_VERIFICATION_EXPIRED")
    })
    @PostMapping("/email-verifications/confirm")
    public ResponseEntity<ApiResponse<Object>> confirmEmailVerification(
            @Valid @RequestBody EmailVerificationConfirmRequest request
    ) {
        Object result = authService.confirmEmailVerification(request.email(), request.purpose(), request.code());

        return ResponseEntity.ok(ApiResponse.success("이메일 인증이 완료되었습니다.", result));
    }

    @Operation(
            summary = "로그인 아이디 중복확인 (AUTH-003)",
            description = "users.login_id 존재 여부를 조회한다. 회원가입 시 서버에서 동일 검사를 다시 수행한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "조회 성공")
    })
    @GetMapping("/login-id/availability")
    public ResponseEntity<ApiResponse<LoginIdAvailabilityResponse>> checkLoginIdAvailability(
            @Parameter(description = "중복 확인할 로그인 아이디", example = "patient01")
            @RequestParam String loginId
    ) {
        if (loginId.isBlank() || loginId.length() > LOGIN_ID_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR);
        }

        return ResponseEntity.ok(
                ApiResponse.success("아이디 사용 가능 여부를 확인했습니다.", authService.checkLoginIdAvailability(loginId))
        );
    }

    @Operation(
            summary = "환자 회원가입 (AUTH-004)",
            description = "loginId, email, password로 PATIENT 계정을 생성한다. "
                    + "이메일은 SIGNUP 목적의 인증(AUTH-001/002)이 먼저 완료되어 있어야 한다. "
                    + "이름은 회원가입 시 입력하지 않는다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201", description = "회원가입 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "AUTH_EMAIL_NOT_VERIFIED - 이메일 인증 미완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "AUTH_DUPLICATE_LOGIN_ID / AUTH_DUPLICATE_EMAIL")
    })
    @PostMapping("/patients/signup")
    public ResponseEntity<ApiResponse<PatientSignupResponse>> signupPatient(
            @Valid @RequestBody PatientSignupRequest request
    ) {
        PatientSignupResponse response = authService.signupPatient(request);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("회원가입이 완료되었습니다.", response));
    }

    @Operation(
            summary = "의료진 회원가입 (AUTH-005)",
            description = "loginId, email, password, 병원 초대코드로 STAFF 계정을 생성한다. "
                    + "이메일은 SIGNUP 목적의 인증(AUTH-001/002)이 먼저 완료되어 있어야 한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201", description = "의료진 회원가입 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "AUTH_EMAIL_NOT_VERIFIED / AUTH_INVALID_INVITE_CODE"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "AUTH_DUPLICATE_LOGIN_ID / AUTH_DUPLICATE_EMAIL")
    })
    @PostMapping("/staff/signup")
    public ResponseEntity<ApiResponse<StaffSignupResponse>> signupStaff(
            @Valid @RequestBody StaffSignupRequest request
    ) {
        StaffSignupResponse response = authService.signupStaff(request);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("의료진 회원가입이 완료되었습니다.", response));
    }

    @Operation(
            summary = "로그인 (AUTH-006)",
            description = "PATIENT/STAFF 공통으로 loginId + password로 로그인한다. "
                    + "Access Token은 이 Response의 data.accessToken으로 반환한다. "
                    + "Refresh Token은 Body에 노출하지 않고 `Set-Cookie: LOOKTALK_REFRESH=...; HttpOnly; Secure; "
                    + "SameSite=Lax; Path=/api/auth` 로만 전달한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "로그인 성공. LOOKTALK_REFRESH Cookie가 함께 발급된다.",
                    headers = @Header(name = "Set-Cookie", description = "LOOKTALK_REFRESH=...; HttpOnly; Secure; "
                            + "SameSite=Lax; Path=/api/auth")),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "AUTH_INVALID_CREDENTIALS")
    })
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        AuthService.LoginResult result = authService.login(request);

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, buildRefreshCookie(result.rawRefreshToken()).toString())
                .body(ApiResponse.success("로그인되었습니다.", result.response()));
    }

    @Operation(
            summary = "Access Token 재발급 (AUTH-007)",
            description = "Request Body를 사용하지 않는다. LOOKTALK_REFRESH HttpOnly Cookie를 입력으로 사용한다. "
                    + "Refresh Token Rotation을 적용해 기존 Refresh Token은 폐기하고 새 Refresh Token을 "
                    + "LOOKTALK_REFRESH Cookie로 다시 내려준다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "재발급 성공. 새 LOOKTALK_REFRESH Cookie가 함께 발급된다.",
                    headers = @Header(name = "Set-Cookie", description = "LOOKTALK_REFRESH=...; HttpOnly; Secure; "
                            + "SameSite=Lax; Path=/api/auth")),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "AUTH_REFRESH_TOKEN_MISSING / AUTH_REFRESH_TOKEN_INVALID / "
                            + "AUTH_REFRESH_TOKEN_EXPIRED")
    })
    @PostMapping("/token/refresh")
    public ResponseEntity<ApiResponse<TokenRefreshResponse>> refresh(
            @Parameter(
                    in = io.swagger.v3.oas.annotations.enums.ParameterIn.COOKIE,
                    description = "로그인 시 발급된 Refresh Token Cookie",
                    required = false
            )
            @CookieValue(name = REFRESH_COOKIE_NAME, required = false) String refreshTokenCookie
    ) {
        AuthService.TokenRefreshResult result = authService.refreshAccessToken(refreshTokenCookie);

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, buildRefreshCookie(result.rawRefreshToken()).toString())
                .body(ApiResponse.success("토큰이 재발급되었습니다.", result.response()));
    }

    @Operation(
            summary = "로그아웃 (AUTH-008)",
            description = "LOOKTALK_REFRESH Cookie의 Refresh Token을 폐기하고 Cookie를 만료시킨다. "
                    + "토큰이 없거나 이미 폐기된 상태에서도 멱등적으로 성공 처리한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "로그아웃 처리 완료 (멱등)")
    })
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            @Parameter(
                    in = io.swagger.v3.oas.annotations.enums.ParameterIn.COOKIE,
                    description = "폐기할 Refresh Token Cookie",
                    required = false
            )
            @CookieValue(name = REFRESH_COOKIE_NAME, required = false) String refreshTokenCookie
    ) {
        authService.logout(refreshTokenCookie);

        ResponseCookie expiredCookie = ResponseCookie.from(REFRESH_COOKIE_NAME, "")
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path(REFRESH_COOKIE_PATH)
                .maxAge(0)
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, expiredCookie.toString())
                .body(ApiResponse.success("로그아웃되었습니다.", null));
    }

    @Operation(
            summary = "비밀번호 재설정 (AUTH-009)",
            description = "AUTH-002(purpose=PASSWORD_RESET) 응답으로 받은 resetToken과 새 비밀번호로 "
                    + "비밀번호를 변경한다. 성공 시 해당 사용자의 모든 Refresh Token을 폐기한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "비밀번호 재설정 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "AUTH_RESET_TOKEN_INVALID / AUTH_RESET_TOKEN_EXPIRED")
    })
    @PostMapping("/password/reset")
    public ResponseEntity<ApiResponse<Void>> resetPassword(@Valid @RequestBody PasswordResetRequest request) {
        authService.resetPassword(request);

        return ResponseEntity.ok(ApiResponse.success("비밀번호가 재설정되었습니다.", null));
    }

    // docs/LookTalk_Backend_API_Spec_v2.1.md 8. Request Header 및 Cookie
    private ResponseCookie buildRefreshCookie(String rawRefreshToken) {
        return ResponseCookie.from(REFRESH_COOKIE_NAME, rawRefreshToken)
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path(REFRESH_COOKIE_PATH)
                .maxAge(Duration.ofDays(refreshTokenExpiryDays))
                .build();
    }
}
