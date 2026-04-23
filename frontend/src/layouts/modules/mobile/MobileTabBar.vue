<script setup lang="ts">
import SvgIcon from '@/components/custom/svg-icon.vue';

defineOptions({
  name: 'MobileTabBar'
});

type MobileTabItem = {
  key: string;
  label: string;
  icon: string;
};

defineProps<{
  items: MobileTabItem[];
  activeKey: string;
}>();

const emit = defineEmits<{
  (e: 'select', key: string): void;
}>();
</script>

<template>
  <nav class="mobile-tab-bar" aria-label="底部导航">
    <div class="mobile-tab-bar__inner">
      <button
        v-for="item in items"
        :key="item.key"
        type="button"
        class="mobile-tab-bar__item"
        :class="{ 'is-active': item.key === activeKey }"
        @click="emit('select', item.key)"
      >
        <SvgIcon :icon="item.icon" class="text-20px" />
        <span>{{ item.label }}</span>
      </button>
    </div>
    <div class="mobile-tab-bar__safe-area" />
  </nav>
</template>

<style scoped lang="scss">
.mobile-tab-bar {
  position: fixed;
  right: 0;
  bottom: 0;
  left: 0;
  z-index: 40;
  border-top: 1px solid var(--app-border);
  background: color-mix(in srgb, var(--color-bg-container) 88%, transparent);
  backdrop-filter: blur(18px);
  -webkit-backdrop-filter: blur(18px);

  &__inner {
    display: grid;
    min-height: var(--mobile-tab-bar-height, 60px);
    grid-auto-flow: column;
    grid-auto-columns: 1fr;
    align-items: stretch;
  }

  &__item {
    display: flex;
    min-width: 0;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 4px;
    background: transparent;
    color: var(--app-text-secondary);
    font-size: 11px;
    font-weight: 600;
    transition: color 0.2s ease;

    &.is-active {
      color: rgb(var(--primary-color));
    }
  }

  &__safe-area {
    height: env(safe-area-inset-bottom);
  }
}
</style>
