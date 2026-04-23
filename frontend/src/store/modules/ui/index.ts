import { SetupStoreId } from '@/enum';

type WorkspaceWindowType = 'reference-preview' | 'settings' | 'knowledge-base' | 'account';

type WorkspaceWindowMode = 'standard' | 'immersive' | 'fullscreen';

type ReferencePreviewPayload = Partial<Api.Document.ReferenceDetailResponse> & {
  fileName: string;
  fileMd5?: string | null;
  pageNumber?: number | null;
  anchorText?: string | null;
  sessionId?: string;
  referenceNumber: number;
};

type WorkspaceWindowState = {
  type: WorkspaceWindowType;
  title?: string;
  badge?: string;
  meta?: string;
  mode?: WorkspaceWindowMode;
  width?: string;
  height?: string;
  closable?: boolean;
  stackId?: string;
  openedAt?: string;
  payload?: Record<string, any> | null;
};

export const useUiStore = defineStore(SetupStoreId.Ui, () => {
  const userPanelVisible = ref(false);
  const workspaceWindow = ref<WorkspaceWindowState | null>(null);
  const workspaceWindowStack = ref<WorkspaceWindowState[]>([]);
  const referencePreview = ref<ReferencePreviewPayload | null>(null);

  const referencePreviewVisible = computed(() => Boolean(referencePreview.value));
  const workspaceWindowVisible = computed(() => Boolean(workspaceWindow.value));
  const workspaceWindowMode = computed<WorkspaceWindowMode>(() => workspaceWindow.value?.mode || 'standard');
  const workspaceWindowDepth = computed(() => workspaceWindowStack.value.length);

  function normalizeWorkspaceWindowState(state: WorkspaceWindowState): WorkspaceWindowState {
    const now = new Date().toISOString();

    return {
      badge: state.badge || '工作窗',
      meta: state.meta || '',
      mode: state.mode || 'standard',
      width: state.width,
      height: state.height,
      closable: state.closable !== false,
      stackId: state.stackId || `${state.type}:${Date.now()}`,
      openedAt: state.openedAt || now,
      ...state,
      payload: state.payload || null
    };
  }

  function openUserPanel() {
    userPanelVisible.value = true;
  }

  function closeUserPanel() {
    userPanelVisible.value = false;
  }

  function toggleUserPanel(visible?: boolean) {
    userPanelVisible.value = typeof visible === 'boolean' ? visible : !userPanelVisible.value;
  }

  function openWorkspaceWindow(state: WorkspaceWindowState) {
    const normalizedState = normalizeWorkspaceWindowState(state);
    workspaceWindow.value = normalizedState;
    workspaceWindowStack.value = [...workspaceWindowStack.value, normalizedState].slice(-6);
  }

  function closeWorkspaceWindow() {
    workspaceWindow.value = null;
  }

  function setWorkspaceWindowMode(mode: WorkspaceWindowMode) {
    if (!workspaceWindow.value) return;

    workspaceWindow.value = {
      ...workspaceWindow.value,
      mode
    };

    workspaceWindowStack.value = workspaceWindowStack.value.map(item => {
      if (item.stackId !== workspaceWindow.value?.stackId) {
        return item;
      }

      return {
        ...item,
        mode
      };
    });
  }

  function cycleWorkspaceWindowMode() {
    const mode = workspaceWindowMode.value;

    if (mode === 'standard') {
      setWorkspaceWindowMode('immersive');
      return;
    }

    if (mode === 'immersive') {
      setWorkspaceWindowMode('fullscreen');
      return;
    }

    setWorkspaceWindowMode('standard');
  }

  function openReferencePreview(payload: ReferencePreviewPayload) {
    referencePreview.value = { ...payload };
    openWorkspaceWindow({
      type: 'reference-preview',
      title: payload.fileName || '引用文档预览',
      badge: '引用预览',
      meta: payload.anchorText || '文档来源、页码与引用上下文将在此工作窗内查看',
      mode: 'immersive',
      width: 'min(1380px, calc(100vw - 40px))',
      height: 'min(90vh, 920px)',
      payload: { ...payload }
    });
  }

  function closeReferencePreview() {
    referencePreview.value = null;
    if (workspaceWindow.value?.type === 'reference-preview') {
      closeWorkspaceWindow();
    }
  }

  function resetUiState() {
    userPanelVisible.value = false;
    workspaceWindow.value = null;
    workspaceWindowStack.value = [];
    referencePreview.value = null;
  }

  return {
    userPanelVisible,
    workspaceWindow,
    workspaceWindowStack,
    workspaceWindowVisible,
    workspaceWindowMode,
    workspaceWindowDepth,
    referencePreview,
    referencePreviewVisible,
    openUserPanel,
    closeUserPanel,
    toggleUserPanel,
    openWorkspaceWindow,
    closeWorkspaceWindow,
    setWorkspaceWindowMode,
    cycleWorkspaceWindowMode,
    openReferencePreview,
    closeReferencePreview,
    resetUiState
  };
});
