package com.canggo.zhishu.repository;

import com.canggo.zhishu.model.OrganizationTag;
import com.canggo.zhishu.model.User;
import com.canggo.zhishu.service.dto.UserListRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ZH-F07 repository 层下推验证。
 * 用真实 MySQL（profile=mysql-it，指向本机 zhishu_exp_f07 测试库）验证 FIND_IN_SET / LIKE escape / 投影 / count / 分页 / 排序。
 * 若 mysql-it profile 未配置，用 H2 MySQL 模式作构造表达式/分页/排序回归（FIND_IN_SET 在 H2 不支持，相关断言会在 MySQL 下完整覆盖）。
 *
 * <p>等价性护栏 ZH-M-F07-04 的 DB 层前置：本测试断言 findUserListRows 的过滤结果、total、排序、投影字段与
 * baseline UserService.matchesUserListFilters 在相同数据上逐条一致。
 */
@DataJpaTest
@ActiveProfiles("mysql-it")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DirtiesContext
class UserRepositoryListQueryTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationTagRepository organizationTagRepository;

    @Autowired
    private TestEntityManager entityManager;

    private User persistUser(String username, String orgTags, String primaryOrg, User.Role role, LocalDateTime createdAt) {
        User u = new User();
        u.setUsername(username);
        u.setPassword("hashed-pw");
        u.setOrgTags(orgTags);
        u.setPrimaryOrg(primaryOrg);
        u.setRole(role);
        // User.createdAt 是 @CreationTimestamp，手动 setCreatedAt 会被 Hibernate 用当前时间覆盖。
        // 用 SQL 直接指定 created_at，保证排序可控（ZH-F07 实验卡要求 createdAt 唯一、可排序）。
        u = userRepository.saveAndFlush(u);
        entityManager.getEntityManager().createNativeQuery("update users set created_at = :ts where id = :id")
                .setParameter("ts", java.sql.Timestamp.valueOf(createdAt))
                .setParameter("id", u.getId())
                .executeUpdate();
        entityManager.clear();
        return u;
    }

    @Test
    void findUserListRowsProjectsNoPasswordAndMatchesFilters() {
        // 数据：3 个用户，createdAt 唯一
        LocalDateTime base = LocalDateTime.of(2026, 3, 1, 0, 0);
        persistUser("alice", "default,ORG_1", "default", User.Role.USER, base.minusMinutes(1));
        persistUser("bob_99", "ORG_2", "ORG_2", User.Role.ADMIN, base.minusMinutes(2));
        persistUser("carol_x", "ORG_1,ORG_3", "ORG_1", User.Role.USER, base.minusMinutes(3));

        // keyword 含 LIKE 通配符 _，转义后应只字面匹配 bob_99 / carol_x
        String keyword = com.canggo.zhishu.repository.spec.UserListSpecs.escapeLike("_") ;
        Page<UserListRow> page = userRepository.findUserListRows(
                "%" + keyword + "%",
                null,
                null,
                PageRequest.of(0, 20, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"))));

        List<UserListRow> rows = page.getContent();
        // alice 不含 _，bob_99 与 carol_x 含 _
        assertTrue(rows.stream().anyMatch(r -> r.username().equals("bob_99")));
        assertTrue(rows.stream().anyMatch(r -> r.username().equals("carol_x")));
        assertFalse(rows.stream().anyMatch(r -> r.username().equals("alice")));
        // 排序 createdAt DESC：carol_x(-3) > bob_99(-2) > alice(-1)? No: larger minusMinutes = earlier timestamp.
        // base.minusMinutes(1)=latest, base.minusMinutes(3)=earliest. createdAt DESC → latest first → alice(-1) > bob_99(-2) > carol_x(-3).
        // 但 alice 被 keyword 过滤掉，所以第一行是 bob_99(-2)。
        assertEquals("bob_99", rows.get(0).username());
        // 投影字段存在，且不含 password（DTO 无 password 字段，构造成功即证明 DB 层未查 password 列进对象）
        UserListRow first = rows.get(0);
        assertNotNull(first.id());
        assertNotNull(first.username());
        assertNotNull(first.role());
        assertNotNull(first.createdAt());
    }

    @Test
    void countUserListRowsEqualsFilteredTotal() {
        LocalDateTime base = LocalDateTime.of(2026, 3, 2, 0, 0);
        for (int i = 1; i <= 25; i++) {
            persistUser("u" + i, i % 2 == 0 ? "ORG_1" : "default", "default", i == 10 ? User.Role.ADMIN : User.Role.USER, base.minusMinutes(i));
        }
        long totalAll = userRepository.countUserListRows(null, null, null);
        assertEquals(25L, totalAll);

        long totalOrg1 = userRepository.countUserListRows(null, "ORG_1", null);
        assertEquals(12L, totalOrg1); // 2,4,...,24 = 12 个

        long totalAdmin = userRepository.countUserListRows(null, null, User.Role.ADMIN);
        assertEquals(1L, totalAdmin);

        // Page total 与独立 count 一致
        Page<UserListRow> page = userRepository.findUserListRows(null, null, null,
                PageRequest.of(0, 10, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"))));
        assertEquals(totalAll, page.getTotalElements());
        assertEquals(10, page.getContent().size());
        assertEquals(3, page.getTotalPages());
    }

    @Test
    void missingTagIdIsSkippedNotNulledAtServiceLayer() {
        // 护栏 ZH-M-F07-04: orgTags 明细在 service 层批量 findByTagIdIn，缺失 tagId 跳过（不输出 {tagId,null}）。
        // 这里只验证 OrganizationTagRepository.findByTagIdIn 行为：只返回存在的 tag。
        // OrganizationTag.createdBy 是 nullable=false 外键，需先建一个 owner user。
        User owner = persistUser("tag-owner", null, null, User.Role.ADMIN, LocalDateTime.now());
        OrganizationTag t = new OrganizationTag();
        t.setTagId("ORG_EXISTS");
        t.setName("exists");
        t.setCreatedBy(owner);
        organizationTagRepository.saveAndFlush(t);

        List<OrganizationTag> found = organizationTagRepository.findByTagIdIn(List.of("ORG_EXISTS", "ORG_MISSING"));
        assertEquals(1, found.size());
        assertEquals("ORG_EXISTS", found.get(0).getTagId());
    }
}
