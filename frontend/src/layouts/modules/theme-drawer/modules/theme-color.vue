<script setup lang="ts">
import { useThemeStore } from '@/store/modules/theme';
import { $t } from '@/locales';
import SettingItem from '../components/setting-item.vue';

defineOptions({
  name: 'ThemeColor'
});

const themeStore = useThemeStore();

const themeColorTitle = $t('theme.themeColor.title' as App.I18n.I18nKey);
const themeColorDesc = $t('theme.themeColor.desc' as App.I18n.I18nKey);

const themeColorLabelMap: Record<App.Theme.ThemeColorKey, string> = {
  primary: $t('theme.themeColor.primary' as App.I18n.I18nKey),
  info: $t('theme.themeColor.info' as App.I18n.I18nKey),
  success: $t('theme.themeColor.success' as App.I18n.I18nKey),
  warning: $t('theme.themeColor.warning' as App.I18n.I18nKey),
  error: $t('theme.themeColor.error' as App.I18n.I18nKey)
};

function handleUpdateColor(color: string, key: App.Theme.ThemeColorKey) {
  themeStore.updateThemeColors(key, color);
}

const swatches: string[] = [
  '#3b82f6',
  '#6366f1',
  '#8b5cf6',
  '#a855f7',
  '#0ea5e9',
  '#06b6d4',
  '#f43f5e',
  '#ef4444',
  '#ec4899',
  '#d946ef',
  '#f97316',
  '#f59e0b',
  '#eab308',
  '#84cc16',
  '#22c55e',
  '#10b981'
];
</script>

<template>
  <NDivider>{{ themeColorTitle }}</NDivider>
  <div class="flex-col-stretch gap-12px">
    <p class="text-12px color-#64748b leading-6 dark:color-#94a3b8">
      {{ themeColorDesc }}
    </p>
    <NTooltip placement="top-start">
      <template #trigger>
        <SettingItem key="recommend-color" :label="$t('theme.recommendColor')">
          <NSwitch v-model:value="themeStore.recommendColor" />
        </SettingItem>
      </template>
      <p>
        <span class="pr-12px">{{ $t('theme.recommendColorDesc') }}</span>
        <br />
        <NButton
          text
          tag="a"
          href="https://uicolors.app/create"
          target="_blank"
          rel="noopener noreferrer"
          class="text-gray"
        >
          https://uicolors.app/create
        </NButton>
      </p>
    </NTooltip>
    <SettingItem v-for="(_, key) in themeStore.themeColors" :key="key" :label="themeColorLabelMap[key]">
      <template v-if="key === 'info'" #suffix>
        <NCheckbox v-model:checked="themeStore.isInfoFollowPrimary">
          {{ $t('theme.themeColor.followPrimary') }}
        </NCheckbox>
      </template>
      <NColorPicker
        class="w-90px"
        :value="themeStore.themeColors[key]"
        :disabled="key === 'info' && themeStore.isInfoFollowPrimary"
        :show-alpha="false"
        :swatches="swatches"
        @update:value="handleUpdateColor($event, key)"
      />
    </SettingItem>
  </div>
</template>

<style scoped></style>
