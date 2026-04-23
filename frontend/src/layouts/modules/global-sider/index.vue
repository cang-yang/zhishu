<script setup lang="ts">
import { computed } from 'vue';
import { GLOBAL_SIDER_MENU_ID } from '@/constants/app';
import { useAppStore } from '@/store/modules/app';
import { useThemeStore } from '@/store/modules/theme';
import GlobalLogo from '../global-logo/index.vue';

defineOptions({
  name: 'GlobalSider'
});

const appStore = useAppStore();
const themeStore = useThemeStore();

const isVerticalMix = computed(() => themeStore.layout.mode === 'vertical-mix');
const isHorizontalMix = computed(() => themeStore.layout.mode === 'horizontal-mix');
const darkMenu = computed(() => !themeStore.darkMode && !isHorizontalMix.value && themeStore.sider.inverted);
const showLogo = computed(() => !isVerticalMix.value && !isHorizontalMix.value);
const menuWrapperClass = computed(() => (showLogo.value ? 'flex-1-hidden' : 'h-full'));
</script>

<template>
  <DarkModeContainer class="global-sider size-full" :inverted="darkMenu">
    <div class="global-sider__panel app-surface-card">
      <div v-if="showLogo" class="global-sider__brand" :style="{ minHeight: themeStore.header.height + 'px' }">
        <GlobalLogo :show-title="!appStore.siderCollapse" />
      </div>
      <div :id="GLOBAL_SIDER_MENU_ID" class="global-sider__menu" :class="[menuWrapperClass]"></div>
    </div>
  </DarkModeContainer>
</template>

<style scoped lang="scss">
.global-sider {
  padding: 16px 0 20px 20px;

  &__panel {
    display: flex;
    height: 100%;
    flex-direction: column;
    overflow: hidden;
  }

  &__brand {
    display: flex;
    align-items: center;
    padding: 0 20px;
    border-bottom: 1px solid rgba(226, 232, 240, 0.85);
  }

  &__menu {
    min-height: 0;
    padding: 14px 12px 16px;
  }
}

:deep(.n-menu) {
  background: transparent !important;
}

:deep(.n-menu-item),
:deep(.n-menu-item-content),
:deep(.n-submenu .n-submenu-label) {
  border-radius: 14px;
}

:deep(.n-menu-item-content),
:deep(.n-submenu .n-submenu-label) {
  margin: 4px 0;
  padding-inline: 14px !important;
  height: 44px !important;
  font-weight: 500;
}

:deep(.n-menu-item-content::before),
:deep(.n-submenu .n-submenu-label::before) {
  border-radius: 14px !important;
}

:deep(.n-menu-item-content-header),
:deep(.n-submenu .n-submenu-label__text) {
  font-size: 14px;
}

:deep(.n-menu-item-content--selected) {
  background: rgba(59, 110, 246, 0.1) !important;
}

:deep(.n-menu-item-content--selected::before) {
  opacity: 0 !important;
}

:deep(.n-menu-item-content--selected .n-menu-item-content-header),
:deep(.n-menu-item-content--selected .n-icon) {
  color: rgb(var(--primary-color)) !important;
}

.dark {
  .global-sider {
    &__brand {
      border-bottom-color: rgba(71, 85, 105, 0.32);
    }
  }

  :deep(.n-menu-item-content--selected) {
    background: rgba(59, 110, 246, 0.18) !important;
  }
}

@media (max-width: 768px) {
  .global-sider {
    padding: 12px 0 16px 12px;
  }
}
</style>
