<script setup lang="ts">
import { computed } from 'vue';

import { NETWORK_ERROR_CODE } from '../../api/errors';

const props = withDefaults(defineProps<{
  detail: string | null;
  code?: string | null;
  traceId?: string | null;
  /** 宿主已经把 ProblemDetail 的字段错误贴到各输入项上时置真：告警条就不再重复报 code 与 trace。 */
  fieldsShown?: boolean;
}>(), {
  code: null,
  traceId: null,
  fieldsShown: false,
});

/**
 * 三个展示分支（D-190）：传输失败只说人话（`CLIENT-*` 是前端本地标记，报给使用者没有意义）、
 * 校验类只说结论（明细已在输入项旁）、其余如实给出 detail + code + trace 供运维上报。
 * 429 的等待秒数刻意不进这里：它由宿主的 useRetryWindow 逐秒倒数，放进 `role="alert"` 会让告警每秒重播一次。
 */
const branch = computed<'network' | 'fields' | 'server'>(() => {
  if (props.code === NETWORK_ERROR_CODE) {
    return 'network';
  }
  return props.fieldsShown ? 'fields' : 'server';
});
</script>

<template>
  <p
    v-if="props.detail !== null"
    class="error-text"
    role="alert"
  >
    {{ props.detail }}
    <span
      v-if="branch === 'server' && props.code !== null"
      class="muted"
    >（code {{ props.code }}）</span>
    <span
      v-if="branch === 'server' && props.traceId !== null"
      class="muted"
    >（trace {{ props.traceId }}）</span>
  </p>
</template>
