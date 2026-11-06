<script setup lang="ts">
const props = withDefaults(defineProps<{
  label: string;
  error?: string | null;
}>(), {
  error: null,
});
</script>

<template>
  <label class="field">
    <span>{{ props.label }}</span>
    <slot />
    <slot name="hint" />
    <span
      v-if="props.error"
      class="field-error"
    >{{ props.error }}</span>
  </label>
</template>
