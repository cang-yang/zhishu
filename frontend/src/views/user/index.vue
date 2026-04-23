<script setup lang="tsx">
import { onActivated, onBeforeUnmount, onMounted, ref } from 'vue';
import dayjs from 'dayjs';
import { NButton, NTag } from 'naive-ui';
import { useTable } from '@/hooks/common/table';
import { useAppStore } from '@/store/modules/app';
import UserSearch from './modules/user-search.vue';
import OrgTagSettingDialog from './modules/org-tag-setting-dialog.vue';

const appStore = useAppStore();
const isMobileViewport = ref(false);

function syncMobileViewport() {
  if (typeof window === 'undefined') return;
  isMobileViewport.value = window.innerWidth <= 768;
}

function apiFn(params: Api.User.SearchParams) {
  return request<Api.User.List>({ url: '/admin/users/list', params });
}

const { columns, columnChecks, data, getData, loading, mobilePagination, searchParams, resetSearchParams } = useTable({
  apiFn,
  immediate: false,
  apiParams: {
    keyword: null,
    orgTag: null,
    status: null
  },
  columns: () => [
    {
      key: 'index',
      title: '序号',
      width: 64
    },
    {
      key: 'username',
      title: '用户名',
      minWidth: 100
    },
    {
      key: 'orgTags',
      title: '标签',
      render: row => (
        <div class="flex flex-wrap gap-2">
          {row.orgTags.map(tag => (
            <NTag key={tag.tagId} type={tag.tagId === row.primaryOrg ? 'primary' : 'default'}>
              {tag.name}
            </NTag>
          ))}
        </div>
      )
    },
    {
      key: 'status',
      title: '是否启用',
      width: 100,
      render: row => <NTag type={row.status ? 'success' : 'warning'}>{row.status ? '已启用' : '已禁用'}</NTag>
    },
    {
      key: 'createdAt',
      title: '创建时间',
      width: 200,
      render: row => dayjs(row.createdAt).format('YYYY-MM-DD HH:mm:ss')
    },
    {
      key: 'chatUsage',
      title: '聊天次数',
      width: 130,
      render: row => (
        <div class="flex flex-col gap-1 text-xs">
          <span>{Number(row.usage?.chatRequestCount || 0).toLocaleString()} 次</span>
          <span class="text-stone-400">今日消息数</span>
        </div>
      )
    },
    {
      key: 'llmUsage',
      title: 'LLM额度',
      width: 220,
      render: row => {
        const quota = row.usage?.llm;
        if (!quota?.enabled) {
          return <span class="text-stone-400">未启用</span>;
        }
        return (
          <div class="flex flex-col gap-1 text-xs">
            <span>{Number(quota.usedTokens || 0).toLocaleString()} / {Number(quota.limitTokens || 0).toLocaleString()}</span>
            <span class="text-stone-400">剩余 {Number(quota.remainingTokens || 0).toLocaleString()} · {quota.requestCount} 次</span>
          </div>
        );
      }
    },
    {
      key: 'embeddingUsage',
      title: 'Embedding额度',
      width: 220,
      render: row => {
        const quota = row.usage?.embedding;
        if (!quota?.enabled) {
          return <span class="text-stone-400">未启用</span>;
        }
        return (
          <div class="flex flex-col gap-1 text-xs">
            <span>{Number(quota.usedTokens || 0).toLocaleString()} / {Number(quota.limitTokens || 0).toLocaleString()}</span>
            <span class="text-stone-400">剩余 {Number(quota.remainingTokens || 0).toLocaleString()} · {quota.requestCount} 次</span>
          </div>
        );
      }
    },
    {
      key: 'operate',
      title: '操作',
      width: 130,
      render: row => (
        <NButton type="primary" ghost size="small" onClick={() => handleOrgTag(row)}>
          分配组织标签
        </NButton>
      )
    }
  ]
});

const visible = ref(false);
const editingData = ref<Api.User.Item | null>(null);

function handleOrgTag(row: Api.User.Item) {
  editingData.value = row;
  visible.value = true;
}

onMounted(() => {
  syncMobileViewport();
  window.addEventListener('resize', syncMobileViewport);
  void getData();
});

onActivated(() => {
  void getData();
});

onBeforeUnmount(() => {
  if (typeof window !== 'undefined') {
    window.removeEventListener('resize', syncMobileViewport);
  }
});
</script>

<template>
  <div class="admin-page-shell min-h-500px flex-col-stretch gap-16px overflow-hidden <sm:overflow-auto">
    <UserSearch v-model:model="searchParams" @reset="resetSearchParams" @search="getData" />

    <NCard title="用户列表" :bordered="false" size="small" class="sm:flex-1-hidden card-wrapper">
      <template #header-extra>
        <TableHeaderOperation v-model:columns="columnChecks" :addable="false" :loading="loading" @refresh="getData" />
      </template>

      <div v-if="isMobileViewport" class="user-page__mobile-list">
        <article v-for="(row, index) in data" :key="row.userId" class="user-page__card">
          <div class="user-page__card-header">
            <strong>{{ index + 1 }}. {{ row.username }}</strong>
            <NTag :type="row.status ? 'success' : 'warning'">{{ row.status ? '已启用' : '已禁用' }}</NTag>
          </div>

          <div class="user-page__tag-row">
            <NTag v-for="tag in row.orgTags" :key="tag.tagId" :type="tag.tagId === row.primaryOrg ? 'primary' : 'default'" size="small">
              {{ tag.name }}
            </NTag>
          </div>

          <div class="user-page__meta">
            <span>创建：{{ dayjs(row.createdAt).format('YYYY-MM-DD HH:mm:ss') }}</span>
            <span>今日消息：{{ Number(row.usage?.chatRequestCount || 0).toLocaleString() }} 次</span>
            <span>
              LLM：
              <template v-if="row.usage?.llm?.enabled">
                {{ Number(row.usage?.llm?.usedTokens || 0).toLocaleString() }} / {{ Number(row.usage?.llm?.limitTokens || 0).toLocaleString() }}
              </template>
              <template v-else>
                未启用
              </template>
            </span>
            <span>
              Emb：
              <template v-if="row.usage?.embedding?.enabled">
                {{ Number(row.usage?.embedding?.usedTokens || 0).toLocaleString() }} / {{ Number(row.usage?.embedding?.limitTokens || 0).toLocaleString() }}
              </template>
              <template v-else>
                未启用
              </template>
            </span>
          </div>

          <NButton type="primary" ghost block @click="handleOrgTag(row)">分配组织标签</NButton>
        </article>

        <div v-if="!data.length && !loading" class="user-page__empty">暂无用户数据</div>

        <NPagination
          v-if="Number(mobilePagination.itemCount || 0) > Number(mobilePagination.pageSize || 10)"
          :page="Number(mobilePagination.page || 1)"
          :page-size="Number(mobilePagination.pageSize || 10)"
          :item-count="Number(mobilePagination.itemCount || 0)"
          :page-slot="5"
          @update:page="mobilePagination.onUpdatePage"
          @update:page-size="mobilePagination.onUpdatePageSize"
        />
      </div>

      <NDataTable
        v-else
        :columns="columns"
        :data="data"
        size="small"
        :flex-height="!appStore.isMobile"
        :scroll-x="1400"
        :loading="loading"
        remote
        :row-key="row => row.userId"
        :pagination="mobilePagination"
        class="sm:h-full"
      />
    </NCard>

    <OrgTagSettingDialog v-model:visible="visible" :row-data="editingData!" @submitted="getData" />
  </div>
</template>

<style scoped lang="scss">
.admin-page-shell {
  color: var(--app-text-primary);
}

.user-page {
  &__mobile-list {
    display: flex;
    flex-direction: column;
    gap: 12px;
  }

  &__card {
    display: flex;
    flex-direction: column;
    gap: 12px;
    border: 1px solid var(--app-border);
    border-radius: 18px;
    background: var(--app-control-bg);
    padding: 16px;
  }

  &__card-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;

    strong {
      font-size: 15px;
      font-weight: 700;
    }
  }

  &__tag-row {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
  }

  &__meta {
    display: grid;
    gap: 6px;
    color: var(--app-text-secondary);
    font-size: 12px;
    line-height: 1.6;
  }

  &__empty {
    border: 1px dashed var(--app-border);
    border-radius: 18px;
    padding: 24px 16px;
    color: var(--app-text-secondary);
    text-align: center;
  }
}
</style>
