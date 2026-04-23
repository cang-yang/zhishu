<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { useUiStore } from '@/store/modules/ui';
import { useWorkspaceStore } from '@/store/modules/workspace';
import { useChatConnectionStore } from '@/store/modules/chat/connection';
import { useChatSessionStore } from '@/store/modules/chat/session';
import InputBox from './input-box.vue';
import ChatList from './chat-list.vue';
import ReferencePreviewModal from './reference-preview-modal.vue';
import ChatWorkspaceWindowHost from './workspace-window-host.vue';

defineOptions({
  name: 'ChatWorkbenchShell'
});

const workspaceStore = useWorkspaceStore();
const sessionStore = useChatSessionStore();
const uiStore = useUiStore();
const { routerPushByKey } = useRouterPush();
const route = useRoute();
const connectionStore = useChatConnectionStore();
const { sessionList, activeSessionId, activeSession, loadingSessions } = storeToRefs(sessionStore);
const { connectionStatus } = storeToRefs(connectionStore);
const { modules, currentModuleItem } = storeToRefs(workspaceStore);
const { workspaceWindow } = storeToRefs(uiStore);
const sessionKeyword = ref('');
const sessionsVisible = ref(true);
const editingSessionId = ref('');
const editingSessionTitle = ref('');
const renamingSession = ref(false);
const sessionMenuVisible = ref(false);
const sessionMenuX = ref(0);
const sessionMenuY = ref(0);
const sessionMenuTarget = ref('');

const filteredSessionList = computed(() => {
  const keyword = sessionKeyword.value.trim().toLowerCase();
  if (!keyword) {
    return sessionList.value;
  }

  return sessionList.value.filter(session => {
    const title = String(session.title || '').toLowerCase();
    const preview = String(session.latestPreview || '').toLowerCase();
    return title.includes(keyword) || preview.includes(keyword);
  });
});

const connectionLabel = computed(() => {
  if (connectionStatus.value === 'OPEN') return '在线';
  if (connectionStatus.value === 'RECONNECTING') return '重连中';
  if (connectionStatus.value === 'CONNECTING') return '连接中';
  return '离线';
});

const sessionGroups = computed(() => {
  const keyword = sessionKeyword.value.trim().toLowerCase();
  const source = sessionList.value.filter(session => {
    if (!keyword) return true;
    const title = String(session.title || '').toLowerCase();
    const preview = String(session.latestPreview || '').toLowerCase();
    return title.includes(keyword) || preview.includes(keyword);
  });

  const groups = new Map<string, typeof source>();

  source.forEach(session => {
    const label = resolveSessionGroupLabel(session.updatedAt || session.lastMessageAt || session.createdAt || null);
    const bucket = groups.get(label) || [];
    bucket.push(session);
    groups.set(label, bucket);
  });

  return Array.from(groups.entries()).map(([label, items]) => ({ label, items }));
});

const activeModuleLabel = computed(() => currentModuleItem.value?.label || '聊天工作区');

const activeWindowModuleKey = computed(() => {
  if (workspaceWindow.value?.type === 'account') return 'overview';
  if (workspaceWindow.value?.type === 'knowledge-base') return 'knowledge-base';
  if (workspaceWindow.value?.type === 'settings') return 'settings';
  return null;
});

const primaryModules = computed(() => {
  const orderedKeys = ['overview', 'chat', 'knowledge-base', 'model-provider'];
  return orderedKeys
    .map(key => modules.value.find(item => item.key === key))
    .filter((item): item is (typeof modules.value)[number] => Boolean(item));
});

const supportModules = computed(() => {
  const orderedKeys = ['chat-history', 'settings'];
  return orderedKeys
    .map(key => modules.value.find(item => item.key === key))
    .filter((item): item is (typeof modules.value)[number] => Boolean(item));
});

const contextbarTitle = computed(() => {
  if (workspaceWindow.value?.type === 'knowledge-base') {
    return '知识库工作窗已展开';
  }

  if (workspaceWindow.value?.type === 'settings') {
    return '工作台设置已展开';
  }

  if (workspaceWindow.value?.type === 'account') {
    return '账户与工作台面板已展开';
  }

  return activeSession.value?.title || '创建一个新的思考线程';
});

const contextbarDescription = computed(() => {
  if (workspaceWindow.value?.type === 'knowledge-base') {
    return '文档检索、引用来源与资料管理通过统一工作窗在主舞台内展开。';
  }

  if (workspaceWindow.value?.type === 'settings') {
    return '主题、快捷键、输入体验与工作区行为将统一收拢到轻量设置工作窗。';
  }

  if (workspaceWindow.value?.type === 'account') {
    return '账户概览、偏好入口与数据操作面板将通过统一悬浮工作窗承载。';
  }

  return '消息主流保持常驻挂载，会话切换、引用预览与设置窗口在中央舞台内并存。';
});

const stageStatusPills = computed(() => {
  const pills = [connectionLabel.value];

  if (activeSession.value?.messageCount) {
    pills.push(`${activeSession.value.messageCount} 条消息`);
  }

  if (!sessionsVisible.value) {
    pills.push('会话侧栏已收起');
  }

  return pills;
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
  if (diffDays <= 7) return '最近 7 天';
  return '更早';
}

async function handleCreateSession() {
  await sessionStore.createSession();
}

async function handleActivateSession(sessionId: string) {
  await sessionStore.activateSession(sessionId);
}

async function handleDeleteSession(sessionId: string) {
  await sessionStore.deleteSession(sessionId);
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

const sessionMenuOptions = computed(() => {
  return [
    { key: 'rename', label: '重命名' },
    { key: 'delete', label: '删除会话' }
  ];
});

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

function handleSessionMenuSelect(key: string) {
  const targetSession = sessionList.value.find(item => item.sessionId === sessionMenuTarget.value);
  if (!targetSession) {
    hideSessionMenu();
    return;
  }

  if (key === 'rename') {
    startRenameSession(targetSession);
    return;
  }

  if (key === 'delete') {
    handleDeleteSession(targetSession.sessionId);
  }

  hideSessionMenu();
}

async function handleWorkbenchNavigate(item: (typeof modules.value)[number]) {
  if (item.disabled) {
    return;
  }

  if (item.key === 'chat') {
    workspaceStore.setCurrentModule(item.key);
    return;
  }

  if (item.key === 'overview') {
    uiStore.openWorkspaceWindow({
      type: 'account',
      title: '账户总览',
      badge: '账户',
      meta: '账户概览、组织信息与工作台偏好将在该浮层集中查看',
      mode: 'standard',
      width: 'min(980px, calc(100vw - 64px))',
      height: 'min(82vh, 760px)'
    });
    return;
  }

  if (item.key === 'knowledge-base') {
    uiStore.openWorkspaceWindow({
      type: 'knowledge-base',
      title: '知识库工作窗',
      badge: '知识库',
      meta: '文档检索、来源追踪与资料操作将在沉浸视图中展开',
      mode: 'immersive',
      width: 'min(1180px, calc(100vw - 52px))',
      height: 'min(86vh, 860px)'
    });
    return;
  }

  if (item.key === 'settings') {
    uiStore.openWorkspaceWindow({
      type: 'settings',
      title: '工作台设置',
      badge: '设置',
      meta: '主题、快捷键、输入体验与主舞台行为将在此统一配置',
      mode: 'standard',
      width: 'min(960px, calc(100vw - 56px))',
      height: 'min(82vh, 760px)'
    });
    return;
  }

  if (!item.routeKey) {
    return;
  }

  workspaceStore.setCurrentModule(item.key);
  await routerPushByKey(item.routeKey);
}

function isWorkbenchModuleActive(item: (typeof modules.value)[number]) {
  return item.key === workspaceStore.currentModule || item.key === activeWindowModuleKey.value;
}

function toggleSessionsVisible() {
  sessionsVisible.value = !sessionsVisible.value;
}

function openStageWindow(type: 'knowledge-base' | 'settings' | 'account') {
  if (type === 'knowledge-base') {
    uiStore.openWorkspaceWindow({
      type,
      title: '知识库工作窗',
      badge: '知识库',
      meta: '文档检索、引用来源与资料管理通过统一工作窗在主舞台内展开。',
      mode: 'immersive',
      width: 'min(1180px, calc(100vw - 52px))',
      height: 'min(86vh, 860px)'
    });
    return;
  }

  if (type === 'settings') {
    uiStore.openWorkspaceWindow({
      type,
      title: '工作台设置',
      badge: '设置',
      meta: '主题、快捷键、输入体验与工作区行为将统一收拢到轻量设置工作窗。',
      mode: 'standard',
      width: 'min(960px, calc(100vw - 56px))',
      height: 'min(82vh, 760px)'
    });
    return;
  }

  uiStore.openWorkspaceWindow({
    type,
    title: '账户总览',
    badge: '账户',
    meta: '账户概览、偏好入口与数据操作面板将通过统一悬浮工作窗承载。',
    mode: 'standard',
    width: 'min(980px, calc(100vw - 64px))',
    height: 'min(82vh, 760px)'
  });
}

watch(
  () => route.name,
  name => {
    workspaceStore.syncModuleByRouteName(typeof name === 'string' ? name : null);
  },
  { immediate: true }
);

onMounted(async () => {
  await sessionStore.fetchSessions();
  if (!sessionList.value.length) {
    await sessionStore.createSession();
  }
});
</script>

<template>
  <div class="chat-workbench" :class="{ 'chat-workbench--sessions-hidden': !sessionsVisible }">
    <aside class="chat-workbench__rail">
      <div class="chat-workbench__rail-brand">
        <span class="chat-workbench__rail-brand-icon">
          <SystemLogo class="text-24px" />
        </span>
        <span class="chat-workbench__rail-brand-copy">
          <strong>智枢</strong>
          <small>Workspace</small>
        </span>
      </div>

      <nav class="chat-workbench__rail-nav" aria-label="AI 工作台模块导航">
        <div class="chat-workbench__rail-group">
          <button
            v-for="item in primaryModules"
            :key="item.key"
            type="button"
            class="chat-workbench__rail-item"
            :class="{
              'chat-workbench__rail-item--active': isWorkbenchModuleActive(item),
              'chat-workbench__rail-item--disabled': item.disabled
            }"
            :title="item.description || item.label"
            :disabled="item.disabled"
            @click="handleWorkbenchNavigate(item)"
          >
            <SvgIcon :icon="item.icon" class="text-20px" />
            <span>{{ item.label }}</span>
          </button>
        </div>

        <div class="chat-workbench__rail-group chat-workbench__rail-group--support">
          <button
            v-for="item in supportModules"
            :key="item.key"
            type="button"
            class="chat-workbench__rail-item"
            :class="{
              'chat-workbench__rail-item--active': isWorkbenchModuleActive(item),
              'chat-workbench__rail-item--disabled': item.disabled
            }"
            :title="item.description || item.label"
            :disabled="item.disabled"
            @click="handleWorkbenchNavigate(item)"
          >
            <SvgIcon :icon="item.icon" class="text-20px" />
            <span>{{ item.label }}</span>
          </button>
        </div>
      </nav>

      <div class="chat-workbench__rail-footer">
        <button type="button" class="chat-workbench__rail-toggle" @click="toggleSessionsVisible">
          <SvgIcon :icon="sessionsVisible ? 'ph:sidebar-simple-fill' : 'ph:sidebar-simple'" class="text-16px" />
          <span>{{ sessionsVisible ? '收起会话' : '展开会话' }}</span>
        </button>
        <span class="chat-workbench__rail-tip">主舞台</span>
        <strong>{{ activeModuleLabel }}</strong>
      </div>
    </aside>

    <aside class="chat-workbench__sessions">
      <section class="chat-workbench__panel chat-workbench__panel--session-browser">
        <div class="chat-workbench__brand-row">
          <div class="chat-workbench__brand-icon">
            <SystemLogo class="text-26px" />
          </div>
          <div class="min-w-0">
            <div class="chat-workbench__brand-name">聊天工作台</div>
            <div class="chat-workbench__brand-desc">会话、上下文与引用工作窗统一收拢到中央舞台</div>
          </div>
        </div>
        <button type="button" class="chat-workbench__new-session" @click="handleCreateSession">
          <span class="chat-workbench__new-session-plus">+</span>
          <span>开始新会话</span>
        </button>

        <header class="chat-workbench__sidebar-header">
          <span class="chat-workbench__sidebar-title">会话列表</span>
          <span class="chat-workbench__sidebar-badge">{{ sessionList.length }}</span>
        </header>

        <div class="chat-workbench__session-search">
          <input
            v-model="sessionKeyword"
            type="text"
            class="chat-workbench__session-search-input"
            placeholder="搜索标题、摘要或最近上下文"
          />
        </div>

        <NSpin :show="loadingSessions">
          <div v-if="sessionGroups.length" class="chat-workbench__session-groups">
            <section v-for="group in sessionGroups" :key="group.label" class="chat-workbench__session-group">
              <header class="chat-workbench__session-group-header">{{ group.label }}</header>
              <div class="chat-workbench__session-list">
                <div
                  v-for="session in group.items"
                  :key="session.sessionId"
                  class="chat-workbench__session-item"
                  :class="{ 'chat-workbench__session-item--active': session.sessionId === activeSessionId }"
                  tabindex="0"
                  @contextmenu="openSessionMenu($event, session.sessionId)"
                  @click="handleActivateSession(session.sessionId)"
                  @keydown.enter.prevent="handleActivateSession(session.sessionId)"
                >
                  <span class="chat-workbench__session-title-row">
                    <template v-if="editingSessionId === session.sessionId">
                      <input
                        v-model="editingSessionTitle"
                        type="text"
                        class="chat-workbench__session-rename-input"
                        maxlength="80"
                        @click.stop
                        @keydown.enter.stop.prevent="submitRenameSession(session.sessionId)"
                        @keydown.esc.stop.prevent="cancelRenameSession"
                        @blur="submitRenameSession(session.sessionId)"
                      />
                    </template>
                    <template v-else>
                      <span class="chat-workbench__session-title">{{ session.title }}</span>
                      <div class="chat-workbench__session-actions">
                        <button
                          type="button"
                          class="chat-workbench__session-action"
                          title="重命名会话"
                          @click.stop="startRenameSession(session)"
                        >
                          <SvgIcon icon="solar:pen-linear" class="text-14px" />
                        </button>
                        <button
                          type="button"
                          class="chat-workbench__session-action chat-workbench__session-action--danger"
                          title="删除会话"
                          @click.stop="handleDeleteSession(session.sessionId)"
                        >
                          <SvgIcon icon="solar:trash-bin-trash-linear" class="text-14px" />
                        </button>
                      </div>
                    </template>
                  </span>
                  <span class="chat-workbench__session-preview">{{ session.latestPreview || '暂时没有消息内容' }}</span>
                </div>
              </div>
            </section>
          </div>
          <div v-else class="chat-workbench__sidebar-empty">
            {{
              filteredSessionList.length ? '没有匹配的会话结果。' : '还没有聊天会话，点击上方按钮即可开启第一段对话。'
            }}
          </div>
        </NSpin>
      </section>
    </aside>

    <NDropdown
      :show="sessionMenuVisible"
      placement="bottom-start"
      trigger="manual"
      :x="sessionMenuX"
      :y="sessionMenuY"
      :options="sessionMenuOptions"
      @clickoutside="hideSessionMenu"
      @select="handleSessionMenuSelect"
    />

    <section class="chat-workbench__stage">
      <header class="chat-workbench__contextbar">
        <div class="chat-workbench__contextbar-main">
          <button type="button" class="chat-workbench__contextbar-toggle" @click="toggleSessionsVisible">
            <SvgIcon :icon="sessionsVisible ? 'ph:panel-left-close' : 'ph:panel-left-open'" class="text-18px" />
          </button>
          <div>
            <p class="chat-workbench__contextbar-eyebrow">{{ activeModuleLabel }}</p>
            <h1 class="chat-workbench__contextbar-title">{{ contextbarTitle }}</h1>
            <p class="chat-workbench__contextbar-desc">{{ contextbarDescription }}</p>
          </div>
        </div>
        <div class="chat-workbench__contextbar-actions">
          <div class="chat-workbench__contextbar-meta">
            <span v-for="pill in stageStatusPills" :key="pill" class="chat-workbench__meta-pill">{{ pill }}</span>
            <span class="chat-workbench__meta-pill chat-workbench__meta-pill--soft">
              引用预览与设置工作窗将在主舞台内打开
            </span>
          </div>
          <div class="chat-workbench__contextbar-tools">
            <button type="button" class="chat-workbench__context-action" @click="openStageWindow('knowledge-base')">
              <SvgIcon icon="solar:folder-with-files-linear" class="text-16px" />
              <span>知识库</span>
            </button>
            <button type="button" class="chat-workbench__context-action" @click="openStageWindow('settings')">
              <SvgIcon icon="solar:settings-linear" class="text-16px" />
              <span>设置</span>
            </button>
            <button type="button" class="chat-workbench__context-action" @click="openStageWindow('account')">
              <SvgIcon icon="solar:user-id-linear" class="text-16px" />
              <span>账户</span>
            </button>
          </div>
        </div>
      </header>

      <section class="chat-workbench__conversation">
        <div class="chat-workbench__conversation-body">
          <ChatList />
        </div>
        <footer class="chat-workbench__composer">
          <InputBox />
        </footer>
      </section>
    </section>
  </div>
  <ReferencePreviewModal />
  <ChatWorkspaceWindowHost />
</template>

<style scoped lang="scss">
.chat-workbench {
  display: grid;
  position: relative;
  overflow: hidden;
  min-height: calc(100vh - 84px);
  grid-template-columns: 88px 320px minmax(0, 1fr);
  gap: 18px;
  padding: 18px;
  background:
    radial-gradient(circle at top left, rgba(79, 124, 255, 0.12), transparent 28%),
    radial-gradient(circle at bottom right, rgba(14, 165, 233, 0.08), transparent 22%), #020617;

  &::before {
    content: '';
    position: absolute;
    inset: 0 0 auto auto;
    width: 320px;
    height: 240px;
    background: radial-gradient(circle, rgba(79, 124, 255, 0.12), transparent 72%);
    pointer-events: none;
  }

  &__rail,
  &__sessions,
  &__stage {
    position: relative;
    z-index: 1;
    min-height: 0;
  }

  &__rail {
    display: flex;
    flex-direction: column;
    gap: 14px;
    padding: 16px 12px;
    border: 1px solid rgba(71, 85, 105, 0.3);
    border-radius: 28px;
    background: rgba(2, 6, 23, 0.84);
    box-shadow: 0 24px 48px rgba(2, 6, 23, 0.26);
    backdrop-filter: blur(18px);
  }

  &__rail-brand {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 10px;
    padding-bottom: 6px;
  }

  &__rail-brand-icon {
    display: inline-flex;
    height: 48px;
    width: 48px;
    align-items: center;
    justify-content: center;
    border-radius: 18px;
    background: linear-gradient(135deg, rgba(79, 124, 255, 0.24), rgba(14, 165, 233, 0.14));
    color: rgb(var(--primary-color));
  }

  &__rail-brand-copy {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 2px;
    color: rgb(226 232 240);
    font-size: 11px;
    line-height: 1.3;

    small {
      color: rgb(100 116 139);
    }
  }

  &__rail-nav {
    display: flex;
    flex: 1;
    flex-direction: column;
    gap: 8px;
  }

  &__rail-group {
    display: flex;
    flex-direction: column;
    gap: 8px;

    &--support {
      margin-top: auto;
      padding-top: 12px;
      border-top: 1px solid rgba(71, 85, 105, 0.22);
    }
  }

  &__rail-item {
    display: inline-flex;
    flex-direction: column;
    align-items: center;
    gap: 8px;
    border: 1px solid transparent;
    border-radius: 18px;
    background: transparent;
    padding: 12px 6px;
    color: rgb(148 163 184);
    font-size: 11px;
    transition:
      border-color 0.2s ease,
      background 0.2s ease,
      color 0.2s ease,
      transform 0.2s ease;

    &:hover:not(:disabled) {
      border-color: rgba(79, 124, 255, 0.18);
      background: rgba(15, 23, 42, 0.84);
      color: rgb(226 232 240);
      transform: translateY(-1px);
    }

    &--active {
      border-color: rgba(79, 124, 255, 0.26);
      background: linear-gradient(180deg, rgba(79, 124, 255, 0.18), rgba(79, 124, 255, 0.1));
      color: rgb(241 245 249);
      box-shadow: inset 0 0 0 1px rgba(79, 124, 255, 0.1);
    }

    &--disabled {
      cursor: not-allowed;
      opacity: 0.46;
    }
  }

  &__rail-footer {
    display: flex;
    flex-direction: column;
    gap: 8px;
    padding-top: 12px;
    border-top: 1px solid rgba(71, 85, 105, 0.3);
    color: rgb(226 232 240);
    font-size: 12px;
    text-align: center;
  }

  &__rail-toggle {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: 8px;
    border: 1px solid rgba(71, 85, 105, 0.28);
    border-radius: 16px;
    background: rgba(15, 23, 42, 0.82);
    padding: 10px 8px;
    color: rgb(203 213 225);
    font-size: 11px;
    transition:
      border-color 0.2s ease,
      background 0.2s ease,
      transform 0.2s ease;

    &:hover {
      border-color: rgba(79, 124, 255, 0.2);
      background: rgba(30, 41, 59, 0.88);
      transform: translateY(-1px);
    }
  }

  &__rail-tip {
    color: rgb(100 116 139);
    font-size: 11px;
  }

  &__sessions {
    min-width: 0;
    transition:
      opacity 0.22s ease,
      transform 0.22s ease;
  }

  &__panel {
    display: flex;
    height: 100%;
    flex-direction: column;
    border: 1px solid rgba(71, 85, 105, 0.3);
    border-radius: 30px;
    background: rgba(2, 6, 23, 0.84);
    padding: 22px 18px;
    box-shadow: 0 26px 60px rgba(2, 6, 23, 0.3);
    backdrop-filter: blur(22px);
  }

  &__brand-row {
    display: flex;
    align-items: center;
    gap: 14px;
    margin-bottom: 18px;
  }

  &__brand-icon {
    display: flex;
    height: 44px;
    width: 44px;
    align-items: center;
    justify-content: center;
    border-radius: 14px;
    background: rgba(79, 124, 255, 0.12);
    color: rgb(var(--primary-color));
  }

  &__brand-name {
    font-size: 18px;
    font-weight: 700;
    color: rgb(241 245 249);
  }

  &__brand-desc {
    font-size: 12px;
    line-height: 1.6;
    color: rgb(148 163 184);
  }

  &__new-session {
    display: flex;
    width: 100%;
    align-items: center;
    gap: 10px;
    border: 0;
    border-radius: 18px;
    background: linear-gradient(135deg, rgb(var(--primary-color)), #5b84f8);
    padding: 14px 16px;
    color: white;
    font-size: 14px;
    font-weight: 600;
    box-shadow: 0 18px 36px rgba(59, 110, 246, 0.28);
  }

  &__new-session-plus {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 20px;
    height: 20px;
    border-radius: 999px;
    background: rgba(255, 255, 255, 0.18);
  }

  &__sidebar-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
    margin-top: 18px;
  }

  &__sidebar-title {
    font-size: 13px;
    font-weight: 600;
    color: rgb(148 163 184);
  }

  &__sidebar-badge {
    display: inline-flex;
    min-width: 24px;
    height: 24px;
    align-items: center;
    justify-content: center;
    border-radius: 999px;
    background: rgba(79, 124, 255, 0.16);
    color: rgb(191 219 254);
    font-size: 12px;
    font-weight: 700;
  }

  &__session-groups {
    display: flex;
    min-height: 0;
    flex: 1;
    flex-direction: column;
    gap: 16px;
    margin-top: 16px;
    overflow: auto;
    padding-right: 4px;
  }

  &__session-group {
    display: flex;
    flex-direction: column;
    gap: 10px;
  }

  &__session-group-header {
    color: rgb(100 116 139);
    font-size: 12px;
    font-weight: 700;
    letter-spacing: 0.06em;
    text-transform: uppercase;
  }

  &__session-list {
    display: flex;
    flex-direction: column;
    gap: 8px;
  }

  &__session-search {
    margin-top: 14px;
  }

  &__session-search-input {
    width: 100%;
    border: 1px solid rgba(71, 85, 105, 0.32);
    border-radius: 16px;
    background: rgba(15, 23, 42, 0.88);
    padding: 10px 12px;
    color: rgb(226 232 240);
    font-size: 13px;
    outline: none;

    &::placeholder {
      color: rgb(100 116 139);
    }
  }

  &__session-item {
    display: flex;
    flex-direction: column;
    gap: 4px;
    width: 100%;
    border: 1px solid transparent;
    border-radius: 18px;
    background: rgba(15, 23, 42, 0.72);
    padding: 13px 14px;
    text-align: left;
    transition:
      background 0.2s ease,
      border-color 0.2s ease,
      transform 0.2s ease;

    &:hover {
      border-color: rgba(79, 124, 255, 0.18);
      transform: translateY(-1px);
    }

    &--active {
      border-color: rgba(79, 124, 255, 0.24);
      background: linear-gradient(180deg, rgba(79, 124, 255, 0.18), rgba(30, 41, 59, 0.88));
      box-shadow: inset 0 0 0 1px rgba(79, 124, 255, 0.08);
    }
  }

  &__session-title-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
  }

  &__session-actions {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    opacity: 0;
    transition: opacity 0.2s ease;
  }

  &__session-title {
    display: -webkit-box;
    overflow: hidden;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 1;
    color: rgb(241 245 249);
    font-size: 14px;
    font-weight: 600;
  }

  &__session-item:hover &__session-actions,
  &__session-item:focus-within &__session-actions {
    opacity: 1;
  }

  &__session-action {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 28px;
    height: 28px;
    border: 1px solid rgba(71, 85, 105, 0.22);
    border-radius: 10px;
    background: rgba(15, 23, 42, 0.84);
    color: rgb(148 163 184);
    transition:
      border-color 0.2s ease,
      color 0.2s ease,
      background 0.2s ease;

    &:hover {
      border-color: rgba(79, 124, 255, 0.22);
      color: rgb(226 232 240);
      background: rgba(30, 41, 59, 0.92);
    }

    &--danger:hover {
      border-color: rgba(239, 68, 68, 0.24);
      color: #fca5a5;
    }
  }

  &__session-rename-input {
    width: 100%;
    border: 1px solid rgba(79, 124, 255, 0.24);
    border-radius: 12px;
    background: rgba(15, 23, 42, 0.92);
    padding: 8px 10px;
    color: rgb(241 245 249);
    font-size: 13px;
    outline: none;
  }

  &__session-delete {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 20px;
    height: 20px;
    border-radius: 999px;
    color: rgb(148 163 184);
    font-size: 16px;
    line-height: 1;
    transition: all 0.2s ease;

    &:hover {
      background: rgba(239, 68, 68, 0.12);
      color: #dc2626;
    }
  }

  &__session-preview,
  &__sidebar-empty {
    color: rgb(148 163 184);
    font-size: 12px;
    line-height: 1.6;
  }

  &__session-preview {
    display: -webkit-box;
    overflow: hidden;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
  }

  &__sidebar-empty {
    margin-top: 14px;
  }

  &__stage {
    display: flex;
    min-height: 0;
    flex-direction: column;
    gap: 12px;
  }

  &__contextbar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    border: 1px solid rgba(71, 85, 105, 0.32);
    border-radius: 28px;
    background: rgba(2, 6, 23, 0.82);
    padding: 16px 22px;
    box-shadow: 0 20px 48px rgba(2, 6, 23, 0.26);
    backdrop-filter: blur(18px);
  }

  &__contextbar-main {
    display: flex;
    min-width: 0;
    align-items: flex-start;
    gap: 14px;
  }

  &__contextbar-toggle {
    display: inline-flex;
    width: 40px;
    height: 40px;
    align-items: center;
    justify-content: center;
    border: 1px solid rgba(71, 85, 105, 0.28);
    border-radius: 14px;
    background: rgba(15, 23, 42, 0.8);
    color: rgb(203 213 225);
    transition:
      border-color 0.2s ease,
      background 0.2s ease,
      transform 0.2s ease;

    &:hover {
      border-color: rgba(79, 124, 255, 0.22);
      background: rgba(30, 41, 59, 0.88);
      transform: translateY(-1px);
    }
  }

  &__contextbar-eyebrow {
    margin: 0 0 4px;
    color: rgb(var(--primary-color));
    font-size: 12px;
    font-weight: 700;
    letter-spacing: 0.08em;
    text-transform: uppercase;
  }

  &__contextbar-title {
    margin: 0;
    color: rgb(241 245 249);
    font-size: 22px;
    font-weight: 700;
  }

  &__contextbar-desc {
    margin: 6px 0 0;
    color: rgb(148 163 184);
    font-size: 13px;
    line-height: 1.7;
  }

  &__contextbar-actions {
    display: flex;
    flex-shrink: 0;
    flex-direction: column;
    align-items: flex-end;
    gap: 12px;
  }

  &__contextbar-meta {
    display: flex;
    flex-wrap: wrap;
    justify-content: flex-end;
    gap: 10px;
  }

  &__contextbar-tools {
    display: flex;
    flex-wrap: wrap;
    justify-content: flex-end;
    gap: 10px;
  }

  &__context-action {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    border: 1px solid rgba(71, 85, 105, 0.28);
    border-radius: 999px;
    background: rgba(15, 23, 42, 0.82);
    padding: 8px 12px;
    color: rgb(203 213 225);
    font-size: 12px;
    font-weight: 600;
    transition:
      border-color 0.2s ease,
      background 0.2s ease,
      transform 0.2s ease;

    &:hover {
      border-color: rgba(79, 124, 255, 0.22);
      background: rgba(30, 41, 59, 0.88);
      transform: translateY(-1px);
    }
  }

  &__meta-pill {
    display: inline-flex;
    align-items: center;
    border-radius: 999px;
    background: rgba(79, 124, 255, 0.12);
    padding: 8px 12px;
    color: rgb(191 219 254);
    font-size: 12px;
    font-weight: 600;

    &--soft {
      background: rgba(30, 41, 59, 0.84);
      color: rgb(148 163 184);
    }
  }

  &__conversation {
    display: flex;
    position: relative;
    min-height: 0;
    flex: 1;
    flex-direction: column;
    border: 1px solid rgba(71, 85, 105, 0.32);
    border-radius: 32px;
    background: linear-gradient(180deg, rgba(2, 6, 23, 0.9), rgba(15, 23, 42, 0.86));
    box-shadow: 0 28px 70px rgba(2, 6, 23, 0.3);
    overflow: hidden;

    &::before {
      content: '';
      position: absolute;
      inset: 0 0 auto 0;
      height: 120px;
      background: linear-gradient(180deg, rgba(79, 124, 255, 0.1), transparent);
      pointer-events: none;
    }
  }

  &__conversation-body {
    display: flex;
    position: relative;
    min-height: 0;
    flex: 1;
    padding: 10px 10px 0;
  }

  &__composer {
    padding: 0 20px 20px;
  }

  &--sessions-hidden {
    grid-template-columns: 88px 0 minmax(0, 1fr);

    .chat-workbench__sessions {
      opacity: 0;
      pointer-events: none;
      transform: translateX(-18px);
    }
  }
}

@media (max-width: 1024px) {
  .chat-workbench {
    grid-template-columns: 80px 280px minmax(0, 1fr);
    min-height: calc(100vh - 76px);

    &__contextbar {
      flex-direction: column;
      align-items: stretch;
    }

    &__contextbar-actions {
      align-items: stretch;
    }

    &__contextbar-meta,
    &__contextbar-tools {
      justify-content: flex-start;
    }
  }
}

@media (max-width: 768px) {
  .chat-workbench {
    grid-template-columns: 1fr;
    gap: 14px;
    padding: 12px;

    &__rail {
      flex-direction: row;
      align-items: center;
      overflow-x: auto;
      padding: 12px;
    }

    &__rail-brand,
    &__rail-footer {
      display: none;
    }

    &__rail-nav {
      flex-direction: row;
      width: max-content;
    }

    &__rail-group {
      flex-direction: row;
      padding-top: 0;
      border-top: 0;

      &--support {
        margin-top: 0;
        margin-left: 8px;
        padding-left: 8px;
        border-left: 1px solid rgba(71, 85, 105, 0.2);
      }
    }

    &__rail-item {
      min-width: 70px;
    }

    &__rail-toggle {
      display: none;
    }

    &__panel {
      padding: 18px 14px;
    }

    &__contextbar {
      padding: 14px 16px;
    }

    &__contextbar-main {
      width: 100%;
    }

    &__contextbar-meta {
      justify-content: flex-start;
    }

    &__contextbar-tools {
      justify-content: flex-start;
    }

    &__conversation-body {
      padding: 6px 4px 0;
    }

    &__composer {
      padding: 0 12px 12px;
    }
  }
}
</style>
