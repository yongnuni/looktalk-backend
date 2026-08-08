package com.looktalk.backend.memo.controller;

import com.looktalk.backend.global.response.ApiResponse;
import com.looktalk.backend.global.swagger.OpenApiConfig;
import com.looktalk.backend.memo.dto.MemoRequest;
import com.looktalk.backend.memo.dto.MemoResponse;
import com.looktalk.backend.memo.service.MemoService;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// docs/LookTalk_Backend_API_Spec_v2.1.md 40~43. MEMO-001~004
// PATIENT 전용 API이므로 클래스 레벨로 bearerAuth를 적용한다.
@Tag(name = "Memo", description = "E2EE 기반 개인 메모 CRUD (MEMO-001~004)")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME_NAME)
@RestController
@RequestMapping("/api/memos")
public class MemoController {

    private final MemoService memoService;

    public MemoController(MemoService memoService) {
        this.memoService = memoService;
    }

    @Operation(
            summary = "메모 조회 (MEMO-001)",
            description = "현재 로그인 환자의 암호화된 개인 메모 목록을 최근 수정 순으로 조회한다. "
                    + "Backend는 메모 평문을 복호화하지 않는다. PATIENT만 호출할 수 있다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공")
    })
    @GetMapping
    public ApiResponse<List<MemoResponse>> getMemos(Principal principal) {
        return ApiResponse.success("메모를 조회했습니다.", memoService.getMemos(principal.getName()));
    }

    @Operation(
            summary = "메모 등록 (MEMO-002)",
            description = "Frontend에서 암호화한 ciphertext만 전달받아 저장한다. keyVersion은 현재 사용자에게 "
                    + "실제 등록된 키 버전이어야 한다. algorithm은 LIBSODIUM_SEALED_BOX만 허용한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "메모 등록 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "E2EE_INVALID_PAYLOAD - algorithm 불일치 또는 ciphertext Base64 형식 오류"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "E2EE_KEY_NOT_FOUND - keyVersion이 본인 소유가 아님"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "E2EE_KEY_REQUIRED - 현재 사용자의 E2EE 공개키가 등록되지 않음")
    })
    @PostMapping
    public ResponseEntity<ApiResponse<MemoResponse>> createMemo(
            Principal principal,
            @Valid @RequestBody MemoRequest request
    ) {
        MemoResponse response = memoService.createMemo(principal.getName(), request);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("메모가 등록되었습니다.", response));
    }

    @Operation(
            summary = "메모 수정 (MEMO-003)",
            description = "본인 소유 메모의 ciphertext/keyVersion을 교체한다. 본인 메모가 아니거나 존재하지 않으면 "
                    + "MEMO_NOT_FOUND를 반환해 리소스 존재 여부를 노출하지 않는다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "수정 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "MEMO_NOT_FOUND - 본인 소유가 아니거나 존재하지 않음")
    })
    @PatchMapping("/{memoId}")
    public ApiResponse<MemoResponse> updateMemo(
            Principal principal,
            @Parameter(description = "수정할 메모 ID", example = "12") @PathVariable Long memoId,
            @Valid @RequestBody MemoRequest request
    ) {
        return ApiResponse.success("메모가 수정되었습니다.", memoService.updateMemo(principal.getName(), memoId, request));
    }

    @Operation(
            summary = "메모 삭제 (MEMO-004)",
            description = "본인 소유 메모인지 확인한 후 삭제한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "삭제 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "MEMO_NOT_FOUND - 본인 소유가 아니거나 존재하지 않음")
    })
    @DeleteMapping("/{memoId}")
    public ApiResponse<Void> deleteMemo(
            Principal principal,
            @Parameter(description = "삭제할 메모 ID", example = "12") @PathVariable Long memoId
    ) {
        memoService.deleteMemo(principal.getName(), memoId);

        return ApiResponse.success("메모가 삭제되었습니다.", null);
    }
}
