<script setup lang="ts">
import { computed } from 'vue';
import type { AskData } from '../types/conversations';
import { BUDGET_LABELS, LOW_CONFIDENCE_NOTE, formatRatio } from '../utils/display';

const props = defineProps<{
  governance: AskData | null;
  tokenCount: number | null;
  traceId: string | null;
}>();

/**
 * 历史消息接口不返回治理字段，所以这一行只能标成「历史消息」，
 * 不能把 `tokenCount` 当成 `tokenUsed` 冒充预算治理结果。
 */
const headline = computed(() =>
  props.governance === null ? '历史消息' : BUDGET_LABELS[props.governance.budgetStatus],
);

const tone = computed(() => {
  if (props.governance === null) {
    return 'plain';
  }
  return props.governance.budgetStatus === 'OK' ? 'ok' : 'warn';
});
</script>

<template>
  <p class="governance">
    <span
      class="governance__chip"
      :class="`governance__chip--${tone}`"
    >{{ headline }}</span>
    <!-- 「证据不足」单看会被读成什么都没查到，这句是  补的界面口径，不是新字段。 -->
    <span
      v-if="governance !== null && governance.budgetStatus === 'LOW_CONFIDENCE'"
      class="governance__item"
    >{{ LOW_CONFIDENCE_NOTE }}</span>
    <template v-if="governance !== null">
      <span class="governance__item">置信度 {{ formatRatio(governance.confidence) }}</span>
      <span class="governance__item">token {{ governance.tokenUsed }}</span>
      <span class="governance__item">工具调用 {{ governance.toolCallsMade }}</span>
      <span class="governance__item">轮次 {{ governance.roundsUsed }}</span>
      <span class="governance__item">{{ governance.cacheHit ? '命中缓存' : '重新检索' }}</span>
      <span
        v-if="governance.idempotentReplay"
        class="governance__item"
      >幂等重放</span>
    </template>
    <template v-else>
      <span class="governance__item">消息 token {{ tokenCount ?? '—' }}</span>
      <span class="governance__item governance__item--faint">治理字段只在提问当次返回</span>
    </template>
    <span
      v-if="traceId !== null"
      class="governance__item governance__item--faint"
    >trace {{ traceId }}</span>
  </p>
</template>
