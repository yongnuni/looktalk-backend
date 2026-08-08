package com.looktalk.backend.user.service;

import com.looktalk.backend.auth.entity.VerificationPurpose;
import com.looktalk.backend.auth.entity.VerificationTargetType;
import com.looktalk.backend.auth.service.VerificationCodeService;
import com.looktalk.backend.global.exception.BusinessException;
import com.looktalk.backend.global.exception.ErrorCode;
import com.looktalk.backend.user.dto.PhoneVerificationResponse;
import com.looktalk.backend.user.dto.UserMeResponse;
import com.looktalk.backend.user.dto.UserNameUpdateResponse;
import com.looktalk.backend.user.dto.UserSettingResponse;
import com.looktalk.backend.user.dto.UserSettingUpdateRequest;
import com.looktalk.backend.user.entity.InputMethod;
import com.looktalk.backend.user.entity.User;
import com.looktalk.backend.user.entity.UserRole;
import com.looktalk.backend.user.entity.UserSetting;
import com.looktalk.backend.user.repository.UserRepository;
import com.looktalk.backend.user.repository.UserSettingRepository;
import com.looktalk.backend.user.support.DisplayNameResolver;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final UserSettingRepository userSettingRepository;
    private final VerificationCodeService verificationCodeService;
    private final SmsSender smsSender;

    public UserService(
            UserRepository userRepository,
            UserSettingRepository userSettingRepository,
            VerificationCodeService verificationCodeService,
            SmsSender smsSender
    ) {
        this.userRepository = userRepository;
        this.userSettingRepository = userSettingRepository;
        this.verificationCodeService = verificationCodeService;
        this.smsSender = smsSender;
    }

    // USER-001
    @Transactional(readOnly = true)
    public UserMeResponse getMe(String userId) {
        User user = findUser(userId);

        return new UserMeResponse(
                user.getUserId(),
                user.getLoginId(),
                user.getUserEmail(),
                user.getUserName(),
                DisplayNameResolver.resolve(user),
                user.getUserPhone(),
                Boolean.TRUE.equals(user.getIsEmailVerified()),
                Boolean.TRUE.equals(user.getIsSmsVerified()),
                user.getRole().name()
        );
    }

    // USER-002
    @Transactional
    public UserNameUpdateResponse updateName(String userId, String rawName) {
        User user = findUser(userId);

        String normalized = normalizeName(rawName);
        user.setUserName(normalized);

        return new UserNameUpdateResponse(userId, normalized, DisplayNameResolver.resolve(user));
    }

    private String normalizeName(String rawName) {
        if (rawName == null) {
            return null;
        }
        String trimmed = rawName.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    // USER-003
    @Transactional
    public PhoneVerificationResponse sendPhoneVerification(String userId, String rawPhone) {
        User user = findUser(userId);
        requirePatient(user);

        String normalizedPhone = normalizePhone(rawPhone);
        VerificationCodeService.IssuedCode issued = verificationCodeService.issue(
                VerificationTargetType.SMS, normalizedPhone, VerificationPurpose.PHONE_VERIFY);

        smsSender.sendVerificationCode(normalizedPhone, issued.code());

        return new PhoneVerificationResponse(issued.expiresInSeconds());
    }

    // USER-004
    @Transactional
    public void confirmPhoneVerification(String userId, String rawPhone, String code) {
        User user = findUser(userId);
        requirePatient(user);

        String normalizedPhone = normalizePhone(rawPhone);
        verificationCodeService.confirm(VerificationTargetType.SMS, normalizedPhone, VerificationPurpose.PHONE_VERIFY, code);

        user.setUserPhone(normalizedPhone);
        user.setIsSmsVerified(true);
    }

    private String normalizePhone(String rawPhone) {
        return rawPhone.replaceAll("[^0-9]", "");
    }

    // USER-005 - PATIENT/STAFF 모두 담당 관계 유무와 관계없이 탈퇴한다.
    // staff, staff_patient_assignment, memo, phrase, friendship, user_e2ee_key, refresh_token은
    // users FK의 ON DELETE CASCADE로, chat_participant.user_id/message_ciphertext는
    // ON DELETE SET NULL/CASCADE로 DB에서 정리된다 (V2/V3/V4/V6 migration).
    @Transactional
    public void withdraw(String userId) {
        User user = findUser(userId);
        userRepository.delete(user);
    }

    // SET-001
    @Transactional(readOnly = true)
    public UserSettingResponse getSettings(String userId) {
        User user = findUser(userId);
        requirePatient(user);

        return toSettingResponse(findUserSetting(userId));
    }

    // SET-002
    @Transactional
    public UserSettingResponse updateSettings(String userId, UserSettingUpdateRequest request) {
        User user = findUser(userId);
        requirePatient(user);

        UserSetting setting = findUserSetting(userId);

        if (request.keyboardLayout() != null) {
            setting.setKeyboardLayout(request.keyboardLayout());
        }
        if (request.keyEnlarged() != null) {
            setting.setIsKeyEnlarged(request.keyEnlarged());
        }
        if (request.currentInputMethod() != null) {
            setting.setCurrentInputMethod(InputMethod.valueOf(request.currentInputMethod()));
        }
        setting.setUpdatedAt(Instant.now());

        return toSettingResponse(setting);
    }

    private UserSetting findUserSetting(String userId) {
        return userSettingRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }

    private UserSettingResponse toSettingResponse(UserSetting setting) {
        return new UserSettingResponse(
                setting.getKeyboardLayout(),
                Boolean.TRUE.equals(setting.getIsKeyEnlarged()),
                setting.getCurrentInputMethod().name()
        );
    }

    private User findUser(String userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }

    // docs/LookTalk_Backend_API_Spec_v2.1.md 28, 29, 31, 32 - PATIENT 전용 API.
    // Spring Security URL 규칙과 별개로 Service에서도 역할을 다시 검증한다.
    private void requirePatient(User user) {
        if (user.getRole() != UserRole.PATIENT) {
            throw new BusinessException(ErrorCode.COMMON_FORBIDDEN);
        }
    }
}
