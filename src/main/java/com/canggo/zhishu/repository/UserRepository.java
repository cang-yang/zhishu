package com.canggo.zhishu.repository;

import com.canggo.zhishu.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);

    boolean existsByRole(User.Role role);
}
