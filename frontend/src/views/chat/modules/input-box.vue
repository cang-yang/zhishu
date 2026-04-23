<script setup lang="ts">
import { onBeforeUnmount, onMounted } from 'vue';
import { useChatConnectionStore } from '@/store/modules/chat/connection';
import { useChatMessageStore } from '@/store/modules/chat/message';
import UploadDialog from '@/views/knowledge-base/modules/upload-dialog.vue';

const chatStore = useChatMessageStore();
const connectionStore = useChatConnectionStore();
const { currentInput, isRateLimited, rateLimitRemainingSeconds, isSending } = storeToRefs(chatStore);
const { connectionStatus } = storeToRefs(connectionStore);

const composerHeight = ref(152);
const minComposerHeight = 96;
const isMobileViewport = ref(false);
const composerInsetBottom = ref(0);
const uploadDialogVisible = ref(false);

const shortcutHintVisible = computed(() => !isMobileViewport.value);

function syncMobileViewport() {
  if (typeof window === 'undefined') return;
  isMobileViewport.value = window.innerWidth <= 768;
}

function syncVisualViewportOffset() {
  if (typeof window === 'undefined') return;

  const viewport = window.visualViewport;
  if (!viewport || !isMobileViewport.value) {
    composerInsetBottom.value = 0;
    return;
  }

  const offset = Math.max(0, window.innerHeight - viewport.height - viewport.offsetTop);
  composerInsetBottom.value = offset;
}

function getMaxComposerHeight() {
  return Math.min(420, Math.floor(window.innerHeight * 0.5));
}

let dragStartY = 0;
let dragStartHeight = 0;

const sendDisabled = computed(() => {
  if (isSending.value) return false;
  if (isRateLimited.value) return true;
  return !currentInput.value.trim() || connectionStatus.value !== 'OPEN';
});

const cooldownText = computed(() => {
  if (!isRateLimited.value) return '';
  return `${rateLimitRemainingSeconds.value} 秒后可重新发送`;
});

const handleSend = async () => {
  if (isRateLimited.value) {
    window.$message?.warning(`当前发送受限，${cooldownText.value}`);
    return;
  }

  if (isSending.value) {
    await chatStore.stopMessage();
    return;
  }

  await chatStore.sendMessage(currentInput.value);
};

function handleOpenUploadDialog() {
  uploadDialogVisible.value = true;
}

function stopResize() {
  window.removeEventListener('mousemove', handleResize);
  window.removeEventListener('mouseup', stopResize);
}

function handleResize(event: MouseEvent) {
  const nextHeight = dragStartHeight + (dragStartY - event.clientY);
  composerHeight.value = Math.min(getMaxComposerHeight(), Math.max(minComposerHeight, nextHeight));
}

function startResize(event: MouseEvent) {
  if (isMobileViewport.value) return;
  dragStartY = event.clientY;
  dragStartHeight = composerHeight.value;
  window.addEventListener('mousemove', handleResize);
  window.addEventListener('mouseup', stopResize);
}

const inputRef = ref<HTMLTextAreaElement>();

const insertNewline = () => {
  const textarea = inputRef.value;
  if (!textarea) return;

  const start = textarea.selectionStart;
  const end = textarea.selectionEnd;

  currentInput.value = `${currentInput.value.substring(0, start)}\n${currentInput.value.substring(end)}`;

  nextTick(() => {
    textarea.selectionStart = start + 1;
    textarea.selectionEnd = start + 1;
    textarea.focus();
  });
};

const handShortcut = (e: KeyboardEvent) => {
  if (e.key === 'Enter') {
    e.preventDefault();

    if (!e.shiftKey && !e.ctrlKey) {
      handleSend();
    } else {
      insertNewline();
    }
  }
};

onBeforeUnmount(() => {
  stopResize();

  if (typeof window !== 'undefined') {
    window.removeEventListener('resize', syncMobileViewport);
    window.visualViewport?.removeEventListener('resize', syncVisualViewportOffset);
    window.visualViewport?.removeEventListener('scroll', syncVisualViewportOffset);
  }
});

onMounted(() => {
  syncMobileViewport();
  syncVisualViewportOffset();

  if (typeof window !== 'undefined') {
    window.addEventListener('resize', syncMobileViewport);
    window.visualViewport?.addEventListener('resize', syncVisualViewportOffset);
    window.visualViewport?.addEventListener('scroll', syncVisualViewportOffset);
  }
});
</script>

<template>
  <section class="chat-composer" :class="{ 'chat-composer--mobile': isMobileViewport }" :style="isMobileViewport ? { bottom: `${composerInsetBottom}px` } : undefined">
    <div class="chat-composer__shell">
      <button v-if="!isMobileViewport" type="button" class="chat-composer__drag-handle" @mousedown.prevent="startResize">
        <span class="chat-composer__drag-bar" />
      </button>

      <div class="chat-composer__textarea-shell" :style="{ height: isMobileViewport ? '48px' : `${composerHeight}px` }">
        <textarea
          ref="inputRef"
          v-model="currentInput"
          :placeholder="isMobileViewport ? '从任何想法开始...' : '从任何想法开始... 按 Enter 发送'"
          class="chat-composer__textarea"
          @keydown="handShortcut"
        />
      </div>

      <div class="chat-composer__footer">
        <div class="chat-composer__status-strip">
          <span v-if="isRateLimited" class="chat-composer__status-pill chat-composer__status-pill--warning">
            {{ cooldownText }}
          </span>
          <span v-if="shortcutHintVisible" class="chat-composer__status-pill">Enter 发送 · Shift/Ctrl + Enter 换行</span>
        </div>

        <div class="chat-composer__action-row">
          <button type="button" class="chat-composer__attachment" @click="handleOpenUploadDialog">
            <SvgIcon icon="solar:paperclip-linear" class="text-18px" />
          </button>

          <NButton :disabled="sendDisabled" class="chat-composer__send" type="primary" @click="handleSend">
            <template #icon>
              <icon-material-symbols:stop-rounded v-if="isSending" />
              <icon-guidance:send v-else />
            </template>
            {{ isSending ? '停止' : '发送' }}
          </NButton>
        </div>
      </div>
    </div>

    <UploadDialog v-model:visible="uploadDialogVisible" />
  </section>
</template>

<style scoped lang="scss">
.chat-composer {
  width: 100%;

  &--mobile {
    position: relative;
  }

  &__shell {
    position: relative;
    overflow: hidden;
    border: 1px solid var(--app-border);
    border-radius: 26px;
    background: var(--app-elevated-bg);
    box-shadow: var(--app-surface-shadow);
    padding: 12px 14px 14px;
    backdrop-filter: blur(24px);
    -webkit-backdrop-filter: blur(24px);
  }

  &__drag-handle {
    display: flex;
    width: 100%;
    justify-content: center;
    padding: 4px 0 10px;
    background: transparent;
  }

  &__drag-bar {
    width: 120px;
    height: 5px;
    border-radius: 999px;
    background: rgba(100, 116, 139, 0.72);
  }

  &__textarea-shell {
    display: flex;
    height: 100%;
    border: 1px solid var(--app-border);
    border-radius: 22px;
    background: var(--app-control-bg);
    box-shadow: inset 0 1px 0 var(--app-fill-soft);
    padding: 12px 14px;
  }

  &__textarea {
    width: 100%;
    height: 100%;
    resize: none;
    border: 0;
    background: transparent;
    color: var(--app-text-primary);
    caret-color: rgb(var(--primary-color));
    font-size: 15px;
    line-height: 1.8;
    outline: none;

    &::placeholder {
      color: var(--app-text-secondary);
    }
  }

  &__footer {
    display: flex;
    align-items: flex-end;
    justify-content: space-between;
    gap: 14px;
    margin-top: 14px;
  }

  &__status-strip {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
  }

  &__status-pill {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    border-radius: 999px;
    background: var(--app-soft-bg);
    padding: 8px 12px;
    color: var(--app-text-secondary);
    font-size: 12px;
    font-weight: 600;
  }

  &__status-pill--warning {
    background: rgba(120, 53, 15, 0.48);
    color: #fde68a;
  }

  &__action-row {
    display: flex;
    align-items: center;
    gap: 10px;
  }

  &__attachment {
    display: inline-flex;
    height: 42px;
    width: 42px;
    align-items: center;
    justify-content: center;
    border: 1px solid var(--app-border);
    border-radius: 14px;
    background: var(--app-control-bg);
    color: var(--app-text-primary);
  }

  &__send {
    min-width: 112px;
    height: 42px;
    border-radius: 14px;
    font-size: 14px;
    font-weight: 600;
    box-shadow: 0 12px 28px rgba(59, 110, 246, 0.28);
  }
}

@media (max-width: 768px) {
  .chat-composer {
    &__shell {
      border-radius: 20px;
      padding: 10px 10px 12px;
    }

    &__footer {
      flex-direction: column;
      align-items: stretch;
      gap: 10px;
    }

    &__action-row {
      width: 100%;
      justify-content: space-between;
    }

    &__send {
      width: auto;
      min-width: 120px;
      margin-left: auto;
    }

    &__textarea-shell {
      min-height: 48px;
      padding: 10px 12px;
    }

    &__textarea {
      min-height: 28px;
      line-height: 1.5;
    }
  }
}
</style>
