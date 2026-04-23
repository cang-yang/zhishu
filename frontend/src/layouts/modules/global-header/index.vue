<script setup lang="ts">
import { useFullscreen } from '@vueuse/core';
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import { GLOBAL_HEADER_MENU_ID } from '@/constants/app';
import { $t } from '@/locales';
import { useAppStore } from '@/store/modules/app';
import { useThemeStore } from '@/store/modules/theme';
import { useUiStore } from '@/store/modules/ui';
import { useWorkspaceStore } from '@/store/modules/workspace';
import GlobalSearch from '../global-search/index.vue';
import ThemeButton from './components/theme-button.vue';
import UserAvatar from './components/user-avatar.vue';

defineOptions({
  name: 'GlobalHeader'
});

interface Props {
  /** Whether to show the logo */
  // showLogo?: App.Global.HeaderProps['showLogo'];
  /** Whether to show the menu toggler */
  showMenuToggler?: App.Global.HeaderProps['showMenuToggler'];
  /** Whether to show the menu */
  // showMenu?: App.Global.HeaderProps['showMenu'];
}

defineProps<Props>();

const appStore = useAppStore();
const themeStore = useThemeStore();
const workspaceStore = useWorkspaceStore();
const uiStore = useUiStore();
const route = useRoute();
const { isFullscreen, toggle } = useFullscreen();

const isDev = import.meta.env.DEV;
const isAiWorkbenchRoute = computed(() => route.name === 'chat');

const workbenchQuickActions = computed(() => {
  return [
    {
      key: 'knowledge-base',
      label: '知识库',
      icon: 'solar:folder-with-files-linear'
    },
    {
      key: 'settings',
      label: '设置',
      icon: 'solar:settings-linear'
    }
  ];
});

function openWorkbenchWindow(key: 'knowledge-base' | 'settings') {
  if (key === 'knowledge-base') {
    uiStore.openWorkspaceWindow({
      type: 'knowledge-base',
      title: '知识库工作窗',
      badge: '知识库',
      meta: '从轻量头栏直接拉起沉浸式知识库工作窗。',
      mode: 'immersive',
      width: 'min(1180px, calc(100vw - 52px))',
      height: 'min(86vh, 860px)'
    });
    return;
  }

  uiStore.openWorkspaceWindow({
    type: 'settings',
    title: '工作台设置',
    badge: '设置',
    meta: '全局工作台偏好、主题与快捷键将在此统一调整。',
    mode: 'standard',
    width: 'min(960px, calc(100vw - 56px))',
    height: 'min(82vh, 760px)'
  });
}
</script>

<template>
  <DarkModeContainer class="global-header h-full bg-transparent">
    <div v-if="isAiWorkbenchRoute" class="global-header__inner global-header__inner--workbench">
      <div class="global-header__meta global-header__meta--workbench">
        <div class="global-header__workbench-brand">
          <span class="global-header__workbench-orb">
            <SystemLogo class="text-18px" />
          </span>
          <div class="global-header__title-block">
            <span class="global-header__eyebrow">AI Workspace</span>
            <div class="global-header__title-row">
              <span class="global-header__title">{{ workspaceStore.currentModuleItem?.label || '聊天工作台' }}</span>
              <span class="global-header__status">中央舞台保持常驻，设置与资料通过工作窗展开</span>
            </div>
          </div>
        </div>
        <div class="global-header__workbench-actions">
          <button
            v-for="action in workbenchQuickActions"
            :key="action.key"
            type="button"
            class="global-header__workbench-action"
            @click="openWorkbenchWindow(action.key as 'knowledge-base' | 'settings')"
          >
            <SvgIcon :icon="action.icon" class="text-16px" />
            <span>{{ action.label }}</span>
          </button>
        </div>
      </div>
      <div class="global-header__actions global-header__actions--workbench">
        <GlobalSearch />
        <UserAvatar />
      </div>
    </div>
    <div v-else class="global-header__inner">
      <div class="global-header__meta app-toolbar">
        <div class="global-header__title-block">
          <span class="global-header__eyebrow">AetherDesk</span>
          <div class="global-header__title-row">
            <span class="global-header__title">{{ $t('system.title') }}</span>
            <span class="global-header__status">专注对话 · 高效协作 · 稳定交付</span>
          </div>
        </div>
        <div :id="GLOBAL_HEADER_MENU_ID" class="global-header__menu"></div>
        <div id="header-extra" class="global-header__extra"></div>
      </div>
      <div class="global-header__actions app-toolbar">
    <MenuToggler
      v-if="showMenuToggler && appStore.isMobile"
      :collapsed="appStore.siderCollapse"
      @click="appStore.toggleSiderCollapse"
    />
        <GlobalSearch />
        <FullScreen v-if="!appStore.isMobile" :full="isFullscreen" @click="toggle" />
        <LangSwitch
          v-if="themeStore.header.multilingual.visible"
          :lang="appStore.locale"
          :lang-options="appStore.localeOptions"
          @change-lang="appStore.changeLocale"
        />
        <ThemeSchemaSwitch
          :theme-schema="themeStore.themeScheme"
          :is-dark="themeStore.darkMode"
          @switch="themeStore.toggleThemeScheme"
        />
        <ThemeButton v-if="isDev" />
        <UserAvatar />
      </div>
    </div>
  </DarkModeContainer>
</template>

<style scoped lang="scss">
.global-header {
  padding: 16px 24px 0;

  &__inner {
    display: flex;
    height: 100%;
    align-items: center;
    justify-content: space-between;
    gap: 16px;

    &--workbench {
      min-height: 60px;
      align-items: stretch;
      gap: 12px;
    }
  }

  &__meta,
  &__actions {
    display: flex;
    min-height: 56px;
    align-items: center;
  }

  &__meta {
    flex: 1;
    justify-content: space-between;
    gap: 20px;
    padding: 0 20px;
    overflow: visible;

    &--workbench {
      padding: 0 16px;
      border: 1px solid rgba(148, 163, 184, 0.18);
      border-radius: 22px;
      background: rgba(2, 6, 23, 0.78);
      box-shadow: 0 14px 28px rgba(2, 6, 23, 0.18);
      backdrop-filter: blur(14px);
    }
  }

  &__workbench-brand {
    display: flex;
    min-width: 0;
    align-items: center;
    gap: 12px;
  }

  &__workbench-orb {
    display: inline-flex;
    width: 38px;
    height: 38px;
    align-items: center;
    justify-content: center;
    border-radius: 14px;
    background: linear-gradient(135deg, rgba(79, 124, 255, 0.22), rgba(14, 165, 233, 0.12));
    color: rgb(var(--primary-color));
    flex-shrink: 0;
  }

  &__workbench-actions {
    display: flex;
    flex-wrap: wrap;
    justify-content: flex-end;
    gap: 8px;
  }

  &__workbench-action {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    border: 1px solid rgba(71, 85, 105, 0.28);
    border-radius: 999px;
    background: rgba(15, 23, 42, 0.76);
    padding: 8px 12px;
    color: rgb(203 213 225);
    font-size: 12px;
    font-weight: 600;
    transition:
      border-color 0.2s ease,
      background 0.2s ease,
      transform 0.2s ease;

    &:hover {
      border-color: rgba(var(--primary-color), 0.24);
      background: rgba(30, 41, 59, 0.84);
      transform: translateY(-1px);
    }
  }

  &__title-block {
    display: flex;
    min-width: 0;
    flex-direction: column;
    justify-content: center;
    gap: 4px;
  }

  &__eyebrow {
    font-size: 12px;
    font-weight: 600;
    letter-spacing: 0.08em;
    text-transform: uppercase;
    color: rgb(var(--primary-color));
  }

  &__title-row {
    display: flex;
    min-width: 0;
    align-items: center;
    gap: 12px;
    flex-wrap: wrap;
  }

  &__title {
    font-size: 18px;
    font-weight: 700;
    color: rgb(var(--base-text-color));
  }

  &__status {
    font-size: 13px;
    color: rgb(100 116 139);
  }

  &__extra {
    display: flex;
    min-width: 0;
    flex: 1;
    justify-content: flex-end;
    overflow: hidden;
  }

  &__menu {
    display: flex;
    min-width: 0;
    flex: 1;
    align-items: center;
    justify-content: center;
    overflow: visible;
  }

  &__actions {
    flex-shrink: 0;
    gap: 4px;
    padding: 0 12px 0 10px;

    &--workbench {
      padding-inline: 0;
      gap: 8px;
    }
  }
}

.dark {
  .global-header {
    &__meta--workbench {
      border-color: rgba(71, 85, 105, 0.42);
      background: rgba(2, 6, 23, 0.72);
    }

    &__status {
      color: rgb(148 163 184);
    }
  }
}

@media (max-width: 1024px) {
  .global-header {
    padding: 12px 16px 0;

    &__meta {
      padding-inline: 16px;
    }

    &__title-row {
      gap: 8px;
    }

    &__title {
      font-size: 16px;
    }

    &__status {
      display: none;
    }

    &__meta--workbench {
      padding-inline: 16px;
    }

    &__workbench-actions {
      display: none;
    }
  }
}

@media (max-width: 768px) {
  .global-header {
    &__meta {
      min-width: 0;
      padding-inline: 14px;
    }

    &__extra {
      display: none;
    }

    &__menu {
      justify-content: flex-start;
    }

    &__actions {
      padding-inline: 8px;
    }

    &__inner--workbench {
      flex-direction: column;
      align-items: stretch;
    }

    &__workbench-brand {
      width: 100%;
    }

    &__actions--workbench {
      justify-content: space-between;
      padding-inline: 0;
    }
  }
}
</style>
