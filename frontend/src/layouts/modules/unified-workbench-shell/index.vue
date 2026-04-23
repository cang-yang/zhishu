<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { LAYOUT_SCROLL_EL_ID } from '@sa/materials';
import { useAuthStore } from '@/store/modules/auth';
import { useThemeStore } from '@/store/modules/theme';
import { useWorkspaceStore } from '@/store/modules/workspace';
import { useChatSessionStore } from '@/store/modules/chat/session';
import { useAdminChatStore } from '@/store/modules/admin-chat';
import SvgIcon from '@/components/custom/svg-icon.vue';
import FloatingMenu from '@/components/custom/FloatingMenu.vue';
import MobileAdminPanel from '../mobile/MobileAdminPanel.vue';
import MobileDrawer from '../mobile/MobileDrawer.vue';
import MobileTabBar from '../mobile/MobileTabBar.vue';
import MobileTopBar from '../mobile/MobileTopBar.vue';

defineOptions({
  name: 'UnifiedWorkbenchShell'
});

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const themeStore = useThemeStore();
const workspaceStore = useWorkspaceStore();
const sessionStore = useChatSessionStore();
const adminChatStore = useAdminChatStore();

const { userInfo, isAdmin } = storeToRefs(authStore);
const { shellView, settingsSection, knowledgeCategory } = storeToRefs(workspaceStore);
const { sessionList, activeSessionId, loadingSessions } = storeToRefs(sessionStore);
const {
  users: adminChatUsers,
  sessions: adminChatSessions,
  userId: adminChatUserId,
  activeSessionId: adminChatActiveSessionId,
  loadingUsers: adminChatLoadingUsers,
  loadingSessions: adminChatLoadingSessions
} = storeToRefs(adminChatStore);

const shellClass = computed(() =>
  themeStore.darkMode ? 'unified-workbench-shell--dark' : 'unified-workbench-shell--light'
);

const currentRouteName = computed(() => String(route.name || 'chat'));

const primaryNavigation = computed(() => {
  return [
    { key: 'chat', label: '聊天', icon: 'solar:chat-round-dots-linear' },
    { key: 'knowledge-base', label: '知识库', icon: 'solar:folder-with-files-linear' },
    { key: 'settings', label: '设置', icon: 'solar:settings-linear' }
  ];
});

const adminNavigation = computed(() => {
  return [
    { key: 'user', label: '用户管理', icon: 'solar:users-group-two-rounded-linear' },
    { key: 'chat-history', label: '聊天管理', icon: 'solar:chat-round-line-linear' },
    { key: 'model-provider', label: '模型管理', icon: 'solar:tuning-square-linear' },
    { key: 'org-tag', label: '组织标签', icon: 'solar:tag-linear' },
    { key: 'invite-code', label: '邀请码', icon: 'solar:ticket-linear' },
    { key: 'usage-monitor', label: '用量监控', icon: 'solar:chart-2-linear' },
    { key: 'recharge', label: '余额充值', icon: 'solar:wallet-money-linear' },
    { key: 'recharge-manage', label: '充值管理', icon: 'solar:card-linear' }
  ];
});

const knowledgeCategories = computed(() => {
  return [
    { key: 'all', label: '全部', icon: 'solar:widget-5-linear' },
    { key: 'document', label: '文档', icon: 'solar:document-text-linear' },
    { key: 'sheet', label: '表格', icon: 'solar:chart-square-linear' }
  ];
});

const profileDropdownOptions = computed(() => {
  return [
    { key: 'profile', label: '个人中心' },
    { key: 'settings', label: '设置' },
    { key: 'divider', type: 'divider' },
    { key: 'logout', label: '退出登录' }
  ];
});

const sessionKeyword = ref('');
const pinnedSessionIds = ref<string[]>([]);
const editingSessionId = ref('');
const editingSessionTitle = ref('');
const renamingSession = ref(false);
const sessionMenuVisible = ref(false);
const sessionMenuX = ref(0);
const sessionMenuY = ref(0);
const sessionMenuTarget = ref('');
const adminChatUserKeyword = ref('');
const adminChatSessionKeyword = ref('');
const adminUserSelectorVisible = ref(false);
const mobileDrawerVisible = ref(false);
const mobileAdminPanelVisible = ref(false);
const mobileAdminChatListVisible = ref(true);
const isMobileViewport = ref(false);
const mobileSessionActionVisible = ref(false);
const mobileSessionLongPressTriggered = ref(false);
const mobileSessionMenuPosition = ref({ x: 0, y: 0 });
let mobileSessionLongPressTimer: ReturnType<typeof setTimeout> | null = null;

const MOBILE_BREAKPOINT = 768;

const filteredSessionList = computed(() => {
  const keyword = sessionKeyword.value.trim().toLowerCase();
  const source = keyword
    ? sessionList.value.filter(session => {
        const title = String(session.title || '').toLowerCase();
        const preview = String(session.latestPreview || '').toLowerCase();
        return title.includes(keyword) || preview.includes(keyword);
      })
    : [...sessionList.value];

  const pinned = source.filter(item => pinnedSessionIds.value.includes(item.sessionId));
  const normal = source.filter(item => !pinnedSessionIds.value.includes(item.sessionId));
  return [...pinned, ...normal];
});

const sessionGroups = computed(() => {
  const groups = new Map<string, typeof filteredSessionList.value>();

  filteredSessionList.value.forEach(session => {
    const label = pinnedSessionIds.value.includes(session.sessionId)
      ? '置顶'
      : resolveSessionGroupLabel(session.updatedAt || session.lastMessageAt || session.createdAt || null);
    const bucket = groups.get(label) || [];
    bucket.push(session);
    groups.set(label, bucket);
  });

  return Array.from(groups.entries()).map(([label, items]) => ({ label, items }));
});

const sessionMenuOptions = computed(() => {
  const target = filteredSessionList.value.find(item => item.sessionId === sessionMenuTarget.value);
  const pinned = target ? pinnedSessionIds.value.includes(target.sessionId) : false;

  return [
    { key: 'pin', label: pinned ? '取消置顶' : '置顶' },
    { key: 'rename', label: '重命名' },
    { key: 'delete', label: '删除' }
  ];
});

const sidebarMode = computed(() => {
  if (currentRouteName.value === 'chat') {
    return shellView.value === 'chat-list' ? 'chat-list' : 'home';
  }

  if (currentRouteName.value === 'chat-history') {
    return 'admin-chat-list';
  }

  if (currentRouteName.value === 'knowledge-base') {
    return 'knowledge-base';
  }

  return 'home';
});

const stageRoutes = ['chat', 'chat-history'];

const isStageRoute = computed(() => stageRoutes.includes(currentRouteName.value));

const isMobileLayout = computed(() => isMobileViewport.value);

const activeSession = computed(() => {
  return sessionList.value.find(item => item.sessionId === activeSessionId.value) || null;
});

const activeNavigationKey = computed(() => {
  if (currentRouteName.value === 'personal-center') return 'settings';
  if (currentRouteName.value === 'chat') return 'chat';
  if (currentRouteName.value === 'knowledge-base') return 'knowledge-base';
  if (adminNavigation.value.some(item => item.key === currentRouteName.value)) return currentRouteName.value;
  return 'chat';
});

const mobileTopBarTitle = computed(() => {
  if (currentRouteName.value === 'chat') {
    if (sidebarMode.value === 'chat-list') return '会话列表';
    return activeSession.value?.title || '聊天';
  }

  if (currentRouteName.value === 'knowledge-base') return '知识库';
  if (currentRouteName.value === 'personal-center') return '设置';

  const adminItem = adminNavigation.value.find(item => item.key === currentRouteName.value);
  return adminItem?.label || '工作台';
});

const mobileTopBarSubtitle = computed(() => {
  if (currentRouteName.value === 'chat-history' && !mobileAdminChatListVisible.value && activeAdminChatUser.value) {
    return `${activeAdminChatUser.value.username} · 管理员只读视角`;
  }

  if (currentRouteName.value === 'chat' && sidebarMode.value !== 'chat-list' && activeSession.value?.latestPreview) {
    return activeSession.value.latestPreview;
  }

  return '';
});

const mobileTopBarLeadingIcon = computed(() => {
  if (currentRouteName.value === 'chat' && sidebarMode.value === 'chat-list') {
    return 'solar:arrow-left-linear';
  }

  if (currentRouteName.value === 'chat-history' && !mobileAdminChatListVisible.value) {
    return 'solar:arrow-left-linear';
  }

  return 'solar:hamburger-menu-linear';
});

const mobileTopBarActions = computed(() => {
  if (currentRouteName.value === 'chat' && sidebarMode.value === 'chat-list') {
    return [{ key: 'new-session', label: '新话题', icon: 'solar:add-circle-linear' }];
  }

  if (currentRouteName.value === 'chat') {
    return [
      { key: 'new-session', label: '新话题', icon: 'solar:add-circle-linear' },
      { key: 'chat-list', label: '会话列表', icon: 'solar:list-linear' }
    ];
  }

  return [];
});

const mobileTabItems = computed(() => {
  const baseItems = primaryNavigation.value.map(item => ({ ...item }));

  if (isAdmin.value) {
    baseItems.push({ key: 'admin', label: '管理', icon: 'solar:crown-linear' });
  }

  return baseItems;
});

const mobileTabActiveKey = computed(() => {
  if (adminNavigation.value.some(item => item.key === currentRouteName.value)) {
    return 'admin';
  }

  if (currentRouteName.value === 'personal-center') return 'settings';
  return currentRouteName.value;
});

const mobileSessionActionOptions = computed(() => {
  const target = filteredSessionList.value.find(item => item.sessionId === sessionMenuTarget.value);
  const pinned = target ? pinnedSessionIds.value.includes(target.sessionId) : false;

  return [
    { key: 'pin', label: pinned ? '取消置顶' : '置顶', icon: 'solar:pin-linear' },
    { key: 'rename', label: '重命名', icon: 'solar:pen-linear' },
    { key: 'delete', label: '删除', icon: 'solar:trash-bin-minimalistic-linear', danger: true }
  ];
});

const mobileSessionActionTitle = computed(() => {
  return filteredSessionList.value.find(item => item.sessionId === sessionMenuTarget.value)?.title || '会话操作';
});

const filteredAdminChatSessions = computed(() => {
  const keyword = adminChatSessionKeyword.value.trim().toLowerCase();
  if (!keyword) return adminChatSessions.value;

  return adminChatSessions.value.filter(item => {
    const title = String(item.title || '').toLowerCase();
    const preview = String(item.latestPreview || '').toLowerCase();
    return title.includes(keyword) || preview.includes(keyword);
  });
});

const adminChatSessionGroups = computed(() => {
  const groups = new Map<string, typeof filteredAdminChatSessions.value>();

  filteredAdminChatSessions.value.forEach(session => {
    const label = resolveSessionGroupLabel(session.updatedAt || null);
    const bucket = groups.get(label) || [];
    bucket.push(session);
    groups.set(label, bucket);
  });

  return Array.from(groups.entries()).map(([label, items]) => ({ label, items }));
});

const filteredAdminChatUsers = computed(() => {
  const keyword = adminChatUserKeyword.value.trim().toLowerCase();
  if (!keyword) return adminChatUsers.value;
  return adminChatUsers.value.filter(item => item.username.toLowerCase().includes(keyword));
});

const activeAdminChatUser = computed(() => {
  return adminChatUsers.value.find(item => item.userId === adminChatUserId.value) || null;
});

function resolveSessionGroupLabel(timestamp: string | null | undefined) {
  if (!timestamp) return '更早';

  const target = new Date(timestamp);
  if (Number.isNaN(target.getTime())) return '更早';

  const now = new Date();
  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  const startOfTarget = new Date(target.getFullYear(), target.getMonth(), target.getDate()).getTime();
  const diffDays = Math.floor((startOfToday - startOfTarget) / 86_400_000);

  if (diffDays <= 0) return '今天';
  if (diffDays === 1) return '昨天';
  if (diffDays <= 7) return '本周';
  return '更早';
}

function navigateHome() {
  workspaceStore.openHome();
  sessionStore.clearActiveSession();
  router.push({ name: 'chat' });
}

function navigatePrimary(key: string) {
  if (key === 'chat') {
    if (isMobileLayout.value) {
      workspaceStore.openHome();
    } else {
      workspaceStore.openChatList();
    }
    router.push({ name: 'chat' });
    return;
  }

  if (key === 'knowledge-base') {
    workspaceStore.openKnowledgeBase();
    router.push({ name: 'knowledge-base' });
    return;
  }

  workspaceStore.openSettings();
  router.push({ name: 'personal-center', query: { tab: settingsSection.value } });
}

function navigateAdmin(key: string) {
  workspaceStore.openAdminSection(key as any);
  router.push({ name: key as any });
}

function changeKnowledgeCategory(key: string) {
  workspaceStore.setKnowledgeCategory(key as any);
}

function handleProfileAction(key: string) {
  if (key === 'logout') {
    authStore.logout();
    return;
  }

  if (key === 'profile') {
    workspaceStore.openSettings('account');
    router.push({ name: 'personal-center', query: { tab: 'account' } });
    return;
  }

  workspaceStore.openSettings('general');
  router.push({ name: 'personal-center', query: { tab: 'general' } });
}

async function handleCreateSession() {
  workspaceStore.openChatList();
  await sessionStore.createSession();

  if (isMobileLayout.value) {
    workspaceStore.openHome();
  }
}

async function handleActivateSession(sessionId: string) {
  workspaceStore.openChatList();
  await sessionStore.activateSession(sessionId);

  if (isMobileLayout.value) {
    workspaceStore.openHome();
  }
}

async function handleAdminChatUserChange(userId: number) {
  adminUserSelectorVisible.value = false;
  await adminChatStore.selectUser(userId);
}

async function handleAdminChatSessionChange(sessionId: string) {
  await adminChatStore.selectSession(sessionId);

  if (isMobileLayout.value) {
    mobileAdminChatListVisible.value = false;
  }
}

function syncMobileViewport() {
  if (typeof window === 'undefined') return;
  isMobileViewport.value = window.innerWidth <= MOBILE_BREAKPOINT;
}

function closeMobileOverlays() {
  mobileDrawerVisible.value = false;
  mobileAdminPanelVisible.value = false;
  mobileSessionActionVisible.value = false;
}

function clearMobileSessionLongPress() {
  if (!mobileSessionLongPressTimer) return;
  clearTimeout(mobileSessionLongPressTimer);
  mobileSessionLongPressTimer = null;
}

function startMobileSessionLongPress(event: TouchEvent, sessionId: string) {
  if (!isMobileLayout.value) return;

  const touch = event.touches?.[0];
  if (!touch) return;

  clearMobileSessionLongPress();
  mobileSessionLongPressTriggered.value = false;
  mobileSessionMenuPosition.value = {
    x: touch.clientX,
    y: touch.clientY
  };
  mobileSessionLongPressTimer = setTimeout(() => {
    sessionMenuTarget.value = sessionId;
    mobileSessionActionVisible.value = true;
    mobileSessionLongPressTriggered.value = true;
    navigator.vibrate?.(10);
  }, 500);
}

function handleMobileSessionClick(sessionId: string) {
  if (mobileSessionLongPressTriggered.value) {
    mobileSessionLongPressTriggered.value = false;
    return;
  }

  handleActivateSession(sessionId);
}

function handleMobileSessionActionSelect(key: string) {
  handleSessionMenuSelect(key);
  mobileSessionActionVisible.value = false;
}

function handleMobileLeadingAction() {
  if (currentRouteName.value === 'chat' && sidebarMode.value === 'chat-list') {
    navigateHome();
    return;
  }

  if (currentRouteName.value === 'chat-history' && !mobileAdminChatListVisible.value) {
    mobileAdminChatListVisible.value = true;
    return;
  }

  mobileDrawerVisible.value = true;
}

function handleMobileTopAction(key: string) {
  if (key === 'new-session') {
    handleCreateSession();
    return;
  }

  if (key === 'chat-list') {
    workspaceStore.openChatList();
  }
}

function handleMobileTabSelect(key: string) {
  closeMobileOverlays();

  if (key === 'admin') {
    mobileAdminPanelVisible.value = true;
    return;
  }

  navigatePrimary(key);
}

function handleMobileDrawerSelect(key: string) {
  closeMobileOverlays();

  if (primaryNavigation.value.some(item => item.key === key)) {
    navigatePrimary(key);
    return;
  }

  if (adminNavigation.value.some(item => item.key === key)) {
    navigateAdmin(key);
  }
}

function handleMobileProfileClick() {
  closeMobileOverlays();
  handleProfileAction('profile');
}

async function handleDeleteSession(sessionId: string) {
  await sessionStore.deleteSession(sessionId);
}

function openSessionMenu(event: MouseEvent, sessionId: string) {
  event.preventDefault();
  sessionMenuVisible.value = true;
  sessionMenuTarget.value = sessionId;
  sessionMenuX.value = event.clientX;
  sessionMenuY.value = event.clientY;
}

function hideSessionMenu() {
  sessionMenuVisible.value = false;
}

function startRenameSession(session: (typeof sessionList.value)[number]) {
  editingSessionId.value = session.sessionId;
  editingSessionTitle.value = session.title;
  sessionMenuVisible.value = false;
}

function cancelRenameSession() {
  editingSessionId.value = '';
  editingSessionTitle.value = '';
}

async function submitRenameSession(sessionId: string) {
  const title = editingSessionTitle.value.trim();
  if (!title || renamingSession.value) return;

  renamingSession.value = true;
  const result = await sessionStore.renameSession(sessionId, title);
  renamingSession.value = false;

  if (result) {
    cancelRenameSession();
    window.$message?.success('会话标题已更新');
    return;
  }

  window.$message?.error('会话重命名失败');
}

function togglePinnedSession(sessionId: string) {
  if (pinnedSessionIds.value.includes(sessionId)) {
    pinnedSessionIds.value = pinnedSessionIds.value.filter(id => id !== sessionId);
  } else {
    pinnedSessionIds.value = [sessionId, ...pinnedSessionIds.value];
  }
}

function handleSessionMenuSelect(key: string) {
  const target = filteredSessionList.value.find(item => item.sessionId === sessionMenuTarget.value);
  if (!target) {
    hideSessionMenu();
    return;
  }

  if (key === 'pin') {
    togglePinnedSession(target.sessionId);
    hideSessionMenu();
    return;
  }

  if (key === 'rename') {
    startRenameSession(target);
    return;
  }

  if (key === 'delete') {
    handleDeleteSession(target.sessionId);
  }

  hideSessionMenu();
}

watch(
  () => route.name,
  async value => {
    const normalized = value ? String(value) : null;
    workspaceStore.syncModuleByRouteName(normalized);
    closeMobileOverlays();

    if (normalized === 'chat-history') {
      mobileAdminChatListVisible.value = true;
    }

    if (normalized === 'chat-history') {
      await adminChatStore.ensureInitialized();
    }
  },
  { immediate: true }
);

watch(
  () => route.query.tab,
  value => {
    if (route.name !== 'personal-center') return;

    const normalized = String(value || 'general');
    if (normalized === 'account' || normalized === 'about' || normalized === 'general') {
      workspaceStore.setSettingsSection(normalized);
    }
  },
  { immediate: true }
);

watch(
  sessionList,
  value => {
    const validIds = new Set(value.map(item => item.sessionId));
    pinnedSessionIds.value = pinnedSessionIds.value.filter(id => validIds.has(id));
  },
  { deep: true }
);

onMounted(async () => {
  syncMobileViewport();
  window.addEventListener('resize', syncMobileViewport);
  await sessionStore.fetchSessions();

  if (currentRouteName.value === 'chat-history') {
    await adminChatStore.ensureInitialized();
  }
});

onBeforeUnmount(() => {
  clearMobileSessionLongPress();
  if (typeof window !== 'undefined') {
    window.removeEventListener('resize', syncMobileViewport);
  }
});
</script>

<template>
  <div class="unified-workbench-shell" :class="[shellClass, { 'unified-workbench-shell--mobile': isMobileLayout }]">
    <MobileTopBar
      v-if="isMobileLayout"
      :title="mobileTopBarTitle"
      :subtitle="mobileTopBarSubtitle"
      :leading-icon="mobileTopBarLeadingIcon"
      :actions="mobileTopBarActions"
      :avatar-text="(userInfo.username || 'U').slice(0, 1)"
      @leading-click="handleMobileLeadingAction"
      @action="handleMobileTopAction"
      @avatar-click="handleMobileProfileClick"
    />

    <MobileDrawer
      v-if="isMobileLayout"
      :show="mobileDrawerVisible"
      :username="userInfo.username"
      :role="userInfo.role"
      :active-key="activeNavigationKey"
      :primary-items="primaryNavigation"
      :admin-items="adminNavigation"
      :is-admin="isAdmin"
      @close="closeMobileOverlays"
      @select="handleMobileDrawerSelect"
      @profile="handleMobileProfileClick"
      @logout="handleProfileAction('logout')"
    />

    <MobileAdminPanel
      v-if="isMobileLayout && isAdmin"
      :show="mobileAdminPanelVisible"
      :items="adminNavigation"
      @close="closeMobileOverlays"
      @select="handleMobileDrawerSelect"
    />

    <aside v-if="!isMobileLayout" class="unified-workbench-shell__sidebar">
      <div class="unified-workbench-shell__sidebar-inner">
        <NDropdown trigger="click" :options="profileDropdownOptions" @select="handleProfileAction">
          <button type="button" class="unified-workbench-shell__profile-card">
            <div class="unified-workbench-shell__avatar">
              <span>{{ (userInfo.username || 'U').slice(0, 1).toUpperCase() }}</span>
            </div>
            <div class="unified-workbench-shell__profile-copy">
              <strong>{{ userInfo.username || '未登录用户' }}</strong>
              <span>{{ userInfo.role || 'USER' }}</span>
            </div>
            <SvgIcon icon="solar:alt-arrow-down-linear" class="text-16px opacity-70" />
          </button>
        </NDropdown>

        <template v-if="sidebarMode === 'home'">
          <section class="unified-workbench-shell__section">
            <div class="unified-workbench-shell__section-body">
              <button
                v-for="item in primaryNavigation"
                :key="item.key"
                type="button"
                class="unified-workbench-shell__nav-item"
                :class="{
                  'is-active':
                    (item.key === 'settings' && currentRouteName === 'personal-center') || currentRouteName === item.key
                }"
                @click="navigatePrimary(item.key)"
              >
                <SvgIcon :icon="item.icon" class="text-18px" />
                <span>{{ item.label }}</span>
              </button>
            </div>
          </section>

          <section v-if="isAdmin" class="unified-workbench-shell__section unified-workbench-shell__section--fill">
            <div class="unified-workbench-shell__section-title">管理（仅管理员）</div>
            <div class="unified-workbench-shell__section-body unified-workbench-shell__section-body--scroll">
              <button
                v-for="item in adminNavigation"
                :key="item.key"
                type="button"
                class="unified-workbench-shell__nav-item"
                :class="{ 'is-active': currentRouteName === item.key }"
                @click="navigateAdmin(item.key)"
              >
                <SvgIcon :icon="item.icon" class="text-18px" />
                <span>{{ item.label }}</span>
              </button>
            </div>
          </section>
        </template>

        <template v-else-if="sidebarMode === 'chat-list'">
          <button type="button" class="unified-workbench-shell__back-button" @click="navigateHome">◀ 返回</button>
          <button type="button" class="unified-workbench-shell__primary-button" @click="handleCreateSession">
            [＋ 新话题]
          </button>
          <div class="unified-workbench-shell__search-shell">
            <input
              v-model="sessionKeyword"
              type="text"
              class="unified-workbench-shell__search-input"
              placeholder="搜索会话..."
            />
          </div>
          <section class="unified-workbench-shell__section unified-workbench-shell__section--fill">
            <div class="unified-workbench-shell__section-title">话题 {{ sessionList.length }}</div>
            <NSpin :show="loadingSessions" class="min-h-0 flex-1">
              <div v-if="sessionGroups.length" class="unified-workbench-shell__session-groups">
                <section
                  v-for="group in sessionGroups"
                  :key="group.label"
                  class="unified-workbench-shell__session-group"
                >
                  <header class="unified-workbench-shell__session-group-title"># {{ group.label }}</header>
                  <div class="unified-workbench-shell__session-items">
                    <button
                      v-for="session in group.items"
                      :key="session.sessionId"
                      type="button"
                      class="unified-workbench-shell__session-item"
                      :class="{ 'is-active': session.sessionId === activeSessionId }"
                      @click="handleActivateSession(session.sessionId)"
                      @contextmenu="openSessionMenu($event, session.sessionId)"
                    >
                      <template v-if="editingSessionId === session.sessionId">
                        <input
                          v-model="editingSessionTitle"
                          type="text"
                          class="unified-workbench-shell__session-rename"
                          maxlength="80"
                          @click.stop
                          @keydown.enter.stop.prevent="submitRenameSession(session.sessionId)"
                          @keydown.esc.stop.prevent="cancelRenameSession"
                          @blur="submitRenameSession(session.sessionId)"
                        />
                      </template>
                      <template v-else>
                        <span class="unified-workbench-shell__session-item-title">
                          <span>{{ pinnedSessionIds.includes(session.sessionId) ? '📌' : '☆' }}</span>
                          <strong>{{ session.title || '未命名会话' }}</strong>
                        </span>
                        <span class="unified-workbench-shell__session-item-preview">
                          {{ session.latestPreview || '从任何想法开始...' }}
                        </span>
                      </template>
                    </button>
                  </div>
                </section>
              </div>
              <div v-else class="unified-workbench-shell__empty-tip">暂无会话，发送第一条消息后会自动生成新话题。</div>
            </NSpin>
          </section>
        </template>

        <template v-else-if="sidebarMode === 'admin-chat-list'">
          <button type="button" class="unified-workbench-shell__back-button" @click="navigateHome">◀ 返回</button>

          <NPopover v-model:show="adminUserSelectorVisible" trigger="click" placement="bottom-start">
            <template #trigger>
              <button
                type="button"
                class="unified-workbench-shell__profile-card unified-workbench-shell__profile-card--compact"
              >
                <div class="unified-workbench-shell__avatar unified-workbench-shell__avatar--mini">
                  <span>{{ (activeAdminChatUser?.username || 'U').slice(0, 1).toUpperCase() }}</span>
                </div>
                <div class="unified-workbench-shell__profile-copy">
                  <strong>{{ activeAdminChatUser?.username || '选择用户' }}</strong>
                  <span>{{ activeAdminChatUser ? `${adminChatSessions.length} 个话题` : '管理员只读视角' }}</span>
                </div>
                <span class="text-14px opacity-70">▾</span>
              </button>
            </template>

            <div class="unified-workbench-shell__user-selector">
              <input
                v-model="adminChatUserKeyword"
                type="text"
                class="unified-workbench-shell__search-input"
                placeholder="搜索用户..."
              />
              <NSpin :show="adminChatLoadingUsers" class="min-h-0">
                <div class="unified-workbench-shell__user-options">
                  <button
                    v-for="item in filteredAdminChatUsers"
                    :key="item.userId"
                    type="button"
                    class="unified-workbench-shell__user-option"
                    :class="{ 'is-active': item.userId === adminChatUserId }"
                    @click="handleAdminChatUserChange(item.userId)"
                  >
                    <span>{{ item.userId === adminChatUserId ? '✓' : '•' }}</span>
                    <strong>{{ item.username }}</strong>
                  </button>
                  <div v-if="!filteredAdminChatUsers.length" class="unified-workbench-shell__empty-tip">
                    没有匹配的用户
                  </div>
                </div>
              </NSpin>
            </div>
          </NPopover>

          <div class="unified-workbench-shell__search-shell">
            <input
              v-model="adminChatSessionKeyword"
              type="text"
              class="unified-workbench-shell__search-input"
              placeholder="搜索会话..."
            />
          </div>

          <section class="unified-workbench-shell__section unified-workbench-shell__section--fill">
            <div class="unified-workbench-shell__section-title">该用户 {{ adminChatSessions.length }} 个话题</div>
            <NSpin :show="adminChatLoadingSessions" class="min-h-0 flex-1">
              <div v-if="adminChatSessionGroups.length" class="unified-workbench-shell__session-groups">
                <section
                  v-for="group in adminChatSessionGroups"
                  :key="group.label"
                  class="unified-workbench-shell__session-group"
                >
                  <header class="unified-workbench-shell__session-group-title"># {{ group.label }}</header>
                  <div class="unified-workbench-shell__session-items">
                    <button
                      v-for="session in group.items"
                      :key="session.sessionId"
                      type="button"
                      class="unified-workbench-shell__session-item"
                      :class="{ 'is-active': session.sessionId === adminChatActiveSessionId }"
                      @click="handleAdminChatSessionChange(session.sessionId)"
                    >
                      <span class="unified-workbench-shell__session-item-title">
                        <span>{{ session.sessionId === adminChatActiveSessionId ? '▶' : '☆' }}</span>
                        <strong>{{ session.title || '未命名会话' }}</strong>
                      </span>
                      <span class="unified-workbench-shell__session-item-preview">
                        {{ session.latestPreview || '暂无消息摘要' }}
                      </span>
                    </button>
                  </div>
                </section>
              </div>
              <div v-else class="unified-workbench-shell__empty-tip">当前用户暂无历史会话。</div>
            </NSpin>
          </section>
        </template>

        <template v-else-if="sidebarMode === 'knowledge-base'">
          <button type="button" class="unified-workbench-shell__back-button" @click="navigateHome">◀ 返回</button>
          <div class="unified-workbench-shell__search">🔍 搜索文件</div>
          <section class="unified-workbench-shell__section unified-workbench-shell__section--fill">
            <div class="unified-workbench-shell__section-title">文件分类</div>
            <div class="unified-workbench-shell__section-body unified-workbench-shell__section-body--scroll">
              <button
                v-for="item in knowledgeCategories"
                :key="item.key"
                type="button"
                class="unified-workbench-shell__nav-item"
                :class="{ 'is-active': knowledgeCategory === item.key }"
                @click="changeKnowledgeCategory(item.key)"
              >
                <SvgIcon :icon="item.icon" class="text-18px" />
                <span>{{ item.label }}</span>
              </button>
            </div>
          </section>
        </template>

        <NDropdown
          :show="sessionMenuVisible"
          trigger="manual"
          placement="bottom-start"
          :x="sessionMenuX"
          :y="sessionMenuY"
          :options="sessionMenuOptions"
          @clickoutside="hideSessionMenu"
          @select="handleSessionMenuSelect"
        />
      </div>
    </aside>

    <section
      class="unified-workbench-shell__main"
      :class="{
        'unified-workbench-shell__main--stage': isStageRoute,
        'unified-workbench-shell__main--scrollable': !isStageRoute
      }"
    >
      <div
        :id="LAYOUT_SCROLL_EL_ID"
        class="unified-workbench-shell__scroll"
        :class="{
          'unified-workbench-shell__scroll--stage': isStageRoute,
          'unified-workbench-shell__scroll--scrollable': !isStageRoute
        }"
      >
        <template v-if="isMobileLayout && sidebarMode === 'chat-list'">
          <section class="unified-workbench-shell__mobile-panel">
            <button type="button" class="unified-workbench-shell__primary-button" @click="handleCreateSession">
              [＋ 新话题]
            </button>
            <div class="unified-workbench-shell__search-shell">
              <input
                v-model="sessionKeyword"
                type="text"
                class="unified-workbench-shell__search-input"
                placeholder="搜索会话..."
              />
            </div>
            <section class="unified-workbench-shell__section unified-workbench-shell__section--fill">
              <div class="unified-workbench-shell__section-title">话题 {{ sessionList.length }}</div>
              <NSpin :show="loadingSessions" class="min-h-0 flex-1">
                <div v-if="sessionGroups.length" class="unified-workbench-shell__session-groups">
                  <section
                    v-for="group in sessionGroups"
                    :key="group.label"
                    class="unified-workbench-shell__session-group"
                  >
                    <header class="unified-workbench-shell__session-group-title"># {{ group.label }}</header>
                    <div class="unified-workbench-shell__session-items">
                      <button
                        v-for="session in group.items"
                        :key="session.sessionId"
                        type="button"
                        class="unified-workbench-shell__session-item"
                        :class="{ 'is-active': session.sessionId === activeSessionId }"
                        @click="handleMobileSessionClick(session.sessionId)"
                        @touchstart.passive="startMobileSessionLongPress($event, session.sessionId)"
                        @touchend="clearMobileSessionLongPress"
                        @touchmove="clearMobileSessionLongPress"
                        @touchcancel="clearMobileSessionLongPress"
                      >
                        <template v-if="editingSessionId === session.sessionId">
                          <input
                            v-model="editingSessionTitle"
                            type="text"
                            class="unified-workbench-shell__session-rename"
                            maxlength="80"
                            @click.stop
                            @keydown.enter.stop.prevent="submitRenameSession(session.sessionId)"
                            @keydown.esc.stop.prevent="cancelRenameSession"
                            @blur="submitRenameSession(session.sessionId)"
                          />
                        </template>
                        <template v-else>
                          <span class="unified-workbench-shell__session-item-title">
                            <span>{{ pinnedSessionIds.includes(session.sessionId) ? '📌' : '☆' }}</span>
                            <strong>{{ session.title || '未命名会话' }}</strong>
                          </span>
                          <span class="unified-workbench-shell__session-item-preview">
                            {{ session.latestPreview || '从任何想法开始...' }}
                          </span>
                        </template>
                      </button>
                    </div>
                  </section>
                </div>
                <div v-else class="unified-workbench-shell__empty-tip">
                  暂无会话，发送第一条消息后会自动生成新话题。
                </div>
              </NSpin>
            </section>
          </section>
        </template>

        <template v-else-if="isMobileLayout && currentRouteName === 'chat-history' && mobileAdminChatListVisible">
          <section class="unified-workbench-shell__mobile-panel">
            <div class="unified-workbench-shell__profile-trigger">
              <NPopover v-model:show="adminUserSelectorVisible" trigger="click" placement="bottom-start">
                <template #trigger>
                  <button
                    type="button"
                    class="unified-workbench-shell__profile-card unified-workbench-shell__profile-card--compact"
                  >
                    <div class="unified-workbench-shell__avatar unified-workbench-shell__avatar--mini">
                      <span>{{ (activeAdminChatUser?.username || 'U').slice(0, 1).toUpperCase() }}</span>
                    </div>
                    <div class="unified-workbench-shell__profile-copy">
                      <strong>{{ activeAdminChatUser?.username || '选择用户' }}</strong>
                      <span>{{ activeAdminChatUser ? `${adminChatSessions.length} 个话题` : '管理员只读视角' }}</span>
                    </div>
                    <span class="text-14px opacity-70">▾</span>
                  </button>
                </template>

                <div class="unified-workbench-shell__user-selector">
                  <input
                    v-model="adminChatUserKeyword"
                    type="text"
                    class="unified-workbench-shell__search-input"
                    placeholder="搜索用户..."
                  />
                  <NSpin :show="adminChatLoadingUsers" class="min-h-0">
                    <div class="unified-workbench-shell__user-options">
                      <button
                        v-for="item in filteredAdminChatUsers"
                        :key="item.userId"
                        type="button"
                        class="unified-workbench-shell__user-option"
                        :class="{ 'is-active': item.userId === adminChatUserId }"
                        @click="handleAdminChatUserChange(item.userId)"
                      >
                        <span>{{ item.userId === adminChatUserId ? '✓' : '•' }}</span>
                        <strong>{{ item.username }}</strong>
                      </button>
                      <div v-if="!filteredAdminChatUsers.length" class="unified-workbench-shell__empty-tip">
                        没有匹配的用户
                      </div>
                    </div>
                  </NSpin>
                </div>
              </NPopover>
            </div>

            <div class="unified-workbench-shell__search-shell">
              <input
                v-model="adminChatSessionKeyword"
                type="text"
                class="unified-workbench-shell__search-input"
                placeholder="搜索会话..."
              />
            </div>

            <section class="unified-workbench-shell__section unified-workbench-shell__section--fill">
              <div class="unified-workbench-shell__section-title">该用户 {{ adminChatSessions.length }} 个话题</div>
              <NSpin :show="adminChatLoadingSessions" class="min-h-0 flex-1">
                <div v-if="adminChatSessionGroups.length" class="unified-workbench-shell__session-groups">
                  <section
                    v-for="group in adminChatSessionGroups"
                    :key="group.label"
                    class="unified-workbench-shell__session-group"
                  >
                    <header class="unified-workbench-shell__session-group-title"># {{ group.label }}</header>
                    <div class="unified-workbench-shell__session-items">
                      <button
                        v-for="session in group.items"
                        :key="session.sessionId"
                        type="button"
                        class="unified-workbench-shell__session-item"
                        :class="{ 'is-active': session.sessionId === adminChatActiveSessionId }"
                        @click="handleAdminChatSessionChange(session.sessionId)"
                      >
                        <span class="unified-workbench-shell__session-item-title">
                          <span>{{ session.sessionId === adminChatActiveSessionId ? '▶' : '☆' }}</span>
                          <strong>{{ session.title || '未命名会话' }}</strong>
                        </span>
                        <span class="unified-workbench-shell__session-item-preview">
                          {{ session.latestPreview || '暂无消息摘要' }}
                        </span>
                      </button>
                    </div>
                  </section>
                </div>
                <div v-else class="unified-workbench-shell__empty-tip">当前用户暂无历史会话。</div>
              </NSpin>
            </section>
          </section>
        </template>

        <slot v-else />
      </div>
    </section>

    <MobileTabBar
      v-if="isMobileLayout"
      :items="mobileTabItems"
      :active-key="mobileTabActiveKey"
      @select="handleMobileTabSelect"
    />

    <FloatingMenu
      v-if="isMobileLayout"
      :visible="mobileSessionActionVisible"
      :title="mobileSessionActionTitle"
      :items="mobileSessionActionOptions"
      :position="mobileSessionMenuPosition"
      @close="mobileSessionActionVisible = false"
      @select="handleMobileSessionActionSelect"
    />
  </div>
</template>

<style scoped lang="scss">
.unified-workbench-shell {
  display: grid;
  height: 100vh;
  min-height: 100vh;
  overflow: hidden;
  grid-template-columns: 280px minmax(0, 1fr);
  background: var(--app-layout-bg);
  color: var(--app-text-primary);

  &--light {
    background: var(--app-layout-bg);
    color: var(--app-text-primary);
  }

  &--mobile {
    display: block;
  }

  &__sidebar {
    min-width: 0;
    min-height: 0;
    overflow: hidden;
    border-right: 1px solid var(--app-border);
    background: var(--app-sidebar-bg);
    backdrop-filter: blur(22px);
    -webkit-backdrop-filter: blur(22px);
  }

  &--light &__sidebar {
    background: var(--app-sidebar-bg);
  }

  &__sidebar-inner {
    display: flex;
    height: 100%;
    min-height: 100vh;
    flex-direction: column;
    gap: 16px;
    padding: 20px 16px;
  }

  &__profile-card,
  &__nav-item,
  &__back-button {
    width: 100%;
    border: 1px solid var(--app-border);
    background: var(--app-control-bg);
    color: inherit;
    transition:
      border-color 0.2s ease,
      background 0.2s ease,
      transform 0.2s ease;
  }

  &--light &__profile-card,
  &--light &__nav-item,
  &--light &__back-button {
    background: var(--app-control-bg);
  }

  &__profile-card {
    display: flex;
    align-items: center;
    gap: 12px;
    border-radius: 22px;
    padding: 14px;
    text-align: left;

    &:hover {
      transform: translateY(-1px);
      border-color: rgba(96, 165, 250, 0.32);
    }

    &--compact {
      padding: 12px;
    }
  }

  &__avatar {
    display: flex;
    height: 44px;
    width: 44px;
    align-items: center;
    justify-content: center;
    border-radius: 16px;
    background: linear-gradient(135deg, #6d7cff, #2dd4bf);
    color: #fff;
    font-size: 18px;
    font-weight: 700;
    flex-shrink: 0;

    &--mini {
      height: 38px;
      width: 38px;
      border-radius: 14px;
      font-size: 15px;
    }
  }

  &__user-selector {
    display: flex;
    width: 240px;
    max-width: min(240px, calc(100vw - 56px));
    flex-direction: column;
    gap: 10px;
  }

  &__user-options {
    display: flex;
    width: 100%;
    max-height: 260px;
    flex-direction: column;
    gap: 8px;
    overflow: auto;
  }

  &__user-option {
    display: flex;
    width: 100%;
    align-items: center;
    gap: 10px;
    border: 1px solid var(--app-border);
    border-radius: 16px;
    background: var(--app-control-bg);
    padding: 10px 12px;
    color: inherit;
    text-align: left;

    strong {
      font-size: 14px;
      font-weight: 600;
    }

    &.is-active {
      border-color: rgba(96, 165, 250, 0.4);
      background: var(--app-accent-soft);
    }
  }

  &__profile-copy {
    display: flex;
    min-width: 0;
    flex: 1;
    flex-direction: column;
    gap: 4px;

    strong {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      font-size: 15px;
    }

    span {
      color: var(--app-text-secondary);
      font-size: 12px;
      letter-spacing: 0.08em;
      text-transform: uppercase;
    }
  }

  &--light &__profile-copy span {
    color: var(--app-text-secondary);
  }

  &__search {
    display: flex;
    align-items: center;
    border: 1px solid var(--app-border);
    border-radius: 18px;
    background: var(--app-control-bg);
    padding: 12px 14px;
    color: var(--app-text-secondary);
    font-size: 14px;
  }

  &--light &__search {
    background: var(--app-control-bg);
    color: var(--app-text-secondary);
  }

  &__search-shell {
    display: flex;
    width: 100%;
  }

  &__search-input {
    width: 100%;
    border: 1px solid var(--app-border);
    border-radius: 18px;
    background: var(--app-control-bg);
    padding: 12px 14px;
    color: inherit;
    outline: none;

    &::placeholder {
      color: var(--app-text-secondary);
    }
  }

  &--light &__search-input {
    background: var(--app-control-bg);
  }

  &__primary-button {
    width: 100%;
    border: 1px solid rgba(96, 165, 250, 0.24);
    border-radius: 18px;
    background: linear-gradient(135deg, rgba(59, 130, 246, 0.9), rgba(6, 182, 212, 0.78));
    padding: 12px 14px;
    color: #fff;
    font-size: 14px;
    font-weight: 700;
    transition: transform 0.2s ease;

    &:hover {
      transform: translateY(-1px);
    }
  }

  &__back-button,
  &__nav-item {
    display: flex;
    align-items: center;
    gap: 10px;
    border-radius: 18px;
    padding: 12px 14px;
    font-size: 14px;
    font-weight: 600;
    text-align: left;

    &:hover {
      transform: translateY(-1px);
      border-color: rgba(96, 165, 250, 0.32);
      background: var(--app-hover-bg);
    }

    &.is-active {
      border-color: rgba(96, 165, 250, 0.4);
      background: var(--app-accent-soft);
      color: var(--app-text-primary);
    }
  }

  &--light &__nav-item:hover,
  &--light &__back-button:hover {
    background: var(--app-hover-bg);
  }

  &--light &__nav-item.is-active {
    color: var(--app-text-primary);
  }

  &__section {
    display: flex;
    width: 100%;
    flex-direction: column;
    gap: 10px;

    &--fill {
      min-height: 0;
      flex: 1;
    }
  }

  &__section-title {
    padding: 0 4px;
    color: var(--app-text-secondary);
    font-size: 12px;
    font-weight: 700;
    letter-spacing: 0.08em;
  }

  &__section-body {
    display: flex;
    flex-direction: column;
    gap: 8px;

    &--scroll {
      min-height: 0;
      overflow: auto;
      padding-right: 4px;
    }
  }

  &__session-groups,
  &__session-items,
  &__session-group {
    display: flex;
    flex-direction: column;
  }

  &__session-groups {
    gap: 16px;
    min-height: 0;
    width: 100%;
    overflow: auto;
    padding-right: 4px;
  }

  &__session-group {
    width: 100%;
    gap: 10px;
  }

  &__session-group-title {
    color: var(--app-text-secondary);
    font-size: 12px;
    font-weight: 700;
    letter-spacing: 0.08em;
  }

  &__session-items {
    width: 100%;
    gap: 8px;
  }

  &__session-item {
    display: flex;
    width: 100%;
    flex-direction: column;
    gap: 8px;
    border: 1px solid var(--app-border);
    border-radius: 18px;
    background: var(--app-control-bg);
    padding: 12px;
    color: inherit;
    text-align: left;
    transition:
      border-color 0.2s ease,
      transform 0.2s ease,
      background 0.2s ease;

    &:hover {
      transform: translateY(-1px);
      border-color: rgba(96, 165, 250, 0.26);
    }

    &.is-active {
      border-color: rgba(96, 165, 250, 0.4);
      background: var(--app-accent-soft);
    }
  }

  &--light &__session-item {
    background: var(--app-control-bg);
  }

  &__session-item-title {
    display: flex;
    align-items: center;
    gap: 8px;

    strong {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      font-size: 14px;
      font-weight: 600;
    }
  }

  &__session-item-preview {
    display: -webkit-box;
    overflow: hidden;
    color: var(--app-text-secondary);
    font-size: 12px;
    line-height: 1.6;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
  }

  &__session-rename {
    width: 100%;
    border: 1px solid rgba(96, 165, 250, 0.26);
    border-radius: 14px;
    background: var(--app-elevated-bg);
    padding: 10px 12px;
    color: inherit;
    outline: none;
  }

  &__empty-tip {
    border: 1px dashed var(--app-border);
    border-radius: 20px;
    padding: 18px 16px;
    color: var(--app-text-secondary);
    font-size: 13px;
    line-height: 1.7;
  }

  &__main {
    display: flex;
    height: 100vh;
    min-width: 0;
    min-height: 0;
    flex-direction: column;
    padding: 20px 24px 24px;
    overflow: hidden;

    &--stage {
      overflow: hidden;
    }

    &--scrollable {
      overflow: hidden;
    }
  }

  &__mobile-panel {
    display: flex;
    height: 100%;
    min-height: 0;
    width: 100%;
    align-self: stretch;
    flex-direction: column;
    gap: 14px;
  }

  &__profile-trigger {
    display: flex;
    width: 100%;
  }

  &__scroll {
    display: flex;
    min-height: 0;
    flex: 1;
    overflow: hidden;

    &--stage {
      overflow: hidden;
    }

    &--scrollable {
      overflow-x: hidden;
      overflow-y: auto;
      -webkit-overflow-scrolling: touch;
      overscroll-behavior: contain;
    }
  }
}

@media (max-width: 1024px) {
  .unified-workbench-shell {
    grid-template-columns: 1fr;

    &__sidebar {
      border-right: 0;
      border-bottom: 1px solid rgba(148, 163, 184, 0.14);
    }

    &__sidebar-inner {
      min-height: auto;
    }
  }
}

@media (max-width: 768px) {
  .unified-workbench-shell {
    &__main {
      height: 100dvh;
      padding: calc(env(safe-area-inset-top) + var(--mobile-top-bar-height, 56px) + 12px)
        var(--mobile-content-padding, 16px)
        calc(env(safe-area-inset-bottom) + var(--mobile-tab-bar-height, 60px) + 12px);
    }

    &__mobile-panel {
      overflow-y: auto;
      -webkit-overflow-scrolling: touch;
      padding-bottom: 8px;
    }

    &__session-item {
      user-select: none;
      -webkit-user-select: none;
    }
  }
}
</style>
