import { ref } from 'vue';
import { defineStore } from 'pinia';
import { request } from '@/service/request';

type AdminChatUser = {
  userId: number;
  username: string;
  sessionCount: number;
  lastActiveAt?: string | null;
};

type AdminChatSession = {
  sessionId: string;
  title: string;
  updatedAt?: string | null;
  latestPreview?: string | null;
  messageCount?: number;
};

type AdminChatMessage = Api.Chat.Message & {
  messageId?: string;
  sessionId?: string;
  seq?: number;
};

export const useAdminChatStore = defineStore('admin-chat-store', () => {
  const users = ref<AdminChatUser[]>([]);
  const sessions = ref<AdminChatSession[]>([]);
  const messages = ref<AdminChatMessage[]>([]);
  const loadingUsers = ref(false);
  const loadingSessions = ref(false);
  const loadingMessages = ref(false);
  const userId = ref<number | null>(null);
  const activeSessionId = ref('');
  const initialized = ref(false);

  function resetState() {
    users.value = [];
    sessions.value = [];
    messages.value = [];
    loadingUsers.value = false;
    loadingSessions.value = false;
    loadingMessages.value = false;
    userId.value = null;
    activeSessionId.value = '';
    initialized.value = false;
  }

  function normalizeUsers(payload: unknown) {
    if (Array.isArray(payload)) return payload as AdminChatUser[];

    const data = payload as { records?: AdminChatUser[]; list?: AdminChatUser[]; content?: AdminChatUser[] } | null;
    return data?.records || data?.list || data?.content || [];
  }

  async function fetchUsers() {
    loadingUsers.value = true;

    try {
      const { error, data } = await request<AdminChatUser[]>({ url: '/admin/chat/users' });
      if (!error) {
        users.value = normalizeUsers(data);

        if (!users.value.length) {
          userId.value = null;
          return;
        }

        const matched = users.value.find(item => item.userId === userId.value);
        if (!matched) {
          userId.value = users.value[0]?.userId ?? null;
        }
      }
    } finally {
      loadingUsers.value = false;
    }
  }

  async function fetchMessages(sessionId = activeSessionId.value) {
    if (!userId.value || !sessionId) {
      messages.value = [];
      return;
    }

    loadingMessages.value = true;

    try {
      const { error, data } = await request<{ sessionMeta: AdminChatSession; messages: AdminChatMessage[] }>({
        url: `/admin/chat/users/${userId.value}/sessions/${sessionId}/messages`
      });

      if (!error && data) {
        messages.value = (data.messages || []).sort((a, b) => Number(a.seq || 0) - Number(b.seq || 0));
      }
    } finally {
      loadingMessages.value = false;
    }
  }

  async function fetchSessions() {
    if (!userId.value) {
      sessions.value = [];
      messages.value = [];
      activeSessionId.value = '';
      return;
    }

    loadingSessions.value = true;

    try {
      const { error, data } = await request<{ userId: number; username: string; sessions: AdminChatSession[] }>({
        url: `/admin/chat/users/${userId.value}/sessions`
      });

      if (!error && data) {
        sessions.value = data.sessions || [];
        const nextSessionId = sessions.value.find(item => item.sessionId === activeSessionId.value)?.sessionId || sessions.value[0]?.sessionId || '';
        activeSessionId.value = nextSessionId;

        if (nextSessionId) {
          await fetchMessages(nextSessionId);
        } else {
          messages.value = [];
        }
      }
    } finally {
      loadingSessions.value = false;
    }
  }

  async function selectUser(value: number) {
    if (userId.value === value) return;

    userId.value = value;
    activeSessionId.value = '';
    sessions.value = [];
    messages.value = [];
    await fetchSessions();
  }

  async function selectSession(sessionId: string) {
    if (activeSessionId.value === sessionId) return;
    activeSessionId.value = sessionId;
    await fetchMessages(sessionId);
  }

  async function ensureInitialized(force = false) {
    if (initialized.value && !force) return;

    await fetchUsers();
    await fetchSessions();
    initialized.value = true;
  }

  return {
    users,
    sessions,
    messages,
    loadingUsers,
    loadingSessions,
    loadingMessages,
    userId,
    activeSessionId,
    initialized,
    resetState,
    fetchUsers,
    fetchSessions,
    fetchMessages,
    selectUser,
    selectSession,
    ensureInitialized
  };
});
