<script setup lang="ts">
import { reactive } from 'vue';
import { loginModuleRecord } from '@/constants/app';
import { useAuthStore } from '@/store/modules/auth';
import { useRouterPush } from '@/hooks/common/router';
import { useFormRules, useNaiveForm } from '@/hooks/common/form';
import { localStg } from '@/utils/storage';
import { $t } from '@/locales';

defineOptions({
  name: 'PwdLogin'
});

const authStore = useAuthStore();
const { toggleLoginModule } = useRouterPush();
const { formRef, validate } = useNaiveForm();

interface FormModel {
  userName: string;
  password: string;
  rememberMe: boolean;
}

type FormRuleModel = Pick<FormModel, 'userName' | 'password'>;

const rememberedLogin = localStg.get('rememberedLogin');

const model: FormModel = reactive({
  userName: rememberedLogin?.userName || '',
  password: rememberedLogin?.password || '',
  rememberMe: Boolean(rememberedLogin)
});

const rules = computed<Record<keyof FormRuleModel, App.Global.FormRule[]>>(() => {
  // inside computed to make locale reactive, if not apply i18n, you can define it without computed
  const { formRules } = useFormRules();

  return {
    userName: formRules.userName,
    password: formRules.pwd
  };
});

/** 一键填充测试账号 */
function fillTestAccount() {
  model.userName = 'admin';
  model.password = 'ZhiShu2026Admin';
  window.$message?.success('已填充测试账号');
}

async function handleSubmit() {
  await validate();

  const success = await authStore.login(model.userName, model.password);

  if (!success) {
    return;
  }

  if (model.rememberMe) {
    localStg.set('rememberedLogin', {
      userName: model.userName,
      password: model.password
    });
  } else {
    localStg.remove('rememberedLogin');
  }
}
</script>

<template>
  <NForm ref="formRef" :model="model" :rules="rules" size="large" :show-label="false" @keyup.enter="handleSubmit">
    <NFormItem path="userName">
      <NInput v-model:value="model.userName" :placeholder="$t('page.login.common.userNamePlaceholder')">
        <template #prefix>
          <icon-ant-design:user-outlined />
        </template>
      </NInput>
    </NFormItem>
    <NFormItem path="password">
      <NInput
        v-model:value="model.password"
        type="password"
        show-password-on="click"
        :placeholder="$t('page.login.common.passwordPlaceholder')"
      >
        <template #prefix>
          <icon-ant-design:key-outlined />
        </template>
      </NInput>
    </NFormItem>
    <div class="mb-6 flex-y-center justify-between">
      <NCheckbox v-model:checked="model.rememberMe">
        {{ $t('page.login.pwdLogin.rememberMe') }}
      </NCheckbox>
    </div>
    <div class="flex-col gap-6">
      <NButton type="primary" size="large" round block :loading="authStore.loginLoading" @click="handleSubmit">
        {{ $t('page.login.common.login') }}
      </NButton>

      <!-- 测试账号快捷填充 -->
      <NButton type="success" size="medium" :block="true" ghost class="mt-12px" @click="fillTestAccount">
        填充测试账号 (admin)
      </NButton>

      <NButton block @click="toggleLoginModule('register')">
        {{ $t(loginModuleRecord.register) }}
      </NButton>

      <span class="text-center">
        登录即代表已阅读并同意我们的
        <NButton text type="primary">用户协议</NButton>
        和
        <NButton text type="primary">隐私政策</NButton>
      </span>
    </div>
  </NForm>
</template>

<style scoped></style>
