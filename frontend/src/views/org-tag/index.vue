<script setup lang="tsx">
import { onActivated, onBeforeUnmount, onMounted, ref } from 'vue';
import { NButton, NPopconfirm } from 'naive-ui';
import { useTable, useTableOperate } from '@/hooks/common/table';
import { fetchGetOrgTagList } from '@/service/api';
import { useAppStore } from '@/store/modules/app';
import OrgTagOperateDialog from './modules/org-tag-operate-dialog.vue';

const appStore = useAppStore();
const isMobileViewport = ref(false);

function syncMobileViewport() {
  if (typeof window === 'undefined') return;
  isMobileViewport.value = window.innerWidth <= 768;
}

const { columns, columnChecks, data, loading, getData } = useTable({
  apiFn: fetchGetOrgTagList,
  immediate: false,
  columns: () => [
    {
      key: 'name',
      title: '标签名称',
      width: 300,
      ellipsis: {
        tooltip: true
      }
    },
    {
      key: 'description',
      title: '描述',
      minWidth: 200,
      ellipsis: {
        tooltip: true
      }
    },
    {
      key: 'uploadMaxSizeMb',
      title: '非Admin上传上限',
      width: 160,
      render: row => (row.uploadMaxSizeMb ? `${row.uploadMaxSizeMb} MB` : '不限制')
    },
    {
      key: 'operate',
      title: '操作',
      width: 240,
      render: row => (
        <div class="flex gap-2">
          <NButton type="success" ghost size="small" onClick={() => addChild(row)}>
            新增下级
          </NButton>
          <NButton type="primary" ghost size="small" onClick={() => edit(row)}>
            编辑
          </NButton>
          <NPopconfirm onPositiveClick={() => handleDelete(row.tagId!)}>
            {{
              default: () => '确认删除当前标签吗？',
              trigger: () => (
                <NButton type="error" ghost size="small">
                  删除
                </NButton>
              )
            }}
          </NPopconfirm>
        </div>
      )
    }
  ]
});

const {
  dialogVisible,
  operateType,
  editingData,
  handleAdd,
  handleAddChild,
  handleEdit,
  onDeleted
  // closeDrawer
} = useTableOperate<Api.OrgTag.Item>(getData);

function addChild(row: Api.OrgTag.Item) {
  handleAddChild(row);
}

/** the editing row data */
function edit(row: Api.OrgTag.Item) {
  handleEdit(row);
}

async function handleDelete(tagId: string) {
  const { error } = await request({ url: `/admin/org-tags/${tagId}`, method: 'DELETE' });
  if (!error) {
    onDeleted();
  }
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
  <div class="admin-page-shell flex-col-stretch gap-16px overflow-hidden <sm:overflow-auto">
    <NCard title="组织标签" :bordered="false" size="small" class="sm:flex-1-hidden card-wrapper">
      <template #header-extra>
        <TableHeaderOperation v-model:columns="columnChecks" :loading="loading" @add="handleAdd" @refresh="getData" />
      </template>

      <div v-if="isMobileViewport" class="org-tag-page__mobile-list">
        <article v-for="item in data" :key="item.tagId" class="org-tag-page__card">
          <strong>{{ item.name }}</strong>
          <p>{{ item.description || '暂无描述' }}</p>
          <span>非 Admin 上传：{{ item.uploadMaxSizeMb ? `${item.uploadMaxSizeMb} MB` : '不限制' }}</span>
          <div class="org-tag-page__actions">
            <NButton type="success" ghost size="small" @click="addChild(item)">新增下级</NButton>
            <NButton type="primary" ghost size="small" @click="edit(item)">编辑</NButton>
            <NPopconfirm @positive-click="handleDelete(item.tagId!)">
              <template #trigger>
                <NButton type="error" ghost size="small">删除</NButton>
              </template>
              确认删除当前标签吗？
            </NPopconfirm>
          </div>
        </article>

        <div v-if="!data.length && !loading" class="org-tag-page__empty">暂无组织标签</div>
      </div>

      <NDataTable
        v-else
        remote
        :columns="columns"
        :data="data"
        size="small"
        :flex-height="!appStore.isMobile"
        :scroll-x="962"
        :loading="loading"
        :pagination="false"
        :row-key="item => item.tagId"
        class="sm:h-full"
      />
      <OrgTagOperateDialog
        v-model:visible="dialogVisible"
        :operate-type="operateType"
        :row-data="editingData!"
        :data="data"
        @submitted="getData"
      />
    </NCard>
  </div>
</template>

<style scoped>
.admin-page-shell {
  color: var(--app-text-primary);
}

.org-tag-page__mobile-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.org-tag-page__card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  border: 1px solid var(--app-border);
  border-radius: 18px;
  background: var(--app-control-bg);
  padding: 16px;
}

.org-tag-page__card strong {
  font-size: 15px;
}

.org-tag-page__card p,
.org-tag-page__card span {
  margin: 0;
  color: var(--app-text-secondary);
  font-size: 12px;
  line-height: 1.7;
}

.org-tag-page__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.org-tag-page__empty {
  border: 1px dashed var(--app-border);
  border-radius: 18px;
  padding: 24px 16px;
  color: var(--app-text-secondary);
  text-align: center;
}
</style>
