package com.looktalk.backend.user.repository;

import com.looktalk.backend.user.entity.Friendship;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FriendshipRepository extends JpaRepository<Friendship, Long> {
}
