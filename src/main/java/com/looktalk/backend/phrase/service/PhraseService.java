package com.looktalk.backend.phrase.service;

import com.looktalk.backend.e2ee.dto.EncryptedPayload;
import com.looktalk.backend.e2ee.repository.UserE2eeKeyRepository;
import com.looktalk.backend.e2ee.service.EncryptedPayloadValidator;
import com.looktalk.backend.global.exception.BusinessException;
import com.looktalk.backend.global.exception.ErrorCode;
import com.looktalk.backend.phrase.dto.PhraseRegisterResponse;
import com.looktalk.backend.phrase.dto.PhraseRequest;
import com.looktalk.backend.phrase.dto.PhraseResponse;
import com.looktalk.backend.phrase.entity.Phrase;
import com.looktalk.backend.phrase.repository.PhraseRepository;
import com.looktalk.backend.user.entity.User;
import com.looktalk.backend.user.entity.UserRole;
import com.looktalk.backend.user.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// docs/LookTalk_Backend_API_Spec_v2.1.md 44~47. PHRASE-001~004
// Backend는 평문 문장을 절대 받거나 저장하지 않는다. 최대 3개, 4번째 등록 시 가장 오래된 문장을 FIFO로 교체한다.
@Service
public class PhraseService {

    private static final int MAX_PHRASE_COUNT = 3;

    private final PhraseRepository phraseRepository;
    private final UserRepository userRepository;
    private final UserE2eeKeyRepository userE2eeKeyRepository;
    private final EncryptedPayloadValidator payloadValidator;

    public PhraseService(
            PhraseRepository phraseRepository,
            UserRepository userRepository,
            UserE2eeKeyRepository userE2eeKeyRepository,
            EncryptedPayloadValidator payloadValidator
    ) {
        this.phraseRepository = phraseRepository;
        this.userRepository = userRepository;
        this.userE2eeKeyRepository = userE2eeKeyRepository;
        this.payloadValidator = payloadValidator;
    }

    // PHRASE-001
    @Transactional(readOnly = true)
    public List<PhraseResponse> getPhrases(String userId) {
        User user = findUser(userId);
        requirePatient(user);

        return phraseRepository.findAllByUser_UserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    // PHRASE-002 - 삭제(있는 경우)와 등록을 하나의 Transaction으로 처리한다.
    // 동시 등록으로 3개를 초과하지 않도록 사용자 행에 PESSIMISTIC_WRITE 락을 걸어 트랜잭션 동안 직렬화한다.
    @Transactional
    public PhraseRegisterResponse registerPhrase(String userId, PhraseRequest request) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        requirePatient(user);

        EncryptedPayload payload = request.encryptedPayload();
        validateKeyOwnership(userId, payload.keyVersion());
        payloadValidator.validate(payload);

        Long replacedPhraseId = null;
        if (phraseRepository.countByUser_UserId(userId) >= MAX_PHRASE_COUNT) {
            Phrase oldest = phraseRepository.findFirstByUser_UserIdOrderByCreatedAtAsc(userId)
                    .orElseThrow(() -> new IllegalStateException("Phrase 개수가 최대치인데 가장 오래된 Phrase를 찾을 수 없습니다."));
            replacedPhraseId = oldest.getPhraseId();
            phraseRepository.delete(oldest);
        }

        Phrase phrase = Phrase.builder()
                .user(user)
                .ciphertext(payload.ciphertext())
                .keyVersion(payload.keyVersion())
                .encryptionAlgorithm(payload.algorithm())
                .category(request.category())
                .createdAt(Instant.now())
                .build();
        phraseRepository.save(phrase);

        return new PhraseRegisterResponse(phrase.getPhraseId(), replacedPhraseId, phrase.getCreatedAt());
    }

    // PHRASE-003 - "수정 시 created_at은 변경하지 않는다."
    @Transactional
    public PhraseResponse updatePhrase(String userId, Long phraseId, PhraseRequest request) {
        User user = findUser(userId);
        requirePatient(user);

        Phrase phrase = phraseRepository.findByPhraseIdAndUser_UserId(phraseId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PHRASE_NOT_FOUND));

        EncryptedPayload payload = request.encryptedPayload();
        validateKeyOwnership(userId, payload.keyVersion());
        payloadValidator.validate(payload);

        phrase.setCiphertext(payload.ciphertext());
        phrase.setKeyVersion(payload.keyVersion());
        phrase.setEncryptionAlgorithm(payload.algorithm());
        phrase.setCategory(request.category());

        return toResponse(phrase);
    }

    // PHRASE-004
    @Transactional
    public void deletePhrase(String userId, Long phraseId) {
        User user = findUser(userId);
        requirePatient(user);

        Phrase phrase = phraseRepository.findByPhraseIdAndUser_UserId(phraseId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PHRASE_NOT_FOUND));

        phraseRepository.delete(phrase);
    }

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

    // docs/LookTalk_Backend_API_Spec_v2.1.md 16. 전체 Endpoint - PHRASE-001~004는 PATIENT 전용이다.
    private void requirePatient(User user) {
        if (user.getRole() != UserRole.PATIENT) {
            throw new BusinessException(ErrorCode.COMMON_FORBIDDEN);
        }
    }

    private PhraseResponse toResponse(Phrase phrase) {
        return new PhraseResponse(
                phrase.getPhraseId(),
                phrase.getCategory(),
                new EncryptedPayload(phrase.getEncryptionAlgorithm(), phrase.getKeyVersion(), phrase.getCiphertext()),
                phrase.getCreatedAt()
        );
    }
}
