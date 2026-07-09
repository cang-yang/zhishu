package com.canggo.zhishu.repository;

import com.canggo.zhishu.model.User;
import com.canggo.zhishu.service.dto.UserListRow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {
    Optional<User> findByUsername(String username);

    boolean existsByRole(User.Role role);

    /**
     * ZH-F07: ADMIN-USERS-LIST 投影查询。
     * 过滤/排序/分页下推到 MySQL；构造表达式投影只取合同字段，不查 password/updated_at。
     * countQuery 显式指向独立 count JPQL，避免 Spring Data 对 `select new ...` 投影派生 count 时回退到全实体 count。
     * LIKE 使用 escape '\\'，配合 service 层对 keyword 的通配符转义，使 SQL LIKE 与 baseline Java String.contains 字面匹配等价。
     * FIND_IN_SET 命中 orgTags CSV 中的一个标签，与 baseline Set.contains 等价。
     */
    @Query(value = """
            select new com.canggo.zhishu.service.dto.UserListRow(
                u.id, u.username, u.orgTags, u.primaryOrg, u.role, u.createdAt)
            from User u
            where (:keyword is null or u.username like :keyword escape '\\')
              and (:orgTag is null or function('FIND_IN_SET', :orgTag, u.orgTags) > 0)
              and (:role is null or u.role = :role)
            """,
            countQuery = """
            select count(u.id) from User u
            where (:keyword is null or u.username like :keyword escape '\\')
              and (:orgTag is null or function('FIND_IN_SET', :orgTag, u.orgTags) > 0)
              and (:role is null or u.role = :role)
            """)
    Page<UserListRow> findUserListRows(
            @Param("keyword") String keyword,
            @Param("orgTag") String orgTag,
            @Param("role") User.Role role,
            Pageable pageable);

    /**
     * 测试用等价 count 入口（主路径走 findUserListRows 的 countQuery 属性，二者 JPQL 逐字相同）。
     * 供 @DataJpaTest 断言 total 一致时单独调用。
     */
    @Query("""
            select count(u.id) from User u
            where (:keyword is null or u.username like :keyword escape '\\')
              and (:orgTag is null or function('FIND_IN_SET', :orgTag, u.orgTags) > 0)
              and (:role is null or u.role = :role)
            """)
    long countUserListRows(
            @Param("keyword") String keyword,
            @Param("orgTag") String orgTag,
            @Param("role") User.Role role);
}
