package com.looktalk.backend.chat.controller;

import com.looktalk.backend.chat.dto.ChatHospitalRoomRequest;
import com.looktalk.backend.chat.dto.ChatMessageListResponse;
import com.looktalk.backend.chat.dto.ChatMessageSendResponse;
import com.looktalk.backend.chat.dto.ChatRoomCreateResponse;
import com.looktalk.backend.chat.dto.ChatRoomListItemResponse;
import com.looktalk.backend.chat.dto.ChatSendMessageRequest;
import com.looktalk.backend.chat.dto.ChatSmsRoomRequest;
import com.looktalk.backend.chat.service.ChatMessageService;
import com.looktalk.backend.chat.service.ChatRoomService;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// docs/LookTalk_Backend_API_Spec_v2.1.md 53~57. CHAT-002~006
// CHAT-002/003/005/006은 AUTH(PATIENT/STAFF 모두, SMS 전송/room 생성은 Service에서 PATIENT만 허용), CHAT-004는
// PATIENT 전용이지만 URL 권한 규칙과 별개로 bearerAuth는 클래스 레벨로 공통 적용한다.
@Tag(name = "Chat", description = "병원 채팅 연락처, 채팅방 목록/생성, 메시지 조회/전송 (CHAT-001~006)")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME_NAME)
@RestController
@RequestMapping("/api/chat-rooms")
public class ChatRoomController {

    private final ChatRoomService chatRoomService;
    private final ChatMessageService chatMessageService;

    public ChatRoomController(ChatRoomService chatRoomService, ChatMessageService chatMessageService) {
        this.chatRoomService = chatRoomService;
        this.chatMessageService = chatMessageService;
    }

    @Operation(
            summary = "생성된 채팅방 목록 조회 (CHAT-002)",
            description = "현재 사용자가 participant로 포함된 이미 생성된 채팅방을 조회한다. type으로 HOSPITAL/SMS를 "
                    + "선택적으로 필터링할 수 있다. 평문 마지막 메시지는 만들지 않고 현재 사용자용 ciphertext를 그대로 반환한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "COMMON_VALIDATION_ERROR - type 값이 HOSPITAL/SMS가 아님")
    })
    @GetMapping
    public ApiResponse<List<ChatRoomListItemResponse>> getChatRooms(
            Principal principal,
            @Parameter(description = "채팅방 종류 필터", example = "HOSPITAL") @RequestParam(required = false) String type
    ) {
        return ApiResponse.success(
                "채팅방을 조회했습니다.", chatRoomService.getChatRooms(principal.getName(), type));
    }

    @Operation(
            summary = "병원 채팅방 조회·생성 (CHAT-003)",
            description = "선택한 내부 회원과의 기존 1:1 HOSPITAL room을 반환하거나 새로 생성한다. hospital 소속, "
                    + "담당 환자 관계, role 조합은 검사하지 않는다. 기존 room이면 200, 신규 생성이면 201을 반환한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "기존 room 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "신규 room 생성"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "CHAT_SELF_TARGET - 자기 자신을 상대로 지정함"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "CHAT_TARGET_NOT_FOUND - 대상 회원이 존재하지 않음")
    })
    @PostMapping("/hospital")
    public ResponseEntity<ApiResponse<ChatRoomCreateResponse>> findOrCreateHospitalRoom(
            Principal principal,
            @Valid @RequestBody ChatHospitalRoomRequest request
    ) {
        ChatRoomService.FindOrCreateResult result =
                chatRoomService.findOrCreateHospitalRoom(principal.getName(), request.targetUserId());

        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        String message = result.created() ? "채팅방이 생성되었습니다." : "채팅방을 조회했습니다.";
        return ResponseEntity.status(status).body(ApiResponse.success(message, result.room()));
    }

    @Operation(
            summary = "SMS 채팅방 조회·생성 (CHAT-004)",
            description = "등록된 외부 SMS 친구와의 기존 SMS room을 반환하거나 새로 생성한다. PATIENT만 호출할 수 있다. "
                    + "기존 room이면 200, 신규 생성이면 201을 반환한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "기존 room 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "신규 room 생성"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "SMS_VERIFICATION_REQUIRED - 전화번호 인증 미완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "FRIEND_NOT_FOUND - 본인 소유가 아니거나 존재하지 않음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "E2EE_KEY_REQUIRED - 현재 사용자의 E2EE 공개키가 등록되지 않음")
    })
    @PostMapping("/sms")
    public ResponseEntity<ApiResponse<ChatRoomCreateResponse>> findOrCreateSmsRoom(
            Principal principal,
            @Valid @RequestBody ChatSmsRoomRequest request
    ) {
        ChatRoomService.FindOrCreateResult result =
                chatRoomService.findOrCreateSmsRoom(principal.getName(), request.friendshipId());

        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        String message = result.created() ? "채팅방이 생성되었습니다." : "채팅방을 조회했습니다.";
        return ResponseEntity.status(status).body(ApiResponse.success(message, result.room()));
    }

    @Operation(
            summary = "채팅방 메시지 조회 (CHAT-005)",
            description = "현재 사용자가 participant인 채팅방의 메시지 이력을 messageId 내림차순 cursor pagination으로 "
                    + "조회한다. 현재 사용자의 message_ciphertext가 존재하는 메시지만 반환하며, 다른 recipient의 "
                    + "ciphertext나 평문 fallback은 반환하지 않는다. 현재 active E2EE key 등록 여부는 선행조건이 아니다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "COMMON_VALIDATION_ERROR - size가 1~100 범위를 벗어나거나 beforeMessageId가 양수가 아님"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "CHAT_ACCESS_DENIED - room은 존재하지만 현재 사용자가 participant가 아님"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "CHAT_ROOM_NOT_FOUND - 채팅방이 존재하지 않음")
    })
    @GetMapping("/{roomId}/messages")
    public ApiResponse<ChatMessageListResponse> getMessages(
            Principal principal,
            @Parameter(description = "채팅방 ID", example = "10") @PathVariable Long roomId,
            @Parameter(description = "이 messageId보다 작은(더 과거) 메시지부터 조회. 미전달 시 최신부터 조회", example = "300")
            @RequestParam(required = false) Long beforeMessageId,
            @Parameter(description = "페이지 크기(1~100, 기본값 50)", example = "50")
            @RequestParam(required = false) Integer size
    ) {
        return ApiResponse.success(
                "메시지를 조회했습니다.",
                chatMessageService.getMessages(principal.getName(), roomId, beforeMessageId, size)
        );
    }

    @Operation(
            summary = "채팅방 메시지 전송 (CHAT-006)",
            description = "room의 실제 roomType에 따라 HOSPITAL(ciphertexts 필수)과 SMS(content 필수) 요청을 구분해 "
                    + "처리한다. HOSPITAL은 Frontend가 암호화한 ciphertext를 그대로 저장하고, SMS는 Provider 발송 "
                    + "수락 후 현재 사용자의 active 공개키로 Backend가 sealed box 암호화해 저장한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "전송 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "COMMON_VALIDATION_ERROR/E2EE_INVALID_PAYLOAD - messageType/roomType별 필드 사용 위반, "
                            + "recipient 집합 불일치, algorithm/Base64 형식 오류 등"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "CHAT_ACCESS_DENIED/SMS_VERIFICATION_REQUIRED/COMMON_FORBIDDEN - participant 아님, "
                            + "SMS 미인증, SMS room에 STAFF가 접근"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "CHAT_ROOM_NOT_FOUND/CHAT_TARGET_NOT_FOUND/E2EE_KEY_NOT_FOUND - room 없음, 상대가 "
                            + "탈퇴해 더 이상 전송할 수 없음, keyVersion이 recipient의 현재 active 버전과 불일치"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "E2EE_KEY_REQUIRED/E2EE_TARGET_KEY_REQUIRED - 현재 사용자 또는 상대의 active 공개키 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "502", description = "SMS_SEND_FAILED - SMS Provider 발송 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "503", description = "SMS_PROVIDER_NOT_CONFIGURED - SMS Provider가 설정되지 않음")
    })
    @PostMapping("/{roomId}/messages")
    public ApiResponse<ChatMessageSendResponse> sendMessage(
            Principal principal,
            @Parameter(description = "채팅방 ID", example = "10") @PathVariable Long roomId,
            @Valid @RequestBody ChatSendMessageRequest request
    ) {
        return ApiResponse.success(
                "메시지가 전송되었습니다.",
                chatMessageService.sendMessage(principal.getName(), roomId, request)
        );
    }
}
