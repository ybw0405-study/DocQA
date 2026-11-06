<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';

import AppAlert from '../../components/ui/AppAlert.vue';
import BaseButton from '../../components/ui/BaseButton.vue';
import BaseCard from '../../components/ui/BaseCard.vue';
import BaseField from '../../components/ui/BaseField.vue';
import EmptyState from '../../components/ui/EmptyState.vue';
import SkeletonBlock from '../../components/ui/SkeletonBlock.vue';
import { useAdminUsageStore } from '../../stores/adminUsage';
import { EMPTY_STATE_TEXT, LATENCY_NOTE, USAGE_STATUS_LABELS, formatDateTime } from '../../utils/display';

const usage = useAdminUsageStore();

const fromValue = ref('');
const toValue = ref('');

const totals = computed(() => usage.summary?.totals ?? null);

/**
 * 比例条必须有归一基准，而契约在这里不提供任何「占比」字段。这里选择「列内最大值」作基准并把它标出来：
 * 换成前端自算的分母（例如四类状态计数相加）就是在造一个契约里没有的指标，
 * 与本片「不做状态推断以外的再计算」的边界冲突。条长只表达相对高低，数字仍然逐行给出。
 */
function maxOf<T>(rows: readonly T[], pick: (row: T) => number): number {
  return rows.reduce((max, row) => Math.max(max, pick(row)), 0);
}

const maxDayQuestions = computed(() => maxOf(usage.summary?.byDay ?? [], (row) => row.questions));
const maxDayTokens = computed(() => maxOf(usage.summary?.byDay ?? [], (row) => row.tokens));
const maxStatusCount = computed(() => maxOf(usage.summary?.byStatus ?? [], (row) => row.count));
const maxTopUserQuestions = computed(() => maxOf(usage.summary?.topUsers ?? [], (row) => row.questions));

function barWidth(value: number, max: number): string {
  return max <= 0 ? '0%' : `${((value / max) * 100).toFixed(1)}%`;
}

const avgLatencyText = computed(() => {
  const value = totals.value?.avgLatencyMs;
  return value === null || value === undefined ? '窗口内无执行' : `${String(value)} ms`;
});

const busy = computed(() => usage.loading || usage.submitting);

async function applyWindow(): Promise<void> {
  await usage.applyWindow({ from: fromValue.value, to: toValue.value });
}

async function clearWindow(): Promise<void> {
  fromValue.value = '';
  toValue.value = '';
  await applyWindow();
}

async function reload(): Promise<void> {
  await usage.load();
}

onMounted(() => {
  void usage.load();
});
</script>

<template>
  <BaseCard
    title="AI 用量"
    :level="1"
  >
    <details class="page-note">
      <summary>这一页的数字怎么算</summary>
      <p>
        数据来自 agent_execution 与问答计数的只读聚合：只提供计数与均值，不返回 p50/p95，
        也不承诺任何延迟上界。
      </p>
      <p>
        两个时刻留空即用服务端默认窗口（最近 24 小时）。跨度上限与先后关系只写在契约描述里、
        没有机器可读键，因此本页不预拦，非法窗口原样呈现服务端那条 400。
      </p>
    </details>

    <div class="admin__toolbar">
      <BaseField label="窗口起始">
        <input
          v-model="fromValue"
          type="text"
          placeholder="2026-11-03T00:00:00Z"
          @keyup.enter="applyWindow"
        >
      </BaseField>
      <BaseField label="窗口结束">
        <input
          v-model="toValue"
          type="text"
          placeholder="2026-11-04T00:00:00Z"
          @keyup.enter="applyWindow"
        >
      </BaseField>
      <BaseButton
        :disabled="busy"
        @click="applyWindow"
      >
        查询
      </BaseButton>
      <BaseButton
        :disabled="busy"
        @click="clearWindow"
      >
        清空窗口
      </BaseButton>
      <BaseButton
        :disabled="busy"
        @click="reload"
      >
        {{ busy ? '处理中…' : '刷新' }}
      </BaseButton>
    </div>

    <p class="muted">
      两个时刻都是 RFC 3339（如 2026-11-03T00:00:00Z）；留空即取最近 24 小时。
    </p>

    <AppAlert
      :detail="usage.detail"
      :code="usage.code"
      :trace-id="usage.traceId"
    />

    <SkeletonBlock
      v-if="usage.loading && !usage.loaded"
      label="用量汇总加载中"
    />

    <p
      v-if="usage.summary !== null"
      class="muted"
    >
      服务端回显的实际窗口：{{ formatDateTime(usage.summary.from) }} — {{ formatDateTime(usage.summary.to) }}（粒度 {{ usage.summary.bucket }}）。
    </p>
  </BaseCard>

  <BaseCard
    v-if="totals !== null"
    title="窗口汇总"
  >
    <dl class="admin-state">
      <div>
        <dt>问答次数</dt>
        <dd>{{ totals.questions }}</dd>
      </div>
      <div>
        <dt>成功</dt>
        <dd>{{ totals.success }}</dd>
      </div>
      <div>
        <dt>预算耗尽</dt>
        <dd>{{ totals.budgetExhausted }}</dd>
      </div>
      <div>
        <dt>失败</dt>
        <dd>{{ totals.failed }}</dd>
      </div>
      <div>
        <dt>token 用量</dt>
        <dd>{{ totals.tokens }}</dd>
      </div>
      <div>
        <dt>工具调用</dt>
        <dd>{{ totals.toolCalls }}</dd>
      </div>
      <div>
        <dt>编排轮次</dt>
        <dd>{{ totals.rounds }}</dd>
      </div>
      <div>
        <dt>缓存命中</dt>
        <dd>{{ totals.cacheHits }}</dd>
      </div>
      <div>
        <dt>平均延迟</dt>
        <dd>{{ avgLatencyText }}</dd>
      </div>
    </dl>
    <p class="muted">
      {{ LATENCY_NOTE }}
    </p>
    <p class="muted">
      「预算耗尽」与「失败」是两个计数：前者是额度闸拦住，后者是执行本身没走完，合并统计会把治理问题读成故障。
    </p>
  </BaseCard>

  <BaseCard
    v-if="usage.summary !== null"
    title="按日序列"
  >
    <div
      v-if="usage.summary.byDay.length > 0"
      class="table-scroll"
    >
      <table class="usage-table">
        <caption>按 UTC 自然日聚合的问答、token 与失败计数。</caption>
        <thead>
          <tr>
            <th scope="col">
              日期
            </th>
            <th scope="col">
              问答
            </th>
            <th scope="col">
              token
            </th>
            <th scope="col">
              失败
            </th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="point in usage.summary.byDay"
            :key="point.date"
          >
            <td>{{ point.date }}</td>
            <td>
              <div class="ratio-bar">
                <span
                  class="ratio-bar__fill"
                  :style="{ width: barWidth(point.questions, maxDayQuestions) }"
                />
                <span>{{ point.questions }}</span>
              </div>
            </td>
            <td>
              <div class="ratio-bar">
                <span
                  class="ratio-bar__fill"
                  :style="{ width: barWidth(point.tokens, maxDayTokens) }"
                />
                <span>{{ point.tokens }}</span>
              </div>
            </td>
            <td>{{ point.failed }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <EmptyState
      v-else
      :text="EMPTY_STATE_TEXT.usageNone"
    />
    <p
      v-if="usage.summary.byDay.length > 0"
      class="muted"
    >
      条长按本列最大值归一：问答列基准 {{ maxDayQuestions }}，token 列基准 {{ maxDayTokens }}。条长只表示相对高低，不是百分比占比。
    </p>
  </BaseCard>

  <BaseCard
    v-if="usage.summary !== null"
    title="状态分布与高频用户"
  >
    <div
      v-if="usage.summary.byStatus.length > 0"
      class="table-scroll"
    >
      <table class="usage-table">
        <caption>执行状态分布，四类计数各自独立。</caption>
        <thead>
          <tr>
            <th scope="col">
              状态
            </th>
            <th scope="col">
              次数
            </th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="row in usage.summary.byStatus"
            :key="row.status"
          >
            <td>{{ USAGE_STATUS_LABELS[row.status] }}</td>
            <td>
              <div class="ratio-bar">
                <span
                  class="ratio-bar__fill"
                  :style="{ width: barWidth(row.count, maxStatusCount) }"
                />
                <span>{{ row.count }}</span>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div
      v-if="usage.summary.topUsers.length > 0"
      class="table-scroll"
    >
      <table class="usage-table">
        <caption>窗口内问答次数最多的用户，排序键由服务端决定。</caption>
        <thead>
          <tr>
            <th scope="col">
              用户
            </th>
            <th scope="col">
              问答
            </th>
            <th scope="col">
              token
            </th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="row in usage.summary.topUsers"
            :key="row.userId"
          >
            <td>
              {{ row.username ?? '—' }}
              <span class="muted">{{ row.userId }}</span>
            </td>
            <td>
              <div class="ratio-bar">
                <span
                  class="ratio-bar__fill"
                  :style="{ width: barWidth(row.questions, maxTopUserQuestions) }"
                />
                <span>{{ row.questions }}</span>
              </div>
            </td>
            <td>{{ row.tokens }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <p class="muted">
      列表按服务端给定的排序展示，本页不重排、也不补「共多少用户」这类总数。
    </p>
  </BaseCard>
</template>
