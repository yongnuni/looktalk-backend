package com.looktalk.backend.calibration.controller;

import com.looktalk.backend.calibration.dto.CalibrationCreateRequest;
import com.looktalk.backend.calibration.dto.CalibrationResponse;
import com.looktalk.backend.calibration.service.CalibrationService;
import com.looktalk.backend.global.response.ApiResponse;
import com.looktalk.backend.global.swagger.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// docs/LookTalk_Backend_Phase2_Implementation_Plan_Updated.md 11. CAL-001/CAL-002
// PATIENT 전용 API이므로 클래스 레벨로 bearerAuth를 적용한다.
@Tag(name = "Calibration", description = "사용자별 공통 gaze calibration 저장/조회 (CAL-001~002)")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME_NAME)
@RestController
@RequestMapping("/api/calibrations")
public class CalibrationController {

    private final CalibrationService calibrationService;

    public CalibrationController(CalibrationService calibrationService) {
        this.calibrationService = calibrationService;
    }

    @Operation(
            summary = "Calibration 저장 (CAL-001)",
            description = "사용자가 측정 결과 모달에서 입력 방식을 선택해 candidate를 적용할 때만 호출한다. "
                    + "기존 active calibration이 있으면 revoke하고 신규 calibration을 active로 저장한다. "
                    + "입력 방식(EYE_TRACKING/BLINK/MOUTH)과 무관한 사용자 공통 gaze calibration이다. PATIENT만 호출할 수 있다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "저장 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "CALIBRATION_INVALID_PAYLOAD - v1 calibrationData 구조 위반"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "COMMON_FORBIDDEN - PATIENT가 아닌 사용자의 호출")
    })
    @PostMapping
    public ResponseEntity<ApiResponse<CalibrationResponse>> createCalibration(
            Principal principal,
            @Valid @RequestBody CalibrationCreateRequest request
    ) {
        CalibrationResponse response = calibrationService.createCalibration(principal.getName(), request);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Calibration이 저장되었습니다.", response));
    }

    @Operation(
            summary = "active Calibration 조회 (CAL-002)",
            description = "현재 로그인 사용자의 active(revoked_at IS NULL) gaze calibration을 조회한다. "
                    + "query parameter는 사용하지 않는다. PATIENT만 호출할 수 있다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "COMMON_FORBIDDEN - PATIENT가 아닌 사용자의 호출"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "CALIBRATION_NOT_FOUND - active calibration이 없음")
    })
    @GetMapping("/active")
    public ApiResponse<CalibrationResponse> getActiveCalibration(Principal principal) {
        return ApiResponse.success(
                "active calibration을 조회했습니다.", calibrationService.getActiveCalibration(principal.getName()));
    }
}
