<script setup lang="ts">
import SvgIcon from '@/components/custom/svg-icon.vue';

type FloatingMenuItem = {
  key: string;
  label: string;
  icon?: string;
  danger?: boolean;
};

const props = defineProps<{
  visible: boolean;
  items: FloatingMenuItem[];
  title?: string;
  position: {
    x: number;
    y: number;
  };
}>();

const emit = defineEmits<{
  (e: 'select', key: string): void;
  (e: 'close'): void;
}>();

const menuRef = ref<HTMLElement | null>(null);
const menuStyle = ref<Record<string, string>>({
  left: '8px',
  top: '8px'
});

function closeMenu() {
  emit('close');
}

function handleSelect(key: string) {
  emit('select', key);
  closeMenu();
}

function updateMenuPosition() {
  if (!props.visible || typeof window === 'undefined') return;

  const viewportWidth = window.innerWidth;
  const viewportHeight = window.innerHeight;
  const menuWidth = menuRef.value?.offsetWidth || 172;
  const menuHeight = menuRef.value?.offsetHeight || 0;
  const margin = 8;
  const offset = 4;

  let left = props.position.x - menuWidth / 2;
  let top = props.position.y + offset;

  if (left + menuWidth > viewportWidth - margin) {
    left = viewportWidth - menuWidth - margin;
  }

  if (left < margin) {
    left = margin;
  }

  if (top + menuHeight > viewportHeight - margin) {
    top = Math.max(margin, props.position.y - menuHeight - offset);
  }

  menuStyle.value = {
    left: `${Math.round(left)}px`,
    top: `${Math.round(top)}px`
  };
}

function handleViewportChange() {
  void nextTick(updateMenuPosition);
}

watch(
  () => props.visible,
  async visible => {
    if (!visible) return;
    await nextTick();
    updateMenuPosition();
  }
);

watch(
  () => [props.position.x, props.position.y, props.items.length, props.title],
  () => {
    if (!props.visible) return;
    void nextTick(updateMenuPosition);
  }
);

onMounted(() => {
  if (typeof window === 'undefined') return;
  window.addEventListener('resize', handleViewportChange);
});

onBeforeUnmount(() => {
  if (typeof window === 'undefined') return;
  window.removeEventListener('resize', handleViewportChange);
});
</script>

<template>
  <Teleport to="body">
    <Transition name="floating-menu-layer">
      <button v-if="visible" type="button" aria-label="关闭菜单" class="floating-menu__overlay" @click="closeMenu" />
    </Transition>

    <Transition name="floating-menu-panel">
      <div v-if="visible" ref="menuRef" class="floating-menu" :style="menuStyle" @click.stop>
        <header v-if="title" class="floating-menu__title">{{ title }}</header>

        <button
          v-for="item in items"
          :key="item.key"
          type="button"
          class="floating-menu__item"
          :class="{ 'floating-menu__item--danger': item.danger }"
          @click="handleSelect(item.key)"
        >
          <SvgIcon v-if="item.icon" :icon="item.icon" class="floating-menu__icon" />
          <span>{{ item.label }}</span>
        </button>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped lang="scss">
.floating-menu__overlay {
  position: fixed;
  inset: 0;
  z-index: 10000;
  border: 0;
  background: transparent;
  padding: 0;
}

.floating-menu {
  position: fixed;
  z-index: 10001;
  width: 172px;
  overflow: hidden;
  border-radius: 10px;
  background: var(--color-bg-elevated);
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.12);
  transform-origin: top center;
}

.floating-menu__title {
  overflow: hidden;
  border-bottom: 0.5px solid rgba(148, 163, 184, 0.18);
  padding: 8px 14px 6px;
  color: var(--color-text-secondary);
  font-size: 12px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.floating-menu__item {
  display: flex;
  height: 36px;
  width: 100%;
  align-items: center;
  gap: 8px;
  border: 0;
  border-bottom: 0.5px solid rgba(148, 163, 184, 0.18);
  background: transparent;
  padding: 0 14px;
  color: var(--color-text-primary);
  font-size: 13px;
  text-align: left;
}

.floating-menu__item:last-child {
  border-bottom: 0;
}

.floating-menu__item:active {
  background: var(--color-bg-card);
}

.floating-menu__item--danger {
  color: #ff4d4f;
}

.floating-menu__icon {
  font-size: 16px;
  opacity: 0.7;
}

.floating-menu-panel-enter-active,
.floating-menu-panel-leave-active {
  transition:
    transform 150ms ease-out,
    opacity 150ms ease-out;
}

.floating-menu-panel-enter-from {
  opacity: 0;
  transform: scale(0.92);
}

.floating-menu-panel-leave-to {
  opacity: 0;
  transform: scale(0.98);
  transition-duration: 100ms;
  transition-timing-function: ease-in;
}

.floating-menu-layer-enter-active,
.floating-menu-layer-leave-active {
  transition: opacity 100ms ease;
}

.floating-menu-layer-enter-from,
.floating-menu-layer-leave-to {
  opacity: 0;
}

.dark .floating-menu {
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.4);
}
</style>
