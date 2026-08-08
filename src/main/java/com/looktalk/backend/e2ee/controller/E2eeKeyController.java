package com.looktalk.backend.e2ee.controller;

import com.looktalk.backend.e2ee.dto.E2eeKeyRegisterRequest;
import com.looktalk.backend.e2ee.dto.E2eeKeyRegisterResponse;
import com.looktalk.backend.e2ee.dto.E2eeKeyStatusResponse;
import com.looktalk.backend.e2ee.dto.E2eeUserPublicKeyResponse;
import com.looktalk.backend.e2ee.service.E2eeKeyService;
import com.looktalk.backend.global.response.ApiResponse;
import com.looktalk.backend.global.swagger.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// docs/LookTalk_Backend_API_Spec_v2.1.md 37~39. E2EE-001~003
// 전부 인증(AUTH) 필요 API이므로 클래스 레벨로 bearerAuth를 적용한다.
@Tag(name = "E2EE", description = "사용자 공개키 등록·조회 (E2EE-001~003)")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME_NAME)
@RestController
@RequestMapping("/api/e2ee")
public class E2eeKeyController {

    private final E2eeKeyService e2eeKeyService;

    public E2eeKeyController(E2eeKeyService e2eeKeyService) {
        this.e2eeKeyService = e2eeKeyService;
    }

    @Operation(
            summary = "공개키 등록 (E2EE-001)",
            description = "Frontend에서 생성한 현재 사용자의 E2EE 공개키를 등록한다. 개인키는 Request에 포함하지 않는다. "
                    + "keyVersion은 클라이언트가 지정하지 않는다 - 서버가 현재 사용자의 다음 순번"
                    + "(현재 최대 버전 + 1, 최초 등록이면 1)을 계산해 저장하고 Response로 반환한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "공개키 등록 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "E2EE_INVALID_PAYLOAD - 공개키가 Base64 형식이 아니거나 디코딩 결과가 32바이트가 아님")
    })
    @PostMapping("/keys")
    public ResponseEntity<ApiResponse<E2eeKeyRegisterResponse>> registerKey(
            Principal principal,
            @Valid @RequestBody E2eeKeyRegisterRequest request
    ) {
        E2eeKeyRegisterResponse response = e2eeKeyService.registerKey(principal.getName(), request);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("암호화 공개키가 등록되었습니다.", response));
    }

    @Operation(
            summary = "내 공개키 등록 상태 조회 (E2EE-002)",
            description = "현재 로그인 사용자의 공개키 등록 여부와 현재 keyVersion을 확인한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공")
    })
    @GetMapping("/keys/me")
    public ApiResponse<E2eeKeyStatusResponse> getMyKeyStatus(Principal principal) {
        return ApiResponse.success("암호화 키 정보를 조회했습니다.", e2eeKeyService.getMyKeyStatus(principal.getName()));
    }

    @Operation(
            summary = "사용자 공개키 조회 (E2EE-003)",
            description = "병원 채팅 메시지를 암호화하기 위해 상대 내부 회원의 현재 활성 공개키를 조회한다. "
                    + "폐기된 키는 제외하고, 다른 사용자의 개인정보는 응답에 포함하지 않는다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "USER_NOT_FOUND - 대상 사용자가 존재하지 않음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "E2EE_TARGET_KEY_REQUIRED - 대상 사용자의 공개키가 등록되지 않음")
    })
    @GetMapping("/users/{userId}/public-key")
    public ApiResponse<E2eeUserPublicKeyResponse> getUserPublicKey(
            @Parameter(description = "공개키를 조회할 내부 사용자 ID", example = "U100")
            @PathVariable String userId
    ) {
        return ApiResponse.success("사용자 공개키를 조회했습니다.", e2eeKeyService.getUserPublicKey(userId));
    }
}
