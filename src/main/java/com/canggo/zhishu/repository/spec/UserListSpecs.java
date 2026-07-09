package com.canggo.zhishu.repository.spec;

import com.canggo.zhishu.model.User;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

/**
 * ADMIN-USERS-LIST 过滤条件工厂（ZH-F07）。
 * 主路径走 UserRepository.findUserListRows 的命名 @Query（更易 EXPLAIN/统计扫描行数）；
 * 本工厂保留为可读的等价 Specification 表达，用于 @DataJpaTest 等价性证明与未来扩展。
 * 三种过滤的语义与 baseline UserService.matchesUserListFilters 逐项对齐：
 *   - usernameContains: username 大小写敏感 contains（dataset 无 case-variant 碰撞）
 *   - orgTagExact: orgTags CSV 中精确命中一个标签（与 baseline Set.contains 一致）
 *   - roleIs: status=1 -> USER，其他整数 -> ADMIN
 */
public final class UserListSpecs {

    private UserListSpecs() {
    }

    public static Specification<User> usernameContains(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        String pattern = "%" + escapeLike(keyword) + "%";
        return (root, query, cb) -> cb.like(root.get("username"), pattern, '\\');
    }

    public static Specification<User> orgTagExact(String orgTag) {
        if (orgTag == null || orgTag.isBlank()) {
            return null;
        }
        return (root, query, cb) -> cb.gt(
                cb.function("FIND_IN_SET", Integer.class,
                        cb.literal(orgTag), root.get("orgTags")),
                0);
    }

    public static Specification<User> roleIs(User.Role role) {
        if (role == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("role"), role);
    }

    public static Specification<User> userList(String keyword, String orgTag, User.Role role) {
        return (root, query, cb) -> {
            List<Predicate> preds = new ArrayList<>();
            Specification<User> s;
            if ((s = usernameContains(keyword)) != null) preds.add(s.toPredicate(root, query, cb));
            if ((s = orgTagExact(orgTag)) != null) preds.add(s.toPredicate(root, query, cb));
            if ((s = roleIs(role)) != null) preds.add(s.toPredicate(root, query, cb));
            return cb.and(preds.toArray(new Predicate[0]));
        };
    }

    /**
     * 转义 LIKE 通配符 % 与 _，使 SQL LIKE 与 baseline Java String.contains 字面匹配等价。
     * 同时转义转义字符本身。service 层调用本方法后再包 %...%。
     */
    public static String escapeLike(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' || c == '%' || c == '_') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
