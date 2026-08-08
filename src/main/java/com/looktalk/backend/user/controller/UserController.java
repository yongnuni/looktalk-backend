package com.looktalk.backend.user.controller;

import com.looktalk.backend.global.response.ApiResponse;
import com.looktalk.backend.global.swagger.OpenApiConfig;
import com.looktalk.backend.user.dto.PhoneVerificationConfirmRequest;
import com.looktalk.backend.user.dto.PhoneVerificationRequest;
import com.looktalk.backend.user.dto.PhoneVerificationResponse;
import com.looktalk.backend.user.dto.UserMeResponse;
import com.looktalk.backend.user.dto.UserNameUpdateRequest;
import com.looktalk.backend.user.dto.UserNameUpdateResponse;
import com.looktalk.backend.user.dto.UserSettingResponse;
import com.looktalk.backend.user.dto.UserSettingUpdateRequest;
import com.looktalk.backend.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.security.Principal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// docs/LookTalk_Backend_API_Spec_v2.1.md 26~32. USER-001~005, SET-001~002
// 전부 인증(AUTH) 필요 API이므로 클래스 레벨로 bearerAuth를 적용한다.
@Tag(name = "User", description = "마이페이지 조회, 이름 설정, 전화번호 인증, 회원탈퇴, 입력 설정 (USER-001~005, SET-001~002)")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME_NAME)
@RestController
@RequestMapping("/api/users/me")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @Operation(summary = "내 정보 조회 (USER-001)", description = "현재 로그인 사용자의 계정 정보와 이름 설정 상태를 조회한다. PATIENT/STAFF 모두 사용한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공")
    })
    @GetMapping
    public ApiResponse<UserMeResponse> getMe(Principal principal) {
        return ApiResponse.success("사용자 정보를 조회했습니다.", userService.getMe(principal.getName()));
    }

    @Operation(
            summary = "이름 설정·수정·삭제 (USER-002)",
            description = "name이 문자열이면 trim 후 설정하고, trim 결과가 blank이거나 name이 null이면 이름을 삭제(NULL)한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "수정 성공")
    })
    @PatchMapping
    public ApiResponse<UserNameUpdateResponse> updateName(
            Principal principal,
            @Valid @RequestBody UserNameUpdateRequest request
    ) {
        return ApiResponse.success(
                "사용자 정보가 수정되었습니다.",
                userService.updateName(principal.getName(), request.name())
        );
    }

    @Operation(
            summary = "전화번호 인증번호 발송 (USER-003)",
            description = "PATIENT가 친구 등록/SMS 채팅 사용 전 본인 전화번호 인증번호를 요청한다. PATIENT만 호출할 수 있다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "발송 요청 처리 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "COMMON_FORBIDDEN - PATIENT가 아닌 사용자의 호출")
    })
    @PostMapping("/phone-verifications")
    public ApiResponse<PhoneVerificationResponse> sendPhoneVerification(
            Principal principal,
            @Valid @RequestBody PhoneVerificationRequest request
    ) {
        return ApiResponse.success(
                "인증번호가 발송되었습니다.",
                userService.sendPhoneVerification(principal.getName(), request.phone())
        );
    }

    @Operation(
            summary = "전화번호 인증 확인 (USER-004)",
            description = "인증 성공 시 users.user_phone과 users.is_sms_verified를 갱신한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "인증 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "AUTH_INVALID_VERIFICATION_CODE / AUTH_VERIFICATION_EXPIRED")
    })
    @PostMapping("/phone-verifications/confirm")
    public ApiResponse<Void> confirmPhoneVerification(
            Principal principal,
            @Valid @RequestBody PhoneVerificationConfirmRequest request
    ) {
        userService.confirmPhoneVerification(principal.getName(), request.phone(), request.code());

        return ApiResponse.success("전화번호 인증이 완료되었습니다.", null);
    }

    @Operation(
            summary = "회원탈퇴 (USER-005)",
            description = "PATIENT/STAFF 모두 담당 관계 유무와 관계없이 탈퇴한다. 탈퇴 시 Refresh Token, 개인 데이터가 함께 정리된다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "회원탈퇴 완료")
    })
    @DeleteMapping
    public ApiResponse<Void> withdraw(Principal principal) {
        userService.withdraw(principal.getName());

        return ApiResponse.success("회원탈퇴가 완료되었습니다.", null);
    }

    @Operation(summary = "사용자 입력 설정 조회 (SET-001)", description = "PATIENT만 호출할 수 있다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "COMMON_FORBIDDEN - PATIENT가 아닌 사용자의 호출")
    })
    @GetMapping("/settings")
    public ApiResponse<UserSettingResponse> getSettings(Principal principal) {
        return ApiResponse.success("사용자 설정을 조회했습니다.", userService.getSettings(principal.getName()));
    }

    @Operation(
            summary = "사용자 입력 설정 수정 (SET-002)",
            description = "PATCH 요청에 포함된 필드만 변경한다. PATIENT만 호출할 수 있다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "수정 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "COMMON_FORBIDDEN - PATIENT가 아닌 사용자의 호출")
    })
    @PatchMapping("/settings")
    public ApiResponse<UserSettingResponse> updateSettings(
            Principal principal,
            @Valid @RequestBody UserSettingUpdateRequest request
    ) {
        return ApiResponse.success("사용자 설정이 수정되었습니다.", userService.updateSettings(principal.getName(), request));
    }
}
