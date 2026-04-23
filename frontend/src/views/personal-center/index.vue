<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, h } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { NTag } from 'naive-ui';
import { useAppStore } from '@/store/modules/app';
import { useAuthStore } from '@/store/modules/auth';
import { useThemeStore } from '@/store/modules/theme';

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const appStore = useAppStore();
const themeStore = useThemeStore();
const { userInfo } = storeToRefs(authStore);
const { themeScheme, grayscale } = storeToRefs(themeStore);
const isMobileViewport = ref(false);

const currentSection = computed<'general' | 'account' | 'about'>(() => {
  const tab = String(route.query.tab || 'general');
  if (tab === 'account' || tab === 'about') return tab;
  return 'general';
});

const roleLabel = computed(() => userInfo.value.role || 'USER');
const orgCount = computed(() => tags.value.orgTagDetails.length || tags.value.orgTags.length || 0);
const sectionTabs = [
  { key: 'general', label: '通用' },
  { key: 'account', label: '账户' },
  { key: 'about', label: '关于' }
] as const;

function handleThemeChange(mode: UnionKey.ThemeScheme) {
  themeStore.setThemeScheme(mode);
}

function changeSection(section: 'general' | 'account' | 'about') {
  router.push({ name: 'personal-center', query: { tab: section } });
}

function handleLocaleChange(value: App.I18n.LangType) {
  appStore.changeLocale(value);
}

function handleLogout() {
  authStore.logout();
}

function syncMobileViewport() {
  if (typeof window === 'undefined') return;
  isMobileViewport.value = window.innerWidth <= 768;
}

const tags = ref<Api.OrgTag.Mine>({
  orgTags: [],
  primaryOrg: '',
  orgTagDetails: []
});

const usage = ref<Api.User.UsageSnapshot>({
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

const loading = ref(false);

// Token 记录相关变量
const tokenRecords = ref<Api.User.TokenRecord[]>([]);
const tokenRecordLoading = ref(false);
const pagination = ref({
  page: 1,
  pageSize: 10,
  total: 0,
  pageCount: 0
});
const getPersonalData = async () => {
  loading.value = true;
  const [{ error: orgError, data: orgData }, { error: usageError, data: usageData }] = await Promise.all([
    request<Api.OrgTag.Mine>({
      url: '/users/org-tags'
    }),
    request<Api.User.UsageSnapshot>({
      url: '/users/usage'
    })
  ]);

  if (!orgError) {
    tags.value = orgData;
  }

  if (!usageError) {
    usage.value = usageData;
  }

  // 获取 Token 记录
  getTokenRecords();

  loading.value = false;
};

const getOrgTags = async () => {
  const { error, data } = await request<Api.OrgTag.Mine>({
    url: '/users/org-tags'
  });
  if (!error) {
    tags.value = data;
  }
};

onMounted(() => {
  syncMobileViewport();
  window.addEventListener('resize', syncMobileViewport);
  getPersonalData();
});

onBeforeUnmount(() => {
  if (typeof window !== 'undefined') {
    window.removeEventListener('resize', syncMobileViewport);
  }
});

const visible = ref(false);
const currentTagId = ref('');
const showModal = (tagId: string) => {
  if (tagId === tags.value.primaryOrg) return;
  visible.value = true;
  currentTagId.value = tagId;
};
const submitLoading = ref(false);
const setPrimaryOrg = async () => {
  submitLoading.value = true;
  const { error } = await request({
    url: '/users/primary-org',
    method: 'PUT',
    data: { primaryOrg: currentTagId.value, userId: userInfo.value.id }
  });
  if (!error) {
    visible.value = false;
    getOrgTags();
  }
  submitLoading.value = false;
};

// Token 记录相关方法
const getTokenRecords = async () => {
  tokenRecordLoading.value = true;
  try {
    const { error, data } = await request({
      url: '/users/token-records',
      method: 'GET',
      params: {
        page: pagination.value.page - 1,
        size: pagination.value.pageSize
      }
    });

    if (!error && data) {
      tokenRecords.value = data.content || [];
      pagination.value.total = data.totalElements || 0;
      pagination.value.pageCount = data.totalPages || 0;
    }
  } finally {
    tokenRecordLoading.value = false;
  }
};

const handlePageChange = (page: number) => {
  pagination.value.page = page;
  getTokenRecords();
};

function getTokenTypeMeta(tokenType: string) {
  const typeMap: Record<string, { text: string; type: 'info' | 'success' | 'default' }> = {
    LLM: { text: 'LLM', type: 'info' },
    EMBEDDING: { text: 'Embedding', type: 'success' }
  };
  return typeMap[tokenType] || { text: tokenType, type: 'default' };
}

function getChangeTypeMeta(changeType: string) {
  const typeMap: Record<string, { text: string; type: 'success' | 'warning' | 'default' }> = {
    INCREASE: { text: '充值', type: 'success' },
    CONSUME: { text: '消耗', type: 'warning' }
  };
  return typeMap[changeType] || { text: changeType, type: 'default' };
}

// Token 记录表格列定义
const tokenRecordColumns = computed(() => [
  {
    title: '日期',
    key: 'recordDate',
    width: 100,
    render: (row: Api.User.TokenRecord) => row.recordDate
  },
  {
    title: 'Token 类型',
    key: 'tokenType',
    width: 100,
    render: (row: Api.User.TokenRecord) => {
      const typeMap: Record<string, { text: string; type: any }> = {
        LLM: { text: 'LLM', type: 'info' },
        EMBEDDING: { text: 'Embedding', type: 'success' }
      };
      const type = typeMap[row.tokenType] || { text: row.tokenType, type: 'default' };
      return h(NTag, { type: type.type }, () => type.text);
    }
  },
  {
    title: '变动类型',
    key: 'changeType',
    width: 100,
    render: (row: Api.User.TokenRecord) => {
      const typeMap: Record<string, { text: string; type: any }> = {
        INCREASE: { text: '充值', type: 'success' },
        CONSUME: { text: '消耗', type: 'warning' }
      };
      const type = typeMap[row.changeType] || { text: row.changeType, type: 'default' };
      return h(NTag, { type: type.type }, () => type.text);
    }
  },
  {
    title: '变动数量',
    key: 'amount',
    width: 120,
    render: (row: Api.User.TokenRecord) => {
      const sign = row.changeType === 'INCREASE' ? '+' : '-';
      return `${sign}${row.amount.toLocaleString()}`;
    }
  },
  {
    title: '变动前余额',
    key: 'balanceBefore',
    width: 120,
    render: (row: Api.User.TokenRecord) => row.balanceBefore?.toLocaleString() || '-'
  },
  {
    title: '变动后余额',
    key: 'balanceAfter',
    width: 120,
    render: (row: Api.User.TokenRecord) => row.balanceAfter?.toLocaleString() || '-'
  },
  {
    title: '原因',
    key: 'reason',
    minWidth: 100,
    ellipsis: { tooltip: true },
    render: (row: Api.User.TokenRecord) => row.reason || '-'
  },
  {
    title: '请求次数',
    key: 'requestCount',
    width: 80,
    render: (row: Api.User.TokenRecord) => row.requestCount?.toLocaleString() || '0'
  },
  {
    title: '创建时间',
    key: 'createdAt',
    width: 180,
    render: (row: Api.User.TokenRecord) => new Date(row.createdAt).toLocaleString('zh-CN')
  }
]);
</script>

<template>
  <NSpin :show="loading">
    <div class="settings-page">
      <nav class="settings-page__tabs" aria-label="设置分组">
        <button
          v-for="item in sectionTabs"
          :key="item.key"
          type="button"
          class="settings-page__tab"
          :class="{ 'is-active': currentSection === item.key }"
          @click="changeSection(item.key)"
        >
          {{ item.label }}
        </button>
      </nav>

      <section v-if="currentSection === 'general'" class="settings-page__panel">
        <div class="settings-page__header">
          <h2>通用设置</h2>
        </div>

        <div class="settings-page__section">
          <div class="settings-page__section-title">主题模式</div>
          <div class="settings-page__choice-row">
            <button type="button" class="settings-page__choice" :class="{ 'is-active': themeScheme === 'light' }" @click="handleThemeChange('light')">☀ 浅色</button>
            <button type="button" class="settings-page__choice" :class="{ 'is-active': themeScheme === 'dark' }" @click="handleThemeChange('dark')">🌙 深色</button>
            <button type="button" class="settings-page__choice" :class="{ 'is-active': themeScheme === 'auto' }" @click="handleThemeChange('auto')">💻 跟随系统</button>
          </div>
        </div>

        <div class="settings-page__section">
          <div class="settings-page__section-title">界面语言</div>
          <NSelect :value="appStore.locale" :options="appStore.localeOptions" class="settings-page__select" @update:value="handleLocaleChange" />
        </div>

        <div class="settings-page__section settings-page__switch-row">
          <div>
            <div class="settings-page__section-title mb-6px!">灰度模式</div>
            <p class="settings-page__hint">用于演示或辅助检查界面层次</p>
          </div>
          <NSwitch :value="grayscale" @update:value="themeStore.setGrayscale" />
        </div>
      </section>

      <section v-else-if="currentSection === 'account'" class="settings-page__panel">
        <div class="settings-page__header">
          <h2>账户信息</h2>
        </div>

        <div class="settings-page__hero">
          <div class="settings-page__hero-main">
            <NAvatar size="large" round>
              <icon-solar:user-circle-linear class="text-icon-large" />
            </NAvatar>
            <div>
              <div class="settings-page__hero-name">{{ userInfo.username }}</div>
              <div class="settings-page__hero-meta">账号 ID {{ userInfo.id }} · 主组织 {{ tags.primaryOrg || userInfo.primaryOrg || '-' }}</div>
            </div>
          </div>
          <NTag type="primary" size="large">{{ roleLabel }}</NTag>
        </div>

        <div class="settings-page__stats">
          <div class="settings-page__stat-card">
            <span>当前角色</span>
            <strong>{{ roleLabel }}</strong>
          </div>
          <div class="settings-page__stat-card">
            <span>主组织</span>
            <strong>{{ tags.primaryOrg || userInfo.primaryOrg || '-' }}</strong>
          </div>
          <div class="settings-page__stat-card">
            <span>组织数量</span>
            <strong>{{ orgCount }}</strong>
          </div>
        </div>

        <div class="settings-page__section">
          <div class="settings-page__section-title">配额总览</div>
          <div class="settings-page__quota-grid">
            <div class="settings-page__quota-card">
              <div class="settings-page__quota-title">LLM Token</div>
              <div class="settings-page__quota-value">{{ usage.llm.usedTokens.toLocaleString() }} / {{ usage.llm.limitTokens.toLocaleString() }}</div>
              <NProgress type="line" :percentage="usage.llm.limitTokens ? Math.min(100, Math.round((usage.llm.usedTokens / usage.llm.limitTokens) * 100)) : 0" :show-indicator="false" />
              <div class="settings-page__quota-meta">剩余 {{ usage.llm.remainingTokens.toLocaleString() }}</div>
            </div>
            <div class="settings-page__quota-card">
              <div class="settings-page__quota-title">Embedding Token</div>
              <div class="settings-page__quota-value">{{ usage.embedding.usedTokens.toLocaleString() }} / {{ usage.embedding.limitTokens.toLocaleString() }}</div>
              <NProgress type="line" :percentage="usage.embedding.limitTokens ? Math.min(100, Math.round((usage.embedding.usedTokens / usage.embedding.limitTokens) * 100)) : 0" :show-indicator="false" />
              <div class="settings-page__quota-meta">剩余 {{ usage.embedding.remainingTokens.toLocaleString() }}</div>
            </div>
          </div>
        </div>

        <div class="settings-page__section">
          <div class="settings-page__section-title">组织标签</div>
          <div class="settings-page__tag-grid">
            <button
              v-for="tag in tags.orgTagDetails"
              :key="tag.tagId"
              type="button"
              class="settings-page__tag-card"
              @click="showModal(tag.tagId)"
            >
              <div class="flex items-center justify-between gap-12px">
                <strong>{{ tag.name }}</strong>
                <NTag v-if="tag.tagId === tags.primaryOrg" type="primary" size="small">主标签</NTag>
              </div>
              <p>{{ tag.description || '暂无描述' }}</p>
            </button>
          </div>
        </div>

        <div class="settings-page__section">
          <div class="settings-page__section-title">Token 变动记录</div>
          <NSpin :show="tokenRecordLoading">
            <div v-if="isMobileViewport && tokenRecords.length > 0" class="settings-page__record-list">
              <article v-for="row in tokenRecords" :key="`${row.createdAt}-${row.recordDate}-${row.amount}`" class="settings-page__record-card">
                <div class="flex items-center justify-between gap-12px">
                  <strong>{{ row.recordDate }}</strong>
                  <div class="flex items-center gap-8px">
                    <NTag :type="getTokenTypeMeta(row.tokenType).type" size="small">{{ getTokenTypeMeta(row.tokenType).text }}</NTag>
                    <NTag :type="getChangeTypeMeta(row.changeType).type" size="small">{{ getChangeTypeMeta(row.changeType).text }}</NTag>
                  </div>
                </div>
                <div class="settings-page__record-amount">
                  {{ row.changeType === 'INCREASE' ? '+' : '-' }}{{ row.amount.toLocaleString() }}
                </div>
                <div class="settings-page__record-meta">
                  <span>{{ (row.balanceBefore ?? 0).toLocaleString() }} → {{ (row.balanceAfter ?? 0).toLocaleString() }}</span>
                  <span>原因：{{ row.reason || '-' }}</span>
                  <span>请求次数：{{ row.requestCount?.toLocaleString() || '0' }}</span>
                  <span>{{ new Date(row.createdAt).toLocaleString('zh-CN') }}</span>
                </div>
              </article>
              <NPagination
                v-if="pagination.pageCount > 1"
                :page="pagination.page"
                :page-count="pagination.pageCount"
                :page-slot="5"
                @update:page="handlePageChange"
              />
            </div>
            <NDataTable
              v-else-if="tokenRecords.length > 0"
              :columns="tokenRecordColumns"
              :data="tokenRecords"
              :loading="tokenRecordLoading"
              :pagination="{
                page: pagination.page,
                pageSize: pagination.pageSize,
                itemCount: pagination.total,
                onChange: handlePageChange
              }"
              :scroll-x="1200"
              size="small"
            />
            <NEmpty v-else description="暂无记录" />
          </NSpin>
        </div>

        <div class="settings-page__footer-action">
          <NButton secondary type="error" @click="handleLogout">退出登录</NButton>
        </div>
      </section>

      <section v-else class="settings-page__panel">
        <div class="settings-page__header">
          <h2>关于智枢 AI</h2>
        </div>

        <div class="settings-page__about-card">
          <h3>智枢 AI</h3>
          <p>当前版本以低调极客风重构为核心，采用统一左侧单列导航与右侧主舞台布局，聊天、知识库、设置与管理功能共享一致的视觉语言。</p>
          <div class="settings-page__about-meta">
            <span>主题：统一 CSS 变量</span>
            <span>布局：单列侧边导航</span>
            <span>模式：桌面端优先</span>
          </div>
        </div>
      </section>

      <NModal
        v-model:show="visible"
        :loading="submitLoading"
        preset="dialog"
        title="设置主标签"
        content="确定将当前标签设置为主标签吗？"
        positive-text="确认"
        negative-text="取消"
        @positive-click="setPrimaryOrg"
        @negative-click="visible = false"
      />
    </div>
  </NSpin>
</template>

<style scoped lang="scss">
.settings-page {
  display: flex;
  min-height: calc(100vh - 120px);
  flex-direction: column;
  gap: 18px;

  &__tabs {
    display: inline-flex;
    width: fit-content;
    flex-wrap: wrap;
    gap: 10px;
    padding: 8px;
    border: 1px solid var(--color-border);
    border-radius: 22px;
    background: var(--color-bg-elevated);
    box-shadow: var(--app-surface-shadow);
  }

  &__tab {
    border: 1px solid transparent;
    border-radius: 16px;
    background: transparent;
    padding: 10px 16px;
    color: var(--color-text-secondary);
    font-size: 14px;
    font-weight: 700;
    transition:
      border-color 0.2s ease,
      background 0.2s ease,
      color 0.2s ease;

    &.is-active {
      border-color: rgba(96, 165, 250, 0.28);
      background: var(--app-accent-soft);
      color: var(--color-text-primary);
    }
  }

  &__panel {
    border: 1px solid var(--color-border);
    border-radius: 28px;
    background: var(--color-bg-container);
    padding: 28px;
    color: var(--color-text-primary);
    box-shadow: var(--app-surface-shadow);
  }

  &__header h2 {
    margin: 0;
    font-size: 28px;
    font-weight: 700;
  }

  &__section {
    margin-top: 24px;
    padding-top: 24px;
    border-top: 1px solid var(--color-border);
  }

  &__section-title {
    margin-bottom: 14px;
    font-size: 15px;
    font-weight: 700;
    color: var(--color-text-primary);
  }

  &__choice-row,
  &__stats,
  &__quota-grid,
  &__about-meta {
    display: flex;
    flex-wrap: wrap;
    gap: 12px;
  }

  &__choice,
  &__stat-card,
  &__quota-card,
  &__tag-card,
  &__about-card {
    border: 1px solid var(--color-border);
    border-radius: 20px;
    background: var(--color-bg-card);
  }

  &__choice {
    padding: 12px 16px;
    color: inherit;
    font-size: 14px;
    font-weight: 600;

    &.is-active {
      border-color: rgba(96, 165, 250, 0.42);
      background: linear-gradient(135deg, rgba(59, 130, 246, 0.22), rgba(6, 182, 212, 0.08));
    }
  }

  &__select {
    max-width: 280px;
  }

  &__switch-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
  }

  &__hint {
    margin: 0;
    color: var(--color-text-secondary);
    font-size: 13px;
  }

  &__hero {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    margin-top: 24px;
    padding: 18px 20px;
    border: 1px solid var(--color-border);
    border-radius: 24px;
    background: var(--color-bg-card);
  }

  &__hero-main {
    display: flex;
    align-items: center;
    gap: 14px;
  }

  &__hero-name {
    font-size: 20px;
    font-weight: 700;
  }

  &__hero-meta,
  &__quota-meta,
  &__tag-card p,
  &__about-card p,
  &__record-meta {
    color: var(--color-text-secondary);
    font-size: 13px;
    line-height: 1.7;
  }

  &__record-list {
    display: flex;
    flex-direction: column;
    gap: 12px;
  }

  &__record-card {
    display: flex;
    flex-direction: column;
    gap: 10px;
    border: 1px solid var(--color-border);
    border-radius: 18px;
    background: var(--color-bg-card);
    padding: 16px;
  }

  &__record-amount {
    font-size: 22px;
    font-weight: 700;
  }

  &__record-meta {
    display: grid;
    gap: 4px;
  }

  &__stat-card,
  &__quota-card {
    min-width: 180px;
    flex: 1;
    padding: 16px;

    span {
      display: block;
      margin-bottom: 8px;
      color: var(--color-text-secondary);
      font-size: 12px;
    }

    strong {
      font-size: 24px;
      font-weight: 700;
    }
  }

  &__quota-title {
    font-size: 13px;
    color: var(--color-text-secondary);
  }

  &__quota-value {
    margin: 10px 0;
    font-size: 22px;
    font-weight: 700;
  }

  &__tag-grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
    gap: 12px;
  }

  &__tag-card {
    padding: 16px;
    text-align: left;
  }

  &__footer-action {
    display: flex;
    justify-content: flex-end;
    margin-top: 28px;
  }

  &__about-card {
    margin-top: 24px;
    padding: 20px;

    h3 {
      margin: 0 0 12px;
      font-size: 22px;
    }
  }
}

@media (max-width: 768px) {
  .settings-page {
    min-height: auto;

    &__tabs {
      display: grid;
      width: 100%;
      grid-template-columns: repeat(3, minmax(0, 1fr));
    }

    &__panel {
      padding: 20px 16px;
      border-radius: 20px;
    }

    &__hero,
    &__switch-row {
      flex-direction: column;
      align-items: flex-start;
    }

    &__choice-row {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
    }

    &__choice {
      justify-content: center;
      text-align: center;
    }

    &__stats,
    &__quota-grid,
    &__tag-grid {
      display: grid;
      grid-template-columns: 1fr;
    }

    &__select {
      max-width: none;
    }

    &__footer-action {
      justify-content: stretch;

      :deep(.n-button) {
        width: 100%;
      }
    }
  }
}
</style>
