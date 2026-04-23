<script setup lang="ts">
import { useAppStore } from '@/store/modules/app';
import { useAuthStore } from '@/store/modules/auth';
import { useThemeStore } from '@/store/modules/theme';
import { useUiStore } from '@/store/modules/ui';
import WorkspaceWindow from '@/components/custom/workspace-window.vue';
import KnowledgeBaseView from '@/views/knowledge-base/index.vue';

defineOptions({
  name: 'ChatWorkspaceWindowHost'
});

const authStore = useAuthStore();
const appStore = useAppStore();
const themeStore = useThemeStore();
const uiStore = useUiStore();
const { workspaceWindow, workspaceWindowVisible, workspaceWindowMode, workspaceWindowDepth } = storeToRefs(uiStore);

const accountLoading = ref(false);
const accountTags = ref<Api.OrgTag.Mine>({
  orgTags: [],
  primaryOrg: '',
  orgTagDetails: []
});
const accountUsage = ref<Api.User.UsageSnapshot>({
  day: '',
  chatRequestCount: 0,
  llm: {
    enabled: false,
    usedTokens: 0,
    limitTokens: 0,
    remainingTokens: 0,
    requestCount: 0
  },
  embedding: {
    enabled: false,
    usedTokens: 0,
    limitTokens: 0,
    remainingTokens: 0,
    requestCount: 0
  }
});

const isReferencePreview = computed(() => workspaceWindow.value?.type === 'reference-preview');
const shouldRender = computed(() => workspaceWindowVisible.value && !isReferencePreview.value);

const windowTitle = computed(() => workspaceWindow.value?.title || '工作窗');
const windowModeLabel = computed(() => {
  if (workspaceWindowMode.value === 'immersive') return '沉浸视图';
  if (workspaceWindowMode.value === 'fullscreen') return '全屏视图';
  return '标准视图';
});
const windowBadge = computed(() => {
  if (workspaceWindow.value?.badge) return workspaceWindow.value.badge;
  if (workspaceWindow.value?.type === 'settings') return '设置';
  if (workspaceWindow.value?.type === 'knowledge-base') return '知识库';
  if (workspaceWindow.value?.type === 'account') return '账户';
  return '工作窗';
});

const windowMeta = computed(() => {
  if (workspaceWindow.value?.meta) return workspaceWindow.value.meta;
  if (workspaceWindow.value?.type === 'settings') return '偏好、主题与工作台行为将在此统一配置';
  if (workspaceWindow.value?.type === 'knowledge-base') return '后续可在此承载文档检索、分片与引用管理';
  if (workspaceWindow.value?.type === 'account') return '用户数据、套餐、导入导出与安全配置面板';
  return '';
});

const actionGroups = computed(() => {
  if (workspaceWindow.value?.type === 'settings') {
    return [
      {
        title: '偏好项',
        items: ['主题模式切换', '语言与快捷键配置', '主舞台默认视图']
      },
      {
        title: '消息体验',
        items: ['自动滚动策略', '流式渲染节流', '输入区工具栏裁剪']
      }
    ];
  }

  if (workspaceWindow.value?.type === 'knowledge-base') {
    return [
      {
        title: '资料入口',
        items: ['上传 PDF / 文本 / 图片', '分组与标签浏览', '引用来源快速检索']
      },
      {
        title: '后续接入点',
        items: ['文档切片任务状态', '召回效果调优', '引用命中详情工作窗']
      }
    ];
  }

  if (workspaceWindow.value?.type === 'account') {
    return [
      {
        title: '账户中心',
        items: ['基础资料与组织信息', '套餐与额度信息', '导入导出与安全设置']
      }
    ];
  }

  return [];
});

const themeSchemeOptions = computed(() => {
  return [
    { label: '浅色', value: 'light' },
    { label: '深色', value: 'dark' },
    { label: '跟随系统', value: 'auto' }
  ];
});

const localeOptions = computed(() => appStore.localeOptions.map(item => ({ label: item.label, value: item.key })));

const accountSummaryCards = computed(() => {
  return [
    {
      label: '当前角色',
      value: authStore.userInfo.role || 'USER'
    },
    {
      label: '主组织',
      value: accountTags.value.primaryOrg || authStore.userInfo.primaryOrg || '未设置'
    },
    {
      label: '组织数量',
      value: String(accountTags.value.orgTagDetails.length || authStore.userInfo.orgTags?.length || 0)
    },
    {
      label: '今日请求',
      value: String(accountUsage.value.chatRequestCount || 0)
    }
  ];
});

async function loadAccountPanelData() {
  accountLoading.value = true;

  const [{ error: orgError, data: orgData }, { error: usageError, data: usageData }] = await Promise.all([
    request<Api.OrgTag.Mine>({ url: '/users/org-tags' }),
    request<Api.User.UsageSnapshot>({ url: '/users/usage' })
  ]);

  if (!orgError && orgData) {
    accountTags.value = orgData;
  }

  if (!usageError && usageData) {
    accountUsage.value = usageData;
  }

  accountLoading.value = false;
}

watch(
  () => workspaceWindow.value?.type,
  type => {
    if (type === 'account') {
      loadAccountPanelData();
    }
  },
  { immediate: true }
);

function handleThemeSchemeChange(value: UnionKey.ThemeScheme) {
  themeStore.setThemeScheme(value);
}

function handleLocaleChange(value: App.I18n.LangType) {
  appStore.changeLocale(value);
}

function renderQuotaSummary(quota: Api.User.UsageQuota) {
  if (!quota.enabled) {
    return '当前未启用';
  }

  const limit = Number(quota.limitTokens || 0);
  const remaining = Number(quota.remainingTokens || 0);

  if (limit <= 0 && remaining > 0) {
    return `余额 ${remaining.toLocaleString()}`;
  }
  if (limit <= 0 && remaining <= 0) {
    return '未配置额度';
  }
  const used = Number(quota.usedTokens || 0);
  return `${Math.max(0, used).toLocaleString()} / ${limit.toLocaleString()}`;
}

function renderQuotaMeta(quota: Api.User.UsageQuota) {
  if (!quota.enabled) {
    return '可在后续套餐模块中扩展';
  }

  const remaining = Number(quota.remainingTokens || 0);
  const limit = Number(quota.limitTokens || 0);
  const requests = Number(quota.requestCount || 0).toLocaleString();

  if (limit <= 0 && remaining > 0) {
    return `无上限 · 按余额消耗 · ${requests} 次请求`;
  }
  if (limit <= 0) {
    return '';
  }
  return `剩余 ${Math.max(0, remaining).toLocaleString()} · ${requests} 次请求`;
}

function handleClose() {
  uiStore.closeWorkspaceWindow();
}

function handleCycleMode() {
  uiStore.cycleWorkspaceWindowMode();
}
</script>

<template>
  <WorkspaceWindow
    :visible="shouldRender"
    :badge="windowBadge"
    :title="windowTitle"
    :meta="windowMeta"
    :mode="workspaceWindowMode"
    :stack-depth="workspaceWindowDepth"
    :width="workspaceWindow?.width || 'min(920px, calc(100vw - 48px))'"
    :height="workspaceWindow?.height || 'min(82vh, 760px)'"
    :closable="workspaceWindow?.closable !== false"
    @close="handleClose"
    @cycle-mode="handleCycleMode"
  >
    <template #header-extra>
      <span class="workspace-window-host__header-pill">{{ windowModeLabel }}</span>
      <span v-if="workspaceWindow?.openedAt" class="workspace-window-host__header-pill workspace-window-host__header-pill--soft">
        {{ workspaceWindow.openedAt.slice(11, 19) }}
      </span>
    </template>

    <section class="workspace-window-host">
      <div class="workspace-window-host__hero">
        <div>
          <h4 class="workspace-window-host__hero-title">{{ windowTitle }}</h4>
          <p class="workspace-window-host__hero-desc">{{ windowMeta }}</p>
        </div>
        <div class="workspace-window-host__hero-meta">
          <span class="workspace-window-host__hero-tag">统一浮层体系</span>
          <span class="workspace-window-host__hero-tag workspace-window-host__hero-tag--soft">{{ actionGroups.length || 1 }} 个分区</span>
        </div>
      </div>

      <div class="workspace-window-host__grid">
        <template v-if="workspaceWindow?.type === 'knowledge-base'">
          <div class="workspace-window-host__knowledge-panel">
            <KnowledgeBaseView />
          </div>
        </template>
        <template v-else-if="workspaceWindow?.type === 'settings'">
          <article class="workspace-window-host__card workspace-window-host__card--settings">
            <h5 class="workspace-window-host__card-title">主题模式</h5>
            <p class="workspace-window-host__card-desc">工作台优先保留深色专注体验，同时支持快速切换到浅色或跟随系统。</p>
            <NRadioGroup :value="themeStore.themeScheme" @update:value="handleThemeSchemeChange">
              <NSpace vertical :size="12">
                <NRadio v-for="item in themeSchemeOptions" :key="item.value" :value="item.value">
                  {{ item.label }}
                </NRadio>
              </NSpace>
            </NRadioGroup>
          </article>

          <article class="workspace-window-host__card workspace-window-host__card--settings">
            <h5 class="workspace-window-host__card-title">界面语言</h5>
            <p class="workspace-window-host__card-desc">保留基础语言切换能力，让工作台与系统文案保持一致。</p>
            <NSelect :value="appStore.locale" :options="localeOptions" @update:value="handleLocaleChange" />
          </article>

          <article class="workspace-window-host__card workspace-window-host__card--settings">
            <h5 class="workspace-window-host__card-title">工作台偏好</h5>
            <p class="workspace-window-host__card-desc">这些是当前版本最基础、最能被用户感知的偏好项。</p>
            <div class="workspace-window-host__toggle-list">
              <div class="workspace-window-host__toggle-item">
                <div>
                  <strong>沉浸内容区</strong>
                  <p>切换是否使用更纯粹的内容展示模式。</p>
                </div>
                <NSwitch :value="appStore.fullContent" @update:value="appStore.toggleFullContent" />
              </div>
              <div class="workspace-window-host__toggle-item">
                <div>
                  <strong>混合侧栏固定</strong>
                  <p>适合桌面端多模块切换时保留侧栏布局习惯。</p>
                </div>
                <NSwitch :value="appStore.mixSiderFixed" @update:value="appStore.setMixSiderFixed" />
              </div>
              <div class="workspace-window-host__toggle-item">
                <div>
                  <strong>灰度模式</strong>
                  <p>用于演示或辅助检查当前界面层次。</p>
                </div>
                <NSwitch :value="themeStore.grayscale" @update:value="themeStore.setGrayscale" />
              </div>
            </div>
          </article>
        </template>
        <template v-else-if="workspaceWindow?.type === 'account'">
          <article class="workspace-window-host__card workspace-window-host__card--account workspace-window-host__card--wide">
            <NSpin :show="accountLoading">
              <div class="workspace-window-host__account-hero">
                <div>
                  <h5 class="workspace-window-host__card-title">{{ authStore.userInfo.username || '当前账户' }}</h5>
                  <p class="workspace-window-host__card-desc">账号 ID {{ authStore.userInfo.id || '--' }} · 主组织 {{ accountTags.primaryOrg || authStore.userInfo.primaryOrg || '未设置' }}</p>
                </div>
                <NTag type="primary" round>
                  {{ authStore.userInfo.role || 'USER' }}
                </NTag>
              </div>

              <div class="workspace-window-host__summary-grid">
                <div v-for="item in accountSummaryCards" :key="item.label" class="workspace-window-host__summary-card">
                  <span class="workspace-window-host__summary-label">{{ item.label }}</span>
                  <strong class="workspace-window-host__summary-value">{{ item.value }}</strong>
                </div>
              </div>
            </NSpin>
          </article>

          <article class="workspace-window-host__card workspace-window-host__card--account">
            <h5 class="workspace-window-host__card-title">配额总览</h5>
            <div class="workspace-window-host__quota-list">
              <div class="workspace-window-host__quota-item">
                <strong>LLM Token</strong>
                <span>{{ renderQuotaSummary(accountUsage.llm) }}</span>
                <small>{{ renderQuotaMeta(accountUsage.llm) }}</small>
              </div>
              <div class="workspace-window-host__quota-item">
                <strong>Embedding Token</strong>
                <span>{{ renderQuotaSummary(accountUsage.embedding) }}</span>
                <small>{{ renderQuotaMeta(accountUsage.embedding) }}</small>
              </div>
            </div>
          </article>

          <article class="workspace-window-host__card workspace-window-host__card--account">
            <h5 class="workspace-window-host__card-title">组织标签</h5>
            <div class="workspace-window-host__tag-list">
              <NTag v-for="tag in accountTags.orgTagDetails" :key="tag.tagId" round>
                {{ tag.name }}
              </NTag>
              <span v-if="!accountTags.orgTagDetails.length" class="workspace-window-host__empty-inline">当前暂无组织标签信息</span>
            </div>
          </article>
        </template>
        <article v-else v-for="group in actionGroups" :key="group.title" class="workspace-window-host__card">
          <h5 class="workspace-window-host__card-title">{{ group.title }}</h5>
          <p class="workspace-window-host__card-desc">当前工作窗会逐步接入更完整的业务能力，现阶段先保留最小可用入口。</p>
          <ul class="workspace-window-host__list">
            <li v-for="item in group.items" :key="item">{{ item }}</li>
          </ul>
        </article>
      </div>

      <div class="workspace-window-host__footnote">
        <span class="workspace-window-host__footnote-label">Stage Notes</span>
        <p class="workspace-window-host__footnote-text">当前工作窗与聊天主舞台共享统一暗色玻璃层级，后续功能模块将继续在该容器内按分区增量接入。</p>
      </div>

      <div class="workspace-window-host__footbar">
        <span class="workspace-window-host__footbar-pill">当前模式：{{ windowModeLabel }}</span>
        <span class="workspace-window-host__footbar-pill workspace-window-host__footbar-pill--soft">支持标准 / 沉浸 / 全屏循环切换</span>
      </div>
    </section>
  </WorkspaceWindow>
</template>

<style scoped lang="scss">
.workspace-window-host {
  display: flex;
  height: 100%;
  flex-direction: column;
  gap: 18px;

  &__header-pill {
    display: inline-flex;
    align-items: center;
    height: 30px;
    padding: 0 12px;
    border-radius: 999px;
    background: rgba(79, 124, 255, 0.14);
    color: rgb(191 219 254);
    font-size: 12px;
    font-weight: 700;

    &--soft {
      background: var(--color-bg-card);
      color: var(--color-text-secondary);
    }
  }

  &__hero {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 16px;
    border: 1px solid var(--color-border);
    border-radius: 24px;
    background: var(--color-bg-container);
    padding: 18px;
  }

  &__hero-meta {
    display: flex;
    flex-wrap: wrap;
    justify-content: flex-end;
    gap: 8px;
  }

  &__hero-title {
    margin: 0;
    color: var(--color-text-primary);
    font-size: 20px;
    font-weight: 700;
  }

  &__hero-desc {
    margin: 8px 0 0;
    color: var(--color-text-secondary);
    font-size: 13px;
    line-height: 1.7;
  }

  &__hero-tag {
    display: inline-flex;
    align-items: center;
    border-radius: 999px;
    background: rgba(79, 124, 255, 0.14);
    padding: 6px 10px;
    color: rgb(191 219 254);
    font-size: 12px;
    font-weight: 700;

    &--soft {
      background: var(--color-bg-card);
      color: var(--color-text-secondary);
    }
  }

  &__grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 14px;
  }

  &__card {
    border: 1px solid var(--color-border);
    border-radius: 22px;
    background: var(--color-bg-card);
    padding: 18px;

    &--wide {
      grid-column: 1 / -1;
    }
  }

  &__card-desc {
    margin: 8px 0 16px;
    color: var(--color-text-secondary);
    font-size: 13px;
    line-height: 1.7;
  }

  &__knowledge-panel {
    min-height: 0;
    grid-column: 1 / -1;

    :deep(.card-wrapper) {
      background: transparent;
      box-shadow: none;
    }

    :deep(.n-card) {
      border-radius: 22px;
    }
  }

  &__toggle-list,
  &__quota-list {
    display: flex;
    flex-direction: column;
    gap: 12px;
  }

  &__toggle-item,
  &__quota-item {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    border: 1px solid var(--color-border);
    border-radius: 16px;
    background: var(--color-bg-elevated);
    padding: 14px 16px;

    p,
    small {
      margin: 4px 0 0;
      color: var(--color-text-secondary);
      font-size: 12px;
      line-height: 1.6;
    }

    span {
      color: var(--color-text-primary);
      font-size: 14px;
      font-weight: 700;
      white-space: nowrap;
    }
  }

  &__account-hero {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 16px;
    margin-bottom: 16px;
  }

  &__summary-grid {
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 12px;
  }

  &__summary-card {
    border: 1px solid var(--color-border);
    border-radius: 16px;
    background: var(--color-bg-elevated);
    padding: 14px;
  }

  &__summary-label {
    display: block;
    margin-bottom: 8px;
    color: var(--color-text-secondary);
    font-size: 12px;
  }

  &__summary-value {
    color: var(--color-text-primary);
    font-size: 16px;
    font-weight: 700;
  }

  &__tag-list {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
  }

  &__empty-inline {
    color: var(--color-text-secondary);
    font-size: 13px;
  }

  &__footnote {
    border: 1px solid var(--color-border);
    border-radius: 20px;
    background: var(--color-bg-card);
    padding: 16px 18px;
  }

  &__footnote-label {
    display: inline-flex;
    margin-bottom: 8px;
    color: rgb(var(--primary-color));
    font-size: 11px;
    font-weight: 700;
    letter-spacing: 0.08em;
    text-transform: uppercase;
  }

  &__footnote-text {
    margin: 0;
    color: var(--color-text-secondary);
    font-size: 13px;
    line-height: 1.75;
  }

  &__footbar {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
  }

  &__footbar-pill {
    display: inline-flex;
    align-items: center;
    border-radius: 999px;
    background: rgba(79, 124, 255, 0.14);
    padding: 7px 12px;
    color: rgb(191 219 254);
    font-size: 12px;
    font-weight: 700;

    &--soft {
      background: var(--color-bg-card);
      color: var(--color-text-secondary);
    }
  }

  &__card-title {
    margin: 0 0 10px;
    color: var(--color-text-primary);
    font-size: 15px;
    font-weight: 700;
  }

  &__list {
    margin: 0;
    padding-left: 18px;
    color: var(--color-text-secondary);
    font-size: 13px;
    line-height: 1.8;
  }
}

@media (max-width: 768px) {
  .workspace-window-host {
    &__hero {
      flex-direction: column;
    }

    &__grid {
      grid-template-columns: 1fr;
    }

    &__toggle-item,
    &__quota-item,
    &__account-hero {
      flex-direction: column;
      align-items: flex-start;
    }

    &__summary-grid {
      grid-template-columns: 1fr;
    }
  }
}
</style>
