package com.looktalk.backend.memo.service;

import com.looktalk.backend.e2ee.dto.EncryptedPayload;
import com.looktalk.backend.e2ee.repository.UserE2eeKeyRepository;
import com.looktalk.backend.e2ee.service.EncryptedPayloadValidator;
import com.looktalk.backend.global.exception.BusinessException;
import com.looktalk.backend.global.exception.ErrorCode;
import com.looktalk.backend.memo.dto.MemoRequest;
import com.looktalk.backend.memo.dto.MemoResponse;
import com.looktalk.backend.memo.entity.Memo;
import com.looktalk.backend.memo.repository.MemoRepository;
import com.looktalk.backend.user.entity.User;
import com.looktalk.backend.user.entity.UserRole;
import com.looktalk.backend.user.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// docs/LookTalk_Backend_API_Spec_v2.1.md 40~43. MEMO-001~004
// Backend는 평문 메모를 절대 받거나 저장하지 않고, ciphertext를 복호화하지도 않는다.
@Service
public class MemoService {

    private final MemoRepository memoRepository;
    private final UserRepository userRepository;
    private final UserE2eeKeyRepository userE2eeKeyRepository;
    private final EncryptedPayloadValidator payloadValidator;

    public MemoService(
            MemoRepository memoRepository,
            UserRepository userRepository,
            UserE2eeKeyRepository userE2eeKeyRepository,
            EncryptedPayloadValidator payloadValidator
    ) {
        this.memoRepository = memoRepository;
        this.userRepository = userRepository;
        this.userE2eeKeyRepository = userE2eeKeyRepository;
        this.payloadValidator = payloadValidator;
    }

    // MEMO-001
    @Transactional(readOnly = true)
    public List<MemoResponse> getMemos(String userId) {
        User user = findUser(userId);
        requirePatient(user);

        return memoRepository.findAllByUser_UserIdOrderByUpdatedAtDesc(userId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    // MEMO-002
    @Transactional
    public MemoResponse createMemo(String userId, MemoRequest request) {
        User user = findUser(userId);
        requirePatient(user);

        EncryptedPayload payload = request.encryptedPayload();
        validateKeyOwnership(userId, payload.keyVersion());
        payloadValidator.validate(payload);

        Instant now = Instant.now();
        Memo memo = Memo.builder()
                .user(user)
                .ciphertext(payload.ciphertext())
                .keyVersion(payload.keyVersion())
                .encryptionAlgorithm(payload.algorithm())
                .createdAt(now)
                .updatedAt(now)
                .build();
        memoRepository.save(memo);

        return toResponse(memo);
    }

    // MEMO-003
    @Transactional
    public MemoResponse updateMemo(String userId, Long memoId, MemoRequest request) {
        User user = findUser(userId);
        requirePatient(user);

        Memo memo = memoRepository.findByMemoIdAndUser_UserId(memoId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMO_NOT_FOUND));

        EncryptedPayload payload = request.encryptedPayload();
        validateKeyOwnership(userId, payload.keyVersion());
        payloadValidator.validate(payload);

        memo.setCiphertext(payload.ciphertext());
        memo.setKeyVersion(payload.keyVersion());
        memo.setEncryptionAlgorithm(payload.algorithm());
        memo.setUpdatedAt(Instant.now());

        return toResponse(memo);
    }

    // MEMO-004
    @Transactional
    public void deleteMemo(String userId, Long memoId) {
        User user = findUser(userId);
        requirePatient(user);

        Memo memo = memoRepository.findByMemoIdAndUser_UserId(memoId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMO_NOT_FOUND));

        memoRepository.delete(memo);
    }

    // docs/LookTalk_Backend_API_Spec_v2.1.md 41. "현재 사용자의 E2EE 공개키 등록 여부를 확인한다" +
    // "Request의 keyVersion이 현재 사용자의 등록된 Key Version인지 확인한다."
    // 다른 사용자의 키 버전은 존재 여부 확인에 걸리지 않으므로 사용할 수 없다.
    // 신규 생성·수정은 활성(revoked_at IS NULL) keyVersion만 허용한다. 폐기된 keyVersion으로 이미 저장된
    // ciphertext의 조회는 이 검증을 거치지 않으므로 계속 정상 반환된다.
    private void validateKeyOwnership(String userId, Integer keyVersion) {
        if (!userE2eeKeyRepository.existsByUser_UserIdAndRevokedAtIsNull(userId)) {
            throw new BusinessException(ErrorCode.E2EE_KEY_REQUIRED);
        }
        if (!userE2eeKeyRepository.existsByUser_UserIdAndKeyVersionAndRevokedAtIsNull(userId, keyVersion)) {
            throw new BusinessException(ErrorCode.E2EE_KEY_NOT_FOUND);
        }
    }

    private User findUser(String userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }

    // docs/LookTalk_Backend_API_Spec_v2.1.md 16. 전체 Endpoint - MEMO-001~004는 PATIENT 전용이다.
    // Spring Security URL 규칙과 별개로 Service에서도 역할을 다시 검증한다.
    private void requirePatient(User user) {
        if (user.getRole() != UserRole.PATIENT) {
            throw new BusinessException(ErrorCode.COMMON_FORBIDDEN);
        }
    }

    private MemoResponse toResponse(Memo memo) {
        return new MemoResponse(
                memo.getMemoId(),
                new EncryptedPayload(memo.getEncryptionAlgorithm(), memo.getKeyVersion(), memo.getCiphertext()),
                memo.getCreatedAt(),
                memo.getUpdatedAt()
        );
    }
}
