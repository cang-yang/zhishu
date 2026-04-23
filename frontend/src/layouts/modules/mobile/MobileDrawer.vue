<script setup lang="ts">
import SvgIcon from '@/components/custom/svg-icon.vue';

defineOptions({
  name: 'MobileDrawer'
});

type MobileNavItem = {
  key: string;
  label: string;
  icon: string;
};

defineProps<{
  show: boolean;
  username?: string;
  role?: string;
  activeKey: string;
  primaryItems: MobileNavItem[];
  adminItems: MobileNavItem[];
  isAdmin: boolean;
}>();

const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'select', key: string): void;
  (e: 'profile'): void;
  (e: 'logout'): void;
}>();
</script>

<template>
  <div class="mobile-drawer" :class="{ 'is-visible': show }" aria-hidden="true">
    <button type="button" class="mobile-drawer__mask" aria-label="关闭菜单" @click="emit('close')" />

    <aside class="mobile-drawer__panel" aria-hidden="false">
      <div class="mobile-drawer__safe-area" />

      <header class="mobile-drawer__header">
        <button type="button" class="mobile-drawer__profile" @click="emit('profile')">
          <div class="mobile-drawer__avatar">
            <span>{{ (username || 'U').slice(0, 1).toUpperCase() }}</span>
          </div>

          <div class="mobile-drawer__profile-copy">
            <strong>{{ username || '未登录用户' }}</strong>
            <span>{{ role || 'USER' }}</span>
          </div>
        </button>

        <button type="button" class="mobile-drawer__close" aria-label="关闭菜单" @click="emit('close')">
          <SvgIcon icon="solar:close-circle-linear" class="text-20px" />
        </button>
      </header>

      <section class="mobile-drawer__section">
        <button
          v-for="item in primaryItems"
          :key="item.key"
          type="button"
          class="mobile-drawer__item"
          :class="{ 'is-active': item.key === activeKey }"
          @click="emit('select', item.key)"
        >
          <SvgIcon :icon="item.icon" class="text-18px" />
          <span>{{ item.label }}</span>
        </button>
      </section>

      <section v-if="isAdmin" class="mobile-drawer__section mobile-drawer__section--fill">
        <div class="mobile-drawer__section-title">管理（仅管理员）</div>
        <div class="mobile-drawer__section-body">
          <button
            v-for="item in adminItems"
            :key="item.key"
            type="button"
            class="mobile-drawer__item"
            :class="{ 'is-active': item.key === activeKey }"
            @click="emit('select', item.key)"
          >
            <SvgIcon :icon="item.icon" class="text-18px" />
            <span>{{ item.label }}</span>
          </button>
        </div>
      </section>

      <footer class="mobile-drawer__footer">
        <button type="button" class="mobile-drawer__logout" @click="emit('logout')">
          <SvgIcon icon="solar:logout-2-linear" class="text-18px" />
          <span>退出登录</span>
        </button>
      </footer>
    </aside>
  </div>
</template>

<style scoped lang="scss">
.mobile-drawer {
  position: fixed;
  inset: 0;
  z-index: 55;
  pointer-events: none;

  &.is-visible {
    pointer-events: auto;

    .mobile-drawer__mask {
      opacity: 1;
    }

    .mobile-drawer__panel {
      transform: translateX(0);
    }
  }

  &__mask {
    position: absolute;
    inset: 0;
    background: rgba(2, 6, 23, 0.5);
    opacity: 0;
    transition: opacity 0.3s ease;
  }

  &__panel {
    position: relative;
    z-index: 1;
    display: flex;
    height: 100%;
    width: min(80vw, 320px);
    flex-direction: column;
    gap: 18px;
    border-right: 1px solid var(--app-border);
    background: var(--app-elevated-bg);
    padding: 0 14px 18px;
    transform: translateX(-100%);
    transition: transform 0.3s ease;
    backdrop-filter: blur(20px);
    -webkit-backdrop-filter: blur(20px);
  }

  &__safe-area {
    height: env(safe-area-inset-top);
    flex-shrink: 0;
  }

  &__header {
    display: flex;
    align-items: center;
    gap: 12px;
  }

  &__profile {
    display: flex;
    min-width: 0;
    flex: 1;
    align-items: center;
    gap: 12px;
    border: 1px solid var(--app-border);
    border-radius: 18px;
    background: var(--app-control-bg);
    padding: 12px;
    text-align: left;
  }

  &__avatar,
  &__close {
    display: inline-flex;
    height: 42px;
    width: 42px;
    align-items: center;
    justify-content: center;
    border-radius: 14px;
    flex-shrink: 0;
  }

  &__avatar {
    background: linear-gradient(135deg, #6d7cff, #2dd4bf);
    color: #fff;
    font-size: 16px;
    font-weight: 700;
  }

  &__close {
    border: 1px solid var(--app-border);
    background: var(--app-control-bg);
    color: var(--app-text-primary);
  }

  &__profile-copy {
    display: flex;
    min-width: 0;
    flex-direction: column;
    gap: 4px;

    strong {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      font-size: 15px;
    }

    span {
      color: var(--app-text-secondary);
      font-size: 12px;
      letter-spacing: 0.08em;
      text-transform: uppercase;
    }
  }

  &__section {
    display: flex;
    flex-direction: column;
    gap: 10px;

    &--fill {
      min-height: 0;
      flex: 1;
    }
  }

  &__section-title {
    padding: 0 4px;
    color: var(--app-text-secondary);
    font-size: 12px;
    font-weight: 700;
    letter-spacing: 0.08em;
  }

  &__section-body {
    display: flex;
    min-height: 0;
    flex-direction: column;
    gap: 8px;
    overflow: auto;
  }

  &__item,
  &__logout {
    display: flex;
    width: 100%;
    align-items: center;
    gap: 10px;
    border: 1px solid var(--app-border);
    border-radius: 18px;
    background: var(--app-control-bg);
    padding: 12px 14px;
    color: var(--app-text-primary);
    font-size: 14px;
    font-weight: 600;
    text-align: left;

    &.is-active {
      border-color: rgba(96, 165, 250, 0.4);
      background: var(--app-accent-soft);
    }
  }

  &__footer {
    padding-top: 4px;
  }

  &__logout {
    color: #ef4444;
  }
}
</style>
