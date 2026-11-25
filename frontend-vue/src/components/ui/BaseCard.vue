<script setup lang="ts">
const props = withDefaults(defineProps<{
  /** 留空就是「只有容器、没有标题」的卡片（文档列表那种），标题的补全属  的结构改造。 */
  title?: string | null;
  level?: 1 | 2;
}>(), {
  title: null,
  level: 2,
});
</script>

<template>
  <section class="card">
    <component
      :is="`h${props.level}`"
      v-if="props.title !== null"
    >
      {{ props.title }}
    </component>
    <slot />
  </section>
</template>
