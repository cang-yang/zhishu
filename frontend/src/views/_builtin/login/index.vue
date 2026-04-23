<script setup lang="ts">
import { computed } from 'vue';
import type { Component } from 'vue';
import { mixColor } from '@sa/color';
import { loginModuleRecord } from '@/constants/app';
import { useAppStore } from '@/store/modules/app';
import { useThemeStore } from '@/store/modules/theme';
import { $t } from '@/locales';
import PwdLogin from './modules/pwd-login.vue';
import CodeLogin from './modules/code-login.vue';
import Register from './modules/register.vue';
import ResetPwd from './modules/reset-pwd.vue';
import BindWechat from './modules/bind-wechat.vue';

interface Props {
  /** The login module */
  module?: UnionKey.LoginModule;
}

const props = defineProps<Props>();

const appStore = useAppStore();
const themeStore = useThemeStore();

interface LoginModule {
  label: string;
  component: Component;
}

const moduleMap: Record<UnionKey.LoginModule, LoginModule> = {
  'pwd-login': { label: loginModuleRecord['pwd-login'], component: PwdLogin },
  'code-login': { label: loginModuleRecord['code-login'], component: CodeLogin },
  register: { label: loginModuleRecord.register, component: Register },
  'reset-pwd': { label: loginModuleRecord['reset-pwd'], component: ResetPwd },
  'bind-wechat': { label: loginModuleRecord['bind-wechat'], component: BindWechat }
};

const activeModule = computed(() => moduleMap[props.module || 'pwd-login']);
const isRegisterModule = computed(() => (props.module || 'pwd-login') === 'register');

const bgColor = computed(() => {
  const ratio = themeStore.darkMode ? 0.9 : 0;

  return mixColor('#fff', '#000', ratio);
});
</script>

<template>
  <div class="relative size-full flex-center" :style="{ backgroundColor: bgColor }">
    <NCard :bordered="false" class="relative z-4 w-auto card-wrapper">
      <div :class="isRegisterModule ? 'login-panel login-panel--register' : 'login-panel'">
        <header class="login-panel__header">
          <div class="login-panel__header-spacer" aria-hidden="true" />
          <h3 class="login-panel__title">
            <span>{{ $t('system.title') }}</span>
            <span v-if="isRegisterModule" class="login-panel__title-sub">
              {{ $t(activeModule.label) }}
            </span>
          </h3>
          <div class="login-panel__toolbar">
            <ThemeSchemaSwitch
              :theme-schema="themeStore.themeScheme"
              :show-tooltip="false"
              class="text-20px lt-sm:text-18px"
              @switch="themeStore.toggleThemeScheme"
            />
            <LangSwitch
              v-if="themeStore.header.multilingual.visible"
              :lang="appStore.locale"
              :lang-options="appStore.localeOptions"
              :show-tooltip="false"
              @change-lang="appStore.changeLocale"
            />
          </div>
        </header>
        <main class="pt-24px">
          <h3 v-if="!isRegisterModule" class="text-18px text-primary font-medium">{{ $t(activeModule.label) }}</h3>
          <div class="pt-24px">
            <Transition :name="themeStore.page.animateMode" mode="out-in" appear>
              <component :is="activeModule.component" />
            </Transition>
          </div>
        </main>
      </div>
    </NCard>
  </div>
</template>

<style scoped>
.login-panel {
  width: 400px;
}

.login-panel__header {
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  align-items: center;
  gap: 16px;
}

.login-panel__header-spacer {
  min-width: 24px;
}

.login-panel__title {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  margin: 0;
  color: rgb(var(--primary-color));
  font-size: 28px;
  font-weight: 500;
  text-align: center;
}

.login-panel__title-sub {
  font-size: 18px;
  font-weight: 500;
  opacity: 0.8;
}

.login-panel__toolbar {
  justify-self: end;
  display: inline-flex;
  flex-direction: column;
}

.login-panel--register {
  width: min(860px, calc(100vw - 72px));
}

@media (max-width: 640px) {
  .login-panel,
  .login-panel--register {
    width: 300px;
  }

  .login-panel__header {
    grid-template-columns: 1fr;
    justify-items: center;
  }

  .login-panel__header-spacer {
    display: none;
  }

  .login-panel__title {
    font-size: 22px;
  }

  .login-panel__title-sub {
    font-size: 16px;
  }

  .login-panel__toolbar {
    justify-self: center;
  }
}
</style>
