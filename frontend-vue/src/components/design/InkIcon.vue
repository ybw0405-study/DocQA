<script setup lang="ts">
import { computed } from 'vue';

/** 墨纸风格的线性图标族：16×16 网格，currentColor，不引图标库。 */
type Shape = { tag: 'path' | 'circle' | 'rect' | 'line'; attrs: Record<string, string> };

const props = withDefaults(defineProps<{ name: string; size?: number | string; stroke?: number }>(), {
  size: 20,
  stroke: 1.3,
});

const ICONS: Record<string, Shape[]> = {
  book: [
    { tag: 'path', attrs: { d: 'M2.5 2.5h5.2c1 0 1.8.8 1.8 1.8v9.2H4.3c-1 0-1.8-.8-1.8-1.8V2.5z' } },
    { tag: 'path', attrs: { d: 'M9.5 4.3h4c0-1-.8-1.8-1.8-1.8M9.5 13.5V4.3M4.3 6h3M4.3 8.5h3' } },
  ],
  question: [
    { tag: 'path', attrs: { d: 'M3 3h10v7H7l-3 2.5V10H3V3z' } },
    { tag: 'path', attrs: { d: 'M6.5 6.2c0-1 2.8-1 2.8 0 0 .9-2.8 1-2.8 2.1M7.9 10.1v.6' } },
  ],
  bell: [
    { tag: 'path', attrs: { d: 'M4 11.5h8l-1.2-1.8V6.4a2.8 2.8 0 0 0-5.6 0v3.3L4 11.5z' } },
    { tag: 'path', attrs: { d: 'M6.8 13.2a1.2 1.2 0 0 0 2.4 0' } },
  ],
  seal: [
    { tag: 'rect', attrs: { x: '3', y: '3', width: '10', height: '10', rx: '1' } },
    { tag: 'path', attrs: { d: 'M5.2 6h5.6M5.2 8h5.6M5.2 10h3.2' } },
  ],
  citation: [
    { tag: 'path', attrs: { d: 'M6.5 6H3.6V4.4h3.6v4.2c0 1.8-1.2 3-3.6 3.2V10c1.2-.1 1.9-.8 1.9-2.2' } },
    { tag: 'path', attrs: { d: 'M13 6h-2.9V4.4h3.6v4.2c0 1.8-1.2 3-3.6 3.2V10c1.2-.1 1.9-.8 1.9-2.2' } },
  ],
  scroll: [
    { tag: 'path', attrs: { d: 'M5 2.5h7.5v11H5a2 2 0 0 1-2-2V4.5a2 2 0 0 1 2-2z' } },
    { tag: 'path', attrs: { d: 'M6.5 6h4M6.5 8.5h4M6.5 11h2.5' } },
  ],
  archive: [
    { tag: 'rect', attrs: { x: '2.5', y: '3', width: '11', height: '2.5', rx: '.5' } },
    { tag: 'path', attrs: { d: 'M3.8 5.5v7.5h8.4V5.5M6.4 8.5h3.2' } },
  ],
  lamp: [
    { tag: 'path', attrs: { d: 'M6 2.5h4l2 5H4l2-5z' } },
    { tag: 'path', attrs: { d: 'M8 7.5v4M5.8 13h4.4' } },
  ],
  compass: [
    { tag: 'circle', attrs: { cx: '8', cy: '8', r: '5.6' } },
    { tag: 'path', attrs: { d: 'M10.3 5.7 9 9l-3.3 1.3L7 7l3.3-1.3z' } },
  ],
  ruler: [
    { tag: 'rect', attrs: { x: '2', y: '5.5', width: '12', height: '5', rx: '.5' } },
    { tag: 'path', attrs: { d: 'M5 5.5v2M8 5.5v3M11 5.5v2' } },
  ],
  brush: [
    { tag: 'path', attrs: { d: 'M11.5 2.8l1.7 1.7-5.6 5.6-1.7-1.7 5.6-5.6z' } },
    { tag: 'path', attrs: { d: 'M5.9 8.4c-2 .2-3.4 1.5-3.4 3.4 0 .8-.3 1.4-1 1.8 1.7.9 4.2.1 4.9-1.8.4-1.1.1-2.4-.5-3.4z' } },
  ],
  paper: [
    { tag: 'path', attrs: { d: 'M4 2.5h5l3 3v8H4v-11z' } },
    { tag: 'path', attrs: { d: 'M9 2.5v3h3M6 8h4M6 10.5h4' } },
  ],
};

const shapes = computed(() => ICONS[props.name] ?? ICONS.paper ?? []);
</script>

<template>
  <svg
    class="ink-icon"
    :width="size"
    :height="size"
    viewBox="0 0 16 16"
    fill="none"
    :stroke-width="stroke"
    stroke="currentColor"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
    focusable="false"
  >
    <component
      :is="shape.tag"
      v-for="(shape, index) in shapes"
      :key="index"
      v-bind="shape.attrs"
    />
  </svg>
</template>
