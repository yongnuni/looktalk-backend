package com.looktalk.backend.user.support;

import com.looktalk.backend.user.entity.User;

// docs/LookTalk_Backend_API_Spec_v2.1.md 63. 사용자 표시명 처리
// user_name이 있으면 user_name, 없으면 login_id를 사용한다. user_id는 화면 표시용 fallback으로 사용하지 않는다.
public final class DisplayNameResolver {

    private DisplayNameResolver() {
    }

    public static String resolve(User user) {
        if (user.getUserName() == null || user.getUserName().isBlank()) {
            return user.getLoginId();
        }
        return user.getUserName();
    }
}
