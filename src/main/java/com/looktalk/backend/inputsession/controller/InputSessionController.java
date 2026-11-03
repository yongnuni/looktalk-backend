package com.looktalk.backend.inputsession.controller;

import com.looktalk.backend.global.response.ApiResponse;
import com.looktalk.backend.inputsession.dto.InputSessionCreateRequest;
import com.looktalk.backend.inputsession.dto.InputSessionResponse;
import com.looktalk.backend.inputsession.service.InputSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/input-sessions")
@Tag(name = "Input Session", description = "입력 방식별 측정 결과 저장 및 조회 API")
@SecurityRequirement(name = "bearerAuth")
public class InputSessionController {

    private final InputSessionService inputSessionService;

    /**
     * 입력 방식별 측정 결과 저장
     */
    @PostMapping
    @Operation(
            summary = "입력 세션 결과 저장",
            description = "현재 로그인한 PATIENT 사용자의 입력 방식별 측정 결과를 저장합니다."
    )
    public ResponseEntity<ApiResponse<InputSessionResponse>> createInputSession(
            Principal principal,
            @Valid @RequestBody InputSessionCreateRequest request
    ) {
        InputSessionResponse response =
                inputSessionService.createInputSession(principal.getName(), request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("측정 결과가 저장되었습니다.", response));
    }

    /**
     * 입력 방식별 측정 결과 조회
     */
    @GetMapping
    @Operation(
            summary = "입력 세션 결과 조회",
            description = "현재 로그인한 PATIENT 사용자의 측정 결과를 최신순으로 조회합니다."
    )
    public ApiResponse<List<InputSessionResponse>> getInputSessions(
            Principal principal,
            @RequestParam(defaultValue = "CALIBRATION_TEST") String sessionType,
            @RequestParam(defaultValue = "30") int size
    ) {
        List<InputSessionResponse> response =
                inputSessionService.getInputSessions(
                        principal.getName(),
                        sessionType,
                        size
                );

        return ApiResponse.success("측정 이력을 조회했습니다.", response);
    }
}