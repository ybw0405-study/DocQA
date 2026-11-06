<script setup lang="ts">
const props = defineProps<{
  /** 文案必须来自 `utils/display.ts` 的 `EMPTY_STATE_TEXT`，由 src/components/ui/EmptyState.spec.ts 机判。 */
  text: string;
}>();
</script>

<template>
  <p class="muted">
    {{ props.text }}
  </p>
</template>
