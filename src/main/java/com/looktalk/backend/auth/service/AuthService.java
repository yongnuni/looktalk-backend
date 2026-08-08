package com.looktalk.backend.auth.service;

import com.looktalk.backend.auth.dto.EmailVerificationResponse;
import com.looktalk.backend.auth.dto.LoginIdAvailabilityResponse;
import com.looktalk.backend.auth.dto.LoginRequest;
import com.looktalk.backend.auth.dto.LoginResponse;
import com.looktalk.backend.auth.dto.PasswordResetRequest;
import com.looktalk.backend.auth.dto.PasswordResetVerificationResult;
import com.looktalk.backend.auth.dto.PatientSignupRequest;
import com.looktalk.backend.auth.dto.PatientSignupResponse;
import com.looktalk.backend.auth.dto.SignupVerificationResult;
import com.looktalk.backend.auth.dto.StaffSignupRequest;
import com.looktalk.backend.auth.dto.StaffSignupResponse;
import com.looktalk.backend.auth.dto.TokenRefreshResponse;
import com.looktalk.backend.auth.entity.VerificationPurpose;
import com.looktalk.backend.auth.entity.VerificationTargetType;
import com.looktalk.backend.auth.refreshtoken.entity.RefreshToken;
import com.looktalk.backend.auth.refreshtoken.repository.RefreshTokenRepository;
import com.looktalk.backend.global.exception.BusinessException;
import com.looktalk.backend.global.exception.ErrorCode;
import com.looktalk.backend.global.security.JwtTokenProvider;
import com.looktalk.backend.global.security.OpaqueTokenGenerator;
import com.looktalk.backend.global.security.Sha256Hasher;
import com.looktalk.backend.hospital.entity.Hospital;
import com.looktalk.backend.hospital.repository.HospitalRepository;
import com.looktalk.backend.staff.entity.Staff;
import com.looktalk.backend.staff.repository.StaffRepository;
import com.looktalk.backend.user.entity.InputMethod;
import com.looktalk.backend.user.entity.User;
import com.looktalk.backend.user.entity.UserRole;
import com.looktalk.backend.user.entity.UserSetting;
import com.looktalk.backend.user.repository.UserRepository;
import com.looktalk.backend.user.repository.UserSettingRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final UserSettingRepository userSettingRepository;
    private final StaffRepository staffRepository;
    private final HospitalRepository hospitalRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final VerificationCodeService verificationCodeService;
    private final EmailSender emailSender;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final OpaqueTokenGenerator opaqueTokenGenerator;
    private final Sha256Hasher sha256Hasher;
    private final long refreshTokenExpiryDays;
    private final long resetTokenExpirySeconds;

    public AuthService(
            UserRepository userRepository,
            UserSettingRepository userSettingRepository,
            StaffRepository staffRepository,
            HospitalRepository hospitalRepository,
            RefreshTokenRepository refreshTokenRepository,
            VerificationCodeService verificationCodeService,
            EmailSender emailSender,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider,
            OpaqueTokenGenerator opaqueTokenGenerator,
            Sha256Hasher sha256Hasher,
            @Value("${looktalk.refresh-token.expiry-days}") long refreshTokenExpiryDays,
            @Value("${looktalk.reset-token.expiry-seconds}") long resetTokenExpirySeconds
    ) {
        this.userRepository = userRepository;
        this.userSettingRepository = userSettingRepository;
        this.staffRepository = staffRepository;
        this.hospitalRepository = hospitalRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.verificationCodeService = verificationCodeService;
        this.emailSender = emailSender;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.opaqueTokenGenerator = opaqueTokenGenerator;
        this.sha256Hasher = sha256Hasher;
        this.refreshTokenExpiryDays = refreshTokenExpiryDays;
        this.resetTokenExpirySeconds = resetTokenExpirySeconds;
    }

    // AUTH-001
    @Transactional
    public EmailVerificationResponse sendEmailVerification(String email, String purposeValue) {
        VerificationPurpose purpose = VerificationPurpose.valueOf(purposeValue);

        if (purpose == VerificationPurpose.SIGNUP && userRepository.existsByUserEmail(email)) {
            throw new BusinessException(ErrorCode.AUTH_DUPLICATE_EMAIL);
        }

        VerificationCodeService.IssuedCode issued =
                verificationCodeService.issue(VerificationTargetType.EMAIL, email, purpose);

        emailSender.sendVerificationCode(email, issued.code(), purpose);

        return new EmailVerificationResponse(issued.expiresInSeconds());
    }

    // AUTH-002
    @Transactional
    public Object confirmEmailVerification(String email, String purposeValue, String code) {
        VerificationPurpose purpose = VerificationPurpose.valueOf(purposeValue);

        verificationCodeService.confirm(VerificationTargetType.EMAIL, email, purpose, code);

        if (purpose == VerificationPurpose.PASSWORD_RESET) {
            return issueResetToken(email);
        }

        return new SignupVerificationResult(true);
    }

    private PasswordResetVerificationResult issueResetToken(String email) {
        User user = userRepository.findByUserEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        String resetToken = opaqueTokenGenerator.generate();
        user.setResetToken(sha256Hasher.hash(resetToken));
        user.setResetTokenExpiry(Instant.now().plusSeconds(resetTokenExpirySeconds));

        return new PasswordResetVerificationResult(resetToken);
    }

    // AUTH-003
    @Transactional(readOnly = true)
    public LoginIdAvailabilityResponse checkLoginIdAvailability(String loginId) {
        return new LoginIdAvailabilityResponse(!userRepository.existsByLoginId(loginId));
    }

    // AUTH-004
    @Transactional
    public PatientSignupResponse signupPatient(PatientSignupRequest request) {
        validateSignup(request.loginId(), request.email());

        String userId = UUID.randomUUID().toString();
        Instant now = Instant.now();

        User user = User.builder()
                .userId(userId)
                .loginId(request.loginId())
                .userEmail(request.email())
                .userPwd(passwordEncoder.encode(request.password()))
                .userName(null)
                .role(UserRole.PATIENT)
                .isEmailVerified(true)
                .isSmsVerified(false)
                .createdAt(now)
                .build();
        User savedUser = userRepository.save(user);

        UserSetting userSetting = UserSetting.builder()
                .user(savedUser)
                .keyboardLayout("QWERTY")
                .isKeyEnlarged(false)
                .currentInputMethod(InputMethod.EYE_TRACKING)
                .updatedAt(now)
                .build();
        userSettingRepository.save(userSetting);

        return new PatientSignupResponse(userId, request.loginId(), UserRole.PATIENT.name());
    }

    // AUTH-005
    @Transactional
    public StaffSignupResponse signupStaff(StaffSignupRequest request) {
        validateSignup(request.loginId(), request.email());

        Hospital hospital = hospitalRepository.findByInviteCode(request.inviteCode())
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_INVALID_INVITE_CODE));

        String userId = UUID.randomUUID().toString();
        Instant now = Instant.now();

        User user = User.builder()
                .userId(userId)
                .loginId(request.loginId())
                .userEmail(request.email())
                .userPwd(passwordEncoder.encode(request.password()))
                .userName(null)
                .role(UserRole.STAFF)
                .isEmailVerified(true)
                .isSmsVerified(false)
                .createdAt(now)
                .build();
        User savedUser = userRepository.save(user);

        Staff staff = Staff.builder()
                .user(savedUser)
                .hospital(hospital)
                .createdAt(now)
                .build();
        staffRepository.save(staff);

        return new StaffSignupResponse(
                userId,
                staff.getStaffId(),
                request.loginId(),
                UserRole.STAFF.name(),
                new StaffSignupResponse.HospitalSummary(hospital.getHospitalId(), hospital.getHospitalName())
        );
    }

    private void validateSignup(String loginId, String email) {
        if (userRepository.existsByLoginId(loginId)) {
            throw new BusinessException(ErrorCode.AUTH_DUPLICATE_LOGIN_ID);
        }
        if (userRepository.existsByUserEmail(email)) {
            throw new BusinessException(ErrorCode.AUTH_DUPLICATE_EMAIL);
        }
        if (!verificationCodeService.isVerified(VerificationTargetType.EMAIL, email, VerificationPurpose.SIGNUP)) {
            throw new BusinessException(ErrorCode.AUTH_EMAIL_NOT_VERIFIED);
        }
    }

    // AUTH-006
    @Transactional
    public LoginResult login(LoginRequest request) {
        User user = userRepository.findByLoginId(request.loginId())
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS));

        if (!passwordEncoder.matches(request.password(), user.getUserPwd())) {
            throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }

        String accessToken = jwtTokenProvider.generateAccessToken(user.getUserId(), user.getRole());
        String rawRefreshToken = issueRefreshToken(user);

        LoginResponse response = new LoginResponse(
                accessToken,
                "Bearer",
                jwtTokenProvider.getAccessTokenExpirySeconds(),
                user.getRole().name()
        );

        return new LoginResult(response, rawRefreshToken);
    }

    // AUTH-007 - Refresh Token Rotation: 기존 토큰 revoke 후 신규 토큰 발급.
    @Transactional
    public TokenRefreshResult refreshAccessToken(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new BusinessException(ErrorCode.AUTH_REFRESH_TOKEN_MISSING);
        }

        RefreshToken existing = refreshTokenRepository.findByTokenHash(sha256Hasher.hash(rawRefreshToken))
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_REFRESH_TOKEN_INVALID));

        if (existing.getRevokedAt() != null) {
            throw new BusinessException(ErrorCode.AUTH_REFRESH_TOKEN_INVALID);
        }

        if (existing.getExpiresAt().isBefore(Instant.now())) {
            throw new BusinessException(ErrorCode.AUTH_REFRESH_TOKEN_EXPIRED);
        }

        existing.setRevokedAt(Instant.now());

        User user = existing.getUser();
        String newRawRefreshToken = issueRefreshToken(user);
        String accessToken = jwtTokenProvider.generateAccessToken(user.getUserId(), user.getRole());

        TokenRefreshResponse response = new TokenRefreshResponse(
                accessToken,
                "Bearer",
                jwtTokenProvider.getAccessTokenExpirySeconds()
        );

        return new TokenRefreshResult(response, newRawRefreshToken);
    }

    // AUTH-008 - 토큰이 없거나 이미 폐기된 상태에서도 멱등적으로 성공 처리한다.
    @Transactional
    public void logout(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }

        refreshTokenRepository.findByTokenHash(sha256Hasher.hash(rawRefreshToken))
                .filter(token -> token.getRevokedAt() == null)
                .ifPresent(token -> token.setRevokedAt(Instant.now()));
    }

    // AUTH-009
    @Transactional
    public void resetPassword(PasswordResetRequest request) {
        User user = userRepository.findByResetToken(sha256Hasher.hash(request.resetToken()))
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_RESET_TOKEN_INVALID));

        if (user.getResetTokenExpiry() == null || user.getResetTokenExpiry().isBefore(Instant.now())) {
            throw new BusinessException(ErrorCode.AUTH_RESET_TOKEN_EXPIRED);
        }

        user.setUserPwd(passwordEncoder.encode(request.newPassword()));
        user.setResetToken(null);
        user.setResetTokenExpiry(null);

        refreshTokenRepository.revokeAllActiveByUserId(user.getUserId(), Instant.now());
    }

    private String issueRefreshToken(User user) {
        String rawToken = opaqueTokenGenerator.generate();
        Instant now = Instant.now();

        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .tokenHash(sha256Hasher.hash(rawToken))
                .expiresAt(now.plus(refreshTokenExpiryDays, ChronoUnit.DAYS))
                .createdAt(now)
                .build();
        refreshTokenRepository.save(refreshToken);

        return rawToken;
    }

    public record LoginResult(LoginResponse response, String rawRefreshToken) {
    }

    public record TokenRefreshResult(TokenRefreshResponse response, String rawRefreshToken) {
    }
}
