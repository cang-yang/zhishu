<script setup lang="ts">
import { request } from '@/service/request';
import { useAuthStore } from '@/store/modules/auth';
import { useChatStore } from '@/store/modules/chat';
import { formatDate } from '@/utils/common';
import { VueMarkdownIt } from '@/vendor/vue-markdown-shiki';
defineOptions({ name: 'ChatMessage' });

const props = defineProps<{
  msg: Api.Chat.Message & {
    messageId?: string;
    sessionId?: string;
    deletable?: boolean;
    regeneratable?: boolean;
    lifecycle?: 'draft' | 'sent' | 'streaming' | 'finished' | 'error' | 'cancelled';
  },
  sessionId?: string,
  retrievalQueryFallback?: string,
  readonly?: boolean,
  userDisplayName?: string,
  assistantDisplayName?: string
}>();

const isUserMessage = computed(() => props.msg.role === 'user');
const isAssistantMessage = computed(() => props.msg.role === 'assistant');
const messageTime = computed(() => formatDate(props.msg.timestamp));
const messageLifecycle = computed(() => props.msg.lifecycle || props.msg.status || 'finished');
const isPendingMessage = computed(() => messageLifecycle.value === 'pending' || messageLifecycle.value === 'draft');
const isStreamingMessage = computed(() => messageLifecycle.value === 'streaming' || props.msg.status === 'loading');
const isErroredMessage = computed(() => messageLifecycle.value === 'error' || props.msg.status === 'error');
const isCancelledMessage = computed(() => messageLifecycle.value === 'cancelled');

const authStore = useAuthStore();
const citationPrefixPattern = /(?:\(|（)?来源#\d+:/;

function handleCopy(content: string) {
  navigator.clipboard.writeText(content);
  window.$message?.success('已复制');
}

function syncMobileViewport() {
  if (typeof window === 'undefined') return;
  isMobileViewport.value = window.innerWidth <= 768;
}

const chatStore = useChatStore();
const canDelete = computed(() => !props.readonly && props.msg.deletable !== false && Boolean(props.msg.messageId));
const canRegenerate = computed(() => !props.readonly && isAssistantMessage.value && Boolean(props.msg.regeneratable) && Boolean(props.msg.messageId));
const showActions = computed(() => !props.readonly && (canDelete.value || canRegenerate.value || Boolean(props.msg.content)));
const isMobileViewport = ref(false);
const activeMobileActionMessageId = inject('activeMobileActionMessageId', ref<string | null>(null));
const setActiveMobileActionMessageId = inject<(id: string | null) => void>('setActiveMobileActionMessageId', () => {});
const messageActionId = computed(() => {
  return props.msg.messageId || `${props.sessionId || 'chat'}-${props.msg.role}-${props.msg.timestamp || ''}-${(props.msg.content || '').slice(0, 24)}`;
});
const isMobileActionExpanded = computed(() => {
  return isMobileViewport.value && showActions.value && activeMobileActionMessageId.value === messageActionId.value;
});

const stateLabel = computed(() => {
  if (isStreamingMessage.value) return '正在流式生成回答…';
  if (isPendingMessage.value) return '请求已发送，等待模型返回首段内容…';
  if (isCancelledMessage.value) return '本次生成已停止，当前内容已保留。';
  if (isErroredMessage.value) return props.msg.content || '服务器繁忙，请稍后再试';
  return '';
});

async function handleDeleteMessage() {
  if (!props.msg.messageId) return;
  await chatStore.deleteMessage(props.msg.messageId);
  collapseMobileActions();
}

async function handleRegenerateMessage() {
  if (!props.msg.messageId) return;
  await chatStore.regenerateMessage(props.msg as any);
  collapseMobileActions();
}

function collapseMobileActions() {
  setActiveMobileActionMessageId(null);
}

function handleBubbleClick(event: MouseEvent) {
  if (!isMobileViewport.value || !showActions.value) return;

  const target = event.target as HTMLElement | null;
  if (target?.closest('.source-file-link')) {
    return;
  }

  setActiveMobileActionMessageId(isMobileActionExpanded.value ? null : messageActionId.value);
}

type SourceFileEntry = {
  fileName: string;
  id: string;
  referenceNumber: number;
  fileMd5?: string;
  pageNumber?: number;
};

type ReferencePreviewPayload = {
  retrievalMode?: Api.Chat.ReferenceEvidence['retrievalMode'];
  retrievalLabel?: string | null;
  retrievalQuery?: string | null;
  evidenceSnippet?: string | null;
  matchedChunkText?: string | null;
  score?: number | null;
  chunkId?: number | null;
  fileName: string;
  fileMd5?: string | null;
  pageNumber?: number | null;
  anchorText?: string | null;
  sessionId?: string;
  referenceNumber: number;
};

// 存储文件名和对应的事件处理
const sourceFiles = ref<SourceFileEntry[]>([]);
const bareUrlPattern = /https?:\/\/(?:[A-Za-z0-9\-._~:/?#@!$&'()*+,;=%]|\[|\])+/g;

function splitTrailingUrlPunctuation(rawUrl: string) {
  let url = rawUrl;
  let trailing = '';

  while (url) {
    const lastChar = url.at(-1);
    if (!lastChar) break;

    if (/[，。！？；：、,.!?;:]/.test(lastChar)) {
      trailing = `${lastChar}${trailing}`;
      url = url.slice(0, -1);
    } else if (lastChar === ')' || lastChar === '）') {
      const openingChar = lastChar === ')' ? '(' : '（';
      const closingChar = lastChar;
      const openingCount = (url.match(new RegExp(`\\${openingChar}`, 'g')) || []).length;
      const closingCount = (url.match(new RegExp(`\\${closingChar}`, 'g')) || []).length;

      if (closingCount > openingCount) {
        trailing = `${lastChar}${trailing}`;
        url = url.slice(0, -1);
      } else {
        break;
      }
    } else {
      break;
    }
  }

  return { url, trailing };
}

function normalizeBareUrls(text: string) {
  return text.replace(bareUrlPattern, (match, offset: number, source: string) => {
    const previousChar = source[offset - 1] || '';
    const previousTwoChars = source.slice(Math.max(0, offset - 2), offset);
    const previousTenChars = source.slice(Math.max(0, offset - 10), offset).toLowerCase();

    if (previousChar === '<' || previousTwoChars === '](' || /(?:href|src)=["']?$/.test(previousTenChars)) {
      return match;
    }

    const { url, trailing } = splitTrailingUrlPunctuation(match);
    return url ? `<${url}>${trailing}` : match;
  });
}

function createSourceLink(
  sourceNum: string,
  fileName: string,
  extras?: { fileMd5?: string; pageNumber?: number; displayName?: string }
): string {
  const linkClass = 'source-file-link';
  const trimmedFileName = fileName.trim();
  const fileId = `source-file-${sourceFiles.value.length}`;
  const referenceNumber = Number.parseInt(sourceNum, 10);

  sourceFiles.value.push({
    fileName: trimmedFileName,
    id: fileId,
    referenceNumber,
    fileMd5: extras?.fileMd5,
    pageNumber: extras?.pageNumber
  });

  return `来源#${sourceNum}: <a href="#" class="${linkClass}" data-reference="true" data-file-id="${fileId}">${extras?.displayName || trimmedFileName}</a>`;
}

function replaceSourceEntries(pattern: RegExp, text: string, replacer: (groups: string[]) => string) {
  let result = '';
  let lastIndex = 0;
  let match = pattern.exec(text);

  while (match) {
    const [matched, ...groups] = match;
    result += text.slice(lastIndex, match.index);
    result += replacer(groups);
    lastIndex = match.index + matched.length;
    match = pattern.exec(text);
  }

  result += text.slice(lastIndex);
  return result;
}

// 处理来源文件链接的函数
function processSourceLinks(text: string): string {
  // 重置来源文件列表，避免重复
  sourceFiles.value = [];

  // 支持单个来源，也支持一个括号里包含多个来源：
  // (来源#1: test.pdf | 第5页; 来源#2: other.pdf | 第8页)
  const entryBoundary = '(?=\\s*(?:[;；,，、。！？!?\\)）]|$))';
  const pagePattern = new RegExp(
    `来源#(\\d+):\\s*([^|;；,，、。！？!?\\n\\r]+?)\\s*\\|\\s*第(\\d+)页${entryBoundary}`,
    'g'
  );
  const md5Pattern = new RegExp(
    `来源#(\\d+):\\s*([^|;；,，、。！？!?\\n\\r]+?)\\s*\\|\\s*MD5:\\s*([a-fA-F0-9]+)${entryBoundary}`,
    'g'
  );
  const simplePattern = new RegExp(
    `来源#(\\d+):\\s*([^<>\\n\\r|;；,，、。！？!?]+?)${entryBoundary}`,
    'g'
  );

  let processedText = replaceSourceEntries(pagePattern, text, ([sourceNum, fileName, pageNum]) => {
    return createSourceLink(sourceNum, fileName, {
      pageNumber: Number.parseInt(pageNum, 10),
      displayName: `${fileName.trim()} (第${pageNum}页)`
    });
  });

  processedText = replaceSourceEntries(md5Pattern, processedText, ([sourceNum, fileName, fileMd5]) => {
    return createSourceLink(sourceNum, fileName, {
      fileMd5: fileMd5.trim()
    });
  });

  processedText = replaceSourceEntries(simplePattern, processedText, ([sourceNum, fileName]) => {
    return createSourceLink(sourceNum, fileName);
  });

  return processedText;
}

const content = computed(() => {
  chatStore.scrollToBottom?.();
  const rawContent = props.msg.content ?? '';

  // 只对助手消息处理来源链接
  if (props.msg.role === 'assistant') {
    return normalizeBareUrls(processSourceLinks(rawContent));
  }

  return rawContent;
});

function extractContextAnchorText(target: HTMLElement) {
  const scope = target.closest('li, p, blockquote, td, th');
  const rawText = scope?.textContent?.replace(/\s+/g, ' ').trim() || '';
  if (!rawText) return '';

  const beforeCitation = rawText.split(citationPrefixPattern)[0] || rawText;
  return beforeCitation
    .replace(/^\s*\d+\.\s*/, '')
    .replace(/[（(]\s*$/, '')
    .replace(/\s+/g, ' ')
    .trim();
}

function openReferencePreviewOverlay(payload: ReferencePreviewPayload) {
  chatStore.openReferencePreview({
    ...payload,
    fileMd5: payload.fileMd5 ?? undefined,
    pageNumber: payload.pageNumber ?? undefined,
    anchorText: payload.anchorText ?? undefined
  });
}

onMounted(() => {
  syncMobileViewport();

  if (typeof window !== 'undefined') {
    window.addEventListener('resize', syncMobileViewport);
  }
});

onBeforeUnmount(() => {
  if (typeof window !== 'undefined') {
    window.removeEventListener('resize', syncMobileViewport);
  }
});

function findSourceFile(target: HTMLElement) {
  const fileId = target.getAttribute('data-file-id');
  if (!fileId) return null;
  return sourceFiles.value.find(file => file.id === fileId) || null;
}

function findSourceFileByText(text: string) {
  const normalized = text.trim();
  if (!normalized) return null;

  return sourceFiles.value.find(file => {
    return normalized.includes(file.fileName) || file.fileName.includes(normalized);
  }) || null;
}

function getPersistedDetail(referenceNumber: number) {
  return props.msg.referenceMappings?.[String(referenceNumber)] || props.msg.referenceMappings?.[referenceNumber];
}

function buildPreviewPayload(options: {
  fileName: string;
  referenceNumber: number;
  sessionId?: string;
  fallbackRetrievalQuery: string;
  payload: Partial<Api.Document.ReferenceDetailResponse> & {
    anchorText?: string | null;
    fileMd5?: string | null;
  };
}) {
  const {
    fileName,
    referenceNumber,
    sessionId,
    fallbackRetrievalQuery,
    payload
  } = options;

  openReferencePreviewOverlay({
    fileName: payload.fileName || fileName,
    fileMd5: payload.fileMd5,
    pageNumber: payload.pageNumber,
    anchorText: payload.anchorText || '',
    retrievalMode: payload.retrievalMode,
    retrievalLabel: payload.retrievalLabel,
    retrievalQuery: payload.retrievalQuery || fallbackRetrievalQuery,
    evidenceSnippet: payload.evidenceSnippet,
    matchedChunkText: payload.matchedChunkText,
    score: payload.score,
    chunkId: payload.chunkId,
    sessionId,
    referenceNumber
  });
}

async function fetchReferenceDetail(sessionId: string, referenceNumber: number) {
  try {
    const { error: detailError, data: detailData } = await request<Api.Document.ReferenceDetailResponse>({
      url: '/documents/reference-detail',
      params: {
        sessionId,
        referenceNumber: referenceNumber.toString()
      }
    });

    if (!detailError && detailData?.fileMd5) {
      return detailData;
    }
  } catch {
    // noop
  }

  return null;
}

async function resolveReferenceDetail(sessionId: string | undefined, referenceNumber: number) {
  const persistedDetail = getPersistedDetail(referenceNumber);
  const needRemoteDetail = sessionId
    && (!persistedDetail?.retrievalQuery || !persistedDetail?.matchedChunkText || !persistedDetail?.evidenceSnippet);

  const detail = needRemoteDetail ? await fetchReferenceDetail(sessionId, referenceNumber) : null;
  return { persistedDetail, detail };
}

// 处理内容点击事件（事件委托）
function handleContentClick(event: MouseEvent) {
  const targetNode = event.target;
  const targetElement = targetNode instanceof Element ? targetNode : targetNode instanceof Node ? targetNode.parentElement : null;
  if (!targetElement) return;

  const sourceLink = targetElement.closest('a.source-file-link, a[data-reference="true"], a[data-file-id]') as HTMLElement | null;
  if (!sourceLink) return;

  event.preventDefault();
  event.stopPropagation();

  const fileId = sourceLink.getAttribute('data-file-id') || '';
  const indexMatch = fileId.match(/source-file-(\d+)/);
  const sourceIndex = indexMatch ? Number.parseInt(indexMatch[1], 10) : -1;

  const file = findSourceFile(sourceLink)
    || (sourceIndex >= 0 ? sourceFiles.value[sourceIndex] || null : null)
    || findSourceFileByText(sourceLink.textContent || '');
  if (!file) return;

  const contextAnchorText = extractContextAnchorText(sourceLink);
  handleSourceFileClick({
    fileName: file.fileName,
    referenceNumber: file.referenceNumber,
    fileMd5: file.fileMd5,
    anchorText: contextAnchorText
  });
}

// 处理来源文件点击事件
async function handleSourceFileClick(fileInfo: {
  fileName: string;
  referenceNumber: number;
  fileMd5?: string;
  anchorText?: string;
}) {
  const { fileName, referenceNumber, fileMd5: extractedMd5, anchorText: clickedAnchorText } = fileInfo;
  const referenceSessionId = props.msg.sessionId || props.msg.conversationId || props.sessionId;

  try {
    const fallbackRetrievalQuery = props.retrievalQueryFallback || '';
    const { persistedDetail, detail } = await resolveReferenceDetail(referenceSessionId, referenceNumber);

    if (persistedDetail?.fileMd5 && !detail) {
      buildPreviewPayload({
        fileName,
        referenceNumber,
        sessionId: referenceSessionId,
        fallbackRetrievalQuery,
        payload: {
          ...persistedDetail,
          anchorText: persistedDetail.anchorText || clickedAnchorText || '',
          fileMd5: persistedDetail.fileMd5
        }
      });
      return;
    }

    buildPreviewPayload({
      fileName,
      referenceNumber,
      sessionId: referenceSessionId,
      fallbackRetrievalQuery,
      payload: {
        ...detail,
        fileMd5: detail?.fileMd5 || extractedMd5 || undefined,
        anchorText: detail?.anchorText || clickedAnchorText || '',
      }
    });
  } catch {
    window.$message?.error(`文件下载失败: ${fileName}`);
  }
}

</script>

<template>
  <article
    class="chat-message"
    :class="{
      'chat-message--user': isUserMessage,
      'chat-message--assistant': isAssistantMessage,
      'chat-message--actions-open': isMobileActionExpanded
    }"
  >
    <header class="chat-message__header">
      <div class="chat-message__identity">
        <NAvatar :class="isUserMessage ? 'chat-message__avatar chat-message__avatar--user' : 'chat-message__avatar chat-message__avatar--assistant'">
          <SvgIcon v-if="isUserMessage" icon="ph:user-circle" class="text-icon-large color-white" />
          <SvgIcon v-else local-icon="logo" class="text-icon-large color-white" />
        </NAvatar>
        <div class="chat-message__meta">
          <div class="chat-message__author-row">
            <NText class="chat-message__author">{{ isUserMessage ? (userDisplayName || authStore.userInfo.username) : (assistantDisplayName || '智枢') }}</NText>
            <span class="chat-message__role-tag">
              {{ isUserMessage ? '提问方' : '智能助手' }}
            </span>
            <span
              v-if="!isUserMessage && messageLifecycle !== 'finished'"
              class="chat-message__lifecycle-pill"
              :class="`chat-message__lifecycle-pill--${messageLifecycle}`"
            >
              {{ stateLabel }}
            </span>
          </div>
          <NText class="chat-message__time">{{ messageTime }}</NText>
        </div>
      </div>
    </header>

    <div class="chat-message__body" :class="{ 'chat-message__body--user': isUserMessage }">
      <div class="chat-message__bubble" :class="{ 'chat-message__bubble--user': isUserMessage }" @click.stop="handleBubbleClick">
        <NText v-if="isPendingMessage" class="chat-message__state chat-message__state--pending">
          <icon-eos-icons:three-dots-loading class="text-8" />
          <span>{{ stateLabel }}</span>
        </NText>
        <NText v-else-if="isErroredMessage" class="chat-message__state chat-message__state--error">
          {{ stateLabel }}
        </NText>
        <NText v-else-if="isCancelledMessage" class="chat-message__state chat-message__state--cancelled">
          <icon-material-symbols:stop-rounded class="text-16px" />
          <span>{{ stateLabel }}</span>
        </NText>
        <div v-if="isAssistantMessage && !isErroredMessage" class="chat-message__markdown" @click="handleContentClick">
          <div v-if="isStreamingMessage" class="chat-message__streaming-badge">
            <span class="chat-message__streaming-dot" />
            <span>流式更新中</span>
          </div>
          <VueMarkdownIt :content="content" />
        </div>
        <NText v-else-if="!isErroredMessage" class="chat-message__plain-text">{{ content }}</NText>
      </div>
    </div>

    <footer v-if="showActions" class="chat-message__footer" @click.stop>
      <div class="chat-message__actions">
        <NButton quaternary class="chat-message__action" @click.stop="handleCopy(msg.content || ''); collapseMobileActions()">
          <template #icon>
            <icon-mynaui:copy />
          </template>
          复制内容
        </NButton>
        <NButton v-if="canDelete" quaternary class="chat-message__action" @click.stop="handleDeleteMessage">
          <template #icon>
            <icon-mdi:delete-outline />
          </template>
          删除
        </NButton>
        <NButton v-if="canRegenerate" quaternary class="chat-message__action" @click.stop="handleRegenerateMessage">
          <template #icon>
            <icon-carbon:renew />
          </template>
          重新生成
        </NButton>
      </div>
    </footer>
  </article>
</template>

<style scoped lang="scss">
:deep(.n-button.chat-message__action .n-button__content) {
  font-weight: 500;
}

.chat-message {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 0 18px;

  &__header,
  &__footer {
    display: flex;
    align-items: center;
    gap: 12px;
  }

  &--user {
    align-items: flex-end;

    .chat-message__header,
    .chat-message__footer {
      width: min(100%, 1040px);
      justify-content: flex-end;
    }

    .chat-message__identity {
      flex-direction: row-reverse;
    }

    .chat-message__meta,
    .chat-message__author-row {
      align-items: flex-end;
      justify-content: flex-end;
    }

    .chat-message__time {
      text-align: right;
    }

    .chat-message__body {
      justify-content: flex-end;
      width: min(100%, 1040px);
      padding-left: 64px;
    }

    .chat-message__footer {
      justify-content: flex-end;
    }
  }

  &--assistant {
    .chat-message__header,
    .chat-message__footer,
    .chat-message__body {
      width: min(100%, 1040px);
    }

    .chat-message__footer {
      justify-content: flex-start;
    }
  }

  &__identity {
    display: flex;
    min-width: 0;
    align-items: center;
    gap: 14px;
  }

  &__avatar {
    flex-shrink: 0;

    &--assistant {
      background: linear-gradient(135deg, rgb(var(--primary-color)) 0%, #5b84f8 100%);
    }

    &--user {
      background: linear-gradient(135deg, #2fa87f 0%, #4ac196 100%);
    }
  }

  &__meta {
    display: flex;
    min-width: 0;
    flex-direction: column;
    gap: 4px;
  }

  &__author-row {
    display: flex;
    min-width: 0;
    align-items: center;
    gap: 10px;
    flex-wrap: wrap;
  }

  &__lifecycle-pill {
    display: inline-flex;
    align-items: center;
    border-radius: 999px;
    border: 1px solid rgba(71, 85, 105, 0.22);
    background: var(--color-bg-card);
    padding: 4px 10px;
    font-size: 12px;
    font-weight: 700;
    color: rgb(191 219 254);

    &--draft,
    &--sent {
      color: var(--color-text-secondary);
    }

    &--streaming {
      border-color: rgba(79, 124, 255, 0.24);
      color: rgb(191 219 254);
    }

    &--error {
      border-color: rgba(239, 68, 68, 0.22);
      color: #fca5a5;
    }

    &--cancelled {
      border-color: rgba(245, 158, 11, 0.2);
      color: #fdba74;
    }
  }

  &__author {
    font-size: 15px;
    font-weight: 700;
    color: var(--color-text-primary);
  }

  &__role-tag {
    display: inline-flex;
    align-items: center;
    border-radius: 999px;
    background: rgba(59, 110, 246, 0.12);
    padding: 4px 10px;
    font-size: 12px;
    font-weight: 600;
    color: rgb(var(--primary-color));
  }

  &__time {
    font-size: 13px;
    color: var(--color-text-secondary);
  }

  &__footer {
    opacity: 0;
    transform: translateY(-4px);
    pointer-events: none;
    transition:
      opacity 0.2s ease,
      transform 0.2s ease;
  }

  &__actions {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    padding: 2px;
    border: 1px solid var(--color-border);
    border-radius: 999px;
    background: var(--color-bg-elevated);
    backdrop-filter: blur(12px);
  }

  &__body {
    display: flex;
    width: min(100%, 1040px);
  }

  &__bubble {
    position: relative;
    overflow: visible;
    width: auto;
    max-width: min(100%, 880px);
    padding: 18px 20px;
    border: 1px solid rgba(148, 163, 184, 0.18);
    border-radius: 24px;
    background: #fff;
    box-shadow: none;
    backdrop-filter: none;

    &--user {
      border-color: rgba(79, 124, 255, 0.18);
      background: rgba(79, 124, 255, 0.06);
    }
  }

  &__state {
    display: inline-flex;
    align-items: center;
    gap: 10px;
    font-size: 14px;
    line-height: 1.7;

    &--pending {
      color: rgb(148 163 184);
    }

    &--error {
      width: 100%;
      padding: 12px 14px;
      border: 1px solid rgba(239, 68, 68, 0.22);
      border-radius: 18px;
      background: rgba(127, 29, 29, 0.18);
      color: #fca5a5;
    }

    &--cancelled {
      width: 100%;
      padding: 12px 14px;
      border: 1px solid rgba(245, 158, 11, 0.2);
      border-radius: 18px;
      background: rgba(120, 53, 15, 0.18);
      color: #fdba74;
    }
  }

  &__plain-text {
    white-space: pre-wrap;
    color: var(--color-text-primary);
    font-size: 15px;
    line-height: 1.8;
  }

  &__markdown {
    position: relative;
    z-index: 1;
    color: var(--color-text-primary);
    font-size: 15px;
    line-height: 1.85;
  }

  &__streaming-badge {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 12px;
    padding: 6px 10px;
    border: 1px solid rgba(79, 124, 255, 0.18);
    border-radius: 999px;
    background: var(--color-bg-card);
    color: rgb(191 219 254);
    font-size: 12px;
    font-weight: 600;
  }

  &__streaming-dot {
    width: 8px;
    height: 8px;
    border-radius: 999px;
    background: rgb(var(--primary-color));
    box-shadow: 0 0 0 6px rgba(79, 124, 255, 0.12);
    animation: chat-message-pulse 1.4s ease-in-out infinite;
  }
}

@keyframes chat-message-pulse {
  0%,
  100% {
    opacity: 0.45;
    transform: scale(0.92);
  }

  50% {
    opacity: 1;
    transform: scale(1);
  }
}

:deep(.chat-message__markdown > *) {
  max-width: 100%;
}

:deep(.chat-message__markdown body) {
  margin: 0;
  background: #fff;
  color: inherit;
}

:deep(.chat-message__markdown p),
:deep(.chat-message__markdown li),
:deep(.chat-message__markdown blockquote) {
  line-height: 1.85;
}

:deep(.chat-message__markdown p) {
  margin: 0 0 12px;
}

:deep(.chat-message__markdown p:last-child) {
  margin-bottom: 0;
}

:deep(.chat-message__markdown pre) {
  overflow-x: auto;
  border-radius: 16px;
}

:deep(.chat-message__markdown code:not(pre code)) {
  padding: 2px 6px;
  border-radius: 8px;
  background: rgba(148, 163, 184, 0.14);
}

:deep(.source-file-link) {
  color: rgb(var(--primary-color));
  cursor: pointer;
  text-decoration: none;
  transition: color 0.2s;
  font-weight: 600;

  &:hover {
    color: #5b84f8;
    text-decoration: underline;
  }

  &:active {
    color: #2959db;
  }
}

.dark {
  .chat-message {
    &__time,
    &__state--pending {
      color: rgb(148 163 184);
    }
  }

  :deep(.chat-message__markdown code:not(pre code)) {
    background: rgba(71, 85, 105, 0.32);
  }
}

@media (hover: hover) and (min-width: 769px) {
  .chat-message {
    &:hover,
    &:focus-within {
      .chat-message__footer {
        opacity: 1;
        transform: translateY(0);
        pointer-events: auto;
      }
    }
  }
}

@media (max-width: 768px) {
  .chat-message {
    padding: 0 8px;

    &--user,
    &--assistant {
      .chat-message__header,
      .chat-message__footer,
      .chat-message__body {
        width: 100%;
      }
    }

    &__body,
    .chat-message--user &__body {
      padding-left: 0;
    }

    &__bubble {
      width: auto;
      max-width: 85vw;
      padding: 16px;
    }

    &__header,
    &__footer {
      align-items: flex-start;
      flex-direction: column;
    }

    &--user {
      .chat-message__header,
      .chat-message__footer {
        align-items: flex-end;
      }

      .chat-message__identity,
      .chat-message__meta,
      .chat-message__author-row {
        width: 100%;
        justify-content: flex-end;
      }
    }

    &__footer {
      transform: none;
      max-height: 0;
      margin-top: 0;
      opacity: 0;
      overflow: hidden;
      pointer-events: none;
      transition:
        max-height 150ms ease,
        opacity 150ms ease,
        margin-top 150ms ease;
    }

    &--actions-open {
      .chat-message__footer {
        max-height: 40px;
        margin-top: -4px;
        opacity: 1;
        pointer-events: auto;
      }

      .chat-message__bubble {
        border-color: rgba(79, 110, 247, 0.28);
        box-shadow: none;
      }
    }

    &__actions {
      gap: 4px;
      padding: 0;
      border: 0;
      border-radius: 0;
      background: transparent;
      backdrop-filter: none;
    }

    &__action {
      min-width: 0;
      width: 28px;
      height: 28px;
      padding: 0;
      border: 1px solid var(--color-border);
      border-radius: 6px;
      background: var(--color-bg-card);
    }

    :deep(.n-button.chat-message__action .n-button__content) {
      gap: 0;
      font-size: 12px;
    }

    :deep(.n-button.chat-message__action .n-button__content > :not(.n-icon)) {
      display: none;
    }
  }
}
</style>
