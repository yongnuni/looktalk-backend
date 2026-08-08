package com.looktalk.backend.staff.controller;

import com.looktalk.backend.global.response.ApiResponse;
import com.looktalk.backend.global.swagger.OpenApiConfig;
import com.looktalk.backend.staff.dto.StaffMeResponse;
import com.looktalk.backend.staff.dto.StaffPatientAddRequest;
import com.looktalk.backend.staff.dto.StaffPatientResponse;
import com.looktalk.backend.staff.service.StaffService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// docs/LookTalk_Backend_API_Spec_v2.1.md 33~36. STAFF-001~004
// 전부 STAFF 전용 인증 API이므로 클래스 레벨로 bearerAuth를 적용한다.
@Tag(name = "Staff", description = "의료진 정보 조회, 담당 환자 조회·등록·해제 (STAFF-001~004)")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME_NAME)
@RestController
@RequestMapping("/api/staff/me")
public class StaffController {

    private final StaffService staffService;

    public StaffController(StaffService staffService) {
        this.staffService = staffService;
    }

    @Operation(summary = "의료진 정보 조회 (STAFF-001)", description = "현재 로그인 의료진의 사용자 정보와 소속 병원을 조회한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "COMMON_FORBIDDEN - STAFF가 아닌 사용자의 호출")
    })
    @GetMapping
    public ApiResponse<StaffMeResponse> getMe(Principal principal) {
        return ApiResponse.success("의료진 정보를 조회했습니다.", staffService.getMe(principal.getName()));
    }

    @Operation(
            summary = "담당 환자 목록 조회 (STAFF-002)",
            description = "현재 로그인 의료진에게 연결된 담당 환자 관계를 조회한다. 병원 채팅 상대 목록이나 접근 권한에는 사용하지 않는다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공")
    })
    @GetMapping("/patients")
    public ApiResponse<List<StaffPatientResponse>> getPatients(Principal principal) {
        return ApiResponse.success("담당 환자를 조회했습니다.", staffService.getPatients(principal.getName()));
    }

    @Operation(
            summary = "담당 환자 추가 (STAFF-003)",
            description = "현재 의료진과 지정한 PATIENT 사용자 사이에 담당 관계를 생성한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "담당 관계 생성 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "STAFF_PATIENT_NOT_FOUND - 대상 환자가 존재하지 않음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "STAFF_PATIENT_ALREADY_ASSIGNED - 이미 담당 관계가 존재함")
    })
    @PostMapping("/patients")
    public ResponseEntity<ApiResponse<Void>> addPatient(
            Principal principal,
            @Valid @RequestBody StaffPatientAddRequest request
    ) {
        staffService.addPatient(principal.getName(), request.patientLoginId());

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("담당 환자가 등록되었습니다.", null));
    }

    @Operation(
            summary = "담당 환자 해제 (STAFF-004)",
            description = "현재 staffId와 patientUserId 조합의 담당 관계만 삭제한다. 병원 채팅방과 메시지는 변경하지 않는다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "담당 관계 해제 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "STAFF_PATIENT_NOT_ASSIGNED - 담당 관계가 존재하지 않음")
    })
    @DeleteMapping("/patients/{patientUserId}")
    public ApiResponse<Void> removePatient(
            Principal principal,
            @Parameter(description = "담당 관계를 해제할 환자의 내부 사용자 ID", example = "P001")
            @PathVariable String patientUserId
    ) {
        staffService.removePatient(principal.getName(), patientUserId);

        return ApiResponse.success("담당 관계가 해제되었습니다.", null);
    }
}
