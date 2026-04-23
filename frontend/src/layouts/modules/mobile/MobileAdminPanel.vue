<script setup lang="ts">
import SvgIcon from '@/components/custom/svg-icon.vue';

defineOptions({
  name: 'MobileAdminPanel'
});

type MobileAdminItem = {
  key: string;
  label: string;
  icon: string;
};

defineProps<{
  show: boolean;
  items: MobileAdminItem[];
}>();

const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'select', key: string): void;
}>();
</script>

<template>
  <div class="mobile-admin-panel" :class="{ 'is-visible': show }">
    <button type="button" class="mobile-admin-panel__mask" aria-label="关闭管理面板" @click="emit('close')" />

    <section class="mobile-admin-panel__sheet">
      <div class="mobile-admin-panel__handle" />
      <header class="mobile-admin-panel__header">管理功能</header>

      <div class="mobile-admin-panel__list">
        <button
          v-for="item in items"
          :key="item.key"
          type="button"
          class="mobile-admin-panel__item"
          @click="emit('select', item.key)"
        >
          <SvgIcon :icon="item.icon" class="text-18px" />
          <span>{{ item.label }}</span>
        </button>
      </div>

      <button type="button" class="mobile-admin-panel__cancel" @click="emit('close')">取消</button>
      <div class="mobile-admin-panel__safe-area" />
    </section>
  </div>
</template>

<style scoped lang="scss">
.mobile-admin-panel {
  position: fixed;
  inset: 0;
  z-index: 60;
  pointer-events: none;

  &.is-visible {
    pointer-events: auto;

    .mobile-admin-panel__mask {
      opacity: 1;
    }

    .mobile-admin-panel__sheet {
      transform: translateY(0);
    }
  }

  &__mask {
    position: absolute;
    inset: 0;
    background: rgba(2, 6, 23, 0.5);
    opacity: 0;
    transition: opacity 0.3s ease;
  }

  &__sheet {
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
    transition: transform 0.3s ease;
    backdrop-filter: blur(20px);
    -webkit-backdrop-filter: blur(20px);
  }

  &__handle {
    align-self: center;
    width: 52px;
    height: 5px;
    border-radius: 999px;
    background: rgba(100, 116, 139, 0.64);
  }

  &__header {
    text-align: center;
    color: var(--app-text-primary);
    font-size: 16px;
    font-weight: 700;
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

  &__cancel {
    margin-top: 2px;
  }

  &__safe-area {
    height: env(safe-area-inset-bottom);
  }
}
</style>
