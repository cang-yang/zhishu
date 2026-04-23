package com.canggo.zhishu.repository;

import com.canggo.zhishu.model.RateLimitConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RateLimitConfigRepository extends JpaRepository<RateLimitConfig, String> {
}
