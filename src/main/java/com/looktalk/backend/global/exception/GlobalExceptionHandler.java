package com.looktalk.backend.global.exception;

import com.looktalk.backend.global.response.ApiResponse;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

// docs/LookTalk_Backend_API_Spec_v2.1.md 65. Exception 구현
// Controller에서 개별 실패 Response를 조립하지 않고 여기서 ErrorCode의 HTTP Status와 공통 ApiResponse를 생성한다.
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException exception) {
        ErrorCode errorCode = exception.getErrorCode();

        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ApiResponse.fail(errorCode));
    }

    // 13. HTTP Status 정책 - "Request 형식 오류"에 해당하는 @Valid 검증 실패
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<ValidationErrorData>> handleValidationException(
            MethodArgumentNotValidException exception
    ) {
        List<ValidationErrorData.FieldErrorDetail> fieldErrors = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(fieldError -> new ValidationErrorData.FieldErrorDetail(
                        fieldError.getField(),
                        fieldError.getDefaultMessage()
                ))
                .toList();

        return ResponseEntity
                .status(ErrorCode.COMMON_VALIDATION_ERROR.getStatus())
                .body(ApiResponse.fail(ErrorCode.COMMON_VALIDATION_ERROR, new ValidationErrorData(fieldErrors)));
    }

    // 13. HTTP Status 정책 - "Request 형식 오류" (예: JSON parse 실패)도 COMMON_VALIDATION_ERROR로 처리한다.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleMessageNotReadableException(
            HttpMessageNotReadableException exception
    ) {
        return ResponseEntity
                .status(ErrorCode.COMMON_VALIDATION_ERROR.getStatus())
                .body(ApiResponse.fail(ErrorCode.COMMON_VALIDATION_ERROR));
    }

    // 13. HTTP Status 정책 - "Request 형식 오류" (필수 query parameter 누락)
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingServletRequestParameterException(
            MissingServletRequestParameterException exception
    ) {
        return ResponseEntity
                .status(ErrorCode.COMMON_VALIDATION_ERROR.getStatus())
                .body(ApiResponse.fail(ErrorCode.COMMON_VALIDATION_ERROR));
    }

    // 정의되지 않은 경로 요청.
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFoundException(NoResourceFoundException exception) {
        return ResponseEntity
                .status(ErrorCode.COMMON_NOT_FOUND.getStatus())
                .body(ApiResponse.fail(ErrorCode.COMMON_NOT_FOUND));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception exception) {
        return ResponseEntity
                .status(ErrorCode.COMMON_INTERNAL_ERROR.getStatus())
                .body(ApiResponse.fail(ErrorCode.COMMON_INTERNAL_ERROR));
    }
}
