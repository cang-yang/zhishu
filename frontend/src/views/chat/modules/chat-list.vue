<script setup lang="ts">
import { NScrollbar } from 'naive-ui';
import { useChatStore } from '@/store/modules/chat';
import { useChatMessageStore } from '@/store/modules/chat/message';
import { useChatSessionStore } from '@/store/modules/chat/session';
import { VueMarkdownItProvider } from '@/vendor/vue-markdown-shiki';
import ChatMessage from './chat-message.vue';

defineOptions({
  name: 'ChatList'
});

const chatStore = useChatStore();
const messageStore = useChatMessageStore();
const sessionStore = useChatSessionStore();
const { activeMessages } = storeToRefs(messageStore);
const { activeSessionId } = storeToRefs(sessionStore);
const { loadingMessages } = storeToRefs(chatStore);
const scrollbarRef = ref<InstanceType<typeof NScrollbar>>();
const shouldAutoScroll = ref(true);
const activeMobileActionMessageId = ref<string | null>(null);

function setActiveMobileActionMessageId(id: string | null) {
  activeMobileActionMessageId.value = id;
}

function clearActiveMobileActionMessage() {
  activeMobileActionMessageId.value = null;
}

provide('activeMobileActionMessageId', activeMobileActionMessageId);
provide('setActiveMobileActionMessageId', setActiveMobileActionMessageId);

const messageRenderSignature = computed(() => {
  return activeMessages.value
    .map(
      item =>
        `${item.messageId}:${item.lifecycle || item.status}:${item.updatedAtClient || item.timestamp || ''}:${item.content?.length || 0}`
    )
    .join('|');
});

watch(
  () => messageRenderSignature.value,
  () => {
    if (shouldAutoScroll.value) {
      scrollToBottom();
    }
  }
);

watch(activeSessionId, () => {
  shouldAutoScroll.value = true;
  clearActiveMobileActionMessage();
  nextTick(() => scrollToBottom());
});

function getScrollContainer() {
  const instance = scrollbarRef.value as any;
  return (
    instance?.containerRef ||
    instance?.scrollbarInstRef?.containerRef ||
    instance?.$el?.querySelector('.n-scrollbar-container') ||
    null
  );
}

function handleScroll() {
  clearActiveMobileActionMessage();

  const container = getScrollContainer();
  if (!container) return;

  const distanceToBottom = container.scrollHeight - container.scrollTop - container.clientHeight;
  shouldAutoScroll.value = distanceToBottom < 72;
}

function scrollToBottom() {
  window.setTimeout(() => {
    scrollbarRef.value?.scrollBy({
      top: 999999999999999,
      behavior: 'auto'
    });
  }, 100);
}

function getRetrievalQueryFallback(index: number) {
  for (let i = index - 1; i >= 0; i -= 1) {
    const candidate = activeMessages.value[i];
    if (candidate?.role === 'user') {
      return candidate.content || '';
    }
  }
  return '';
}

onMounted(() => {
  chatStore.scrollToBottom = scrollToBottom;
});
</script>

<template>
  <Suspense>
    <div class="chat-list" @click="clearActiveMobileActionMessage">
      <NScrollbar ref="scrollbarRef" class="chat-list__scroll" @scroll="handleScroll">
        <div class="chat-list__inner">
          <NSpin :show="loadingMessages">
            <VueMarkdownItProvider>
              <div v-if="activeMessages.length" class="chat-list__stream">
                <ChatMessage
                  v-for="(item, index) in activeMessages"
                  :key="item.messageId"
                  :msg="item"
                  :session-id="activeSessionId"
                  :retrieval-query-fallback="getRetrievalQueryFallback(index)"
                />
              </div>
              <div v-else-if="!loadingMessages" class="chat-list__empty">
                <div class="chat-list__empty-badge">智枢 AI</div>
                <h3 class="chat-list__empty-title">这里是智枢 AI。从一句话开始，把目标说清楚就行</h3>
                <p class="chat-list__empty-description">
                  首页即可直接发消息。发送后会自动创建新会话，并切换到会话列表视图继续对话。
                </p>
              </div>
            </VueMarkdownItProvider>
          </NSpin>
        </div>
      </NScrollbar>
    </div>
  </Suspense>
</template>

<style scoped lang="scss">
.chat-list {
  display: grid;
  height: 100%;
  min-height: 0;
  grid-template-columns: minmax(0, 1fr);

  :deep(.n-scrollbar-content) {
    min-height: 100%;
  }

  &__scroll {
    min-height: 0;
    height: 100%;
  }

  &__inner {
    min-height: 100%;
    padding: 8px 0 24px;
  }

  &__stream {
    display: flex;
    width: min(100%, 1120px);
    margin: 0 auto;
    flex-direction: column;
    gap: 24px;
    padding: 24px 0 18px;
  }

  &__empty {
    display: flex;
    min-height: calc(100vh - 360px);
    width: min(100%, 960px);
    margin: 0 auto;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 20px;
    padding: 32px 20px;
    text-align: center;
  }

  &__empty-badge {
    display: inline-flex;
    align-items: center;
    border: 1px solid rgba(96, 165, 250, 0.24);
    border-radius: 999px;
    background: var(--app-control-bg);
    padding: 8px 14px;
    color: var(--app-text-primary);
    font-size: 12px;
    font-weight: 700;
    letter-spacing: 0.08em;
  }

  &__empty-title {
    max-width: 560px;
    margin: 0;
    color: var(--app-text-primary);
    font-size: 36px;
    font-weight: 700;
    line-height: 1.25;
  }

  &__empty-description {
    max-width: 620px;
    margin: 0;
    color: var(--app-text-secondary);
    font-size: 15px;
    line-height: 1.75;
  }
}

@media (max-width: 768px) {
  .chat-list {
    &__inner {
      padding: 12px 0 16px;
    }

    &__empty {
      min-height: 320px;
      padding: 22px 12px;
    }

    &__empty-title {
      font-size: 28px;
    }
  }
}
</style>
