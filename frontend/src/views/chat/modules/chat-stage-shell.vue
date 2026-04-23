<script setup lang="ts">
import ChatList from './chat-list.vue';
import InputBox from './input-box.vue';

defineOptions({
  name: 'ChatStageShell'
});
</script>

<template>
  <section class="chat-stage-shell">
    <div class="chat-stage-shell__panel">
      <div class="chat-stage-shell__messages">
        <ChatList />
      </div>
      <footer class="chat-stage-shell__composer">
        <InputBox />
      </footer>
    </div>
  </section>
</template>

<style scoped lang="scss">
.chat-stage-shell {
  height: 100%;
  min-height: 0;

  &__panel {
    display: flex;
    height: 100%;
    min-height: 0;
    overflow: hidden;
    flex-direction: column;
    border: 1px solid var(--app-border);
    border-radius: 30px;
    background: radial-gradient(circle at top, rgba(59, 130, 246, 0.08), transparent 26%), var(--app-surface-bg);
    box-shadow: var(--app-surface-shadow);
  }

  &__messages {
    min-height: 0;
    flex: 1;
    overflow: hidden;
    padding: 28px 0 12px;
  }

  &__composer {
    flex-shrink: 0;
    padding: 0 28px 28px;
  }
}

@media (max-width: 768px) {
  .chat-stage-shell {
    height: 100%;
    min-height: 0;

    &__panel {
      height: 100%;
      min-height: 0;
      border: 0;
      border-radius: 18px;
      background: transparent;
      box-shadow: none;
    }

    &__messages {
      min-height: 0;
      overflow: hidden;
      padding: 10px 0 132px;
    }

    &__composer {
      position: fixed;
      right: 0;
      bottom: calc(env(safe-area-inset-bottom) + var(--mobile-tab-bar-height, 60px));
      left: 0;
      z-index: 18;
      padding: 8px 12px 12px;
      background: linear-gradient(
        180deg,
        rgba(0, 0, 0, 0) 0%,
        color-mix(in srgb, var(--app-layout-bg) 94%, transparent) 36%
      );
    }
  }
}
</style>
