import type { RouteKey } from '@elegant-router/types';
import { computed, ref } from 'vue';
import { defineStore } from 'pinia';
import { SetupStoreId } from '@/enum';
import { useAuthStore } from '../auth';

type WorkspaceModuleKey = 'overview' | 'chat' | 'knowledge-base' | 'model-provider' | 'chat-history' | 'settings';

type WorkbenchShellView = 'home' | 'chat-list' | 'knowledge-base' | 'settings' | 'admin';

type SettingsSectionKey = 'general' | 'account' | 'about';

type KnowledgeCategoryKey = 'all' | 'document' | 'sheet';

type AdminSectionKey = 'user' | 'chat-history' | 'model-provider' | 'org-tag' | 'invite-code' | 'usage-monitor' | 'recharge' | 'recharge-manage';

type WorkspaceModuleItem = {
  key: WorkspaceModuleKey;
  label: string;
  icon: string;
  routeKey: RouteKey;
  adminOnly?: boolean;
  description: string;
};

const WORKSPACE_MODULES: WorkspaceModuleItem[] = [
  {
    key: 'overview',
    label: '总览',
    icon: 'solar:home-angle-linear',
    routeKey: 'personal-center',
    description: '账户与工作台概览'
  },
  {
    key: 'chat',
    label: '聊天',
    icon: 'solar:chat-round-call-linear',
    routeKey: 'chat',
    description: '当前 AI 对话舞台'
  },
  {
    key: 'knowledge-base',
    label: '知识库',
    icon: 'solar:folder-with-files-linear',
    routeKey: 'knowledge-base',
    description: '文档、切片与引用来源'
  },
  {
    key: 'model-provider',
    label: '模型',
    icon: 'solar:tuning-square-linear',
    routeKey: 'model-provider',
    adminOnly: true,
    description: '模型与提供方配置'
  },
  {
    key: 'chat-history',
    label: '历史',
    icon: 'solar:history-linear',
    routeKey: 'chat-history',
    adminOnly: true,
    description: '会话审计与只读回放'
  },
  {
    key: 'settings',
    label: '设置',
    icon: 'solar:settings-linear',
    routeKey: 'personal-center',
    description: '个人偏好与工作区配置'
  }
];

export const useWorkspaceStore = defineStore(SetupStoreId.Workspace, () => {
  const authStore = useAuthStore();
  const currentModule = ref<WorkspaceModuleKey>('chat');
  const shellView = ref<WorkbenchShellView>('home');
  const settingsSection = ref<SettingsSectionKey>('general');
  const knowledgeCategory = ref<KnowledgeCategoryKey>('all');
  const adminSection = ref<AdminSectionKey>('user');

  const modules = computed(() => {
    return WORKSPACE_MODULES.map(item => ({
      ...item,
      disabled: Boolean(item.adminOnly && !authStore.isAdmin)
    }));
  });

  const currentModuleItem = computed(() => {
    return modules.value.find(item => item.key === currentModule.value) || modules.value[1];
  });

  function setCurrentModule(moduleKey: WorkspaceModuleKey) {
    currentModule.value = moduleKey;
  }

  function setShellView(view: WorkbenchShellView) {
    shellView.value = view;
  }

  function setSettingsSection(section: SettingsSectionKey) {
    settingsSection.value = section;
  }

  function setKnowledgeCategory(category: KnowledgeCategoryKey) {
    knowledgeCategory.value = category;
    shellView.value = 'knowledge-base';
  }

  function openHome() {
    shellView.value = 'home';
    currentModule.value = 'chat';
  }

  function openChatList() {
    shellView.value = 'chat-list';
    currentModule.value = 'chat';
  }

  function openKnowledgeBase(category: KnowledgeCategoryKey = knowledgeCategory.value) {
    knowledgeCategory.value = category;
    shellView.value = 'knowledge-base';
    currentModule.value = 'knowledge-base';
  }

  function openSettings(section: SettingsSectionKey = settingsSection.value) {
    settingsSection.value = section;
    shellView.value = 'home';
    currentModule.value = 'settings';
  }

  function openAdminSection(section: AdminSectionKey = adminSection.value) {
    adminSection.value = section;
    shellView.value = 'home';
  }

  function syncModuleByRouteName(routeName: string | null | undefined) {
    const matched = WORKSPACE_MODULES.find(item => item.routeKey === routeName);
    currentModule.value = matched?.key || 'chat';

    if (routeName === 'chat') {
      shellView.value = shellView.value === 'chat-list' ? 'chat-list' : 'home';
      return;
    }

    if (routeName === 'knowledge-base') {
      shellView.value = 'knowledge-base';
      return;
    }

    if (routeName === 'personal-center') {
      shellView.value = 'home';
      return;
    }

    const adminRouteNames: AdminSectionKey[] = [
      'user',
      'chat-history',
      'model-provider',
      'org-tag',
      'invite-code',
      'usage-monitor',
      'recharge',
      'recharge-manage'
    ];

    if (adminRouteNames.includes(routeName as AdminSectionKey)) {
      adminSection.value = routeName as AdminSectionKey;
      shellView.value = 'home';
    }
  }

  return {
    modules,
    currentModule,
    currentModuleItem,
    shellView,
    settingsSection,
    knowledgeCategory,
    adminSection,
    setCurrentModule,
    setShellView,
    setSettingsSection,
    setKnowledgeCategory,
    openHome,
    openChatList,
    openKnowledgeBase,
    openSettings,
    openAdminSection,
    syncModuleByRouteName
  };
});
