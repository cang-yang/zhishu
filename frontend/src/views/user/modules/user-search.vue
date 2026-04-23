<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watchEffect } from 'vue';
import { enableStatusOptions } from '@/constants/common';
import { useNaiveForm } from '@/hooks/common/form';

defineOptions({
  name: 'UserSearch'
});

const emit = defineEmits<{
  search: [];
}>();

const { formRef } = useNaiveForm();

const model = defineModel<Api.User.SearchParams>('model', { required: true });
const isMobileViewport = ref(false);

function syncMobileViewport() {
  if (typeof window === 'undefined') return;
  isMobileViewport.value = window.innerWidth <= 768;
}

watchEffect(() => {
  search();
});

async function search() {
  emit('search');
}

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
</script>

<template>
  <NCard :bordered="false" size="small" class="user-search">
    <NForm ref="formRef" :model="model" label-placement="left" :show-feedback="false" inline class="user-search__form">
      <NFormItem label="关键词" path="keyword" class="user-search__item user-search__item--keyword">
        <NInput
          v-model:value="model.keyword"
          :placeholder="isMobileViewport ? '搜索用户...' : '请输入关键词'"
          clearable
        />
      </NFormItem>
      <NFormItem label="组织标签" path="orgTag" class="user-search__item user-search__item--org">
        <OrgTagCascader
          v-model:value="model.orgTag"
          :placeholder="isMobileViewport ? '组织' : '请选择组织标签'"
          clearable
          class="user-search__control"
        />
      </NFormItem>
      <NFormItem label="启用状态" path="status" class="user-search__item user-search__item--status">
        <NSelect
          v-model:value="model.status"
          :placeholder="isMobileViewport ? '状态' : '请选择启用状态'"
          :options="enableStatusOptions"
          clearable
          class="user-search__control"
        />
      </NFormItem>
    </NForm>
  </NCard>
</template>

<style scoped lang="scss">
.user-search {
  :deep(.n-card__content) {
    padding: 12px 16px !important;
  }

  &__form {
    display: flex;
    flex-wrap: wrap;
    gap: 12px;
  }

  &__item {
    margin: 0 !important;
  }

  &__control {
    width: 200px;
  }
}

@media (max-width: 768px) {
  .user-search {
    :deep(.n-card__content) {
      padding: 6px 12px !important;
    }

    &__form {
      width: 100%;
      flex-wrap: nowrap;
      align-items: center;
      gap: 6px;
    }

    &__item {
      min-width: 0;
      margin: 0 !important;

      &--keyword {
        flex: 2 1 0;
      }

      &--org,
      &--status {
        flex: 1 1 0;
      }
    }

    &__control {
      width: 100%;
      min-width: 0;
    }

    :deep(.n-form-item-label),
    :deep(.n-form-item-blank__label) {
      display: none !important;
    }

    :deep(.n-form-item-blank),
    :deep(.n-input),
    :deep(.n-input-wrapper),
    :deep(.n-base-selection),
    :deep(.n-base-selection-label),
    :deep(.n-cascader),
    :deep(.n-select) {
      min-height: 32px !important;
      height: 32px !important;
    }

    :deep(.n-input-wrapper),
    :deep(.n-base-selection) {
      border-radius: 6px !important;
      padding-top: 0 !important;
      padding-bottom: 0 !important;
      font-size: 13px !important;
    }

    :deep(.n-base-selection-input),
    :deep(.n-input__input-el),
    :deep(.n-base-selection-placeholder),
    :deep(.n-base-selection-label__render-label) {
      font-size: 13px !important;
      line-height: 32px !important;
    }
  }
}
</style>
