<script setup lang="ts">
defineOptions({
  name: 'WorkspaceWindow'
});

interface Props {
  visible: boolean;
  title: string;
  badge?: string;
  meta?: string;
  mode?: 'standard' | 'immersive' | 'fullscreen';
  stackDepth?: number;
  width?: string;
  height?: string;
  fullscreen?: boolean;
  closeOnBackdrop?: boolean;
  closable?: boolean;
}

const props = withDefaults(defineProps<Props>(), {
  badge: '工作窗',
  meta: '',
  mode: 'standard',
  stackDepth: 1,
  width: 'min(1480px, calc(100vw - 40px))',
  height: 'min(92vh, 960px)',
  fullscreen: false,
  closeOnBackdrop: true,
  closable: true
});

const emit = defineEmits<{
  close: [];
  cycleMode: [];
}>();

const panelStyle = computed(() => {
  if (props.fullscreen || props.mode === 'fullscreen') {
    return {
      width: 'calc(100vw - 20px)',
      height: 'calc(100vh - 20px)'
    };
  }

  if (props.mode === 'immersive') {
    return {
      width: 'min(1600px, calc(100vw - 28px))',
      height: 'min(94vh, 980px)'
    };
  }

  return {
    width: props.width,
    height: props.height
  };
});

const modeLabel = computed(() => {
  if (props.mode === 'immersive') return '沉浸';
  if (props.mode === 'fullscreen') return '全屏';
  return '标准';
});

function handleClose() {
  emit('close');
}

function handleCycleMode() {
  emit('cycleMode');
}

function handleBackdropClose() {
  if (props.closeOnBackdrop && props.closable) {
    handleClose();
  }
}
</script>

<template>
  <Teleport to="body">
    <Transition name="workspace-window">
      <div v-if="visible" class="workspace-window" :class="[`workspace-window--${mode}`]">
        <div class="workspace-window__backdrop" @click="handleBackdropClose" />

        <section
          class="workspace-window__panel"
          :style="panelStyle"
          role="dialog"
          aria-modal="true"
          :aria-label="title"
        >
          <header class="workspace-window__header">
            <div class="workspace-window__heading">
              <span class="workspace-window__badge">{{ badge }}</span>
              <div class="workspace-window__title-group">
                <h3 class="workspace-window__title">{{ title }}</h3>
                <p v-if="meta" class="workspace-window__meta">{{ meta }}</p>
              </div>
            </div>

            <div class="workspace-window__actions">
              <span class="workspace-window__mode-pill">{{ modeLabel }}</span>
              <span v-if="stackDepth > 1" class="workspace-window__stack-pill">栈层 {{ stackDepth }}</span>
              <slot name="header-extra" />
              <NButton quaternary class="workspace-window__mode-switch" @click="handleCycleMode">
                <template #icon>
                  <icon-solar:widget-5-linear />
                </template>
                切换视图
              </NButton>
              <NButton v-if="closable" quaternary circle class="workspace-window__close" @click="handleClose">
                <template #icon>
                  <icon-mdi-close />
                </template>
              </NButton>
            </div>
          </header>

          <div class="workspace-window__body">
            <slot />
          </div>
        </section>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped lang="scss">
.workspace-window {
  position: fixed;
  inset: 0;
  z-index: 2100;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;

  &__backdrop {
    position: absolute;
    inset: 0;
    background: radial-gradient(circle at top, rgba(79, 124, 255, 0.16), transparent 42%), rgba(15, 23, 42, 0.52);
    backdrop-filter: blur(18px);
  }

  &__panel {
    position: relative;
    z-index: 1;
    display: flex;
    min-height: 680px;
    flex-direction: column;
    overflow: hidden;
    border: 1px solid rgba(226, 232, 240, 0.8);
    border-radius: 28px;
    background: rgba(255, 255, 255, 0.9);
    box-shadow: 0 28px 80px rgba(15, 23, 42, 0.18);
    backdrop-filter: blur(18px);
  }

  &__header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    padding: 18px 20px 14px;
    border-bottom: 1px solid rgba(226, 232, 240, 0.7);
    background: linear-gradient(180deg, rgba(248, 250, 252, 0.92) 0%, rgba(255, 255, 255, 0.8) 100%);
  }

  &__heading {
    display: flex;
    min-width: 0;
    align-items: center;
    gap: 14px;
  }

  &__badge {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    height: 30px;
    padding: 0 12px;
    border-radius: 999px;
    background: rgba(79, 124, 255, 0.12);
    color: rgb(var(--primary-color));
    font-size: 12px;
    font-weight: 700;
    letter-spacing: 0.04em;
    flex-shrink: 0;
  }

  &__title-group {
    min-width: 0;
  }

  &__title {
    margin: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    color: rgb(var(--base-text-color));
    font-size: 18px;
    font-weight: 700;
  }

  &__meta {
    margin: 4px 0 0;
    color: rgb(100 116 139);
    font-size: 13px;
  }

  &__actions {
    display: inline-flex;
    align-items: center;
    flex-wrap: wrap;
    justify-content: flex-end;
    gap: 8px;
    flex-shrink: 0;
  }

  &__mode-pill,
  &__stack-pill {
    display: inline-flex;
    align-items: center;
    height: 30px;
    padding: 0 12px;
    border-radius: 999px;
    background: rgba(15, 23, 42, 0.06);
    color: rgb(71 85 105);
    font-size: 12px;
    font-weight: 700;
  }

  &__mode-switch {
    border-radius: 999px;
  }

  &__body {
    min-height: 0;
    flex: 1;
    padding: 16px;
    background: linear-gradient(180deg, rgba(248, 250, 252, 0.54) 0%, rgba(255, 255, 255, 0.9) 100%);
  }
}

.workspace-window-enter-active,
.workspace-window-leave-active {
  transition: opacity 0.22s ease;
}

.workspace-window-enter-active .workspace-window__panel,
.workspace-window-leave-active .workspace-window__panel {
  transition:
    transform 0.22s ease,
    opacity 0.22s ease;
}

.workspace-window-enter-from,
.workspace-window-leave-to {
  opacity: 0;
}

.workspace-window-enter-from .workspace-window__panel,
.workspace-window-leave-to .workspace-window__panel {
  opacity: 0;
  transform: translateY(14px) scale(0.985);
}

.dark {
  .workspace-window {
    &__panel {
      border-color: rgba(71, 85, 105, 0.42);
      background: rgba(15, 23, 42, 0.9);
      box-shadow: 0 30px 90px rgba(2, 6, 23, 0.44);
    }

    &__header {
      border-bottom-color: rgba(71, 85, 105, 0.4);
      background: linear-gradient(180deg, rgba(30, 41, 59, 0.92) 0%, rgba(15, 23, 42, 0.84) 100%);
    }

    &__meta {
      color: rgb(148 163 184);
    }

    &__mode-pill,
    &__stack-pill {
      background: rgba(15, 23, 42, 0.84);
      color: rgb(203 213 225);
    }

    &__body {
      background: linear-gradient(180deg, rgba(15, 23, 42, 0.72) 0%, rgba(2, 6, 23, 0.88) 100%);
    }
  }
}

@media (max-width: 960px) {
  .workspace-window {
    padding: 12px;

    &__panel {
      max-width: calc(100vw - 24px);
      max-height: calc(100vh - 24px);
      min-height: 0;
      border-radius: 22px;
    }

    &__header {
      align-items: flex-start;
      padding: 16px 16px 12px;
    }

    &__heading {
      align-items: flex-start;
      flex-direction: column;
      gap: 10px;
    }

    &__body {
      padding: 10px;
    }
  }
}
</style>
