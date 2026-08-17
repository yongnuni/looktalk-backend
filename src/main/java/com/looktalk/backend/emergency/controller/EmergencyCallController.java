package com.looktalk.backend.emergency.controller;

import com.looktalk.backend.emergency.dto.EmergencyCallCreateResponse;
import com.looktalk.backend.emergency.dto.EmergencyCallPageResponse;
import com.looktalk.backend.emergency.dto.EmergencyCallReadResponse;
import com.looktalk.backend.emergency.service.EmergencyCallService;
import com.looktalk.backend.global.response.ApiResponse;
import com.looktalk.backend.global.swagger.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// docs/LookTalk_Backend_Phase2_Implementation_Plan.md 6. EMERGENCY-001~003
// 이번 Step에서는 WebSocket publish 없이 REST 3종만 구현한다.
@Tag(name = "Emergency", description = "긴급 호출 생성/조회/읽음 처리 (EMERGENCY-001~003)")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME_NAME)
@RestController
@RequestMapping("/api/emergency-calls")
public class EmergencyCallController {

    private final EmergencyCallService emergencyCallService;

    public EmergencyCallController(EmergencyCallService emergencyCallService) {
        this.emergencyCallService = emergencyCallService;
    }

    @Operation(
            summary = "긴급 호출 발생 (EMERGENCY-001)",
            description = "PATIENT 전용. Request body 없이 JWT의 현재 userId로 호출 1건을 저장한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "호출 생성 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "COMMON_FORBIDDEN - PATIENT가 아닌 사용자의 호출")
    })
    @PostMapping
    public ResponseEntity<ApiResponse<EmergencyCallCreateResponse>> createCall(Principal principal) {
        EmergencyCallCreateResponse response = emergencyCallService.createCall(principal.getName());

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("긴급 호출이 접수되었습니다.", response));
    }

    @Operation(
            summary = "담당 환자 긴급 호출 조회 (EMERGENCY-002)",
            description = "STAFF 전용. staff_patient_assignment로 현재 STAFF가 담당하는 환자의 호출만 반환한다. "
                    + "미담당 환자의 호출은 노출하지 않는다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "COMMON_FORBIDDEN - STAFF가 아닌 사용자의 호출")
    })
    @GetMapping
    public ApiResponse<EmergencyCallPageResponse> getAssignedCalls(
            Principal principal,
            @Parameter(description = "페이지 번호(0-base)", example = "0")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기(1~100)", example = "20")
            @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "읽지 않은 호출만 조회할지 여부", example = "false")
            @RequestParam(defaultValue = "false") boolean unreadOnly
    ) {
        return ApiResponse.success(
                "담당 환자의 긴급 호출을 조회했습니다.",
                emergencyCallService.getAssignedCalls(principal.getName(), page, size, unreadOnly)
        );
    }

    @Operation(
            summary = "긴급 호출 읽음 처리 (EMERGENCY-003)",
            description = "STAFF 전용. 담당 환자의 호출만 읽음 처리할 수 있다. 최초 reader만 기록되고 "
                    + "이후 요청은 기존 read 정보를 그대로 반환한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "읽음 처리 완료(또는 기존 읽음 정보 반환)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "EMERGENCY_CALL_NOT_FOUND - 존재하지 않거나 현재 STAFF가 담당하지 않는 호출")
    })
    @PatchMapping("/{emergencyCallId}/read")
    public ApiResponse<EmergencyCallReadResponse> markRead(
            Principal principal,
            @Parameter(description = "읽음 처리할 긴급 호출 ID", example = "10")
            @PathVariable Long emergencyCallId
    ) {
        return ApiResponse.success(
                "긴급 호출을 읽음 처리했습니다.",
                emergencyCallService.markRead(principal.getName(), emergencyCallId)
        );
    }
}
