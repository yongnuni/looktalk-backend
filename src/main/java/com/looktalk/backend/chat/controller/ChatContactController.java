package com.looktalk.backend.chat.controller;

import com.looktalk.backend.chat.dto.ChatContactListResponse;
import com.looktalk.backend.chat.service.ChatContactService;
import com.looktalk.backend.global.response.ApiResponse;
import com.looktalk.backend.global.swagger.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.Principal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// docs/LookTalk_Backend_API_Spec_v2.1.md 52. CHAT-001
// PATIENT/STAFF 모두 호출할 수 있는 AUTH 전용 API이므로 클래스 레벨로 bearerAuth를 적용한다.
@Tag(name = "Chat", description = "병원 채팅 연락처, 채팅방 목록/생성 (CHAT-001~004)")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME_NAME)
@RestController
@RequestMapping("/api/chat-contacts")
public class ChatContactController {

    private final ChatContactService chatContactService;

    public ChatContactController(ChatContactService chatContactService) {
        this.chatContactService = chatContactService;
    }

    @Operation(
            summary = "병원 채팅 연락처 조회 (CHAT-001)",
            description = "Look Talk 내부 회원(PATIENT/STAFF) 전체를 대상으로 채팅 연락처를 조회한다. "
                    + "병원 소속, 담당 환자 관계, role 조합으로 제한하지 않는다. 현재 로그인 사용자는 결과에서 제외한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공")
    })
    @GetMapping
    public ApiResponse<ChatContactListResponse> getChatContacts(
            Principal principal,
            @Parameter(description = "이름/로그인 아이디 검색어") @RequestParam(required = false) String keyword,
            @Parameter(description = "페이지 번호(0부터)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기") @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.success(
                "채팅 상대 목록을 조회했습니다.",
                chatContactService.getChatContacts(principal.getName(), keyword, page, size)
        );
    }
}
