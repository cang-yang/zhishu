<script setup lang="ts">
import { computed } from 'vue';
import { useThemeStore } from '@/store/modules/theme';
import { $t } from '@/locales';
import SettingItem from '../components/setting-item.vue';

defineOptions({
  name: 'DarkMode'
});

const themeStore = useThemeStore();

const presetOptions: UnionKey.ThemePreset[] = ['aether-light', 'aether-dark', 'system'];

const presetTitle = $t('theme.preset.title' as App.I18n.I18nKey);

const presetLabelMap: Record<UnionKey.ThemePreset, string> = {
  'aether-light': $t('theme.preset.aether-light' as App.I18n.I18nKey),
  'aether-dark': $t('theme.preset.aether-dark' as App.I18n.I18nKey),
  system: $t('theme.preset.system' as App.I18n.I18nKey)
};

const presetDescMap: Record<UnionKey.ThemePreset, string> = {
  'aether-light': $t('theme.presetDesc.aether-light' as App.I18n.I18nKey),
  'aether-dark': $t('theme.presetDesc.aether-dark' as App.I18n.I18nKey),
  system: $t('theme.presetDesc.system' as App.I18n.I18nKey)
};

function handlePresetChange(value: UnionKey.ThemePreset) {
  themeStore.setThemePreset(value);
}

function handleGrayscaleChange(value: boolean) {
  themeStore.setGrayscale(value);
}

function handleColourWeaknessChange(value: boolean) {
  themeStore.setColourWeakness(value);
}

const showSiderInverted = computed(() => !themeStore.darkMode && themeStore.layout.mode.includes('vertical'));
</script>

<template>
  <NDivider>{{ presetTitle }}</NDivider>
  <div class="flex-col-stretch gap-16px">
    <div class="theme-preset-grid">
      <button
        v-for="preset in presetOptions"
        :key="preset"
        type="button"
        class="theme-preset-card"
        :class="{ 'theme-preset-card--active': themeStore.themePreset === preset }"
        @click="handlePresetChange(preset)"
      >
        <span class="theme-preset-card__title">{{ presetLabelMap[preset] }}</span>
        <span class="theme-preset-card__desc">{{ presetDescMap[preset] }}</span>
      </button>
    </div>
    <Transition name="sider-inverted">
      <SettingItem v-if="showSiderInverted" :label="$t('theme.sider.inverted')">
        <NSwitch v-model:value="themeStore.sider.inverted" />
      </SettingItem>
    </Transition>
    <SettingItem :label="$t('theme.grayscale')">
      <NSwitch :value="themeStore.grayscale" @update:value="handleGrayscaleChange" />
    </SettingItem>
    <SettingItem :label="$t('theme.colourWeakness')">
      <NSwitch :value="themeStore.colourWeakness" @update:value="handleColourWeaknessChange" />
    </SettingItem>
  </div>
</template>

<style scoped>
.theme-preset-grid {
  display: grid;
  gap: 12px;
}

.theme-preset-card {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 14px 16px;
  border: 1px solid rgba(203, 213, 225, 0.9);
  border-radius: 16px;
  background: rgba(248, 250, 252, 0.92);
  text-align: left;
  transition: all 0.2s ease;
}

.theme-preset-card:hover {
  border-color: rgba(79, 124, 255, 0.45);
  transform: translateY(-1px);
}

.theme-preset-card--active {
  border-color: rgb(var(--primary-color));
  box-shadow: 0 0 0 3px rgba(79, 124, 255, 0.14);
  background: rgba(79, 124, 255, 0.08);
}

.theme-preset-card__title {
  font-size: 14px;
  font-weight: 700;
  color: rgb(var(--base-text-color));
}

.theme-preset-card__desc {
  font-size: 12px;
  line-height: 1.6;
  color: rgb(100 116 139);
}

.dark .theme-preset-card {
  border-color: rgba(71, 85, 105, 0.4);
  background: rgba(15, 23, 42, 0.7);
}

.dark .theme-preset-card__desc {
  color: rgb(148 163 184);
}

.dark .theme-preset-card--active {
  background: rgba(79, 124, 255, 0.14);
}

.sider-inverted-enter-active,
.sider-inverted-leave-active {
  --uno: h-22px transition-all-300;
}

.sider-inverted-enter-from,
.sider-inverted-leave-to {
  --uno: translate-x-20px opacity-0 h-0;
}
</style>
