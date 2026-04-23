<script setup lang="ts">
import type { UploadFileInfo } from 'naive-ui';
import { NButton, NDropdown, NProgress, NSpin, NTag, NUpload } from 'naive-ui';
import { uploadAccept } from '@/constants/common';
import { fakePaginationRequest } from '@/service/request';
import { UploadStatus } from '@/enum';
import { useTable } from '@/hooks/common/table';
import FloatingMenu from '@/components/custom/FloatingMenu.vue';
import SvgIcon from '@/components/custom/svg-icon.vue';
import FilePreview from '@/components/custom/file-preview.vue';
import WorkspaceWindow from '@/components/custom/workspace-window.vue';
import { useAuthStore } from '@/store/modules/auth';
import { useWorkspaceStore } from '@/store/modules/workspace';
import { useKnowledgeBaseStore } from '@/store/modules/knowledge-base';
import { fileSize, getFileExt } from '@/utils/common';
import UploadDialog from './modules/upload-dialog.vue';

defineOptions({
  name: 'KnowledgeBaseView'
});

const authStore = useAuthStore();
const workspaceStore = useWorkspaceStore();
const store = useKnowledgeBaseStore();
const { tasks } = storeToRefs(store);
const { knowledgeCategory } = storeToRefs(workspaceStore);

const keyword = ref('');
const uploadVisible = ref(false);
const previewVisible = ref(false);
const previewFileName = ref('');
const previewFileMd5 = ref('');
const previewSearchText = ref('');
const previewScore = ref<number | null>(null);
const previewChunkId = ref<number | null>(null);
const fileMenuVisible = ref(false);
const fileMenuX = ref(0);
const fileMenuY = ref(0);
const fileMenuTarget = ref<Api.KnowledgeBase.UploadTask | null>(null);
const isMobileViewport = ref(false);
const mobileFileActionVisible = ref(false);
const ignoreNextFileClick = ref(false);
const mobileFileMenuPosition = ref({ x: 0, y: 0 });
let fileLongPressTimer: ReturnType<typeof setTimeout> | null = null;

function apiFn() {
  return fakePaginationRequest<Api.KnowledgeBase.List>({ url: '/documents/accessible' });
}

const { data, getData, loading } = useTable({
  apiFn,
  immediate: false,
  columns: () => []
});

function getFileCategory(fileName: string) {
  const ext = String(getFileExt(fileName) || '').toLowerCase();
  if (['xls', 'xlsx', 'csv'].includes(ext)) return 'sheet';
  return 'document';
}

function renderIcon(fileName: string) {
  const ext = String(getFileExt(fileName) || '');
  if (ext) {
    if (uploadAccept.split(',').includes(`.${ext}`)) return ext;
    return 'dflt';
  }
  return 'dflt';
}

function handleFilePreview(
  fileName: string,
  fileMd5: string,
  options?: { searchText?: string; score?: number | null; chunkId?: number | null }
) {
  if (ignoreNextFileClick.value) {
    ignoreNextFileClick.value = false;
    return;
  }

  previewFileName.value = fileName;
  previewFileMd5.value = fileMd5;
  previewSearchText.value = options?.searchText || '';
  previewScore.value = options?.score ?? null;
  previewChunkId.value = options?.chunkId ?? null;
  previewVisible.value = true;
}

function closeFilePreview() {
  previewVisible.value = false;
  previewFileName.value = '';
  previewFileMd5.value = '';
  previewSearchText.value = '';
  previewScore.value = null;
  previewChunkId.value = null;
}

function syncTaskFromServer(target: Api.KnowledgeBase.UploadTask, source: Api.KnowledgeBase.UploadTask) {
  Object.assign(target, {
    fileName: source.fileName,
    totalSize: source.totalSize,
    status: source.status,
    userId: source.userId,
    orgTag: source.orgTag,
    orgTagName: source.orgTagName,
    public: source.public,
    isPublic: source.isPublic,
    createdAt: source.createdAt,
    mergedAt: source.mergedAt,
    estimatedEmbeddingTokens: source.estimatedEmbeddingTokens,
    estimatedChunkCount: source.estimatedChunkCount,
    actualEmbeddingTokens: source.actualEmbeddingTokens,
    actualChunkCount: source.actualChunkCount
  });
}

function getTaskIdentity(task: Pick<Api.KnowledgeBase.UploadTask, 'fileMd5' | 'userId'>) {
  return `${task.fileMd5}::${task.userId || ''}`;
}

async function getList() {
  await getData();

  if (data.value.length === 0) {
    tasks.value = [];
    return;
  }

  data.value.forEach(item => {
    const index = tasks.value.findIndex(task => getTaskIdentity(task) === getTaskIdentity(item));
    if (index !== -1) {
      syncTaskFromServer(tasks.value[index], item);
    } else if (item.status === UploadStatus.Completed) {
      tasks.value.push(item);
    } else if (!tasks.value.some(task => getTaskIdentity(task) === getTaskIdentity(item))) {
      item.status = UploadStatus.Break;
      tasks.value.push(item);
    }
  });
}

async function handleDelete(row: Api.KnowledgeBase.UploadTask) {
  const index = tasks.value.findIndex(task => getTaskIdentity(task) === getTaskIdentity(row));
  if (index === -1) return;

  tasks.value[index].requestIds?.forEach(requestId => {
    request.cancelRequest(requestId);
  });

  if (tasks.value[index].uploadedChunks && tasks.value[index].uploadedChunks.length === 0) {
    tasks.value.splice(index, 1);
    return;
  }

  const { error } = await request({
    url: `/documents/${row.fileMd5}`,
    method: 'DELETE',
    params: { ownerUserId: row.userId }
  });
  if (!error) {
    tasks.value.splice(index, 1);
    window.$message?.success('删除成功');
    await getData();
  }
}

async function downloadFile(row: Api.KnowledgeBase.UploadTask) {
  const { error, data: payload } = await request<{ fileName: string; downloadUrl: string }>({
    url: '/documents/download-by-md5',
    params: { fileMd5: row.fileMd5 }
  });

  if (error || !payload) {
    window.$message?.error('下载失败');
    return;
  }

  const link = document.createElement('a');
  link.href = payload.downloadUrl;
  link.download = payload.fileName;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
}

function handleUpload() {
  uploadVisible.value = true;
}

function renderStatusText(row: Api.KnowledgeBase.UploadTask) {
  if (row.status === UploadStatus.Completed) return '已完成';
  if (row.status === UploadStatus.Break) return '上传中断';
  return '索引中';
}

function resumeUpload(row: Api.KnowledgeBase.UploadTask) {
  row.status = UploadStatus.Pending;
  store.startUpload();
}

async function onBeforeUpload(options: { file: UploadFileInfo; fileList: UploadFileInfo[] }, row: Api.KnowledgeBase.UploadTask) {
  const md5 = await calculateMD5(options.file.file!);
  if (md5 !== row.fileMd5) {
    window.$message?.error('两次上传的文件不一致');
    return false;
  }

  loading.value = true;
  const { error, data: progress } = await request<Api.KnowledgeBase.Progress>({
    url: '/upload/status',
    params: { file_md5: row.fileMd5 }
  });

  if (!error) {
    row.file = options.file.file!;
    row.status = UploadStatus.Pending;
    row.progress = progress.progress;
    row.uploadedChunks = progress.uploaded;
    store.startUpload();
    loading.value = false;
    return true;
  }

  loading.value = false;
  return false;
}

function openFileMenu(event: MouseEvent, row: Api.KnowledgeBase.UploadTask) {
  event.preventDefault();
  fileMenuVisible.value = true;
  fileMenuTarget.value = row;
  fileMenuX.value = event.clientX;
  fileMenuY.value = event.clientY;
}

function hideFileMenu() {
  fileMenuVisible.value = false;
}

function closeFileMenus() {
  fileMenuVisible.value = false;
  mobileFileActionVisible.value = false;
}

function syncMobileViewport() {
  if (typeof window === 'undefined') return;
  isMobileViewport.value = window.innerWidth <= 768;
}

function startFileLongPress(event: TouchEvent, row: Api.KnowledgeBase.UploadTask) {
  if (!isMobileViewport.value) return;

  const touch = event.touches?.[0];
  if (!touch) return;

  clearFileLongPress();
  mobileFileMenuPosition.value = {
    x: touch.clientX,
    y: touch.clientY
  };
  fileLongPressTimer = setTimeout(() => {
    fileMenuTarget.value = row;
    ignoreNextFileClick.value = true;
    mobileFileActionVisible.value = true;
    navigator.vibrate?.(10);
  }, 500);
}

function clearFileLongPress() {
  if (!fileLongPressTimer) return;
  clearTimeout(fileLongPressTimer);
  fileLongPressTimer = null;
}

async function handleFileMenuSelect(key: string) {
  const row = fileMenuTarget.value;
  if (!row) return;

  if (key === 'download') {
    await downloadFile(row);
    closeFileMenus();
    return;
  }

  if (key === 'preview') {
    handleFilePreview(row.fileName, row.fileMd5);
    closeFileMenus();
    return;
  }

  if (key === 'delete') {
    await handleDelete(row);
  }

  closeFileMenus();
}

const fileMenuOptions = computed(() => {
  return [
    { key: 'preview', label: '预览' },
    { key: 'download', label: '下载' },
    { key: 'delete', label: '删除', props: { style: 'color:#f87171;' } }
  ];
});

const mobileFileActionOptions = computed(() => {
  return [
    { key: 'preview', label: '预览', icon: 'solar:eye-linear' },
    { key: 'download', label: '下载', icon: 'solar:download-linear' },
    { key: 'delete', label: '删除', icon: 'solar:trash-bin-minimalistic-linear', danger: true }
  ];
});

const filteredTasks = computed(() => {
  const normalizedKeyword = keyword.value.trim().toLowerCase();
  return tasks.value.filter(item => {
    const categoryMatched = knowledgeCategory.value === 'all' || getFileCategory(item.fileName) === knowledgeCategory.value;
    const keywordMatched = !normalizedKeyword || String(item.fileName || '').toLowerCase().includes(normalizedKeyword);
    return categoryMatched && keywordMatched;
  });
});

const totalSizeLabel = computed(() => {
  const total = filteredTasks.value.reduce((sum, item) => sum + Number(item.totalSize || 0), 0);
  return fileSize(total || 0);
});

const totalTokensLabel = computed(() => {
  const total = filteredTasks.value.reduce(
    (sum, item) => sum + Number(item.actualEmbeddingTokens || item.estimatedEmbeddingTokens || 0),
    0
  );
  return total.toLocaleString();
});

onMounted(async () => {
  syncMobileViewport();
  window.addEventListener('resize', syncMobileViewport);
  await getList();
});

onActivated(async () => {
  await getList();
});

onBeforeUnmount(() => {
  clearFileLongPress();
  if (typeof window !== 'undefined') {
    window.removeEventListener('resize', syncMobileViewport);
  }
});
</script>

<template>
  <div class="knowledge-base-page">
    <section class="knowledge-base-page__panel">
      <div class="knowledge-base-page__header">
        <div>
          <h2>知识库</h2>
          <p>文件以列表形式统一管理，支持预览、上传、续传与右键操作。</p>
        </div>
        <div class="knowledge-base-page__header-actions">
          <NButton type="primary" @click="handleUpload">＋ 添加</NButton>
        </div>
      </div>

      <div class="knowledge-base-page__toolbar">
        <input v-model="keyword" type="text" class="knowledge-base-page__search" placeholder="搜索文件名..." />
        <span class="knowledge-base-page__summary">共 {{ filteredTasks.length }} 个文件 · 总计 {{ totalSizeLabel }} · 已消耗 {{ totalTokensLabel }} Token</span>
      </div>

      <div class="knowledge-base-page__category-row">
        <button
          v-for="item in [
            { key: 'all', label: '全部' },
            { key: 'document', label: '文档' },
            { key: 'sheet', label: '表格' }
          ]"
          :key="item.key"
          type="button"
          class="knowledge-base-page__category-chip"
          :class="{ 'is-active': knowledgeCategory === item.key }"
          @click="workspaceStore.setKnowledgeCategory(item.key as any)"
        >
          {{ item.label }}
        </button>
      </div>

      <div class="knowledge-base-page__table">
        <div class="knowledge-base-page__thead">
          <span>文件名</span>
          <span>大小</span>
          <span>分块</span>
          <span>Token</span>
          <span>组织</span>
          <span>公开</span>
        </div>

        <NSpin :show="loading" class="min-h-0">
          <div v-if="filteredTasks.length" class="knowledge-base-page__tbody">
            <div
              v-for="row in filteredTasks"
              :key="getTaskIdentity(row)"
              class="knowledge-base-page__row"
              @click="handleFilePreview(row.fileName, row.fileMd5)"
              @contextmenu="openFileMenu($event, row)"
              @touchstart.passive="startFileLongPress($event, row)"
              @touchend="clearFileLongPress"
              @touchmove="clearFileLongPress"
              @touchcancel="clearFileLongPress"
            >
              <span class="knowledge-base-page__name-cell">
                <SvgIcon :local-icon="renderIcon(row.fileName)" class="text-18px" />
                <span>
                  <strong>{{ row.fileName }}</strong>
                  <small>{{ renderStatusText(row) }}</small>
                </span>
              </span>
              <span>{{ fileSize(row.totalSize) }}</span>
              <span>
                <template v-if="row.status === UploadStatus.Completed">
                  {{ Number(row.actualChunkCount || row.estimatedChunkCount || 0).toLocaleString() }}/{{ Number(row.actualChunkCount || row.estimatedChunkCount || 0).toLocaleString() }}
                </template>
                <template v-else>
                  索引中...
                </template>
              </span>
              <span>
                <template v-if="row.actualEmbeddingTokens || row.estimatedEmbeddingTokens">
                  {{ Number(row.actualEmbeddingTokens || row.estimatedEmbeddingTokens || 0).toLocaleString() }}
                </template>
                <template v-else>
                  -
                </template>
              </span>
              <span>{{ row.orgTagName || row.orgTag || '-' }}</span>
              <span>
                <NTag size="small" :type="row.public || row.isPublic ? 'success' : 'warning'">
                  {{ row.public || row.isPublic ? '✓' : '✗' }}
                </NTag>
              </span>

              <div class="knowledge-base-page__mobile-meta">
                <span>{{ fileSize(row.totalSize) }}</span>
                <span>
                  <template v-if="row.status === UploadStatus.Completed">
                    {{ Number(row.actualChunkCount || row.estimatedChunkCount || 0).toLocaleString() }}/{{ Number(row.actualChunkCount || row.estimatedChunkCount || 0).toLocaleString() }}
                  </template>
                  <template v-else>
                    索引中...
                  </template>
                </span>
                <span>Token: {{ Number(row.actualEmbeddingTokens || row.estimatedEmbeddingTokens || 0).toLocaleString() || '-' }}</span>
                <span>{{ row.orgTagName || row.orgTag || '-' }} · {{ renderStatusText(row) }}</span>
              </div>

              <div v-if="row.status !== UploadStatus.Completed" class="knowledge-base-page__progress-row">
                <span>上传 / 索引进度</span>
                <NProgress type="line" :percentage="Number(row.progress || 0)" processing :show-indicator="false" />
              </div>

              <div v-if="row.status === UploadStatus.Break" class="knowledge-base-page__resume-row">
               <template v-if="row.file">
                 <NButton type="primary" size="small" ghost @click.stop="resumeUpload(row)">续传</NButton>
               </template>
               <template v-else>
                  <div @click.stop>
                    <NUpload
                      :show-file-list="false"
                      :default-upload="false"
                      :accept="uploadAccept"
                      @before-upload="options => onBeforeUpload(options, row)"
                    >
                      <NButton type="primary" size="small" ghost>续传</NButton>
                    </NUpload>
                  </div>
                </template>
              </div>
            </div>
          </div>

          <div v-else class="knowledge-base-page__empty">当前分类下暂无文件。</div>
        </NSpin>
      </div>
    </section>

    <NDropdown
      v-if="!isMobileViewport"
      :show="fileMenuVisible"
      trigger="manual"
      placement="bottom-start"
      :x="fileMenuX"
      :y="fileMenuY"
      :options="fileMenuOptions"
      @clickoutside="hideFileMenu"
      @select="handleFileMenuSelect"
    />

    <FloatingMenu
      v-if="isMobileViewport"
      :visible="mobileFileActionVisible"
      :title="fileMenuTarget?.fileName || '文件操作'"
      :items="mobileFileActionOptions"
      :position="mobileFileMenuPosition"
      @close="mobileFileActionVisible = false"
      @select="handleFileMenuSelect"
    />

    <UploadDialog v-model:visible="uploadVisible" />

    <WorkspaceWindow
      :visible="previewVisible"
      badge="文档预览"
      :title="previewFileName || '文件预览'"
      :meta="previewSearchText ? '检索命中预览' : '文档内容预览'"
      width="min(1180px, calc(100vw - 48px))"
      height="min(86vh, 860px)"
      @close="closeFilePreview"
    >
      <FilePreview
        :file-name="previewFileName"
        :file-md5="previewFileMd5"
        :search-text="previewSearchText || undefined"
        :score="previewScore"
        :chunk-id="previewChunkId"
        :visible="previewVisible"
        @close="closeFilePreview"
      />
    </WorkspaceWindow>

  </div>
</template>

<style scoped lang="scss">
.knowledge-base-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  overflow: hidden;

  &__panel {
    display: flex;
    height: 100%;
    min-height: 0;
    overflow: hidden;
    flex-direction: column;
    gap: 18px;
    border: 1px solid var(--app-border);
    border-radius: 28px;
    background: var(--app-surface-bg);
    padding: 24px;
    color: var(--app-text-primary);
    box-shadow: var(--app-surface-shadow);
  }

  &__header {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 16px;

    h2 {
      margin: 0 0 8px;
      font-size: 28px;
      font-weight: 700;
    }

    p {
      margin: 0;
      color: var(--app-text-secondary);
      font-size: 14px;
    }
  }

  &__header-actions,
  &__toolbar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    flex-wrap: wrap;
  }

  &__search {
    width: min(100%, 320px);
    border: 1px solid var(--app-border);
    border-radius: 16px;
    background: var(--app-control-bg);
    padding: 12px 14px;
    color: inherit;
    outline: none;
  }

  &__summary {
    color: var(--app-text-secondary);
    font-size: 13px;
  }

  &__category-row {
    display: flex;
    gap: 10px;
    overflow-x: auto;
    padding-bottom: 2px;
  }

  &__category-chip {
    flex-shrink: 0;
    border: 1px solid var(--app-border);
    border-radius: 999px;
    background: var(--app-control-bg);
    padding: 8px 14px;
    color: var(--app-text-secondary);
    font-size: 13px;
    font-weight: 700;

    &.is-active {
      border-color: rgba(96, 165, 250, 0.4);
      background: rgba(79, 110, 247, 0.16);
      color: rgb(var(--primary-color));
    }
  }

  &__table {
    display: flex;
    flex: 1;
    min-height: 0;
    overflow: hidden;
    flex-direction: column;
    gap: 10px;
  }

  &__thead,
  &__row {
    display: grid;
    grid-template-columns: minmax(280px, 2.6fr) 0.8fr 0.8fr 0.9fr 0.9fr 0.6fr;
    gap: 14px;
  }

  &__thead {
    padding: 0 16px;
    color: var(--app-text-secondary);
    font-size: 12px;
    font-weight: 700;
    letter-spacing: 0.08em;
  }

  &__tbody {
    display: flex;
    overflow: auto;
    flex-direction: column;
    gap: 10px;
  }

  &__row {
    position: relative;
    align-items: start;
    border: 1px solid var(--app-border);
    border-radius: 20px;
    background: var(--app-control-bg);
    padding: 16px;
    color: inherit;
    text-align: left;
    transition:
      border-color 0.2s ease,
      transform 0.2s ease,
      background 0.2s ease;

    &:hover {
      transform: translateY(-1px);
      border-color: rgba(96, 165, 250, 0.26);
      background: var(--app-hover-bg);
    }
  }

  &__name-cell {
    display: flex;
    align-items: flex-start;
    gap: 12px;

    strong,
    small {
      display: block;
    }

    strong {
      font-size: 15px;
      font-weight: 700;
    }

    small {
      margin-top: 6px;
      color: var(--app-text-secondary);
      font-size: 12px;
    }
  }

  &__progress-row,
  &__resume-row {
    grid-column: 1 / -1;
  }

  &__mobile-meta {
    display: none;
  }

  &__progress-row {
    display: flex;
    flex-direction: column;
    gap: 8px;
    color: var(--app-text-secondary);
    font-size: 12px;
  }

  &__empty {
    border: 1px dashed var(--app-border);
    border-radius: 20px;
    padding: 24px;
    color: var(--app-text-secondary);
    text-align: center;
  }

}

:deep(.workspace-window__body) {
  display: flex;
  min-height: 0;
}

:deep(.workspace-window__body > .file-preview-container) {
  flex: 1;
  min-height: 0;
}

@media (max-width: 1024px) {
  .knowledge-base-page {
    &__thead {
      display: none;
    }

    &__row {
      grid-template-columns: 1fr 1fr;
    }
  }
}

@media (max-width: 768px) {
  .knowledge-base-page {
    overflow-y: auto;
    overscroll-behavior-y: contain;
    -webkit-overflow-scrolling: touch;

    &__panel {
      height: auto;
      min-height: max-content;
      overflow: visible;
      padding: 18px 14px;
      border-radius: 20px;
    }

    &__table {
      overflow: visible;
    }

    &__tbody {
      overflow: visible;
    }

    &__header {
      align-items: stretch;

      h2 {
        font-size: 22px;
      }

      p {
        display: none;
      }
    }

    &__header-actions,
    &__toolbar {
      align-items: stretch;
      flex-direction: column;
    }

    &__search {
      width: 100%;
    }

    &__summary {
      width: 100%;
      font-size: 12px;
    }

    &__thead {
      display: none;
    }

    &__row {
      grid-template-columns: 1fr;
      gap: 10px;
      padding: 16px;
      user-select: none;
      -webkit-user-select: none;

      > span:not(.knowledge-base-page__name-cell) {
        display: none;
      }
    }

    &__name-cell {
      strong {
        font-size: 14px;
      }
    }

    &__mobile-meta {
      display: grid;
      gap: 6px;
      color: var(--app-text-secondary);
      font-size: 12px;
      line-height: 1.6;
    }

  }

}
</style>
