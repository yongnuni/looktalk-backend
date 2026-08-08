package com.looktalk.backend.auth.service;

import com.looktalk.backend.auth.entity.VerificationCode;
import com.looktalk.backend.auth.entity.VerificationPurpose;
import com.looktalk.backend.auth.entity.VerificationTargetType;
import com.looktalk.backend.auth.repository.VerificationCodeRepository;
import com.looktalk.backend.global.exception.BusinessException;
import com.looktalk.backend.global.exception.ErrorCode;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// docs/LookTalk_Backend_API_Spec_v2.1.md 17, 18. AUTH-001/002 인증번호 발급/확인 공통 로직.
// USER-003/004(전화번호 인증)에서도 동일 로직을 재사용한다.
@Service
public class VerificationCodeService {

    private static final int CODE_UPPER_BOUND = 1_000_000;

    private final VerificationCodeRepository verificationCodeRepository;
    private final OtpHasher otpHasher;
    private final long expirySeconds;
    private final SecureRandom secureRandom = new SecureRandom();

    public VerificationCodeService(
            VerificationCodeRepository verificationCodeRepository,
            OtpHasher otpHasher,
            @Value("${looktalk.otp.expiry-seconds}") long expirySeconds
    ) {
        this.verificationCodeRepository = verificationCodeRepository;
        this.otpHasher = otpHasher;
        this.expirySeconds = expirySeconds;
    }

    @Transactional
    public IssuedCode issue(VerificationTargetType targetType, String targetValue, VerificationPurpose purpose) {
        String code = generateCode();
        Instant now = Instant.now();

        VerificationCode verificationCode = VerificationCode.builder()
                .targetType(targetType)
                .targetValue(targetValue)
                .purpose(purpose)
                .codeHash(otpHasher.hash(code))
                .expiresAt(now.plusSeconds(expirySeconds))
                .verifiedAt(null)
                .createdAt(now)
                .build();

        verificationCodeRepository.save(verificationCode);

        return new IssuedCode(code, expirySeconds);
    }

    // AUTH-002 - 코드 일치 여부를 먼저 확인하고, 그다음 만료시간을 확인한다.
    @Transactional
    public void confirm(
            VerificationTargetType targetType,
            String targetValue,
            VerificationPurpose purpose,
            String code
    ) {
        VerificationCode latest = findLatest(targetType, targetValue, purpose)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_INVALID_VERIFICATION_CODE));

        if (!otpHasher.matches(code, latest.getCodeHash())) {
            throw new BusinessException(ErrorCode.AUTH_INVALID_VERIFICATION_CODE);
        }

        if (latest.getExpiresAt().isBefore(Instant.now())) {
            throw new BusinessException(ErrorCode.AUTH_VERIFICATION_EXPIRED);
        }

        latest.setVerifiedAt(Instant.now());
    }

    // AUTH-004 - "verified_at IS NOT NULL인 인증 레코드를 서버에서 다시 조회해 인증 완료 여부를 판단한다."
    @Transactional(readOnly = true)
    public boolean isVerified(VerificationTargetType targetType, String targetValue, VerificationPurpose purpose) {
        return findLatest(targetType, targetValue, purpose)
                .map(verificationCode -> verificationCode.getVerifiedAt() != null)
                .orElse(false);
    }

    private Optional<VerificationCode> findLatest(
            VerificationTargetType targetType,
            String targetValue,
            VerificationPurpose purpose
    ) {
        return verificationCodeRepository
                .findFirstByTargetTypeAndTargetValueAndPurposeOrderByVerificationCodeIdDesc(
                        targetType,
                        targetValue,
                        purpose
                );
    }

    private String generateCode() {
        return String.format("%06d", secureRandom.nextInt(CODE_UPPER_BOUND));
    }

    public record IssuedCode(String code, long expiresInSeconds) {
    }
}
