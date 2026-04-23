<script setup lang="ts">
import ChatStageShell from './modules/chat-stage-shell.vue';

defineOptions({
  name: 'ChatView'
});
</script>

<template>
  <div class="chat-view-shell">
    <ChatStageShell />
  </div>
</template>

<style scoped>
.chat-view-shell {
  height: 100%;
  min-height: 0;
}
</style>
