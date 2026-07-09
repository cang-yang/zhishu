package com.canggo.zhishu.service;

import com.canggo.zhishu.config.AppAuthProperties;
import com.canggo.zhishu.model.OrganizationTag;
import com.canggo.zhishu.model.User;
import com.canggo.zhishu.repository.OrganizationTagRepository;
import com.canggo.zhishu.repository.UserRepository;
import com.canggo.zhishu.service.dto.UserListRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ZH-F07 护栏 ZH-M-F07-04 自动化服务级等价性回归（G3 R1 requiredFix 2）。
 *
 * <p>在固定小数据集上同进程分别调用 after 路径 {@link UserService#getUserList} 与
 * baseline 路径 {@link UserService#getUserListBaseline}，断言 canonicalJson(content + total + totalPages + size + number)
 * 逐字段相等，覆盖 content/total/排序/字段白名单。
 *
 * <p>baseline 路径用真实 UserService.getUserListBaseline（改造前 findAll+内存过滤+subList+逐个findByTagId），
 * 不再依赖 src/test legacy 副本；after 路径 mock findUserListRows 返回等价的投影行。
 */
class UserListEquivalenceTest {

    @Mock private UserRepository userRepository;
    @Mock private OrganizationTagRepository organizationTagRepository;
    @Mock private OrgTagCacheService orgTagCacheService;
    @Mock private AppAuthProperties appAuthProperties;
    @Mock private AppAuthProperties.Registration registration;
    @Mock private InviteCodeService inviteCodeService;
    @Mock private UsageQuotaService usageQuotaService;
    @Mock private org.springframework.core.env.Environment environment;

    @InjectMocks private UserService userService;

    /** 固定小数据集：5 用户，createdAt 唯一递减，覆盖 keyword/orgTag/status 三种过滤 */
    private List<User> fixtureUsers() {
        LocalDateTime base = LocalDateTime.of(2026, 3, 1, 0, 0);
        List<User> users = new ArrayList<>();
        // id, username, role, orgTags, primaryOrg, createdAt(递减: id 大 = 时间早)
        users.add(makeUser(1L, "alice_test", User.Role.USER, "default,ORG_1", "default", base.minusMinutes(1)));
        users.add(makeUser(2L, "bob_test", User.Role.USER, "ORG_2", "ORG_2", base.minusMinutes(2)));
        users.add(makeUser(3L, "carol", User.Role.ADMIN, "default,ORG_1,ORG_3", "default", base.minusMinutes(3)));
        users.add(makeUser(4L, "dave_test", User.Role.USER, "ORG_1", "ORG_1", base.minusMinutes(4)));
        users.add(makeUser(5L, "eve_test", User.Role.USER, "default", "default", base.minusMinutes(5)));
        return users;
    }

    private User makeUser(Long id, String username, User.Role role, String orgTags, String primaryOrg, LocalDateTime createdAt) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setPassword("hashed");
        u.setRole(role);
        u.setOrgTags(orgTags);
        u.setPrimaryOrg(primaryOrg);
        u.setCreatedAt(createdAt);
        return u;
    }

    private OrganizationTag tag(String tagId, String name) {
        OrganizationTag t = new OrganizationTag();
        t.setTagId(tagId);
        t.setName(name);
        return t;
    }

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        ReflectionTestUtils.setField(userService, "globalUploadMaxFileSize", "50MB");
        ReflectionTestUtils.setField(userService, "userListExperimentArm", "");
        // 等价性测试直接调 getUserList/getUserListBaseline，不经 getUserListForExperiment 分派；
        // environment mock 默认 dev（非 experiment），与生产一致。
        when(environment.getActiveProfiles()).thenReturn(new String[]{"dev"});
        when(appAuthProperties.getRegistration()).thenReturn(registration);
        when(registration.getMode()).thenReturn(com.canggo.zhishu.model.RegistrationMode.OPEN);
        when(registration.isInviteRequired()).thenReturn(false);
        when(usageQuotaService.getSnapshots(anyList())).thenReturn(Map.of());
        when(usageQuotaService.getSnapshot(anyString())).thenReturn(null);
    }

    /** 02 §6.2 计划的 canonical diff：同参数同数据，baseline vs after 全字段一致 */
    @Test
    void baselineAndAfterProduceCanonicalEquivalentResponse() {
        List<User> users = fixtureUsers();
        // 过滤：keyword=test, orgTag=ORG_1, status=1(USER) → 命中 alice_test(1,ORG_1), dave_test(4,ORG_1)
        // carol 是 ADMIN(status!=1) 排除；bob_test 无 ORG_1 排除；eve_test 无 ORG_1 排除
        List<User> filtered = users.stream()
                .filter(u -> u.getUsername().contains("test"))
                .filter(u -> u.getOrgTags() != null && Arrays.asList(u.getOrgTags().split(",")).contains("ORG_1"))
                .filter(u -> u.getRole() == User.Role.USER)
                .sorted(Comparator.comparing(User::getCreatedAt).reversed())
                .collect(Collectors.toList());
        // 排序 createdAt DESC: alice_test(-1) > dave_test(-4) → [alice_test, dave_test]
        assertEquals(List.of(1L, 4L), filtered.stream().map(User::getId).toList());

        // === baseline 路径 mock ===
        when(userRepository.findAll(any(Sort.class))).thenReturn(users);
        when(organizationTagRepository.findByTagId(eq("default"))).thenReturn(Optional.of(tag("default", "default")));
        when(organizationTagRepository.findByTagId(eq("ORG_1"))).thenReturn(Optional.of(tag("ORG_1", "Org 1")));
        when(organizationTagRepository.findByTagId(eq("ORG_2"))).thenReturn(Optional.of(tag("ORG_2", "Org 2")));
        when(organizationTagRepository.findByTagId(eq("ORG_3"))).thenReturn(Optional.of(tag("ORG_3", "Org 3")));

        Map<String, Object> baseResult = userService.getUserListBaseline("test", "ORG_1", 1, 1, 20);

        // === after 路径 mock：findUserListRows 返回与 baseline 过滤+分页等价的投影行 ===
        List<UserListRow> afterRows = filtered.stream()
                .map(u -> new UserListRow(u.getId(), u.getUsername(), u.getOrgTags(), u.getPrimaryOrg(), u.getRole(), u.getCreatedAt()))
                .collect(Collectors.toList());
        when(userRepository.findUserListRows(eq("%test%"), eq("ORG_1"), eq(User.Role.USER), any()))
                .thenReturn(new PageImpl<>(afterRows,
                        PageRequest.of(0, 20, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"))),
                        filtered.size()));
        when(organizationTagRepository.findByTagIdIn(anyList())).thenAnswer(inv -> {
            List<String> ids = inv.getArgument(0);
            return ids.stream().map(id -> switch (id) {
                case "default" -> tag("default", "default");
                case "ORG_1" -> tag("ORG_1", "Org 1");
                case "ORG_2" -> tag("ORG_2", "Org 2");
                case "ORG_3" -> tag("ORG_3", "Org 3");
                default -> null;
            }).filter(Objects::nonNull).collect(Collectors.toList());
        });

        Map<String, Object> afterResult = userService.getUserList("test", "ORG_1", 1, 1, 20);

        // === canonical 比较 ===
        assertEquals(canonical(baseResult), canonical(afterResult),
                "baseline vs after response must be canonically equal");
        // 关键字段逐项确认
        assertEquals(baseResult.get("totalElements"), afterResult.get("totalElements"));
        assertEquals(baseResult.get("totalPages"), afterResult.get("totalPages"));
        assertEquals(baseResult.get("number"), afterResult.get("number"));
        assertEquals(baseResult.get("size"), afterResult.get("size"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> baseContent = (List<Map<String, Object>>) baseResult.get("content");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> afterContent = (List<Map<String, Object>>) afterResult.get("content");
        assertEquals(baseContent.size(), afterContent.size());
        for (int i = 0; i < baseContent.size(); i++) {
            assertEquals(baseContent.get(i).keySet(), afterContent.get(i).keySet(), "row " + i + " field whitelist");
            assertEquals(baseContent.get(i).get("userId"), afterContent.get(i).get("userId"));
            assertEquals(baseContent.get(i).get("username"), afterContent.get(i).get("username"));
            assertEquals(baseContent.get(i).get("status"), afterContent.get(i).get("status"));
            assertEquals(baseContent.get(i).get("primaryOrg"), afterContent.get(i).get("primaryOrg"));
            assertEquals(baseContent.get(i).get("orgTags"), afterContent.get(i).get("orgTags"));
        }
    }

    /** 缺失 tagId 在两 arm 都跳过（不输出 {tagId,null}）— 护栏 ZH-M-F07-04 边界 */
    @Test
    void missingTagIdSkippedInBothArms() {
        List<User> users = List.of(makeUser(1L, "u1_test", User.Role.USER, "default,ORG_MISSING", "default", LocalDateTime.now()));
        when(userRepository.findAll(any(Sort.class))).thenReturn(users);
        // baseline: findByTagId("ORG_MISSING") → empty
        when(organizationTagRepository.findByTagId(eq("default"))).thenReturn(Optional.of(tag("default", "default")));
        when(organizationTagRepository.findByTagId(eq("ORG_MISSING"))).thenReturn(Optional.empty());
        Map<String, Object> baseResult = userService.getUserListBaseline("test", null, 1, 1, 20);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> baseContent = (List<Map<String, Object>>) baseResult.get("content");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> baseTags = (List<Map<String, String>>) baseContent.get(0).get("orgTags");
        assertEquals(1, baseTags.size());
        assertEquals("default", baseTags.get(0).get("tagId"));

        // after: findByTagIdIn([default,ORG_MISSING]) → only default
        when(userRepository.findUserListRows(eq("%test%"), isNull(), eq(User.Role.USER), any()))
                .thenReturn(new PageImpl<>(
                        List.of(new UserListRow(1L, "u1_test", "default,ORG_MISSING", "default", User.Role.USER, LocalDateTime.now())),
                        PageRequest.of(0, 20), 1L));
        when(organizationTagRepository.findByTagIdIn(anyList())).thenReturn(List.of(tag("default", "default")));
        Map<String, Object> afterResult = userService.getUserList("test", null, 1, 1, 20);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> afterContent = (List<Map<String, Object>>) afterResult.get("content");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> afterTags = (List<Map<String, String>>) afterContent.get(0).get("orgTags");
        assertEquals(1, afterTags.size());
        assertEquals("default", afterTags.get(0).get("tagId"));

        assertEquals(canonical(baseResult), canonical(afterResult));
    }

    /** mixed-case username 不变量钉死：LIKE（ci collation）vs Java contains（cs）在当前 dataset 无碰撞时等价 */
    @Test
    void mixedCaseUsernameNoCollisionInvariant() {
        // dataset 不变量：synthetic_user_<n> 全小写，无 case-variant 碰撞。
        // 本测试钉死：当 username 含大小写差异时，Java contains 大小写敏感；此为已知边界，dataset 规避。
        List<User> users = List.of(
                makeUser(1L, "Alice", User.Role.USER, "default", "default", LocalDateTime.now()),
                makeUser(2L, "alice", User.Role.USER, "default", "default", LocalDateTime.now().minusMinutes(1)));
        when(userRepository.findAll(any(Sort.class))).thenReturn(users);
        // Java contains("alice") 大小写敏感：只匹配 "alice"(id=2)，不匹配 "Alice"(id=1)
        Map<String, Object> baseResult = userService.getUserListBaseline("alice", null, null, 1, 20);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) baseResult.get("content");
        assertEquals(1, content.size());
        assertEquals("alice", content.get(0).get("username"));
        // 钉死：dataset 用全小写 synthetic_user_<n> 时无此问题；本测试记录 Java contains 大小写敏感语义。
    }

    private String canonical(Map<String, Object> result) {
        // canonical: content 按 userId 排序后序列化 + 固定 key 顺序
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        List<Map<String, Object>> sorted = content.stream()
                .sorted(Comparator.comparing(r -> ((Number) r.get("userId")).longValue()))
                .collect(Collectors.toList());
        return String.format("total=%s|totalPages=%s|size=%s|number=%s|content=%s",
                result.get("totalElements"), result.get("totalPages"), result.get("size"), result.get("number"),
                sorted.stream().map(r -> r.get("userId") + ":" + r.get("username") + ":" + r.get("status") + ":" + r.get("orgTags")).collect(Collectors.joining(",")));
    }
}
