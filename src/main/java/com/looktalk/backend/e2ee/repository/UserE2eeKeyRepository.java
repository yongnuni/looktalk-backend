package com.looktalk.backend.e2ee.repository;

import com.looktalk.backend.e2ee.entity.UserE2eeKey;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserE2eeKeyRepository extends JpaRepository<UserE2eeKey, Long> {

    // CHAT-001 - 한 페이지(예: 20명)의 e2eeReady 여부를 사용자마다 별도 쿼리로 확인하는 N+1을 피하기 위해
    // 활성 키를 가진 userId 집합을 한 번의 쿼리로 조회한다.
    @Query("select distinct k.user.userId from UserE2eeKey k "
            + "where k.user.userId in :userIds and k.revokedAt is null")
    List<String> findUserIdsWithActiveKey(@Param("userIds") Collection<String> userIds);

    // CHAT-006 HOSPITAL - room의 활성 내부 participant 전원(1:1이므로 최대 2명)의 활성 키 등록 여부와
    // 현재 active keyVersion을 한 번의 쿼리로 조회한다(참여자마다 별도 쿼리를 날리는 N+1 방지).
    // 사용자당 활성 키는 최대 1개만 존재하므로(E2eeKeyService가 신규 등록 시 기존 active를 모두 revoke) 결과는
    // userId당 최대 1건이다.
    @Query("select k from UserE2eeKey k where k.user.userId in :userIds and k.revokedAt is null")
    List<UserE2eeKey> findActiveKeysByUserIds(@Param("userIds") Collection<String> userIds);

    // 39. E2EE-003 / 38. E2EE-002 - "최신 활성 공개키". 폐기된(revoked) 키는 제외한다.
    Optional<UserE2eeKey> findFirstByUser_UserIdAndRevokedAtIsNullOrderByKeyVersionDesc(String userId);

    // key_version은 사용자별 1부터 순차 증가한다 (revoked 여부와 무관하게 uq_user_e2ee_key_version UNIQUE 제약이
    // 모든 행에 적용되므로, 폐기된 버전도 포함해 다음 버전을 계산해야 DB UNIQUE 위반을 피할 수 있다).
    @Query("select coalesce(max(k.keyVersion), 0) from UserE2eeKey k where k.user.userId = :userId")
    int findMaxKeyVersion(@Param("userId") String userId);

    // Memo/Phrase 신규 생성·수정 시 "현재 사용자가 사용 가능한(활성) 공개키를 가지고 있는지" 확인할 때 사용한다
    // (E2EE_KEY_REQUIRED 판단). 신규 암호화는 항상 활성 키만 사용해야 하므로 revoked_at IS NULL인 키만 인정한다.
    // 이미 저장된 ciphertext의 keyVersion은 이 검사를 거치지 않으므로 과거 revoked 키로 암호화된 데이터의
    // 조회(복호화)는 계속 가능하다(§72).
    boolean existsByUser_UserIdAndRevokedAtIsNull(String userId);

    // Request의 keyVersion이 현재 사용자 본인의 "활성" 키 버전인지 확인할 때 사용한다 (E2EE_KEY_NOT_FOUND 판단).
    // 다른 사용자의 키 버전이거나 이미 폐기된 본인 키 버전은 이 조건에 걸리지 않으므로 신규 생성·수정에 사용할 수 없다.
    boolean existsByUser_UserIdAndKeyVersionAndRevokedAtIsNull(String userId, Integer keyVersion);

    // E2EE-001 - 신규 키 등록 시 기존 active 키를 전부 폐기해 "신규 암호화용 active key는 항상 1개"를 보장한다.
    // 직전 1개만이 아니라 revoked_at IS NULL인 모든 행을 대상으로 해, 과거에 여러 개가 active로 남아있던
    // 잘못된 상태도 다음 rotation에서 정상화한다.
    @Modifying
    @Query("update UserE2eeKey k set k.revokedAt = :revokedAt "
            + "where k.user.userId = :userId and k.revokedAt is null")
    int revokeAllActiveByUserId(@Param("userId") String userId, @Param("revokedAt") Instant revokedAt);
}
