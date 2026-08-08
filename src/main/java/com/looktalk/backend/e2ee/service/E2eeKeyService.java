package com.looktalk.backend.e2ee.service;

import com.looktalk.backend.e2ee.dto.E2eeKeyRegisterRequest;
import com.looktalk.backend.e2ee.dto.E2eeKeyRegisterResponse;
import com.looktalk.backend.e2ee.dto.E2eeKeyStatusResponse;
import com.looktalk.backend.e2ee.dto.E2eeUserPublicKeyResponse;
import com.looktalk.backend.e2ee.entity.UserE2eeKey;
import com.looktalk.backend.e2ee.repository.UserE2eeKeyRepository;
import com.looktalk.backend.global.exception.BusinessException;
import com.looktalk.backend.global.exception.ErrorCode;
import com.looktalk.backend.user.entity.User;
import com.looktalk.backend.user.repository.UserRepository;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// docs/LookTalk_Backend_API_Spec_v2.1.md 37~39. E2EE-001~003
// Backend는 공개키만 저장/조회하며 개인키는 Request로 받지도, 저장하지도 않는다.
@Service
public class E2eeKeyService {

    private static final int SEALED_BOX_PUBLIC_KEY_BYTES = 32;

    private final UserE2eeKeyRepository userE2eeKeyRepository;
    private final UserRepository userRepository;

    public E2eeKeyService(UserE2eeKeyRepository userE2eeKeyRepository, UserRepository userRepository) {
        this.userE2eeKeyRepository = userE2eeKeyRepository;
        this.userRepository = userRepository;
    }

    // E2EE-001 - keyVersion은 클라이언트가 지정하지 않는다. 서버가 사용자별 1부터 순차 증가하는 다음 버전을 계산한다.
    // 동시에 같은 사용자에 대한 등록 요청이 들어와도 같은 버전을 계산하지 않도록 사용자 행에
    // PESSIMISTIC_WRITE 락을 걸어 트랜잭션 동안 직렬화한다. (user_id, key_version) UNIQUE 제약은 최종 방어선으로 유지한다.
    // 신규 키를 저장하기 전에 기존 active 키를 전부 revoke해, 신규 암호화용 active key가 항상 사용자당 1개만
    // 존재하도록 한다. 같은 Transaction이므로 이후 단계(신규 키 저장)가 실패하면 이 revoke도 함께 rollback된다.
    //
    // 재시도 안정성: 락을 획득한 뒤 현재 active key를 조회해 decode된 32바이트 값이 Request의 publicKey와
    // 동일하면(Base64 문자열 표현이 아니라 실제 바이트 값 기준) 새 버전을 만들거나 기존 키를 revoke하지 않고
    // 현재 active keyVersion을 그대로 반환한다. Frontend가 네트워크 오류 등으로 동일 요청을 재시도해도
    // DB 상태(및 keyVersion 번호)가 매번 증가하지 않도록 하기 위함이다.
    @Transactional
    public E2eeKeyRegisterResponse registerKey(String userId, E2eeKeyRegisterRequest request) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        byte[] decodedPublicKey = validatePublicKeyFormat(request.publicKey());

        Optional<UserE2eeKey> activeKey =
                userE2eeKeyRepository.findFirstByUser_UserIdAndRevokedAtIsNullOrderByKeyVersionDesc(userId);

        if (activeKey.isPresent() && isSamePublicKey(activeKey.get(), decodedPublicKey)) {
            return new E2eeKeyRegisterResponse(activeKey.get().getKeyVersion());
        }

        int nextVersion = userE2eeKeyRepository.findMaxKeyVersion(userId) + 1;

        userE2eeKeyRepository.revokeAllActiveByUserId(userId, Instant.now());

        UserE2eeKey key = UserE2eeKey.builder()
                .user(user)
                .keyVersion(nextVersion)
                .publicKey(request.publicKey())
                .createdAt(Instant.now())
                .build();
        userE2eeKeyRepository.save(key);

        return new E2eeKeyRegisterResponse(key.getKeyVersion());
    }

    private boolean isSamePublicKey(UserE2eeKey existingKey, byte[] decodedRequestPublicKey) {
        return Arrays.equals(Base64.getDecoder().decode(existingKey.getPublicKey()), decodedRequestPublicKey);
    }

    // E2EE-002
    @Transactional(readOnly = true)
    public E2eeKeyStatusResponse getMyKeyStatus(String userId) {
        Optional<UserE2eeKey> activeKey =
                userE2eeKeyRepository.findFirstByUser_UserIdAndRevokedAtIsNullOrderByKeyVersionDesc(userId);

        if (activeKey.isEmpty()) {
            return new E2eeKeyStatusResponse(false, null, null);
        }

        UserE2eeKey key = activeKey.get();
        return new E2eeKeyStatusResponse(true, key.getKeyVersion(), key.getPublicKey());
    }

    // E2EE-003 - 다른 사용자의 개인정보는 포함하지 않고 공개키 관련 정보만 반환한다.
    @Transactional(readOnly = true)
    public E2eeUserPublicKeyResponse getUserPublicKey(String targetUserId) {
        if (!userRepository.existsById(targetUserId)) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }

        UserE2eeKey key = userE2eeKeyRepository
                .findFirstByUser_UserIdAndRevokedAtIsNullOrderByKeyVersionDesc(targetUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.E2EE_TARGET_KEY_REQUIRED));

        return new E2eeUserPublicKeyResponse(targetUserId, key.getKeyVersion(), key.getPublicKey());
    }

    // docs/LookTalk_Backend_API_Spec_v2.1.md 6. E2EE 처리 방식 - LIBSODIUM_SEALED_BOX 공개키는 32바이트다.
    // Base64 형식 오류 또는 디코딩 결과가 정확히 32바이트가 아니면 모두 E2EE_INVALID_PAYLOAD를 반환한다.
    // 디코딩된 바이트 값을 반환해 호출부에서 기존 active key와의 바이트 단위 비교에 재사용할 수 있게 한다.
    private byte[] validatePublicKeyFormat(String publicKey) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(publicKey);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.E2EE_INVALID_PAYLOAD);
        }

        if (decoded.length != SEALED_BOX_PUBLIC_KEY_BYTES) {
            throw new BusinessException(ErrorCode.E2EE_INVALID_PAYLOAD);
        }

        return decoded;
    }
}
