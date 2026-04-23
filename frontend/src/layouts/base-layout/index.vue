<script setup lang="ts">
import GlobalContent from '../modules/global-content/index.vue';
import UnifiedWorkbenchShell from '../modules/unified-workbench-shell/index.vue';
import ReferencePreviewModal from '@/views/chat/modules/reference-preview-modal.vue';

defineOptions({
  name: 'BaseLayout'
});

</script>

<template>
  <UnifiedWorkbenchShell>
    <GlobalContent :show-padding="false" />
    <Teleport to="body">
      <ReferencePreviewModal />
    </Teleport>
  </UnifiedWorkbenchShell>
</template>

<style scoped lang="scss"></style>
