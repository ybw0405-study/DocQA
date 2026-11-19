<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';

import AppAlert from '../../components/ui/AppAlert.vue';
import BaseButton from '../../components/ui/BaseButton.vue';
import BaseCard from '../../components/ui/BaseCard.vue';
import BaseField from '../../components/ui/BaseField.vue';
import EmptyState from '../../components/ui/EmptyState.vue';
import SkeletonBlock from '../../components/ui/SkeletonBlock.vue';
import { useAdminAuditStore } from '../../stores/adminAudit';
import type { AuditEventItem, AuditSeverity } from '../../types/admin';
import { AUDIT_PAYLOAD_LABELS, AUDIT_SEVERITY_LABELS, EMPTY_STATE_TEXT, formatDateTime } from '../../utils/display';

const audit = useAdminAuditStore();

const traceIdValue = ref('');
const userIdValue = ref('');
const eventTypeValue = ref('');
const fromValue = ref('');
const toValue = ref('');
const expandedId = ref<string | null>(null);

/**
 * 载荷是开放对象（契约 `additionalProperties: true`），这里的白名单只决定「有没有中文标签」，
 * 不决定「显不显示」：未知键照样出现，只是显示原始键名。反过来把它当校验就会把服务端新加的
 * 治理字段静默吞掉，而那是 US-08 可追溯性的一部分。
 */
function payloadEntries(event: AuditEventItem): { key: string; label: string; value: string; known: boolean }[] {
  return Object.keys(event.payload)
    .sort()
    .map((key) => ({
      key,
      label: AUDIT_PAYLOAD_LABELS[key] ?? key,
      value: formatValue(event.payload[key]),
      known: AUDIT_PAYLOAD_LABELS[key] !== undefined,
    }));
}

/** 布尔按「是 / 否」读，数组按逗号并列，其余一律走文本插值；不做任何 HTML 拼接。 */
function formatValue(value: unknown): string {
  if (value === null) {
    return '无值';
  }
  if (typeof value === 'boolean') {
    return value ? '是' : '否';
  }
  if (Array.isArray(value)) {
    return value.map((item) => String(item)).join('、');
  }
  return String(value);
}

function rawPayload(event: AuditEventItem): string {
  return JSON.stringify(event.payload);
}

function hasPayload(event: AuditEventItem): boolean {
  return Object.keys(event.payload).length > 0;
}

function severityChip(severity: AuditSeverity): string {
  return severity === 'INFO' ? 'governance__chip' : 'governance__chip governance__chip--warn';
}

function toggle(event: AuditEventItem): void {
  expandedId.value = expandedId.value === event.eventId ? null : event.eventId;
}

const busy = computed(() => audit.loading || audit.submitting);

async function applyFilters(): Promise<void> {
  expandedId.value = null;
  await audit.applyFilters({
    traceId: traceIdValue.value,
    userId: userIdValue.value,
    eventType: eventTypeValue.value,
    from: fromValue.value,
    to: toValue.value,
  });
}

async function clearFilters(): Promise<void> {
  traceIdValue.value = '';
  userIdValue.value = '';
  eventTypeValue.value = '';
  fromValue.value = '';
  toValue.value = '';
  await applyFilters();
}

async function reload(): Promise<void> {
  expandedId.value = null;
  await audit.loadFirst();
}

onMounted(() => {
  void audit.loadFirst();
});
</script>

<template>
  <BaseCard
    title="审计查询"
    :level="1"
  >
    <details class="page-note">
      <summary>这一页读的是什么</summary>
      <p>
        事件来自 audit_event 单表只读查询：payload 是写入时已过白名单与 PII 清洗的结果，不是原始输入。
        审计只有追加，本页不提供任何修改或删除入口。
      </p>
      <p>
        两个时刻留空即由服务端取默认时间窗（最近 24 小时）。跨度和先后关系只写在契约描述里、
        没有机器可读键，因此本页不预拦，超出上限时原样呈现服务端那条 400。
      </p>
    </details>

    <div class="admin__toolbar">
      <BaseField label="traceId">
        <input
          v-model="traceIdValue"
          type="text"
          placeholder="32 位小写十六进制"
          @keyup.enter="applyFilters"
        >
      </BaseField>
      <BaseField label="userId">
        <input
          v-model="userIdValue"
          type="text"
          placeholder="UUID"
          @keyup.enter="applyFilters"
        >
      </BaseField>
      <BaseField label="eventType">
        <input
          v-model="eventTypeValue"
          type="text"
          placeholder="如 RAG_RETRIEVED"
          @keyup.enter="applyFilters"
        >
      </BaseField>
      <BaseField label="起始时刻">
        <input
          v-model="fromValue"
          type="text"
          placeholder="2026-11-03T00:00:00Z"
          @keyup.enter="applyFilters"
        >
      </BaseField>
      <BaseField label="结束时刻">
        <input
          v-model="toValue"
          type="text"
          placeholder="2026-11-04T00:00:00Z"
          @keyup.enter="applyFilters"
        >
      </BaseField>
      <BaseButton
        :disabled="busy"
        @click="applyFilters"
      >
        查询
      </BaseButton>
      <BaseButton
        :disabled="busy"
        @click="clearFilters"
      >
        清空条件
      </BaseButton>
      <BaseButton
        :disabled="busy"
        @click="reload"
      >
        {{ busy ? '处理中…' : '刷新' }}
      </BaseButton>
    </div>

    <p class="muted">
      留空即由服务端取最近 24 小时；跨度超限时原样呈现服务端那条 400。
    </p>

    <AppAlert
      :detail="audit.detail"
      :code="audit.code"
      :trace-id="audit.traceId"
    />

    <div
      v-if="audit.events.length > 0"
      class="table-scroll"
    >
      <table class="audit-table">
        <caption>按发生时间倒序排列的审计事件，展开行可查看清洗后的治理载荷。</caption>
        <thead>
          <tr>
            <th scope="col">
              发生时间
            </th>
            <th scope="col">
              事件类型
            </th>
            <th scope="col">
              写入端
            </th>
            <th scope="col">
              严重度
            </th>
            <th scope="col">
              traceId
            </th>
            <th scope="col">
              用户
            </th>
            <th scope="col">
              操作
            </th>
          </tr>
        </thead>
        <tbody>
          <template
            v-for="event in audit.events"
            :key="event.eventId"
          >
            <tr>
              <td>{{ formatDateTime(event.occurredAt) }}</td>
              <td>
                <span class="audit-table__event">{{ event.eventType }}</span>
                <span class="muted">{{ event.agentName ?? '无执行体' }}</span>
              </td>
              <td>{{ event.source }}</td>
              <td>
                <span :class="severityChip(event.severity)">{{ AUDIT_SEVERITY_LABELS[event.severity] }}</span>
              </td>
              <td class="mono-id">
                {{ event.traceId }}
              </td>
              <td class="mono-id">
                {{ event.userId }}
              </td>
              <td class="audit-table__actions">
                <BaseButton @click="toggle(event)">
                  {{ expandedId === event.eventId ? '收起载荷' : '载荷' }}
                </BaseButton>
              </td>
            </tr>

            <tr
              v-if="expandedId === event.eventId"
              class="audit-table__expanded"
            >
              <td colspan="7">
                <dl
                  v-if="hasPayload(event)"
                  class="admin-state"
                >
                  <div
                    v-for="entry in payloadEntries(event)"
                    :key="entry.key"
                  >
                    <dt>
                      {{ entry.label }}
                      <span
                        v-if="!entry.known"
                        class="muted"
                      >（未登记键名）</span>
                    </dt>
                    <dd>{{ entry.value }}</dd>
                  </div>
                </dl>
                <p
                  v-else
                  class="muted"
                >
                  这条事件没有载荷。
                </p>

                <details class="admin-raw">
                  <summary>原文 JSON</summary>
                  <p class="admin-raw__body">
                    {{ rawPayload(event) }}
                  </p>
                </details>

                <p class="muted">
                  载荷键名按字母序展示，只为便于扫读，不改变服务端返回的内容与顺序含义。
                </p>
              </td>
            </tr>
          </template>
        </tbody>
      </table>
    </div>
    <SkeletonBlock
      v-else-if="audit.loading && !audit.loaded"
      label="审计事件加载中"
    />
    <EmptyState
      v-else-if="audit.loaded"
      :text="audit.hasFilter ? EMPTY_STATE_TEXT.auditFiltered : EMPTY_STATE_TEXT.auditWindow"
    />

    <BaseButton
      v-if="audit.canLoadMore"
      @click="audit.loadMore()"
    >
      加载更多
    </BaseButton>
    <p
      v-else-if="audit.events.length > 0"
      class="muted"
    >
      本页已加载 {{ audit.events.length }} 条。契约不返回总数，翻页时若锚点失效会回到第一页并给出提示。
    </p>
  </BaseCard>
</template>
