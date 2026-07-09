package com.canggo.zhishu.service;

import com.canggo.zhishu.config.AppAuthProperties;
import com.canggo.zhishu.exception.CustomException;
import com.canggo.zhishu.model.OrganizationTag;
import com.canggo.zhishu.model.RegistrationMode;
import com.canggo.zhishu.model.User;
import com.canggo.zhishu.repository.OrganizationTagRepository;
import com.canggo.zhishu.repository.UserRepository;
import com.canggo.zhishu.utils.PasswordUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private OrganizationTagRepository organizationTagRepository;

    @Mock
    private OrgTagCacheService orgTagCacheService;

    @Mock
    private AppAuthProperties appAuthProperties;

    @Mock
    private AppAuthProperties.Registration registration;

    @Mock
    private InviteCodeService inviteCodeService;

    @Mock
    private UsageQuotaService usageQuotaService;

    @Mock
    private org.springframework.core.env.Environment environment;

    @InjectMocks
    private UserService userService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        ReflectionTestUtils.setField(userService, "globalUploadMaxFileSize", "50MB");
        // 默认 mock：非 experiment profile（生产路径），arm 门控应无视 ?arm=baseline 走 after
        when(environment.getActiveProfiles()).thenReturn(new String[]{"dev"});
        when(appAuthProperties.getRegistration()).thenReturn(registration);
        when(registration.getMode()).thenReturn(RegistrationMode.OPEN);
        when(registration.isInviteRequired()).thenReturn(false);
    }

    @Test
    void testRegisterUserSuccessWhenOpenRegistration() {
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.empty());
        when(organizationTagRepository.existsByTagId("DEFAULT")).thenReturn(true);
        when(organizationTagRepository.existsByTagId("PRIVATE_testuser")).thenReturn(false);

        userService.registerUser("testuser", "password123", null);

        verify(userRepository, atLeastOnce()).save(any(User.class));
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository, atLeast(2)).save(userCaptor.capture());
        User savedUser = userCaptor.getAllValues().get(userCaptor.getAllValues().size() - 1);
        Set<String> savedOrgTags = new HashSet<>(Arrays.asList(savedUser.getOrgTags().split(",")));
        assertEquals(Set.of("DEFAULT", "PRIVATE_testuser"), savedOrgTags);
        assertEquals("PRIVATE_testuser", savedUser.getPrimaryOrg());
        verify(orgTagCacheService).cacheUserOrgTags("testuser", List.of("DEFAULT", "PRIVATE_testuser"));
        verify(orgTagCacheService).cacheUserPrimaryOrg("testuser", "PRIVATE_testuser");
        verify(inviteCodeService, never()).consume(anyString(), anyString());
    }

    @Test
    void testRegisterUserClosed() {
        when(registration.getMode()).thenReturn(RegistrationMode.CLOSED);

        CustomException exception = assertThrows(CustomException.class,
                () -> userService.registerUser("testuser", "password123", null));

        assertEquals("REGISTRATION_CLOSED", exception.getMessage());
        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
    }

    @Test
    void testRegisterUserInviteRequired() {
        when(registration.getMode()).thenReturn(RegistrationMode.INVITE_ONLY);
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.empty());
        when(organizationTagRepository.existsByTagId("DEFAULT")).thenReturn(true);
        when(organizationTagRepository.existsByTagId("PRIVATE_testuser")).thenReturn(false);

        userService.registerUser("testuser", "password123", "INVITE-001");

        verify(inviteCodeService, times(1)).consume("INVITE-001", "testuser");
    }

    @Test
    void testRegisterUserUsernameExists() {
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(new User()));

        CustomException exception = assertThrows(CustomException.class,
                () -> userService.registerUser("testuser", "password123", null));

        assertEquals("Username already exists", exception.getMessage());
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
    }

    @Test
    void testAuthenticateUserSuccess() {
        String rawPassword = "password123";
        String encodedPassword = PasswordUtil.encode(rawPassword);

        User user = new User();
        user.setUsername("testuser");
        user.setPassword(encodedPassword);

        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user));

        String username = userService.authenticateUser("testuser", rawPassword);
        assertEquals("testuser", username);
    }

    @Test
    void testAuthenticateUserInvalidCredentials() {
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.empty());

        CustomException exception = assertThrows(CustomException.class,
                () -> userService.authenticateUser("testuser", "wrongpassword"));

        assertEquals("Invalid username or password", exception.getMessage());
        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatus());
    }

    @Test
    void testEnsureDefaultOrgRequiresAdminWhenMissing() {
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.empty());
        when(registration.getMode()).thenReturn(RegistrationMode.OPEN);
        when(registration.isInviteRequired()).thenReturn(false);

        when(organizationTagRepository.existsByTagId("DEFAULT")).thenReturn(false);
        when(userRepository.findAll()).thenReturn(List.of());

        CustomException exception = assertThrows(CustomException.class,
                () -> userService.registerUser("testuser", "password123", null));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exception.getStatus());
    }

    @Test
    void testCreateOrganizationTagStoresUploadLimit() {
        User admin = new User();
        admin.setUsername("admin");
        admin.setRole(User.Role.ADMIN);

        OrganizationTag parentTag = new OrganizationTag();
        parentTag.setTagId("ROOT");

        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));
        when(organizationTagRepository.existsByTagId("TEAM_A")).thenReturn(false);
        when(organizationTagRepository.findByTagId("ROOT")).thenReturn(Optional.of(parentTag));
        when(organizationTagRepository.save(any(OrganizationTag.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OrganizationTag saved = userService.createOrganizationTag("TEAM_A", "Team A", "desc", "ROOT", 20L, "admin");

        assertEquals(20L * 1024 * 1024, saved.getUploadMaxSizeBytes());
        verify(orgTagCacheService).invalidateAllEffectiveTagsCache();
    }

    @Test
    void testCreateOrganizationTagAllowsUnlimitedUploadSize() {
        User admin = new User();
        admin.setUsername("admin");
        admin.setRole(User.Role.ADMIN);

        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));
        when(organizationTagRepository.existsByTagId("TEAM_A")).thenReturn(false);
        when(organizationTagRepository.save(any(OrganizationTag.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OrganizationTag saved = userService.createOrganizationTag("TEAM_A", "Team A", "desc", null, null, "admin");

        assertNull(saved.getUploadMaxSizeBytes());
    }

    @Test
    void testCreateOrganizationTagRejectsInvalidUploadLimit() {
        User admin = new User();
        admin.setUsername("admin");
        admin.setRole(User.Role.ADMIN);

        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));
        when(organizationTagRepository.existsByTagId("TEAM_A")).thenReturn(false);

        CustomException exception = assertThrows(
                CustomException.class,
                () -> userService.createOrganizationTag("TEAM_A", "Team A", "desc", null, 0L, "admin")
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        assertEquals("上传大小上限必须大于 0 MB", exception.getMessage());
    }

    @Test
    void testCreateOrganizationTagRejectsLimitOverGlobalMax() {
        User admin = new User();
        admin.setUsername("admin");
        admin.setRole(User.Role.ADMIN);

        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));
        when(organizationTagRepository.existsByTagId("TEAM_A")).thenReturn(false);

        CustomException exception = assertThrows(
                CustomException.class,
                () -> userService.createOrganizationTag("TEAM_A", "Team A", "desc", null, 60L, "admin")
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        assertEquals("上传大小上限不能超过系统全局限制 50 MB", exception.getMessage());
    }

    @Test
    void testGetUserListKeepsTotalCountAcrossPages() {
        // ZH-F07 after: getUserList 下推到 findUserListRows，不再调用 findAll。
        // 构造 25 条中第 2 页（pageIndex=1, size=10）的 10 条投影行。
        List<com.canggo.zhishu.service.dto.UserListRow> pageRows = java.util.stream.IntStream.rangeClosed(11, 20)
                .mapToObj(index -> new com.canggo.zhishu.service.dto.UserListRow(
                        (long) index,
                        "user-" + index,
                        null,
                        null,
                        User.Role.USER,
                        LocalDateTime.of(2026, 3, 1, 0, 0).minusMinutes(index)))
                .toList();
        org.springframework.data.domain.Page<com.canggo.zhishu.service.dto.UserListRow> rowPage =
                new org.springframework.data.domain.PageImpl<>(
                        pageRows,
                        org.springframework.data.domain.PageRequest.of(1, 10,
                                org.springframework.data.domain.Sort.by(
                                        org.springframework.data.domain.Sort.Order.desc("createdAt"),
                                        org.springframework.data.domain.Sort.Order.asc("id"))),
                        25L);

        when(userRepository.findUserListRows(isNull(), isNull(), isNull(), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(rowPage);
        when(usageQuotaService.getSnapshots(anyList())).thenReturn(Map.of());
        when(usageQuotaService.getSnapshot(anyString())).thenReturn(null);

        Map<String, Object> result = userService.getUserList(null, null, null, 2, 10);

        assertEquals(25L, result.get("totalElements"));
        assertEquals(3, result.get("totalPages"));
        assertEquals(10, result.get("size"));
        assertEquals(2, result.get("number"));
        assertEquals(10, ((List<?>) result.get("content")).size());
        verify(userRepository, never()).findAll(any(Sort.class));
        verify(userRepository).findUserListRows(isNull(), isNull(), isNull(), any(org.springframework.data.domain.Pageable.class));
    }

    @Test
    void testGetUserListKeywordEscapesLikeWildcards() {
        // ZH-F07 R3 minorFinding: keyword 含 LIKE 通配符 %/_ 时必须转义，使 SQL LIKE 与 Java String.contains 字面匹配等价。
        com.canggo.zhishu.service.dto.UserListRow row = new com.canggo.zhishu.service.dto.UserListRow(
                1L, "user-1_2", null, null, User.Role.USER, LocalDateTime.now());
        org.springframework.data.domain.Page<com.canggo.zhishu.service.dto.UserListRow> rowPage =
                new org.springframework.data.domain.PageImpl<>(List.of(row),
                        org.springframework.data.domain.PageRequest.of(0, 20), 1L);

        ArgumentCaptor<String> keywordCaptor = ArgumentCaptor.forClass(String.class);
        when(userRepository.findUserListRows(keywordCaptor.capture(), isNull(), isNull(), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(rowPage);
        when(usageQuotaService.getSnapshots(anyList())).thenReturn(Map.of());
        when(usageQuotaService.getSnapshot(anyString())).thenReturn(null);

        userService.getUserList("a_b%c", null, null, 1, 20);

        String passed = keywordCaptor.getValue();
        // 传入 repository 的 keyword 已包 %...% 且内部 %/_ 被反斜杠转义
        assertEquals("%a\\_b\\%c%", passed);
    }

    @Test
    void testGetUserListStatusMappingAndNumberOneBased() {
        com.canggo.zhishu.service.dto.UserListRow adminRow = new com.canggo.zhishu.service.dto.UserListRow(
                1L, "admin-1", null, null, User.Role.ADMIN, LocalDateTime.now());
        org.springframework.data.domain.Page<com.canggo.zhishu.service.dto.UserListRow> rowPage =
                new org.springframework.data.domain.PageImpl<>(List.of(adminRow),
                        org.springframework.data.domain.PageRequest.of(0, 20), 1L);

        when(userRepository.findUserListRows(isNull(), isNull(), eq(User.Role.ADMIN), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(rowPage);
        when(usageQuotaService.getSnapshots(anyList())).thenReturn(Map.of());
        when(usageQuotaService.getSnapshot(anyString())).thenReturn(null);

        Map<String, Object> result = userService.getUserList(null, null, 0, 1, 20);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        assertEquals(0, content.get(0).get("status")); // ADMIN -> 0
        assertEquals(1, result.get("number")); // 1-based

        when(userRepository.findUserListRows(isNull(), isNull(), eq(User.Role.USER), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(rowPage);
        userService.getUserList(null, null, 1, 1, 20);
        verify(userRepository).findUserListRows(isNull(), isNull(), eq(User.Role.USER), any(org.springframework.data.domain.Pageable.class));
    }

    @Test
    void testGetUserListBaselineUsesFindAllAndInMemoryPaging() {
        // G3 R1 requiredFix 4: getUserListBaseline 单元测试，锁定 baseline arm 行为防漂移。
        // 验证 baseline 路径调 findAll（不是 findUserListRows）、内存 subList 分页、total=filtered、number 1-based。
        List<User> users = java.util.stream.IntStream.rangeClosed(1, 25)
                .mapToObj(i -> {
                    User u = new User();
                    u.setId((long) i);
                    u.setUsername("user-" + i);
                    u.setRole(i % 5 == 0 ? User.Role.ADMIN : User.Role.USER);
                    u.setOrgTags(i % 2 == 0 ? "ORG_1" : "default");
                    u.setCreatedAt(LocalDateTime.of(2026, 3, 1, 0, 0).minusMinutes(i));
                    return u;
                })
                .toList();

        when(userRepository.findAll(any(Sort.class))).thenReturn(users);
        when(usageQuotaService.getSnapshots(anyList())).thenReturn(Map.of());
        when(usageQuotaService.getSnapshot(anyString())).thenReturn(null);

        // page=2 size=10，无过滤：25 条 → page2 是 id 15..6（createdAt DESC）
        Map<String, Object> result = userService.getUserListBaseline(null, null, null, 2, 10);

        assertEquals(25L, result.get("totalElements"));
        assertEquals(3, result.get("totalPages"));
        assertEquals(10, result.get("size"));
        assertEquals(2, result.get("number"));
        assertEquals(10, ((List<?>) result.get("content")).size());
        verify(userRepository, never()).findUserListRows(any(), any(), any(), any());
        verify(userRepository).findAll(any(Sort.class));

        // 带 status=1(USER) 过滤：20 个 USER → page1 应有 10 条全是 USER
        Map<String, Object> filtered = userService.getUserListBaseline(null, null, 1, 1, 10);
        assertEquals(20L, filtered.get("totalElements"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) filtered.get("content");
        assertEquals(10, content.size());
        assertTrue(content.stream().allMatch(r -> Integer.valueOf(1).equals(r.get("status"))));
    }

    @Test
    void testGetUserListForExperimentIgnoresBaselineArmUnderNonExperimentProfile() {
        // G3 R2 requiredFix 1: profile 门控护栏。生产 profile（dev）下 ?arm=baseline 必须被无视，仍走 after 下推路径。
        // mock after 路径返回，baseline 路径不 mock；若门控失效走了 baseline 会因 findAll 无 stub 抛 NPE/返回空，断言失败。
        com.canggo.zhishu.service.dto.UserListRow row = new com.canggo.zhishu.service.dto.UserListRow(
                1L, "user-1", null, null, User.Role.USER, LocalDateTime.now());
        org.springframework.data.domain.Page<com.canggo.zhishu.service.dto.UserListRow> rowPage =
                new org.springframework.data.domain.PageImpl<>(List.of(row),
                        org.springframework.data.domain.PageRequest.of(0, 20), 1L);

        when(environment.getActiveProfiles()).thenReturn(new String[]{"dev"}); // 生产 profile
        when(userRepository.findUserListRows(isNull(), isNull(), isNull(), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(rowPage);
        when(usageQuotaService.getSnapshots(anyList())).thenReturn(Map.of());
        when(usageQuotaService.getSnapshot(anyString())).thenReturn(null);

        // ?arm=baseline 在 dev profile 下应被无视 → 走 getUserList（after）
        Map<String, Object> result = userService.getUserListForExperiment(null, null, null, 1, 20, "baseline");

        // after 路径被调（findUserListRows），baseline 路径（findAll）绝不被调
        verify(userRepository).findUserListRows(isNull(), isNull(), isNull(), any(org.springframework.data.domain.Pageable.class));
        verify(userRepository, never()).findAll(any(Sort.class));
        assertEquals(1L, result.get("totalElements"));
    }

    @Test
    void testGetUserListForExperimentHonorsBaselineArmUnderExperimentProfile() {
        // G3 R2 requiredFix 1 对照：experiment profile 下 ?arm=baseline 才真走 baseline 路径。
        when(environment.getActiveProfiles()).thenReturn(new String[]{"experiment"});
        when(userRepository.findAll(any(Sort.class))).thenReturn(List.of());
        when(usageQuotaService.getSnapshots(anyList())).thenReturn(Map.of());
        when(usageQuotaService.getSnapshot(anyString())).thenReturn(null);

        userService.getUserListForExperiment(null, null, null, 1, 20, "baseline");

        // experiment profile + arm=baseline → 走 baseline（findAll），不调 after（findUserListRows）
        verify(userRepository).findAll(any(Sort.class));
        verify(userRepository, never()).findUserListRows(any(), any(), any(), any());
    }
}
