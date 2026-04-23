import { useWebSocket } from '@vueuse/core';
import { SetupStoreId } from '@/enum';
import { useAuthStore } from '../auth';
import { useUiStore } from '../ui';
import { useWorkspaceStore } from '../workspace';

type ChatReferencePreviewPayload = Partial<Api.Document.ReferenceDetailResponse> & {
  fileName: string;
  fileMd5?: string | null;
  pageNumber?: number | null;
  anchorText?: string | null;
  sessionId?: string;
  referenceNumber: number;
};

type ChatSessionMeta = {
  sessionId: string;
  title: string;
  createdAt?: string | null;
  updatedAt?: string | null;
  lastMessageAt?: string | null;
  latestPreview?: string | null;
  messageCount?: number;
};

type ChatMessageLifecycle = 'draft' | 'sent' | 'streaming' | 'finished' | 'error' | 'cancelled';

type ChatMessageRecord = Api.Chat.Message & {
  messageId: string;
  sessionId: string;
  seq: number;
  clientMessageId?: string;
  serverMessageId?: string;
  lifecycle?: ChatMessageLifecycle;
  updatedAtClient?: number;
  version?: number;
  regenerateCount?: number;
  deletable?: boolean;
  regeneratable?: boolean;
};

type PendingChunkState = {
  sessionId: string;
  messageId: string;
  delta: string;
  updatedAt: number;
};

type ChatLifecycleLog = {
  id: string;
  at: string;
  type: string;
  sessionId?: string;
  messageId?: string;
  detail?: string;
};

type DeleteMessageResponse = {
  message: ChatMessageRecord;
  sessionMeta: ChatSessionMeta;
};

type ChatAcceptedEvent = {
  type: 'message.accepted';
  requestId: string;
  sessionId: string;
  sessionMeta: ChatSessionMeta;
  userMessage?: ChatMessageRecord;
  assistantMessage: ChatMessageRecord;
};

type ChatChunkEvent = {
  type: 'message.chunk';
  requestId: string;
  sessionId: string;
  messageId: string;
  delta: string;
  status: string;
};

type ChatCompletedEvent = {
  type: 'message.completed';
  requestId: string;
  sessionId: string;
  messageId: string;
  status: string;
  message: ChatMessageRecord;
  sessionMeta: ChatSessionMeta;
};

type ChatErrorEvent = {
  type: 'message.error';
  requestId?: string;
  sessionId?: string;
  messageId?: string;
  message?: string;
  code?: number;
  retryAfterSeconds?: number;
};

type ChatStoppedEvent = {
  type: 'message.stopped';
  requestId?: string;
  sessionId?: string;
  messageId?: string;
};

type ChatConnectionEvent = {
  type: 'connection';
  sessionId?: string;
  wsSessionId?: string;
};

type ChatSocketEvent = ChatAcceptedEvent | ChatChunkEvent | ChatCompletedEvent | ChatErrorEvent | ChatStoppedEvent | ChatConnectionEvent;

const CHAT_HOME_DRAFT_KEY = '__home__';

const CHAT_SOCKET_EVENT_TYPES = new Set<ChatSocketEvent['type']>([
  'connection',
  'message.accepted',
  'message.chunk',
  'message.completed',
  'message.error',
  'message.stopped'
]);

function resolveMessageLifecycle(status: unknown): ChatMessageLifecycle {
  const normalized = String(status || '').toLowerCase();

  if (normalized === 'loading' || normalized === 'streaming' || normalized === 'pending') {
    return 'streaming';
  }

  if (normalized === 'error' || normalized === 'failed') {
    return 'error';
  }

  if (normalized === 'stopped' || normalized === 'cancelled') {
    return 'cancelled';
  }

  if (normalized === 'sent') {
    return 'sent';
  }

  return 'finished';
}

function normalizeSessionMeta(session: Record<string, any>): ChatSessionMeta {
  return {
    sessionId: String(session.sessionId),
    title: String(session.title || '新会话'),
    createdAt: session.createdAt || null,
    updatedAt: session.updatedAt || null,
    lastMessageAt: session.lastMessageAt || null,
    latestPreview: session.latestPreview || '',
    messageCount: Number(session.messageCount || 0)
  };
}

function normalizeMessage(message: Record<string, any>): ChatMessageRecord {
  return {
    messageId: String(message.messageId),
    sessionId: String(message.sessionId || message.conversationId || ''),
    seq: Number(message.seq || 0),
    clientMessageId: message.clientMessageId ? String(message.clientMessageId) : undefined,
    serverMessageId: String(message.serverMessageId || message.messageId || ''),
    lifecycle: resolveMessageLifecycle(message.lifecycle || message.status),
    updatedAtClient: Number(message.updatedAtClient || Date.now()),
    role: message.role,
    content: message.content || '',
    status: message.status || 'finished',
    timestamp: message.updatedAt || message.createdAt || message.timestamp,
    conversationId: message.sessionId || message.conversationId,
    referenceMappings: message.referenceMappings || undefined,
    version: Number(message.version || 1),
    regenerateCount: Number(message.regenerateCount || 0),
    deletable: message.deletable !== false,
    regeneratable: Boolean(message.regeneratable)
  };
}

export const useChatStore = defineStore(SetupStoreId.Chat, () => {
  const NON_RETRYABLE_CLOSE_CODES = new Set([1002, 1003, 1007, 1008]);
  const WS_HEARTBEAT_PING = '__chat_ping__';
  const WS_HEARTBEAT_PONG = '__chat_pong__';

  const store = useAuthStore();
  const socketUrl = computed(() => {
    const token = store.token?.trim();
    return token ? `/proxy-ws/chat/${encodeURIComponent(token)}` : undefined;
  });

  const uiStore = useUiStore();
  const workspaceStore = useWorkspaceStore();
  const sessionList = ref<ChatSessionMeta[]>([]);
  const activeSessionId = ref('');
  const messagesBySessionId = ref<Record<string, ChatMessageRecord[]>>({});
  const draftsBySessionId = ref<Record<string, string>>({});
  const wsSessionId = ref('');
  const allowReconnect = ref(true);
  const authFailureNotified = ref(false);
  const handshakeConfirmed = ref(false);
  const intentionalDisconnect = ref(false);
  const rateLimitUntil = ref<number | null>(null);
  const rateLimitRemainingSeconds = ref(0);
  const loadingSessions = ref(false);
  const loadingMessages = ref(false);
  const activeRequestId = ref('');
  const activeStreamingMessageId = ref('');
  const scrollToBottom = ref<null | (() => void)>(null);
  const pendingChunkMap = ref<Record<string, PendingChunkState>>({});
  const messageLifecycleLogs = ref<ChatLifecycleLog[]>([]);
  let rateLimitTimer: number | null = null;
  let chunkFlushTimer: number | null = null;

  const {
    status: wsStatus,
    data: wsData,
    send: rawWsSend,
    open: rawWsOpen,
    close: rawWsClose
  } = useWebSocket(socketUrl, {
    immediate: false,
    autoConnect: false,
    heartbeat: {
      message: WS_HEARTBEAT_PING,
      responseMessage: WS_HEARTBEAT_PONG,
      interval: 20_000,
      pongTimeout: 10_000
    },
    autoReconnect: {
      retries: () => allowReconnect.value,
      delay: 1500,
      onFailed: () => {
        if (allowReconnect.value && socketUrl.value) {
          window.$message?.warning('WebSocket 重连失败，请检查网络或刷新页面后重试');
        }
      }
    },
    onConnected: () => {
      allowReconnect.value = true;
      authFailureNotified.value = false;
      intentionalDisconnect.value = false;
    },
    onDisconnected: (_, event) => {
      if (intentionalDisconnect.value) {
        intentionalDisconnect.value = false;
        allowReconnect.value = Boolean(socketUrl.value);
        return;
      }

      const closedBeforeHandshake = !handshakeConfirmed.value;
      const isAuthOrProtocolFailure = NON_RETRYABLE_CLOSE_CODES.has(event.code) || closedBeforeHandshake;
      allowReconnect.value = !isAuthOrProtocolFailure;
      if (isAuthOrProtocolFailure && !authFailureNotified.value) {
        authFailureNotified.value = true;
        window.$message?.error('聊天连接鉴权失败，请重新登录后再试');
      }
    }
  });

  const activeMessages = computed(() => messagesBySessionId.value[activeSessionId.value] || []);
  const activeDraftKey = computed(() => activeSessionId.value || CHAT_HOME_DRAFT_KEY);
  const currentInput = computed<string>({
    get() {
      return draftsBySessionId.value[activeDraftKey.value] || '';
    },
    set(value) {
      draftsBySessionId.value[activeDraftKey.value] = value;
    }
  });
  const input = computed<Api.Chat.Input>({
    get() {
      return { message: currentInput.value, conversationId: activeSessionId.value };
    },
    set(value) {
      currentInput.value = value.message;
    }
  });

  const connectionStatus = computed(() => {
    if (wsStatus.value === 'OPEN') return 'OPEN';
    if (wsStatus.value === 'CONNECTING' && handshakeConfirmed.value) return 'RECONNECTING';
    return wsStatus.value;
  });

  const isRateLimited = computed(() => rateLimitRemainingSeconds.value > 0);
  const isSending = computed(() => Boolean(activeRequestId.value && activeStreamingMessageId.value));

  function pushLifecycleLog(type: string, payload: Omit<ChatLifecycleLog, 'id' | 'at' | 'type'> = {}) {
    messageLifecycleLogs.value = [
      {
        id: crypto.randomUUID(),
        at: new Date().toISOString(),
        type,
        ...payload
      },
      ...messageLifecycleLogs.value
    ].slice(0, 40);
  }

  function clearRateLimitTimer() {
    if (rateLimitTimer !== null) {
      window.clearInterval(rateLimitTimer);
      rateLimitTimer = null;
    }
  }

  function clearChunkFlushTimer() {
    if (chunkFlushTimer !== null) {
      window.clearTimeout(chunkFlushTimer);
      chunkFlushTimer = null;
    }
  }

  function syncRateLimitCountdown() {
    if (!rateLimitUntil.value) {
      rateLimitRemainingSeconds.value = 0;
      return;
    }
    const remainingMs = rateLimitUntil.value - Date.now();
    rateLimitRemainingSeconds.value = Math.max(0, Math.ceil(remainingMs / 1000));
    if (remainingMs <= 0) {
      clearRateLimitCountdown();
    }
  }

  function clearRateLimitCountdown() {
    clearRateLimitTimer();
    rateLimitUntil.value = null;
    rateLimitRemainingSeconds.value = 0;
  }

  function startRateLimitCountdown(retryAfterSeconds: number) {
    const normalizedSeconds = Math.max(0, Math.ceil(retryAfterSeconds));
    if (normalizedSeconds <= 0) {
      clearRateLimitCountdown();
      return;
    }
    rateLimitUntil.value = Date.now() + normalizedSeconds * 1000;
    syncRateLimitCountdown();
    clearRateLimitTimer();
    rateLimitTimer = window.setInterval(syncRateLimitCountdown, 1000);
  }

  function resetConnectionState() {
    handshakeConfirmed.value = false;
    wsSessionId.value = '';
    authFailureNotified.value = false;
  }

  function wsOpen() {
    if (!socketUrl.value) return;
    resetConnectionState();
    allowReconnect.value = true;
    intentionalDisconnect.value = wsStatus.value === 'OPEN' || wsStatus.value === 'CONNECTING';
    rawWsOpen();
  }

  function wsClose(code?: number, reason?: string) {
    intentionalDisconnect.value = true;
    allowReconnect.value = false;
    rawWsClose(code, reason);
  }

  function ensureMessageBucket(sessionId: string) {
    if (!messagesBySessionId.value[sessionId]) {
      messagesBySessionId.value[sessionId] = [];
    }
    return messagesBySessionId.value[sessionId];
  }

  function upsertSession(session: ChatSessionMeta) {
    const normalized = normalizeSessionMeta(session as any);
    const index = sessionList.value.findIndex(item => item.sessionId === normalized.sessionId);
    if (index >= 0) sessionList.value[index] = normalized;
    else sessionList.value.unshift(normalized);
    sessionList.value = [...sessionList.value].sort((a, b) => String(b.updatedAt || '').localeCompare(String(a.updatedAt || '')));
    if (!activeSessionId.value) activeSessionId.value = normalized.sessionId;
  }

  function deriveSessionTitleFromMessage(message: string) {
    const trimmed = message.trim().replace(/\s+/g, ' ');
    if (!trimmed) return '新话题';
    return trimmed.length > 18 ? `${trimmed.slice(0, 18)}…` : trimmed;
  }

  function replaceMessage(sessionId: string, message: ChatMessageRecord) {
    const bucket = ensureMessageBucket(sessionId);
    const normalized = normalizeMessage(message as any);
    const index = bucket.findIndex(item => item.messageId === normalized.messageId);
    if (index >= 0) bucket[index] = normalized;
    else bucket.push(normalized);
    bucket.sort((a, b) => a.seq - b.seq);
    messagesBySessionId.value = { ...messagesBySessionId.value, [sessionId]: [...bucket] };
  }

  function patchMessage(
    sessionId: string,
    messageId: string,
    patch: Partial<ChatMessageRecord> | ((message: ChatMessageRecord) => Partial<ChatMessageRecord>)
  ) {
    const bucket = ensureMessageBucket(sessionId);
    const target = bucket.find(item => item.messageId === messageId);
    if (!target) return;

    const nextPatch = typeof patch === 'function' ? patch(target) : patch;
    Object.assign(target, nextPatch, { updatedAtClient: Date.now() });
    messagesBySessionId.value = { ...messagesBySessionId.value, [sessionId]: [...bucket] };
  }

  function removeMessage(sessionId: string, messageId: string) {
    const bucket = ensureMessageBucket(sessionId).filter(item => item.messageId !== messageId);
    messagesBySessionId.value = { ...messagesBySessionId.value, [sessionId]: bucket };
  }

  function omitSessionState<T>(state: Record<string, T>, sessionId: string) {
    return Object.fromEntries(Object.entries(state).filter(([key]) => key !== sessionId)) as Record<string, T>;
  }

  function resetStreamingState() {
    flushPendingChunks();
    activeStreamingMessageId.value = '';
    activeRequestId.value = '';
  }

  function flushPendingChunks(sessionId?: string, messageId?: string) {
    const entries = Object.entries(pendingChunkMap.value).filter(([, state]) => {
      if (sessionId && state.sessionId !== sessionId) return false;
      if (messageId && state.messageId !== messageId) return false;
      return true;
    });

    if (!entries.length) return;

    const nextPendingMap = { ...pendingChunkMap.value };

    entries.forEach(([key, state]) => {
      const bucket = ensureMessageBucket(state.sessionId);
      const target = bucket.find(item => item.messageId === state.messageId);

      if (target && state.delta) {
        target.content += state.delta;
        target.status = 'loading';
        target.lifecycle = 'streaming';
        target.updatedAtClient = Date.now();
        messagesBySessionId.value = { ...messagesBySessionId.value, [state.sessionId]: [...bucket] };
      }

      delete nextPendingMap[key];
    });

    pendingChunkMap.value = nextPendingMap;
    nextTick(() => scrollToBottom.value?.());
  }

  function scheduleChunkFlush() {
    if (chunkFlushTimer !== null) return;

    chunkFlushTimer = window.setTimeout(() => {
      flushPendingChunks();
      clearChunkFlushTimer();
    }, 48);
  }

  function stageChunk(event: ChatChunkEvent) {
    const key = `${event.sessionId}:${event.messageId}`;
    const current = pendingChunkMap.value[key];

    pendingChunkMap.value = {
      ...pendingChunkMap.value,
      [key]: {
        sessionId: event.sessionId,
        messageId: event.messageId,
        delta: `${current?.delta || ''}${event.delta || ''}`,
        updatedAt: Date.now()
      }
    };

    scheduleChunkFlush();
  }

  async function fetchSessions() {
    loadingSessions.value = true;
    const { error, data } = await request<ChatSessionMeta[]>({ url: 'users/chat/sessions' });
    if (!error) {
      sessionList.value = (data || []).map(item => normalizeSessionMeta(item as any));
      if (activeSessionId.value && !sessionList.value.some(item => item.sessionId === activeSessionId.value)) {
        activeSessionId.value = '';
      }
    }
    loadingSessions.value = false;
  }

  async function createSession(options?: { title?: string | null }) {
    const { error, data } = await request<ChatSessionMeta>({ url: 'users/chat/sessions', method: 'POST' });
    if (!error && data) {
      let session = normalizeSessionMeta(data as any);
      upsertSession(session);
      activeSessionId.value = session.sessionId;
      draftsBySessionId.value[session.sessionId] = '';
      messagesBySessionId.value = { ...messagesBySessionId.value, [session.sessionId]: [] };
      delete draftsBySessionId.value[CHAT_HOME_DRAFT_KEY];

      const title = options?.title?.trim();
      if (title && title !== session.title) {
        const renamed = await renameSession(session.sessionId, title);
        if (renamed) {
          session = renamed;
        }
      }

      return session;
    }

    return null;
  }

  async function fetchMessages(sessionId = activeSessionId.value) {
    if (!sessionId) return;
    loadingMessages.value = true;
    const { error, data } = await request<ChatMessageRecord[]>({ url: `users/chat/sessions/${sessionId}/messages` });
    if (!error) {
      messagesBySessionId.value = {
        ...messagesBySessionId.value,
        [sessionId]: (data || []).map(item => normalizeMessage(item as any)).sort((a, b) => a.seq - b.seq)
      };
      nextTick(() => scrollToBottom.value?.());
    }
    loadingMessages.value = false;
  }

  async function activateSession(sessionId: string) {
    activeSessionId.value = sessionId;
    if (!messagesBySessionId.value[sessionId]) {
      await fetchMessages(sessionId);
    }
  }

  function clearActiveSession() {
    activeSessionId.value = '';
    resetStreamingState();
  }

  async function renameSession(sessionId: string, title: string) {
    const normalizedTitle = title.trim();
    if (!sessionId || !normalizedTitle) return null;

    const { error, data } = await request<ChatSessionMeta>({
      url: `users/chat/sessions/${sessionId}`,
      method: 'PATCH',
      data: { title: normalizedTitle }
    });

    if (!error && data) {
      const session = normalizeSessionMeta(data as any);
      upsertSession(session);
      return session;
    }

    return null;
  }

  async function deleteMessage(messageId: string) {
    const { error, data } = await request<DeleteMessageResponse>({ url: `users/chat/messages/${messageId}`, method: 'DELETE' });
    if (!error && data) {
      const target = normalizeMessage(data.message as any);
      removeMessage(target.sessionId, target.messageId);
      if (data.sessionMeta) {
        upsertSession(data.sessionMeta as any);
      }
      await fetchMessages(target.sessionId);
    }
  }

  async function deleteSession(sessionId: string) {
    const { error } = await request({ url: `users/chat/sessions/${sessionId}`, method: 'DELETE' });
    if (!error) {
      const deletingActive = activeSessionId.value === sessionId;
      sessionList.value = sessionList.value.filter(item => item.sessionId !== sessionId);
      messagesBySessionId.value = omitSessionState(messagesBySessionId.value, sessionId);
      draftsBySessionId.value = omitSessionState(draftsBySessionId.value, sessionId);
      if (deletingActive) {
        activeSessionId.value = '';
        resetStreamingState();
      }
    }
  }

  async function sendMessage(content?: string) {
    const draftKey = activeSessionId.value || CHAT_HOME_DRAFT_KEY;
    const message = (content ?? draftsBySessionId.value[draftKey] ?? '').trim();
    if (!message) return;

    let sessionId = activeSessionId.value;

    if (!sessionId) {
      const createdSession = await createSession({
        title: deriveSessionTitleFromMessage(message)
      });

      if (!createdSession) {
        return;
      }

      sessionId = createdSession.sessionId;
      activeSessionId.value = createdSession.sessionId;
    }

    draftsBySessionId.value[sessionId] = message;
    delete draftsBySessionId.value[CHAT_HOME_DRAFT_KEY];

    const requestId = crypto.randomUUID();
    pushLifecycleLog('message.requested', {
      sessionId,
      detail: `request:${requestId}`
    });
    activeRequestId.value = requestId;
    rawWsSend(JSON.stringify({ type: 'chat.send', requestId, sessionId, content: message }));
    draftsBySessionId.value[sessionId] = '';
  }

  async function regenerateMessage(message: ChatMessageRecord) {
    const requestId = crypto.randomUUID();
    pushLifecycleLog('message.regenerate', {
      sessionId: message.sessionId,
      messageId: message.messageId,
      detail: `request:${requestId}`
    });
    activeRequestId.value = requestId;
    rawWsSend(JSON.stringify({ type: 'chat.regenerate', requestId, sessionId: message.sessionId, messageId: message.messageId }));
  }

  async function stopMessage() {
    if (!activeRequestId.value || !activeSessionId.value) return;
    pushLifecycleLog('message.stop.requested', {
      sessionId: activeSessionId.value,
      messageId: activeStreamingMessageId.value,
      detail: `request:${activeRequestId.value}`
    });
    rawWsSend(JSON.stringify({
      type: 'chat.stop',
      requestId: activeRequestId.value,
      sessionId: activeSessionId.value,
      messageId: activeStreamingMessageId.value
    }));
  }

  function handleConnectionEvent(event: ChatConnectionEvent) {
    const currentWsSessionId = event.wsSessionId || event.sessionId;
    if (currentWsSessionId) {
      handshakeConfirmed.value = true;
      wsSessionId.value = currentWsSessionId;
    }
    pushLifecycleLog('connection', { sessionId: event.sessionId, detail: currentWsSessionId ? 'handshake-confirmed' : 'no-session-id' });
  }

  function handleAcceptedEvent(event: ChatAcceptedEvent) {
    upsertSession(event.sessionMeta);
    if (event.userMessage) {
      replaceMessage(event.sessionId, {
        ...event.userMessage,
        clientMessageId: event.requestId,
        lifecycle: 'sent'
      });
    }
    replaceMessage(event.sessionId, {
      ...event.assistantMessage,
      clientMessageId: event.requestId,
      lifecycle: resolveMessageLifecycle(event.assistantMessage.status)
    });
    activeSessionId.value = event.sessionId;
    activeStreamingMessageId.value = event.assistantMessage.messageId;
    pushLifecycleLog('message.accepted', {
      sessionId: event.sessionId,
      messageId: event.assistantMessage.messageId,
      detail: `request:${event.requestId}`
    });
    nextTick(() => scrollToBottom.value?.());
  }

  function handleChunkEvent(event: ChatChunkEvent) {
    patchMessage(event.sessionId, event.messageId, {
      status: 'loading',
      lifecycle: 'streaming'
    });
    stageChunk(event);
  }

  function handleCompletedEvent(event: ChatCompletedEvent) {
    flushPendingChunks(event.sessionId, event.messageId);
    replaceMessage(event.sessionId, {
      ...event.message,
      serverMessageId: event.messageId,
      lifecycle: 'finished'
    });
    upsertSession(event.sessionMeta);
    pushLifecycleLog('message.completed', {
      sessionId: event.sessionId,
      messageId: event.messageId,
      detail: `request:${event.requestId}`
    });
    resetStreamingState();
    nextTick(() => scrollToBottom.value?.());
  }

  function updateErroredMessage(event: ChatErrorEvent) {
    if (!event.sessionId || !event.messageId) {
      return;
    }

    flushPendingChunks(event.sessionId, event.messageId);
    patchMessage(event.sessionId, event.messageId, message => ({
      status: 'error',
      lifecycle: 'error',
      content: event.message || message.content || '服务器繁忙，请稍后再试'
    }));
  }

  function handleErrorEvent(event: ChatErrorEvent) {
    if (Number(event.code) === 429) {
      startRateLimitCountdown(Number(event.retryAfterSeconds || 0));
    }
    updateErroredMessage(event);
    pushLifecycleLog('message.error', {
      sessionId: event.sessionId,
      messageId: event.messageId,
      detail: event.message || `code:${event.code || 'unknown'}`
    });
    resetStreamingState();
    if (event.message) {
      if (Number(event.code) === 429) window.$message?.warning(event.message);
      else window.$message?.error(event.message);
    }
  }

  function handleStoppedEvent(event: ChatStoppedEvent) {
    if (event.sessionId && event.messageId) {
      flushPendingChunks(event.sessionId, event.messageId);
      patchMessage(event.sessionId, event.messageId, message => ({
        status: message.content ? 'finished' : 'pending',
        lifecycle: message.content ? 'cancelled' : 'draft'
      }));
    }
    pushLifecycleLog('message.stopped', {
      sessionId: event.sessionId,
      messageId: event.messageId,
      detail: 'manual-stop'
    });
    resetStreamingState();
  }

  function isKnownSocketEvent(event: unknown): event is ChatSocketEvent {
    return Boolean(event && typeof event === 'object' && 'type' in (event as Record<string, unknown>) && CHAT_SOCKET_EVENT_TYPES.has((event as ChatSocketEvent).type));
  }

  function handleSocketEvent(event: ChatSocketEvent) {
    if (event.type === 'connection') {
      handleConnectionEvent(event);
      return;
    }

    if (event.type === 'message.accepted') {
      handleAcceptedEvent(event);
      return;
    }

    if (event.type === 'message.chunk') {
      handleChunkEvent(event);
      return;
    }

    if (event.type === 'message.completed') {
      handleCompletedEvent(event);
      return;
    }

    if (event.type === 'message.error') {
      handleErrorEvent(event);
      return;
    }

    if (event.type === 'message.stopped') {
      handleStoppedEvent(event);
    }
  }

  function openReferencePreview(payload: ChatReferencePreviewPayload) {
    uiStore.openReferencePreview(payload);
  }

  function closeReferencePreview() {
    uiStore.closeReferencePreview();
  }

  function handleAuthReset() {
    clearRateLimitCountdown();
    clearChunkFlushTimer();
    resetConnectionState();
    activeSessionId.value = '';
    sessionList.value = [];
    messagesBySessionId.value = {};
    draftsBySessionId.value = {};
    pendingChunkMap.value = {};
    messageLifecycleLogs.value = [];
    uiStore.resetUiState();
    activeRequestId.value = '';
    activeStreamingMessageId.value = '';
    wsClose(1000, 'auth-reset');
  }

  watch(socketUrl, url => {
    resetConnectionState();
    if (!url) {
      wsClose();
      clearRateLimitCountdown();
      return;
    }
    wsOpen();
  }, { immediate: true });

  watch(wsData, val => {
    if (!val) return;
    try {
      const parsed = JSON.parse(val) as unknown;
      if (!isKnownSocketEvent(parsed)) {
        pushLifecycleLog('message.unknown', { detail: typeof parsed === 'object' ? JSON.stringify(parsed) : String(parsed) });
        return;
      }
      handleSocketEvent(parsed);
    } catch {
      // ignore non-json heartbeat payloads
    }
  });

  watch(activeSessionId, sessionId => {
    if (sessionId && !messagesBySessionId.value[sessionId]) {
      fetchMessages(sessionId);
    }
  });

  return {
    input,
    currentInput,
    sessionList,
    activeSessionId,
    activeMessages,
    messagesBySessionId,
    loadingSessions,
    loadingMessages,
    referencePreview: computed(() => uiStore.referencePreview),
    referencePreviewVisible: computed(() => uiStore.referencePreviewVisible),
    connectionStatus,
    isRateLimited,
    rateLimitRemainingSeconds,
    wsStatus,
    wsData,
    wsSend: rawWsSend,
    wsOpen,
    wsClose,
    wsSessionId,
    scrollToBottom,
    clearRateLimitCountdown,
    openReferencePreview,
    closeReferencePreview,
    startRateLimitCountdown,
    messageLifecycleLogs,
    handleAuthReset,
    fetchSessions,
    fetchMessages,
    activateSession,
    clearActiveSession,
    renameSession,
    createSession,
    deleteMessage,
    deleteSession,
    sendMessage,
    regenerateMessage,
    stopMessage,
    isSending,
    activeRequestId,
    activeStreamingMessageId
  };
});
