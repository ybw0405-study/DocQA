<script setup lang="ts">
import { computed } from 'vue';
import type { Citation } from '../types/conversations';
import { CITATION_LEXICAL_ONLY_LABEL, CITATION_SCORE_NOTE, formatPercent } from '../utils/display';

const props = defineProps<{
  citations: Citation[];
  knownFileNames: Record<string, string | undefined>;
}>();

/** 口径说明只在真的出现百分比时跟一次；全是词法召回时这句话无处可贴。 */
const showsPercent = computed(() => props.citations.some((citation) => citation.score !== 0));

/**
 * keyset 分页下引用完全可以指向当前页之外的文档，映射缺失是常态；
 * 三级回落后仍然只显示 ID 片段，不为此新增任何接口。
 */
function displayName(citation: Citation): string {
  const fallback = props.knownFileNames[citation.documentId];
  return citation.fileName ?? fallback ?? `文档 ${citation.documentId.slice(0, 8)}`;
}
</script>

<template>
  <div class="citations">
    <p
      v-if="citations.length === 0"
      class="muted"
    >
      本次回答没有可用引用。
    </p>
    <ol v-else>
      <li
        v-for="citation in citations"
        :key="`${citation.documentId}-${citation.chunkIndex}`"
      >
        <p class="citations__head">
          <!-- 文件名与片段都是服务端/对象存储回来的不可信文本，一律文本插值。 -->
          <span class="citations__name">{{ displayName(citation) }}</span>
          <span class="muted">分块 {{ citation.chunkIndex }}</span>
          <!-- score 为 0 是「向量通道没匹配上」，不是「相关度 0%」（D-202 / D-84）。 -->
          <span
            v-if="citation.score === 0"
            class="muted"
          >{{ CITATION_LEXICAL_ONLY_LABEL }}</span>
          <span
            v-else
            class="muted"
          >相关度 {{ formatPercent(citation.score) }}</span>
        </p>
        <p class="citations__snippet">
          {{ citation.snippet }}
        </p>
      </li>
    </ol>
    <p
      v-if="showsPercent"
      class="muted"
    >
      {{ CITATION_SCORE_NOTE }}
    </p>
  </div>
</template>
