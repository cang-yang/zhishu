<script setup lang="ts">
import { computed, onBeforeUnmount, watch } from 'vue';
import { useChatStore } from '@/store/modules/chat';
import FilePreview from '@/components/custom/file-preview.vue';
import WorkspaceWindow from '@/components/custom/workspace-window.vue';

defineOptions({ name: 'ReferencePreviewModal' });

const chatStore = useChatStore();
const { referencePreview, referencePreviewVisible } = storeToRefs(chatStore);

const previewTitle = computed(() => referencePreview.value?.fileName || '引用文档预览');
const previewMeta = computed(() => {
  const payload = referencePreview.value;
  if (!payload) return '';

  return [
    payload.retrievalLabel || '',
    payload.pageNumber ? `第 ${payload.pageNumber} 页` : '',
    typeof payload.score === 'number' ? `相关度 ${payload.score.toFixed(3)}` : ''
  ]
    .filter(Boolean)
    .join(' · ');
});

function handleClose() {
  chatStore.closeReferencePreview();
}

function handleKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && referencePreviewVisible.value) {
    handleClose();
  }
}

const previewWindowWidth = computed(() => 'min(1180px, calc(100vw - 48px))');
const previewWindowHeight = computed(() => 'min(86vh, 860px)');

let previousBodyOverflow = '';

watch(
  referencePreviewVisible,
  visible => {
    if (typeof document === 'undefined') return;

    if (visible) {
      previousBodyOverflow = document.body.style.overflow;
      document.body.style.overflow = 'hidden';
    } else {
      document.body.style.overflow = previousBodyOverflow;
    }
  },
  { immediate: true }
);

if (typeof window !== 'undefined') {
  window.addEventListener('keydown', handleKeydown);
}

onBeforeUnmount(() => {
  if (typeof window !== 'undefined') {
    window.removeEventListener('keydown', handleKeydown);
  }

  if (typeof document !== 'undefined') {
    document.body.style.overflow = previousBodyOverflow;
  }
});
</script>

<template>
  <WorkspaceWindow
    :visible="referencePreviewVisible && Boolean(referencePreview)"
    badge="引用预览"
    :title="previewTitle"
    :meta="previewMeta"
    :width="previewWindowWidth"
    :height="previewWindowHeight"
    @close="handleClose"
  >
    <FilePreview
      v-if="referencePreview"
      :file-name="referencePreview.fileName"
      :file-md5="referencePreview.fileMd5 || undefined"
      :page-number="referencePreview.pageNumber || undefined"
      :anchor-text="referencePreview.anchorText || undefined"
      :retrieval-mode="referencePreview.retrievalMode"
      :retrieval-label="referencePreview.retrievalLabel || undefined"
      :retrieval-query="referencePreview.retrievalQuery || undefined"
      :evidence-snippet="referencePreview.evidenceSnippet || undefined"
      :matched-chunk-text="referencePreview.matchedChunkText || undefined"
      :score="referencePreview.score ?? null"
      :chunk-id="referencePreview.chunkId ?? null"
      :visible="referencePreviewVisible"
      @close="handleClose"
    />
  </WorkspaceWindow>
</template>

<style scoped lang="scss">
:deep(.workspace-window__body) {
  display: flex;
  min-height: 0;
}

:deep(.workspace-window__body > .file-preview-container) {
  flex: 1;
  min-height: 0;
}

@media (max-width: 768px) {
  :deep(.workspace-window__body) {
    padding: 10px;
  }
}
</style>
