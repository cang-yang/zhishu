package com.canggo.zhishu.service.dto;

import com.canggo.zhishu.model.User;

import java.time.LocalDateTime;

/**
 * ADMIN-USERS-LIST 投影 DTO（ZH-F07）。
 * 构造表达式投影，DB 层只查合同所需字段，不查 password/updated_at。
 * 字段顺序与 UserRepository.findUserListRows 的 `select new ...` 一一对应。
 */
public record UserListRow(
        Long id,
        String username,
        String orgTags,
        String primaryOrg,
        User.Role role,
        LocalDateTime createdAt
) {
}
