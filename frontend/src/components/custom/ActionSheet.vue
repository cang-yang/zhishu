<script setup lang="ts">
import SvgIcon from '@/components/custom/svg-icon.vue';

defineOptions({
  name: 'ActionSheet'
});

type ActionSheetOption = {
  key: string;
  label: string;
  icon?: string;
  danger?: boolean;
  disabled?: boolean;
};

withDefaults(
  defineProps<{
    show: boolean;
    title?: string;
    description?: string;
    options: ActionSheetOption[];
    cancelText?: string;
  }>(),
  {
    title: '',
    description: '',
    cancelText: '取消'
  }
);

const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'select', key: string): void;
}>();

function handleSelect(key: string, disabled?: boolean) {
  if (disabled) return;
  emit('select', key);
}
</script>

<template>
  <div class="action-sheet" :class="{ 'is-visible': show }">
    <button type="button" class="action-sheet__mask" aria-label="关闭操作面板" @click="emit('close')" />

    <section class="action-sheet__panel">
      <div class="action-sheet__handle" />

      <header v-if="title || description" class="action-sheet__header">
        <strong v-if="title" class="action-sheet__title">{{ title }}</strong>
        <span v-if="description" class="action-sheet__description">{{ description }}</span>
      </header>

      <div class="action-sheet__list">
        <button
          v-for="item in options"
          :key="item.key"
          type="button"
          class="action-sheet__item"
          :class="{ 'is-danger': item.danger, 'is-disabled': item.disabled }"
          :disabled="item.disabled"
          @click="handleSelect(item.key, item.disabled)"
        >
          <SvgIcon v-if="item.icon" :icon="item.icon" class="text-18px" />
          <span>{{ item.label }}</span>
        </button>
      </div>

      <button type="button" class="action-sheet__cancel" @click="emit('close')">{{ cancelText }}</button>
      <div class="action-sheet__safe-area" />
    </section>
  </div>
</template>

<style scoped lang="scss">
.action-sheet {
  position: fixed;
  inset: 0;
  z-index: 80;
  pointer-events: none;

  &.is-visible {
    pointer-events: auto;

    .action-sheet__mask {
      opacity: 1;
    }

    .action-sheet__panel {
      transform: translateY(0);
    }
  }

  &__mask {
    position: absolute;
    inset: 0;
    background: rgba(2, 6, 23, 0.5);
    opacity: 0;
    transition: opacity 0.28s ease;
  }

  &__panel {
    position: absolute;
    right: 0;
    bottom: 0;
    left: 0;
    display: flex;
    flex-direction: column;
    gap: 14px;
    border-top-left-radius: 24px;
    border-top-right-radius: 24px;
    background: var(--app-elevated-bg);
    padding: 10px 16px 0;
    transform: translateY(100%);
    transition: transform 0.28s ease;
    backdrop-filter: blur(18px);
    -webkit-backdrop-filter: blur(18px);
  }

  &__handle {
    align-self: center;
    width: 52px;
    height: 5px;
    border-radius: 999px;
    background: rgba(100, 116, 139, 0.58);
  }

  &__header {
    display: flex;
    flex-direction: column;
    gap: 6px;
    padding: 0 2px;
    text-align: center;
  }

  &__title {
    color: var(--app-text-primary);
    font-size: 16px;
    font-weight: 700;
  }

  &__description {
    color: var(--app-text-secondary);
    font-size: 12px;
    line-height: 1.5;
  }

  &__list {
    display: flex;
    flex-direction: column;
    gap: 10px;
  }

  &__item,
  &__cancel {
    display: flex;
    width: 100%;
    align-items: center;
    justify-content: center;
    gap: 10px;
    border: 1px solid var(--app-border);
    border-radius: 18px;
    background: var(--app-control-bg);
    padding: 14px 16px;
    color: var(--app-text-primary);
    font-size: 14px;
    font-weight: 600;
  }

  &__item.is-danger {
    color: #ef4444;
  }

  &__item.is-disabled {
    opacity: 0.5;
  }

  &__safe-area {
    height: env(safe-area-inset-bottom);
  }
}
</style>
