package com.looktalk.backend.user.repository;

import com.looktalk.backend.user.entity.UserSetting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserSettingRepository extends JpaRepository<UserSetting, String> {
}
