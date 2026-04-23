import { defineStore, storeToRefs } from 'pinia';
import { useChatStore } from './index';

export const useChatMessageStore = defineStore('chat-message-store', () => {
  const chatStore = useChatStore();
  const {
    activeMessages,
    messagesBySessionId,
    currentInput,
    isSending,
    activeRequestId,
    activeStreamingMessageId,
    isRateLimited,
    rateLimitRemainingSeconds,
    messageLifecycleLogs
  } = storeToRefs(chatStore);

  async function sendMessage(content?: string) {
    await chatStore.sendMessage(content);
  }

  async function stopMessage() {
    await chatStore.stopMessage();
  }

  async function regenerateMessage(message: Parameters<typeof chatStore.regenerateMessage>[0]) {
    await chatStore.regenerateMessage(message);
  }

  async function deleteMessage(messageId: string) {
    await chatStore.deleteMessage(messageId);
  }

  return {
    activeMessages,
    messagesBySessionId,
    currentInput,
    isSending,
    isRateLimited,
    rateLimitRemainingSeconds,
    messageLifecycleLogs,
    activeRequestId,
    activeStreamingMessageId,
    sendMessage,
    stopMessage,
    regenerateMessage,
    deleteMessage
  };
});
