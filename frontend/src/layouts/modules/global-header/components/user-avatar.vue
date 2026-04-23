<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { useAppStore } from '@/store/modules/app';
import { useThemeStore } from '@/store/modules/theme';
import { useUiStore } from '@/store/modules/ui';
import { useRouterPush } from '@/hooks/common/router';
import { $t } from '@/locales';

defineOptions({
  name: 'UserAvatar'
});

const authStore = useAuthStore();
const appStore = useAppStore();
const themeStore = useThemeStore();
const uiStore = useUiStore();
const { routerPushByKey, toLogin } = useRouterPush();
const route = useRoute();

function loginOrRegister() {
  toLogin();
}

const accountTier = computed(() => (authStore.userInfo.role === 'ADMIN' ? 'Admin Plan' : 'Workspace User'));
const localeLabel = computed(() => (appStore.locale === 'zh-CN' ? '中文' : 'English'));
const themeLabel = computed(() => (themeStore.darkMode ? '暗色模式' : '亮色模式'));
const primaryOrgLabel = computed(() => authStore.userInfo.primaryOrg || '未绑定组织');
const isWorkbenchRoute = computed(() => route.name === 'chat');

const panelStats = computed(() => {
  return [
    { label: '身份', value: authStore.userInfo.role || 'USER' },
    { label: '语言', value: localeLabel.value },
    { label: '主题', value: themeLabel.value },
    { label: '组织', value: primaryOrgLabel.value }
  ];
});

function logout() {
  window.$dialog?.info({
    title: $t('common.tip'),
    content: $t('common.logoutConfirm'),
    positiveText: $t('common.confirm'),
    negativeText: $t('common.cancel'),
    onPositiveClick: async () => {
      uiStore.closeUserPanel();
      await authStore.logout();
    }
  });
}

function closePanel() {
  uiStore.closeUserPanel();
}

async function navigateTo(key: 'personal-center' | 'knowledge-base' | 'chat-history' | 'usage-monitor' | 'recharge') {
  closePanel();
  await routerPushByKey(key);
}

function openWorkbenchWindow(type: 'account' | 'knowledge-base' | 'settings') {
  closePanel();

  const titleMap = {
    account: '账户总览',
    'knowledge-base': '知识库工作窗',
    settings: '工作台设置'
  } as const;

  uiStore.openWorkspaceWindow({
    type,
    title: titleMap[type],
    badge: type === 'account' ? '账户' : type === 'knowledge-base' ? '知识库' : '设置',
    meta:
      type === 'account'
        ? '用户资料、组织信息与账户偏好将在此统一查看。'
        : type === 'knowledge-base'
          ? '知识库检索、资料管理与引用来源将在统一工作窗中展开。'
          : '主题、语言、快捷键与主舞台行为将在此集中配置。',
    mode: type === 'knowledge-base' ? 'immersive' : 'standard',
    width: type === 'knowledge-base' ? 'min(1180px, calc(100vw - 52px))' : 'min(980px, calc(100vw - 64px))',
    height: type === 'knowledge-base' ? 'min(86vh, 860px)' : 'min(82vh, 760px)'
  });
}

function toggleLocale() {
  appStore.changeLocale(appStore.locale === 'zh-CN' ? 'en-US' : 'zh-CN');
}

function toggleTheme() {
  themeStore.toggleThemeScheme();
}
</script>

<template>
  <NButton v-if="!authStore.isLogin" quaternary @click="loginOrRegister">
    {{ $t('page.login.common.loginOrRegister') }}
  </NButton>
  <NPopover
    v-else
    :show="uiStore.userPanelVisible"
    trigger="click"
    placement="bottom-end"
    :show-arrow="false"
    @update:show="uiStore.toggleUserPanel"
  >
    <template #trigger>
      <button type="button" class="user-avatar-trigger">
        <span class="user-avatar-trigger__avatar">
          <SvgIcon icon="ph:user-circle" class="text-20px" />
        </span>
        <span class="user-avatar-trigger__meta">
          <span class="user-avatar-trigger__name">{{ authStore.userInfo.username }}</span>
          <span class="user-avatar-trigger__role">{{ accountTier }}</span>
        </span>
        <SvgIcon icon="solar:alt-arrow-down-linear" class="user-avatar-trigger__arrow text-16px" />
      </button>
    </template>

    <section class="user-panel">
      <header class="user-panel__hero">
        <div class="user-panel__identity">
          <span class="user-panel__avatar">
            <SvgIcon icon="ph:user-circle" class="text-26px" />
          </span>
          <div class="user-panel__identity-copy">
            <h4 class="user-panel__name">{{ authStore.userInfo.username }}</h4>
            <p class="user-panel__subline">账号 ID {{ authStore.userInfo.id }} · {{ primaryOrgLabel }}</p>
          </div>
        </div>
        <span class="user-panel__tier">{{ accountTier }}</span>
      </header>

      <div class="user-panel__stats">
        <div v-for="item in panelStats" :key="item.label" class="user-panel__stat-card">
          <span class="user-panel__stat-label">{{ item.label }}</span>
          <strong class="user-panel__stat-value">{{ item.value }}</strong>
        </div>
      </div>

      <div class="user-panel__group">
        <button
          type="button"
          class="user-panel__action"
          @click="isWorkbenchRoute ? openWorkbenchWindow('account') : navigateTo('personal-center')"
        >
          <SvgIcon icon="solar:user-id-linear" class="text-18px" />
          <span>{{ isWorkbenchRoute ? '账户总览' : '账户中心' }}</span>
        </button>
        <button
          type="button"
          class="user-panel__action"
          @click="isWorkbenchRoute ? openWorkbenchWindow('knowledge-base') : navigateTo('knowledge-base')"
        >
          <SvgIcon icon="solar:folder-open-linear" class="text-18px" />
          <span>{{ isWorkbenchRoute ? '知识库工作窗' : '知识库管理' }}</span>
        </button>
        <button
          v-if="isWorkbenchRoute"
          type="button"
          class="user-panel__action"
          @click="openWorkbenchWindow('settings')"
        >
          <SvgIcon icon="solar:widget-add-linear" class="text-18px" />
          <span>工作台设置</span>
        </button>
        <button type="button" class="user-panel__action" @click="toggleTheme">
          <SvgIcon icon="solar:moon-stars-linear" class="text-18px" />
          <span>主题偏好</span>
        </button>
        <button type="button" class="user-panel__action" @click="toggleLocale">
          <SvgIcon icon="solar:global-linear" class="text-18px" />
          <span>语言切换</span>
        </button>
      </div>

      <div class="user-panel__group">
        <button v-if="authStore.isAdmin" type="button" class="user-panel__action" @click="navigateTo('chat-history')">
          <SvgIcon icon="solar:history-linear" class="text-18px" />
          <span>会话审计</span>
        </button>
        <button v-if="authStore.isAdmin" type="button" class="user-panel__action" @click="navigateTo('usage-monitor')">
          <SvgIcon icon="solar:chart-2-linear" class="text-18px" />
          <span>用量监控</span>
        </button>
        <button v-else type="button" class="user-panel__action" @click="navigateTo('recharge')">
          <SvgIcon icon="solar:wallet-money-linear" class="text-18px" />
          <span>套餐与充值</span>
        </button>
      </div>

      <button type="button" class="user-panel__logout" @click="logout">
        <SvgIcon icon="ph:sign-out" class="text-18px" />
        <span>{{ $t('common.logout') }}</span>
      </button>
    </section>
  </NPopover>
</template>

<style scoped lang="scss">
.user-avatar-trigger {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  border: 1px solid rgba(148, 163, 184, 0.16);
  border-radius: 18px;
  background: rgba(15, 23, 42, 0.72);
  padding: 8px 10px;
  color: rgb(241 245 249);
  transition:
    border-color 0.2s ease,
    transform 0.2s ease,
    background 0.2s ease;

  &:hover {
    border-color: rgba(var(--primary-color), 0.26);
    background: rgba(15, 23, 42, 0.84);
    transform: translateY(-1px);
  }

  &__avatar {
    display: inline-flex;
    height: 34px;
    width: 34px;
    align-items: center;
    justify-content: center;
    border-radius: 12px;
    background: rgba(79, 124, 255, 0.18);
    color: rgb(var(--primary-color));
  }

  &__meta {
    display: flex;
    min-width: 0;
    flex-direction: column;
    align-items: flex-start;
  }

  &__name {
    color: rgb(226 232 240);
    font-size: 14px;
    font-weight: 600;
    line-height: 1.2;
  }

  &__role {
    color: rgb(148 163 184);
    font-size: 12px;
    line-height: 1.2;
  }

  &__arrow {
    color: rgb(148 163 184);
  }
}

.user-panel {
  width: 320px;
  border: 1px solid rgba(71, 85, 105, 0.42);
  border-radius: 24px;
  background: rgba(2, 6, 23, 0.94);
  padding: 16px;
  color: rgb(226 232 240);
  box-shadow: 0 28px 60px rgba(2, 6, 23, 0.34);

  &__hero {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 12px;
    margin-bottom: 14px;
  }

  &__identity {
    display: flex;
    min-width: 0;
    gap: 12px;
  }

  &__avatar {
    display: inline-flex;
    height: 44px;
    width: 44px;
    flex-shrink: 0;
    align-items: center;
    justify-content: center;
    border-radius: 16px;
    background: linear-gradient(135deg, rgba(79, 124, 255, 0.22), rgba(99, 102, 241, 0.18));
    color: rgb(var(--primary-color));
  }

  &__identity-copy {
    min-width: 0;
  }

  &__name {
    margin: 0;
    font-size: 16px;
    font-weight: 700;
  }

  &__subline {
    margin: 4px 0 0;
    color: rgb(148 163 184);
    font-size: 12px;
    line-height: 1.5;
  }

  &__tier {
    display: inline-flex;
    align-items: center;
    border-radius: 999px;
    background: rgba(79, 124, 255, 0.14);
    padding: 6px 10px;
    color: rgb(var(--primary-color));
    font-size: 12px;
    font-weight: 700;
  }

  &__stats {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 10px;
    margin-bottom: 14px;
  }

  &__stat-card {
    display: flex;
    flex-direction: column;
    gap: 6px;
    border: 1px solid rgba(71, 85, 105, 0.3);
    border-radius: 16px;
    background: rgba(15, 23, 42, 0.82);
    padding: 10px;
  }

  &__stat-label {
    color: rgb(148 163 184);
    font-size: 11px;
  }

  &__stat-value {
    color: rgb(241 245 249);
    font-size: 13px;
    font-weight: 700;
  }

  &__group {
    display: grid;
    gap: 8px;
    margin-bottom: 12px;
  }

  &__action,
  &__logout {
    display: flex;
    width: 100%;
    align-items: center;
    gap: 10px;
    border: 1px solid rgba(71, 85, 105, 0.26);
    border-radius: 16px;
    background: rgba(15, 23, 42, 0.66);
    padding: 11px 12px;
    color: rgb(226 232 240);
    transition:
      border-color 0.2s ease,
      background 0.2s ease,
      transform 0.2s ease;

    &:hover {
      border-color: rgba(var(--primary-color), 0.26);
      background: rgba(30, 41, 59, 0.82);
      transform: translateY(-1px);
    }
  }

  &__logout {
    justify-content: center;
    background: rgba(127, 29, 29, 0.28);
    color: rgb(254 202 202);
  }
}

@media (max-width: 768px) {
  .user-avatar-trigger {
    &__meta {
      display: none;
    }
  }

  .user-panel {
    width: min(320px, calc(100vw - 24px));

    &__stats {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
  }
}
</style>
