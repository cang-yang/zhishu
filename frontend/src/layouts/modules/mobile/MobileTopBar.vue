<script setup lang="ts">
import SvgIcon from '@/components/custom/svg-icon.vue';

defineOptions({
  name: 'MobileTopBar'
});

type MobileTopBarAction = {
  key: string;
  label: string;
  icon: string;
};

const props = withDefaults(
  defineProps<{
    title: string;
    subtitle?: string;
    leadingIcon?: string;
    leadingLabel?: string;
    actions?: MobileTopBarAction[];
    avatarText?: string;
    avatarLabel?: string;
  }>(),
  {
    subtitle: '',
    leadingIcon: 'solar:hamburger-menu-linear',
    leadingLabel: '打开菜单',
    actions: () => [],
    avatarText: 'U',
    avatarLabel: '个人中心'
  }
);

const emit = defineEmits<{
  (e: 'leading-click'): void;
  (e: 'action', key: string): void;
  (e: 'avatar-click'): void;
}>();

const normalizedAvatarText = computed(() => {
  return (props.avatarText || 'U').slice(0, 1).toUpperCase();
});
</script>

<template>
  <header class="mobile-top-bar">
    <div class="mobile-top-bar__safe-area" />
    <div class="mobile-top-bar__inner">
      <button
        type="button"
        class="mobile-top-bar__icon-button"
        :aria-label="leadingLabel"
        @click="emit('leading-click')"
      >
        <SvgIcon :icon="leadingIcon" class="text-20px" />
      </button>

      <div class="mobile-top-bar__headline">
        <strong class="mobile-top-bar__title">{{ title }}</strong>
        <span v-if="subtitle" class="mobile-top-bar__subtitle">{{ subtitle }}</span>
      </div>

      <div class="mobile-top-bar__actions">
        <button
          v-for="item in actions"
          :key="item.key"
          type="button"
          class="mobile-top-bar__icon-button"
          :aria-label="item.label"
          @click="emit('action', item.key)"
        >
          <SvgIcon :icon="item.icon" class="text-18px" />
        </button>

        <button type="button" class="mobile-top-bar__avatar" :aria-label="avatarLabel" @click="emit('avatar-click')">
          <span>{{ normalizedAvatarText }}</span>
        </button>
      </div>
    </div>
  </header>
</template>

<style scoped lang="scss">
.mobile-top-bar {
  position: fixed;
  top: 0;
  right: 0;
  left: 0;
  z-index: 40;
  border-bottom: 1px solid var(--app-border);
  background: color-mix(in srgb, var(--color-bg-container) 86%, transparent);
  backdrop-filter: blur(18px);
  -webkit-backdrop-filter: blur(18px);

  &__safe-area {
    height: env(safe-area-inset-top);
  }

  &__inner {
    display: flex;
    height: var(--mobile-top-bar-height, 56px);
    align-items: center;
    gap: 8px;
    padding: 0 12px;
  }

  &__headline {
    display: flex;
    min-width: 0;
    flex: 1;
    flex-direction: column;
    gap: 2px;
    text-align: center;
  }

  &__title {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    color: var(--app-text-primary);
    font-size: 16px;
    font-weight: 700;
  }

  &__subtitle {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    color: var(--app-text-secondary);
    font-size: 11px;
    line-height: 1.2;
  }

  &__actions {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  &__icon-button,
  &__avatar {
    display: inline-flex;
    height: 38px;
    width: 38px;
    align-items: center;
    justify-content: center;
    border: 1px solid var(--app-border);
    border-radius: 14px;
    background: var(--app-control-bg);
    color: var(--app-text-primary);
    flex-shrink: 0;
  }

  &__avatar {
    background: linear-gradient(135deg, #6d7cff, #2dd4bf);
    color: #fff;
    font-size: 14px;
    font-weight: 700;
  }
}
</style>
