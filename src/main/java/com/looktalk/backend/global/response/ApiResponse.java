package com.looktalk.backend.global.response;

import com.looktalk.backend.global.exception.ErrorCode;

// docs/LookTalk_Backend_API_Spec_v2.1.md 11, 12. 공통 성공/실패 Response 구조를 그대로 따른다.
public record ApiResponse<T>(
        boolean success,
        String code,
        String message,
        T data
) {
    private static final String SUCCESS_CODE = "SUCCESS";
    private static final String DEFAULT_SUCCESS_MESSAGE = "요청이 성공했습니다.";

    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(true, SUCCESS_CODE, message, data);
    }

    public static <T> ApiResponse<T> success(T data) {
        return success(DEFAULT_SUCCESS_MESSAGE, data);
    }

    public static <T> ApiResponse<T> fail(ErrorCode errorCode, T data) {
        return new ApiResponse<>(false, errorCode.name(), errorCode.getMessage(), data);
    }

    public static ApiResponse<Void> fail(ErrorCode errorCode) {
        return fail(errorCode, null);
    }
}
