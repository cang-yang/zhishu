import { computed } from 'vue';
import { defineStore, storeToRefs } from 'pinia';
import { useChatStore } from './index';

export const useChatSessionStore = defineStore('chat-session-store', () => {
  const chatStore = useChatStore();
  const { sessionList, activeSessionId, loadingSessions } = storeToRefs(chatStore);

  const activeSession = computed(() => {
    return sessionList.value.find(item => item.sessionId === activeSessionId.value) || null;
  });

  async function fetchSessions() {
    await chatStore.fetchSessions();
  }

  async function createSession(options?: { title?: string | null }) {
    return chatStore.createSession(options);
  }

  async function activateSession(sessionId: string) {
    await chatStore.activateSession(sessionId);
  }

  function clearActiveSession() {
    chatStore.clearActiveSession();
  }

  async function renameSession(sessionId: string, title: string) {
    return chatStore.renameSession(sessionId, title);
  }

  async function deleteSession(sessionId: string) {
    await chatStore.deleteSession(sessionId);
  }

  return {
    sessionList,
    activeSessionId,
    activeSession,
    loadingSessions,
    fetchSessions,
    createSession,
    activateSession,
    clearActiveSession,
    renameSession,
    deleteSession
  };
});
