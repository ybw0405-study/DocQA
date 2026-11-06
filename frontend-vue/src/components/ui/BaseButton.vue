<script setup lang="ts">
import { computed } from 'vue';

type Variant = 'primary' | 'link' | 'danger';

const props = withDefaults(defineProps<{
  variant?: Variant;
  submit?: boolean;
  disabled?: boolean;
}>(), {
  variant: 'primary',
  submit: false,
  disabled: false,
});

/**
 * 变体只映射到历史 class 名（D-188），外观仍由 base.css 的元素基准与 `button.link` / `button.danger` 两个变体类承担；
 * 原语在这里的价值是「意图到 class 的单一映射」与 `type` 纪律——原生 button 在 form 里省略 type 就是提交按钮。
 */
const variantClass = computed(() => (props.variant === 'primary' ? null : props.variant));
const typeAttr = computed(() => (props.submit ? 'submit' : 'button'));
</script>

<template>
  <button
    :type="typeAttr"
    :class="variantClass"
    :disabled="props.disabled"
  >
    <slot />
  </button>
</template>
