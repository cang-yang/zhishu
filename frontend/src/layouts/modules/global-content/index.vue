<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import { LAYOUT_SCROLL_EL_ID } from '@sa/materials';
import { useAppStore } from '@/store/modules/app';
import { useThemeStore } from '@/store/modules/theme';
import { useRouteStore } from '@/store/modules/route';
import { useTabStore } from '@/store/modules/tab';

defineOptions({
  name: 'GlobalContent'
});

interface Props {
  /** Show padding for content */
  showPadding?: boolean;
}

withDefaults(defineProps<Props>(), {
  showPadding: true
});

const appStore = useAppStore();
const themeStore = useThemeStore();
const routeStore = useRouteStore();
const tabStore = useTabStore();
const route = useRoute();

const transitionName = computed(() => (themeStore.page.animate ? themeStore.page.animateMode : ''));
const isWorkbenchRoute = computed(() => Boolean(route.name));

function resetScroll() {
  const el = document.querySelector(`#${LAYOUT_SCROLL_EL_ID}`);

  el?.scrollTo({ left: 0, top: 0 });
}
</script>

<template>
  <RouterView v-slot="{ Component, route }">
    <Transition
      :name="transitionName"
      mode="out-in"
      @before-leave="appStore.setContentXScrollable(true)"
      @after-leave="resetScroll"
      @after-enter="appStore.setContentXScrollable(false)"
    >
      <KeepAlive :include="routeStore.cacheRoutes" :exclude="routeStore.excludeCacheRoutes">
        <component
          :is="Component"
          v-if="appStore.reloadFlag"
          :key="tabStore.getTabIdByRoute(route)"
          :class="[
            {
              'global-page-view--padded': showPadding && !isWorkbenchRoute,
              'global-page-view--workbench': isWorkbenchRoute
            },
            'global-page-view'
          ]"
          class="flex-grow bg-layout transition-300"
        />
      </KeepAlive>
    </Transition>
  </RouterView>
</template>

<style scoped lang="scss">
.global-page-view {
  height: 100%;
  min-height: 100%;
  width: 100%;
  overflow: hidden;
  border-radius: 24px 24px 0 0;

  &--workbench {
    height: 100%;
    min-height: 100%;
    border-radius: 0;
    background: transparent !important;
  }
}

.global-page-view--padded {
  padding: 24px 24px 28px;
}

@media (max-width: 1024px) {
  .global-page-view--padded {
    padding: 20px 18px 24px;
  }
}

@media (max-width: 768px) {
  .global-page-view {
    border-radius: 20px 20px 0 0;

    &--workbench {
      min-height: 100%;
      border-radius: 0;
    }
  }

  .global-page-view--padded {
    padding: 16px 14px 20px;
  }
}
</style>
