<script setup lang="ts">
import { useFormRules, useNaiveForm } from '@/hooks/common/form';
import { useAuthStore } from '@/store/modules/auth';
import { useKnowledgeBaseStore } from '@/store/modules/knowledge-base';
import { uploadAccept } from '@/constants/common';

defineOptions({
  name: 'UploadDialog'
});

const loading = ref(false);
const visible = defineModel<boolean>('visible', { default: false });
const singleOrgOnly = ref(false);
const isMobileViewport = ref(false);

const authStore = useAuthStore();

const { formRef, validate, restoreValidation } = useNaiveForm();
const { defaultRequiredRule } = useFormRules();

const model = ref<Api.KnowledgeBase.Form>(createDefaultModel());

function createDefaultModel(): Api.KnowledgeBase.Form {
  return {
    orgTag: null,
    orgTagName: '',
    uploadMaxSizeBytes: null,
    uploadMaxSizeMb: null,
    isPublic: false,
    fileList: []
  };
}

const rules = ref<FormRules>({
  orgTag: defaultRequiredRule,
  isPublic: defaultRequiredRule,
  fileList: defaultRequiredRule
});

const fileSizeLimitError = computed(() => {
  if (authStore.isAdmin) return '';
  const file = model.value.fileList?.[0]?.file;
  if (!file || !model.value.uploadMaxSizeBytes) return '';
  if (file.size <= model.value.uploadMaxSizeBytes) return '';

  return `当前组织限制非管理员上传文件不超过 ${model.value.uploadMaxSizeMb} MB，当前文件大小为 ${(file.size / 1024 / 1024).toFixed(2)} MB`;
});

const submitDisabled = computed(() => loading.value);

function syncMobileViewport() {
  if (typeof window === 'undefined') return;
  isMobileViewport.value = window.innerWidth <= 768;
}

function close() {
  visible.value = false;
}

const store = useKnowledgeBaseStore();
async function handleSubmit() {
  await validate();

  loading.value = true;
  await store.enqueueUpload(model.value);
  loading.value = false;
  close();
}

async function presetSingleOrgForUser() {
  singleOrgOnly.value = false;
  const { error, data } = await request<Api.OrgTag.Mine>({ url: '/users/org-tags' });
  if (error || !visible.value) return;

  const orgTagDetails = data.orgTagDetails || [];
  if (orgTagDetails.length !== 1) return;

  const singleOrg = orgTagDetails[0];
  model.value.orgTag = singleOrg.tagId;
  onUpdate(singleOrg);
  singleOrgOnly.value = true;
}

watch(visible, () => {
  if (visible.value) {
    model.value = createDefaultModel();
    singleOrgOnly.value = false;
    if (!authStore.isAdmin) {
      presetSingleOrgForUser();
    }
    restoreValidation();
  }
});

onMounted(() => {
  syncMobileViewport();
  if (typeof window !== 'undefined') {
    window.addEventListener('resize', syncMobileViewport);
  }
});

onBeforeUnmount(() => {
  if (typeof window !== 'undefined') {
    window.removeEventListener('resize', syncMobileViewport);
  }
});

function onUpdate(option: unknown) {
  if (option) {
    const selected = option as Api.OrgTag.Item;
    model.value.orgTagName = selected.name;
    model.value.uploadMaxSizeBytes = selected.uploadMaxSizeBytes;
    model.value.uploadMaxSizeMb = selected.uploadMaxSizeMb;
    return;
  }
  model.value.orgTagName = '';
  model.value.uploadMaxSizeBytes = null;
  model.value.uploadMaxSizeMb = null;
}
</script>

<template>
  <div v-if="isMobileViewport && visible" class="upload-dialog-mobile">
    <div class="upload-dialog-mobile__safe-area" />
    <header class="upload-dialog-mobile__topbar">
      <button type="button" class="upload-dialog-mobile__text-button" @click="close">返回</button>
      <strong>文件上传</strong>
      <button type="button" class="upload-dialog-mobile__text-button upload-dialog-mobile__text-button--primary" :disabled="submitDisabled" @click="handleSubmit">保存</button>
    </header>

    <div class="upload-dialog-mobile__body">
      <NForm ref="formRef" :model="model" :rules="rules" label-placement="top">
        <NFormItem v-if="authStore.isAdmin" label="组织标签" path="orgTag">
          <OrgTagCascader v-model:value="model.orgTag" @change="onUpdate" />
        </NFormItem>
        <NFormItem v-else label="组织标签" path="orgTag">
          <TheSelect
            v-model:value="model.orgTag"
            url="/users/org-tags"
            key-field="orgTagDetails"
            label-field="name"
            value-field="tagId"
            :disabled="singleOrgOnly"
            @change="onUpdate"
          />
        </NFormItem>

        <NFormItem label="是否公开" path="isPublic">
          <NRadioGroup v-model:value="model.isPublic" name="upload-dialog-mobile-visibility">
            <NSpace :size="16">
              <NRadio :value="true">公开</NRadio>
              <NRadio :value="false">私有</NRadio>
            </NSpace>
          </NRadioGroup>
        </NFormItem>

        <NFormItem label="标签描述" path="fileList">
          <NUpload
            v-model:file-list="model.fileList"
            :accept="uploadAccept"
            :max="1"
            :multiple="false"
            :default-upload="false"
          >
            <NButton block>上传文件</NButton>
          </NUpload>
          <div v-if="fileSizeLimitError" class="upload-dialog-mobile__feedback upload-dialog-mobile__feedback--error">
            {{ fileSizeLimitError }}
          </div>
          <div v-else-if="!authStore.isAdmin && model.uploadMaxSizeMb" class="upload-dialog-mobile__feedback upload-dialog-mobile__feedback--warning">
            当前组织限制非管理员上传文件不超过 {{ model.uploadMaxSizeMb }} MB
          </div>
        </NFormItem>
      </NForm>
    </div>
  </div>

  <NModal
    v-else
    v-model:show="visible"
    preset="dialog"
    title="文件上传"
    :show-icon="false"
    :mask-closable="false"
    class="w-500px!"
    @positive-click="handleSubmit"
  >
    <NForm ref="formRef" :model="model" :rules="rules" label-placement="left" :label-width="100" mt-10>
      <NFormItem v-if="authStore.isAdmin" label="组织标签" path="orgTag">
        <OrgTagCascader v-model:value="model.orgTag" @change="onUpdate" />
      </NFormItem>
      <NFormItem v-else label="组织标签" path="orgTag">
        <TheSelect
          v-model:value="model.orgTag"
          url="/users/org-tags"
          key-field="orgTagDetails"
          label-field="name"
          value-field="tagId"
          :disabled="singleOrgOnly"
          @change="onUpdate"
        />
      </NFormItem>

      <NFormItem label="是否公开" path="isPublic">
        <NRadioGroup v-model:value="model.isPublic" name="radiogroup">
          <NSpace :size="16">
            <NRadio :value="true">公开</NRadio>
            <NRadio :value="false">私有</NRadio>
          </NSpace>
        </NRadioGroup>
      </NFormItem>
      <NFormItem label="标签描述" path="fileList">
        <NUpload
          v-model:file-list="model.fileList"
          :accept="uploadAccept"
          :max="1"
          :multiple="false"
          :default-upload="false"
        >
          <NButton>上传文件</NButton>
        </NUpload>
        <div v-if="fileSizeLimitError" class="mt-8px text-12px text-#ef4444">
          {{ fileSizeLimitError }}
        </div>
        <div v-else-if="!authStore.isAdmin && model.uploadMaxSizeMb" class="mt-8px text-12px text-#d97706">
          当前组织限制非管理员上传文件不超过 {{ model.uploadMaxSizeMb }} MB
        </div>
      </NFormItem>
    </NForm>
    <template #action>
      <NSpace :size="16">
        <NButton @click="close">取消</NButton>
        <NButton type="primary" :disabled="submitDisabled" @click="handleSubmit">保存</NButton>
      </NSpace>
    </template>
  </NModal>
</template>

<style scoped lang="scss">
.upload-dialog-mobile {
  position: fixed;
  inset: 0;
  z-index: 90;
  display: flex;
  flex-direction: column;
  background: var(--app-layout-bg);

  &__safe-area {
    height: env(safe-area-inset-top);
    flex-shrink: 0;
  }

  &__topbar {
    display: grid;
    grid-template-columns: 64px 1fr 64px;
    align-items: center;
    padding: 10px 12px;
    border-bottom: 1px solid var(--app-border);
    background: color-mix(in srgb, var(--color-bg-container) 92%, transparent);

    strong {
      text-align: center;
      color: var(--app-text-primary);
      font-size: 16px;
      font-weight: 700;
    }
  }

  &__text-button {
    background: transparent;
    color: var(--app-text-secondary);
    font-size: 14px;
    font-weight: 600;

    &--primary {
      color: rgb(var(--primary-color));
    }
  }

  &__body {
    flex: 1;
    overflow: auto;
    padding: 16px 16px calc(env(safe-area-inset-bottom) + 20px);
  }

  &__feedback {
    margin-top: 8px;
    font-size: 12px;
    line-height: 1.6;

    &--error {
      color: #ef4444;
    }

    &--warning {
      color: #d97706;
    }
  }
}
</style>
