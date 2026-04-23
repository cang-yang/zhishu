<script setup lang="ts">
import type { NScrollbar } from 'naive-ui';
import { VueMarkdownItProvider } from '@/vendor/vue-markdown-shiki';
import { useAdminChatStore } from '@/store/modules/admin-chat';
import ChatMessage from '../chat/modules/chat-message.vue';

defineOptions({
  name: 'ChatHistory'
});

const scrollbarRef = ref<InstanceType<typeof NScrollbar>>();
const adminChatStore = useAdminChatStore();
const { messages, loadingMessages, activeSessionId, users, userId } = storeToRefs(adminChatStore);

const activeUser = computed(() => users.value.find(item => item.userId === userId.value) || null);

watch(
  () => messages.value.map(item => `${item.messageId || item.seq || ''}:${item.content?.length || 0}`).join('|'),
  () => {
    nextTick(() => scrollToBottom());
  }
);

function scrollToBottom() {
  window.setTimeout(() => {
    scrollbarRef.value?.scrollBy({ top: 999999999999999, behavior: 'auto' });
  }, 80);
}

onMounted(async () => {
  await adminChatStore.ensureInitialized();
});
</script>

<template>
  <div class="history-workspace admin-page-shell admin-page-shell--fixed">
    <section class="history-workspace__shell">
      <section class="history-workspace__panel app-surface-card">
        <NScrollbar ref="scrollbarRef" class="history-workspace__messages">
          <NSpin :show="loadingMessages" class="h-full">
            <VueMarkdownItProvider>
              <div v-if="messages.length" class="history-workspace__message-list">
                <ChatMessage
                  v-for="item in messages"
                  :key="item.messageId || `${item.sessionId || activeSessionId}-${item.seq}`"
                  :msg="item"
                  :session-id="activeSessionId"
                  :user-display-name="activeUser?.username || '用户'"
                  readonly
                />
              </div>

              <div v-else-if="!loadingMessages" class="history-workspace__empty app-surface-card--soft">
                <div class="history-workspace__empty-icon">
                  <SystemLogo class="text-28px" />
                </div>
                <div>
                  <h3 class="history-workspace__empty-title">当前没有可查看的会话消息</h3>
                  <p class="history-workspace__empty-desc">选择左侧用户与话题后，可在这里只读查看完整对话与引用来源。</p>
                </div>
              </div>
            </VueMarkdownItProvider>
          </NSpin>
        </NScrollbar>

        <footer class="history-workspace__readonly-tip">管理员视角只读浏览，不可发送、删除或重生成</footer>
      </section>
    </section>
  </div>
</template>

<style scoped lang="scss">
.history-workspace {
  height: 100%;
  min-height: 0;

  &__shell {
    height: 100%;
    min-height: 0;
    display: flex;
  }

  &__panel {
    min-height: 0;
    width: 100%;
    border: 1px solid var(--color-border);
    background: var(--color-bg-container);
  }

  &__empty-desc,
  &__panel-desc,
  &__session-group-title,
  &__sidebar-meta,
  &__user-option-check,
  &__empty-desc {
    color: var(--color-text-secondary);
    font-size: 12px;
  }

  &__panel {
    display: flex;
    min-height: 0;
    flex-direction: column;
    overflow: hidden;
  }

  &__panel-title,
  &__empty-title {
    margin: 0;
    color: var(--color-text-primary);
    font-weight: 700;
  }

  &__messages {
    min-height: 0;
    flex: 1;
    padding-top: 18px;
  }

  &__message-list {
    display: flex;
    flex-direction: column;
    gap: 18px;
    padding: 20px 0 24px;
  }

  &__readonly-tip {
    margin: 0 24px 24px;
    border: 1px solid var(--color-border);
    border-radius: 18px;
    background: var(--color-bg-card);
    padding: 14px 16px;
    color: var(--color-text-secondary);
    font-size: 13px;
    line-height: 1.7;
  }

  &__empty {
    display: flex;
    min-height: 320px;
    align-items: center;
    justify-content: center;
    gap: 18px;
    margin: 28px;
    padding: 28px;
  }

  &__empty-icon {
    display: flex;
    width: 56px;
    height: 56px;
    align-items: center;
    justify-content: center;
    border-radius: 18px;
    background: rgba(79, 124, 255, 0.1);
    color: rgb(var(--primary-color));
  }

  &__empty-title {
    margin-bottom: 8px;
    font-size: 20px;
  }

  &__empty-tip {
    border: 1px dashed var(--color-border);
    border-radius: 18px;
    padding: 16px;
    color: var(--color-text-secondary);
    line-height: 1.7;
  }
}
</style>
