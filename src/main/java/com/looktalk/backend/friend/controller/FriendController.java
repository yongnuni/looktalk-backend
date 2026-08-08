package com.looktalk.backend.friend.controller;

import com.looktalk.backend.friend.dto.FriendCreateRequest;
import com.looktalk.backend.friend.dto.FriendResponse;
import com.looktalk.backend.friend.dto.FriendUpdateRequest;
import com.looktalk.backend.friend.service.FriendService;
import com.looktalk.backend.global.response.ApiResponse;
import com.looktalk.backend.global.swagger.OpenApiConfig;
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

// docs/LookTalk_Backend_API_Spec_v2.1.md 48~51. FRIEND-001~004
// PATIENT 전용 API이므로 클래스 레벨로 bearerAuth를 적용한다.
@Tag(name = "Friend", description = "전화번호 인증 기반 외부 SMS 지인 CRUD (FRIEND-001~004)")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME_NAME)
@RestController
@RequestMapping("/api/friends")
public class FriendController {

    private final FriendService friendService;

    public FriendController(FriendService friendService) {
        this.friendService = friendService;
    }

    @Operation(
            summary = "SMS 지인 목록 조회 (FRIEND-001)",
            description = "현재 로그인 환자가 등록한 외부 SMS 지인 목록을 조회한다. PATIENT만 호출할 수 있다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공")
    })
    @GetMapping
    public ApiResponse<List<FriendResponse>> getFriends(Principal principal) {
        return ApiResponse.success("친구 목록을 조회했습니다.", friendService.getFriends(principal.getName()));
    }

    @Operation(
            summary = "SMS 지인 등록 (FRIEND-002)",
            description = "전화번호 인증이 완료된 PATIENT가 이름과 전화번호로 외부 SMS 지인을 등록한다. "
                    + "등록 시 SMS 채팅방은 생성하지 않는다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "등록 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "COMMON_VALIDATION_ERROR - 전화번호가 숫자를 포함하지 않아 정규화 결과가 빈 값임"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "SMS_VERIFICATION_REQUIRED - 전화번호 인증 미완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "FRIEND_ALREADY_EXISTS - 같은 전화번호가 이미 등록됨")
    })
    @PostMapping
    public ResponseEntity<ApiResponse<FriendResponse>> registerFriend(
            Principal principal,
            @Valid @RequestBody FriendCreateRequest request
    ) {
        FriendResponse response = friendService.registerFriend(principal.getName(), request);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("지인이 등록되었습니다.", response));
    }

    @Operation(
            summary = "SMS 지인 수정 (FRIEND-003)",
            description = "본인 소유 지인의 이름/전화번호를 부분 수정한다(partial update). name/phone 중 최소 하나는 "
                    + "전달해야 하며, 전달한 필드만 변경되고 나머지는 기존 값을 유지한다. 전달된 필드는 빈 값을 허용하지 않는다. "
                    + "전화번호 변경 시 동일 사용자의 다른 지인과 중복 여부를 다시 확인한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "수정 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "COMMON_VALIDATION_ERROR - name/phone 모두 미전달이거나, 전달된 필드가 빈 값이거나, "
                            + "전화번호 정규화 결과가 빈 값임"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "SMS_VERIFICATION_REQUIRED - 전화번호 인증 미완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "FRIEND_NOT_FOUND - 본인 소유가 아니거나 존재하지 않음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "FRIEND_ALREADY_EXISTS - 같은 전화번호가 이미 등록됨")
    })
    @PatchMapping("/{friendshipId}")
    public ApiResponse<FriendResponse> updateFriend(
            Principal principal,
            @Parameter(description = "수정할 friendship ID", example = "7") @PathVariable Long friendshipId,
            @Valid @RequestBody FriendUpdateRequest request
    ) {
        return ApiResponse.success(
                "지인 정보가 수정되었습니다.",
                friendService.updateFriend(principal.getName(), friendshipId, request)
        );
    }

    @Operation(
            summary = "SMS 지인 삭제 (FRIEND-004)",
            description = "본인 소유 지인인지 확인한 후 삭제한다. 기존 SMS 채팅방과 암호화된 메시지 이력은 유지한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "삭제 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "FRIEND_NOT_FOUND - 본인 소유가 아니거나 존재하지 않음")
    })
    @DeleteMapping("/{friendshipId}")
    public ApiResponse<Void> deleteFriend(
            Principal principal,
            @Parameter(description = "삭제할 friendship ID", example = "7") @PathVariable Long friendshipId
    ) {
        friendService.deleteFriend(principal.getName(), friendshipId);

        return ApiResponse.success("지인이 삭제되었습니다.", null);
    }
}
