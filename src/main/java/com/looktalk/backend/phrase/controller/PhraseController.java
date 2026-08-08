package com.looktalk.backend.phrase.controller;

import com.looktalk.backend.global.response.ApiResponse;
import com.looktalk.backend.global.swagger.OpenApiConfig;
import com.looktalk.backend.phrase.dto.PhraseRegisterResponse;
import com.looktalk.backend.phrase.dto.PhraseRequest;
import com.looktalk.backend.phrase.dto.PhraseResponse;
import com.looktalk.backend.phrase.service.PhraseService;
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

// docs/LookTalk_Backend_API_Spec_v2.1.md 44~47. PHRASE-001~004
// PATIENT 전용 API이므로 클래스 레벨로 bearerAuth를 적용한다.
@Tag(name = "Phrase", description = "E2EE 기반 자주 쓰는 문장 CRUD, 최대 3개 FIFO 유지 (PHRASE-001~004)")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME_NAME)
@RestController
@RequestMapping("/api/phrases")
public class PhraseController {

    private final PhraseService phraseService;

    public PhraseController(PhraseService phraseService) {
        this.phraseService = phraseService;
    }

    @Operation(
            summary = "자주 쓰는 문장 조회 (PHRASE-001)",
            description = "현재 사용자가 등록한 자주 쓰는 문장을 조회한다. 최대 3개까지 유지된다. PATIENT만 호출할 수 있다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공")
    })
    @GetMapping
    public ApiResponse<List<PhraseResponse>> getPhrases(Principal principal) {
        return ApiResponse.success("자주 쓰는 문장을 조회했습니다.", phraseService.getPhrases(principal.getName()));
    }

    @Operation(
            summary = "자주 쓰는 문장 등록 (PHRASE-002)",
            description = "새 문장을 등록한다. 이미 3개가 있으면 가장 오래된 문장을 삭제하고(FIFO) 새 문장을 저장해 "
                    + "항상 최대 3개를 유지한다. 삭제와 등록은 하나의 Transaction으로 처리하며, 동시 등록으로 3개를 "
                    + "초과하지 않도록 사용자 단위로 동시성을 제어한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201", description = "등록 완료. 교체된 문장이 없으면 replacedPhraseId는 null."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "E2EE_INVALID_PAYLOAD - algorithm 불일치 또는 ciphertext Base64 형식 오류"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "E2EE_KEY_NOT_FOUND - keyVersion이 본인 소유가 아님"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "E2EE_KEY_REQUIRED - 현재 사용자의 E2EE 공개키가 등록되지 않음")
    })
    @PostMapping
    public ResponseEntity<ApiResponse<PhraseRegisterResponse>> registerPhrase(
            Principal principal,
            @Valid @RequestBody PhraseRequest request
    ) {
        PhraseRegisterResponse response = phraseService.registerPhrase(principal.getName(), request);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("자주 쓰는 문장이 등록되었습니다.", response));
    }

    @Operation(
            summary = "자주 쓰는 문장 수정 (PHRASE-003)",
            description = "본인 소유 문장의 ciphertext/keyVersion/category를 수정한다. created_at은 변경하지 않으며, "
                    + "FIFO 교체 기준은 최초 생성시각을 그대로 사용한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "수정 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "PHRASE_NOT_FOUND - 본인 소유가 아니거나 존재하지 않음")
    })
    @PatchMapping("/{phraseId}")
    public ApiResponse<PhraseResponse> updatePhrase(
            Principal principal,
            @Parameter(description = "수정할 문장 ID", example = "10") @PathVariable Long phraseId,
            @Valid @RequestBody PhraseRequest request
    ) {
        return ApiResponse.success(
                "자주 쓰는 문장이 수정되었습니다.",
                phraseService.updatePhrase(principal.getName(), phraseId, request)
        );
    }

    @Operation(
            summary = "자주 쓰는 문장 삭제 (PHRASE-004)",
            description = "본인 소유 문장인지 확인한 후 삭제한다. 자동 FIFO 교체와 별개로 사용자가 직접 삭제할 수 있다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "삭제 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "PHRASE_NOT_FOUND - 본인 소유가 아니거나 존재하지 않음")
    })
    @DeleteMapping("/{phraseId}")
    public ApiResponse<Void> deletePhrase(
            Principal principal,
            @Parameter(description = "삭제할 문장 ID", example = "10") @PathVariable Long phraseId
    ) {
        phraseService.deletePhrase(principal.getName(), phraseId);

        return ApiResponse.success("자주 쓰는 문장이 삭제되었습니다.", null);
    }
}
