import { defineStore, storeToRefs } from 'pinia';
import { useChatStore } from './index';

export const useChatConnectionStore = defineStore('chat-connection-store', () => {
  const chatStore = useChatStore();
  const { connectionStatus, wsStatus, wsSessionId, isRateLimited, rateLimitRemainingSeconds } = storeToRefs(chatStore);

  function open() {
    chatStore.wsOpen();
  }

  function close(code?: number, reason?: string) {
    chatStore.wsClose(code, reason);
  }

  return {
    connectionStatus,
    wsStatus,
    wsSessionId,
    isRateLimited,
    rateLimitRemainingSeconds,
    open,
    close
  };
});
