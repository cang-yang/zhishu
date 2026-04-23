<script setup lang="tsx">
import { onBeforeUnmount, onMounted, ref } from 'vue';
import { NButton, NCard, NEmpty } from 'naive-ui';

const modelProvidersLoading = ref(false);
const modelProvidersSaving = ref(false);
const modelProviders = ref<Api.Admin.ModelProviderSettings | null>(null);
const isMobileViewport = ref(false);

function syncMobileViewport() {
  if (typeof window === 'undefined') return;
  isMobileViewport.value = window.innerWidth <= 768;
}

function cloneProviderItem(item: Api.Admin.ModelProviderItem): Api.Admin.ModelProviderItem {
  return {
    provider: item.provider,
    displayName: item.displayName,
    apiStyle: item.apiStyle,
    apiBaseUrl: item.apiBaseUrl,
    model: item.model,
    dimension: item.dimension ?? null,
    enabled: Boolean(item.enabled),
    active: Boolean(item.active),
    hasApiKey: Boolean(item.hasApiKey),
    maskedApiKey: item.maskedApiKey || '',
    apiKeyInput: ''
  };
}

function cloneModelProviderScope(payload: Api.Admin.ModelProviderScopeSettings): Api.Admin.ModelProviderScopeSettings {
  return {
    scope: payload.scope,
    activeProvider: payload.activeProvider,
    providers: (payload.providers || []).map(cloneProviderItem)
  };
}

function cloneModelProviderSettings(
  payload?: Api.Admin.ModelProviderSettings | null
): Api.Admin.ModelProviderSettings | null {
  if (!payload) {
    return null;
  }
  return {
    llm: cloneModelProviderScope(payload.llm),
    embedding: cloneModelProviderScope(payload.embedding)
  };
}

async function getModelProviders() {
  modelProvidersLoading.value = true;
  const { error, data } = await request<Api.Admin.ModelProviderSettings>({
    url: '/admin/model-providers'
  });

  if (!error && data) {
    modelProviders.value = cloneModelProviderSettings(data);
  }
  modelProvidersLoading.value = false;
}

function buildProviderPayload(scope: Api.Admin.ModelProviderScopeSettings) {
  return {
    activeProvider: scope.activeProvider,
    providers: scope.providers.map(item => ({
      provider: item.provider,
      apiBaseUrl: item.apiBaseUrl,
      model: item.model,
      apiKey: item.apiKeyInput?.trim() || '',
      dimension: scope.scope === 'embedding' ? item.dimension : null,
      enabled: item.enabled
    }))
  };
}

async function submitModelProviders(scopeKey: 'llm' | 'embedding') {
  const scope = modelProviders.value?.[scopeKey];
  if (!scope) {
    return;
  }

  modelProvidersSaving.value = true;
  const { error, data } = await request<Api.Admin.ModelProviderScopeSettings>({
    url: `/admin/model-providers/${scopeKey}`,
    method: 'put',
    data: buildProviderPayload(scope)
  });

  if (!error && data && modelProviders.value) {
    modelProviders.value[scopeKey] = cloneModelProviderScope(data);
    window.$message?.success(scopeKey === 'llm' ? 'LLM 模型配置已更新' : 'Embedding 配置已更新');
  }
  modelProvidersSaving.value = false;
}

async function testModelProvider(scopeKey: 'llm' | 'embedding', provider: Api.Admin.ModelProviderItem) {
  const { error, data } = await request<Api.Admin.ConnectivityTestResult>({
    url: `/admin/model-providers/${scopeKey}/test`,
    method: 'post',
    data: {
      apiBaseUrl: provider.apiBaseUrl,
      model: provider.model,
      apiKey: provider.apiKeyInput?.trim() || '',
      dimension: scopeKey === 'embedding' ? provider.dimension : null
    }
  });

  if (!error && data) {
    if (data.success) {
      window.$message?.success(`${provider.displayName} 连接成功，耗时 ${data.latencyMs}ms`);
    } else {
      window.$message?.error(`${provider.displayName} 连接失败：${data.message}`);
    }
  }
}

onMounted(() => {
  syncMobileViewport();
  window.addEventListener('resize', syncMobileViewport);
  getModelProviders();
});

onBeforeUnmount(() => {
  if (typeof window !== 'undefined') {
    window.removeEventListener('resize', syncMobileViewport);
  }
});
</script>

<template>
  <div class="admin-page-shell min-h-500px flex-col-stretch gap-16px overflow-auto">
    <NCard :bordered="false" size="small" class="card-wrapper">
      <template #header>模型 Provider 配置</template>
      <template #header-extra>
        <div class="flex items-center gap-2">
          <span class="text-xs text-stone-400">切换模型后保存即时生效，Embedding 暂不允许危险直切</span>
        </div>
      </template>

      <NSpin :show="modelProvidersLoading">
        <div class="mb-4 border border-stone-200 rounded-2xl bg-stone-50 px-4 py-3 text-xs text-stone-500">
          这里集中管理平台的多模型接入配置，AI 支持自由切换任意已配置的模型服务商（如 DeepSeek、Qwen、智谱 等）。API Key
          输入为空时保留现有密钥，不会回显明文。Embedding 如果切换 active provider，后端会拦截需要重嵌入的危险变更。
        </div>

        <div v-if="modelProviders" class="grid gap-4">
          <div class="provider-scope">
            <div class="provider-scope-header">
              <div>
                <div class="provider-scope-title">LLM Provider</div>
                <div class="provider-scope-sub">可自由切换，聊天将通过选中的 Provider 路由至对应模型</div>
              </div>
              <div class="flex items-center gap-3">
                <NSelect
                  v-model:value="modelProviders.llm.activeProvider"
                  :options="
                    modelProviders.llm.providers.map(item => ({
                      label: item.displayName,
                      value: item.provider,
                      disabled: !item.enabled
                    }))
                  "
                  class="min-w-180px"
                />
                <NButton
                  type="primary"
                  size="small"
                  :loading="modelProvidersSaving"
                  @click="submitModelProviders('llm')"
                >
                  保存 LLM 配置
                </NButton>
              </div>
            </div>

            <div class="provider-grid" :class="{ 'provider-grid--mobile': isMobileViewport }">
              <div v-for="item in modelProviders.llm.providers" :key="`llm-${item.provider}`" class="provider-card">
                <div class="provider-card-header">
                  <div>
                    <div class="provider-name">{{ item.displayName }}</div>
                    <div class="provider-code">{{ item.provider }} · {{ item.apiStyle }}</div>
                  </div>
                  <NSwitch v-model:value="item.enabled" size="small" />
                </div>
                <div class="limit-grid">
                  <div>
                    <div class="limit-label">API 地址</div>
                    <NInput v-model:value="item.apiBaseUrl" />
                  </div>
                  <div>
                    <div class="limit-label">模型</div>
                    <NInput v-model:value="item.model" />
                  </div>
                  <div>
                    <div class="limit-label">现有密钥</div>
                    <div class="provider-mask">{{ item.hasApiKey ? item.maskedApiKey : '未配置' }}</div>
                  </div>
                  <div>
                    <div class="limit-label">新 API Key</div>
                    <NInput
                      v-model:value="item.apiKeyInput"
                      type="password"
                      show-password-on="click"
                      placeholder="留空则保留现有值"
                    />
                  </div>
                </div>
                <div class="mt-3 flex justify-end">
                  <NButton size="small" secondary @click="testModelProvider('llm', item)">测试连接</NButton>
                </div>
              </div>
            </div>
          </div>

          <div class="provider-scope">
            <div class="provider-scope-header">
              <div>
                <div class="provider-scope-title">Embedding Provider</div>
                <div class="provider-scope-sub">
                  当前版本只支持配置管理；切 active provider 若需要重嵌入会被后端拦截
                </div>
              </div>
              <div class="flex items-center gap-3">
                <NSelect
                  v-model:value="modelProviders.embedding.activeProvider"
                  :options="
                    modelProviders.embedding.providers.map(item => ({
                      label: item.displayName,
                      value: item.provider,
                      disabled: !item.enabled
                    }))
                  "
                  class="min-w-180px"
                />
                <NButton
                  type="primary"
                  size="small"
                  :loading="modelProvidersSaving"
                  @click="submitModelProviders('embedding')"
                >
                  保存 Embedding 配置
                </NButton>
              </div>
            </div>

            <div class="provider-grid" :class="{ 'provider-grid--mobile': isMobileViewport }">
              <div
                v-for="item in modelProviders.embedding.providers"
                :key="`embedding-${item.provider}`"
                class="provider-card"
              >
                <div class="provider-card-header">
                  <div>
                    <div class="provider-name">{{ item.displayName }}</div>
                    <div class="provider-code">{{ item.provider }} · {{ item.apiStyle }}</div>
                  </div>
                  <NSwitch v-model:value="item.enabled" size="small" />
                </div>
                <div class="limit-grid">
                  <div>
                    <div class="limit-label">API 地址</div>
                    <NInput v-model:value="item.apiBaseUrl" />
                  </div>
                  <div>
                    <div class="limit-label">模型</div>
                    <NInput v-model:value="item.model" />
                  </div>
                  <div>
                    <div class="limit-label">维度</div>
                    <NInputNumber v-model:value="item.dimension" :min="1" class="w-full" />
                  </div>
                  <div>
                    <div class="limit-label">现有密钥</div>
                    <div class="provider-mask">{{ item.hasApiKey ? item.maskedApiKey : '未配置' }}</div>
                  </div>
                  <div class="sm:col-span-2">
                    <div class="limit-label">新 API Key</div>
                    <NInput
                      v-model:value="item.apiKeyInput"
                      type="password"
                      show-password-on="click"
                      placeholder="留空则保留现有值"
                    />
                  </div>
                </div>
                <div class="mt-3 flex justify-end">
                  <NButton size="small" secondary @click="testModelProvider('embedding', item)">测试连接</NButton>
                </div>
              </div>
            </div>
          </div>
        </div>
        <NEmpty v-else size="small" description="暂未加载到模型配置" />
      </NSpin>
    </NCard>
  </div>
</template>

<style scoped lang="scss">
.admin-page-shell {
  color: var(--color-text-primary);
}

.provider-scope {
  @apply rounded-3xl border p-5;
  border-color: var(--color-border);
  background: var(--color-bg-container);
}

.provider-scope-header {
  @apply mb-4 flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between;
}

.provider-scope-title {
  @apply text-sm font-semibold;
  color: var(--color-text-primary);
}

.provider-scope-sub {
  @apply mt-1 text-xs;
  color: var(--color-text-secondary);
}

.provider-grid {
  @apply grid gap-4 xl:grid-cols-2;
}

.provider-grid--mobile {
  grid-template-columns: 1fr;
}

.provider-card {
  @apply rounded-2xl border p-4;
  border-color: var(--color-border);
  background: var(--color-bg-card);
}

.provider-card-header {
  @apply mb-4 flex items-start justify-between gap-3;
}

.provider-name {
  @apply text-sm font-semibold;
  color: var(--color-text-primary);
}

.provider-code {
  @apply mt-1 text-xs uppercase tracking-0.08em;
  color: var(--color-text-secondary);
}

.provider-mask {
  @apply rounded-xl border border-dashed px-3 py-2 text-sm;
  border-color: var(--color-border);
  background: var(--color-bg-elevated);
  color: var(--color-text-secondary);
}

.limit-grid {
  @apply grid gap-3 sm:grid-cols-2;
}

.limit-label {
  @apply mb-2 text-xs font-semibold uppercase tracking-0.08em;
  color: var(--color-text-secondary);
}

@media (max-width: 768px) {
  .provider-scope {
    border-radius: 20px;
    padding: 16px;
  }

  .provider-card {
    border-radius: 18px;
    padding: 14px;
  }

  .provider-card-header {
    flex-direction: column;
    align-items: flex-start;
  }

  .limit-grid {
    grid-template-columns: 1fr;
  }
}
</style>
