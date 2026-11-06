<script setup lang="ts">
const props = withDefaults(defineProps<{
  /** 读屏播报的占位说明；只描述「正在加载什么」，不携带数量。 */
  label: string;
  rows?: number;
}>(), {
  rows: 3,
});
</script>

<template>
  <div
    class="skeleton"
    role="status"
    aria-busy="true"
    :aria-label="props.label"
  >
    <div
      v-for="row in props.rows"
      :key="row"
      class="skeleton__row"
      aria-hidden="true"
    />
  </div>
</template>
