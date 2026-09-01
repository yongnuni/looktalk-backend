package com.looktalk.backend.sms.controller;

import com.looktalk.backend.global.response.ApiResponse;
import com.looktalk.backend.global.swagger.OpenApiConfig;
import com.looktalk.backend.sms.dto.SmsInboundNumberAssignmentRequest;
import com.looktalk.backend.sms.dto.SmsInboundNumberAssignmentResponse;
import com.looktalk.backend.sms.entity.SmsInboundNumberAssignment;
import com.looktalk.backend.sms.entity.SmsProvider;
import com.looktalk.backend.sms.service.SmsInboundNumberAssignmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 환자별 SMS 회신 수신용 서비스 번호(U+ MO 수신번호) 배정 관리. 번호 자체의 구매/발급은 외부 계약/콘솔에서
// 이뤄지고, 여기서는 이미 확보한 번호를 환자에게 매핑하는 것만 담당한다. 일반 PATIENT는 스스로 자신의 번호를
// 등록할 수 없다 - /api/staff/** 하위라 SecurityConfig의 기존 hasRole("STAFF") 규칙을 그대로 상속받는다
// (이 endpoint만을 위한 별도 SecurityConfig 규칙을 추가하지 않는다).
@Tag(name = "Sms", description = "환자별 SMS 회신 수신 서비스 번호 배정(STAFF 전용, 운영 관리용)")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME_NAME)
@RestController
@RequestMapping("/api/staff/patients/{patientUserId}/sms-inbound-number")
public class SmsInboundNumberAssignmentController {

    private final SmsInboundNumberAssignmentService assignmentService;

    public SmsInboundNumberAssignmentController(SmsInboundNumberAssignmentService assignmentService) {
        this.assignmentService = assignmentService;
    }

    @Operation(
            summary = "환자에게 SMS 회신 번호 배정",
            description = "이미 외부(U+ 메시지허브)에서 확보한 MO 수신용 서비스 번호를 환자에게 배정한다. 기존 "
                    + "active 배정이 있으면 비활성화하고 새 배정으로 교체한다(history 유지)."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "배정 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "USER_NOT_FOUND - 대상 환자가 존재하지 않음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "SMS_INBOUND_NUMBER_ALREADY_ASSIGNED - 해당 번호가 이미 다른 환자에게 배정됨")
    })
    @PostMapping
    public ApiResponse<SmsInboundNumberAssignmentResponse> assign(
            @Parameter(description = "번호를 배정할 환자의 내부 사용자 ID", example = "P001")
            @PathVariable String patientUserId,
            @Valid @RequestBody SmsInboundNumberAssignmentRequest request
    ) {
        SmsInboundNumberAssignment assignment =
                assignmentService.assign(patientUserId, request.serviceNumber(), SmsProvider.UPLUS_MO);

        return ApiResponse.success("SMS 회신 번호가 배정되었습니다.", toResponse(assignment));
    }

    @Operation(
            summary = "환자의 SMS 회신 번호 배정 해제",
            description = "환자의 현재 active 배정을 비활성화한다(history는 유지, 행을 삭제하지 않는다)."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "해제 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "SMS_INBOUND_NUMBER_NOT_ASSIGNED - active 배정이 없음")
    })
    @DeleteMapping
    public ApiResponse<Void> deactivate(
            @Parameter(description = "배정을 해제할 환자의 내부 사용자 ID", example = "P001")
            @PathVariable String patientUserId
    ) {
        assignmentService.deactivate(patientUserId);
        return ApiResponse.success("SMS 회신 번호 배정이 해제되었습니다.", null);
    }

    private SmsInboundNumberAssignmentResponse toResponse(SmsInboundNumberAssignment assignment) {
        return new SmsInboundNumberAssignmentResponse(
                assignment.getAssignmentId(),
                assignment.getUser().getUserId(),
                assignment.getServiceNumber(),
                assignment.isActive(),
                assignment.getActivatedAt()
        );
    }
}
