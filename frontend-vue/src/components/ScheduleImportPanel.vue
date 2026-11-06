<script setup lang="ts">
import { computed } from 'vue';
import AppAlert from './ui/AppAlert.vue';
import BaseButton from './ui/BaseButton.vue';
import BaseCard from './ui/BaseCard.vue';
import BaseField from './ui/BaseField.vue';
import { useRetryWindow } from '../composables/useRetryWindow';
import { useScheduleImportStore } from '../stores/scheduleImport';
import type { ScheduleImportItemData } from '../types/reminders';
import {
  ICS_MAX_BYTES,
  LEAD_MINUTES_MAX,
  LEAD_MINUTES_MIN,
  REMINDER_TIMEZONE_MAX_LENGTH,
  SCHEDULE_EVENT_MAX,
} from '../types/reminders';
import { SCHEDULE_IMPORT_ACTION_LABELS, formatBytes, formatDateTime } from '../utils/display';

/** 预览与导入响应共用的判定摘要字段；导入成功后以导入侧为权威。 */
interface ScheduleCounts {
  itemCount: number;
  createCount: number;
  updateCount: number;
  unchangedCount: number;
  staleCount: number;
  fallbackTimezone: string;
  leadMinutes: number;
}

const schedule = useScheduleImportStore();

const { secondsLeft, waiting } = useRetryWindow(() => schedule.retryAfterSeconds);

/** 预览与导入共用同一个限流桶，所以等待期里两个动作都要门控，否则等待提示形同虚设。 */
const busy = computed(() => schedule.previewing || schedule.importing);

const previewLabel = computed(() => (schedule.previewing ? '判定中…' : '预览判定'));

const importLabel = computed(() => {
  if (schedule.importing) {
    return '导入中…';
  }
  return schedule.pendingKey === null ? '确认导入' : '重试同一请求';
});

/**
 * 计数与行的权威都在「最近一次判定」上：导入成功后一律换成导入响应，
 * 因为契约明写导入判定可能与预览不同；同键重放时 `items` 恒为空数组，
 * 这时只替换摘要、保留预览行，否则一张空表会被读成「什么都没导入」。
 */
const counts = computed<ScheduleCounts | null>(() => schedule.result ?? schedule.preview);

const rows = computed<ScheduleImportItemData[]>(() => {
  if (schedule.result !== null && schedule.result.items.length > 0) {
    return schedule.result.items;
  }
  return schedule.preview?.items ?? [];
});

const stageLabel = computed(() => (schedule.result === null ? '预览判定' : '导入后的判定'));

const staleCount = computed(() => counts.value?.staleCount ?? 0);

/** 算不出未来触发时刻的事件：契约上 `nextTriggerAt` 为 null 就是「规则已耗尽或全被排除」。 */
const silentCount = computed(() => rows.value.filter((item) => item.nextTriggerAt === null).length);

function onFileChange(event: Event): void {
  const target = event.target as HTMLInputElement;
  schedule.selectFile(target.files?.[0] ?? null);
  // 不清空 value 的话，重新选同一个文件不会再次触发 change。
  target.value = '';
}

function onTimezone(event: Event): void {
  const target = event.target as HTMLInputElement;
  schedule.patchForm({ fallbackTimezone: target.value });
}

function onLeadMinutes(event: Event): void {
  const target = event.target as HTMLInputElement;
  schedule.patchForm({ leadMinutes: target.value });
}
</script>

<template>
  <BaseCard
    title="课表导入"
    class="schedule-panel"
  >
    <p class="muted">
      选择教务系统导出的 .ics 课表，先看逐事件判定，再确认导入。判定只比较文件里的 SEQUENCE
      与库内同 UID 行的版本号，四种结果互斥且穷尽；导入不会删除课表里已经消失的课程。
    </p>

    <BaseField :label="`课表文件（不超过 ${formatBytes(ICS_MAX_BYTES)}）`">
      <input
        data-testid="ics-file"
        type="file"
        accept=".ics,text/calendar"
        :disabled="busy"
        @change="onFileChange"
      >
      <template #hint>
        <span
          v-if="schedule.file !== null"
          class="muted"
        >已选择 {{ schedule.file.name }}（{{ formatBytes(schedule.file.size) }}）</span>
        <span
          v-else
          class="muted"
        >本地只按字节数预拦，文件内容是否真是 ICS 由服务端判定。</span>
      </template>
    </BaseField>

    <BaseField label="兜底时区（IANA 名称）">
      <input
        data-testid="ics-timezone"
        :value="schedule.fallbackTimezone"
        :maxlength="REMINDER_TIMEZONE_MAX_LENGTH"
        :disabled="busy"
        @input="onTimezone"
      >
    </BaseField>
    <BaseField :label="`提前提醒分钟数（${LEAD_MINUTES_MIN}–${LEAD_MINUTES_MAX}）`">
      <input
        data-testid="ics-lead-minutes"
        type="number"
        :min="LEAD_MINUTES_MIN"
        :max="LEAD_MINUTES_MAX"
        step="1"
        :value="schedule.leadMinutes"
        :disabled="busy"
        @input="onLeadMinutes"
      >
    </BaseField>
    <p class="muted">
      事件缺 DTSTART 时区且课表没有可用 VTIMEZONE 时，按这个兜底时区解释。
      单个课表最多 {{ SCHEDULE_EVENT_MAX }} 个事件。
    </p>

    <BaseButton
      data-testid="ics-preview"
      :disabled="!schedule.canPreview || waiting"
      @click="schedule.runPreview()"
    >
      {{ previewLabel }}
    </BaseButton>
    <BaseButton
      data-testid="ics-import"
      :disabled="!schedule.canImport || waiting"
      @click="schedule.commitImport()"
    >
      {{ importLabel }}
    </BaseButton>

    <p
      v-if="waiting"
      class="muted"
    >
      限流等待 {{ secondsLeft }} 秒。
    </p>
    <p
      v-else-if="schedule.preview === null && schedule.file !== null"
      class="muted"
    >
      必须先预览判定，确认导入才可用。
    </p>

    <AppAlert
      :detail="schedule.detail"
      :code="schedule.code"
      :trace-id="schedule.traceId"
      data-testid="ics-error"
    />

    <template v-if="counts !== null">
      <div class="schedule-panel__counts">
        <span class="schedule-panel__stage">{{ stageLabel }}</span>
        <span class="schedule-count">共 {{ counts?.itemCount }} 个事件</span>
        <span class="schedule-count">新建 {{ counts?.createCount }}</span>
        <span class="schedule-count">更新 {{ counts?.updateCount }}</span>
        <span class="schedule-count">无变化 {{ counts?.unchangedCount }}</span>
        <span
          v-if="staleCount > 0"
          class="schedule-count schedule-count--warn"
        >旧版忽略 {{ staleCount }}</span>
        <span
          v-else
          class="schedule-count"
        >旧版忽略 0</span>
        <span class="schedule-panel__echo muted">
          兜底 {{ counts?.fallbackTimezone }} · 提前 {{ counts?.leadMinutes }} 分钟
          <span v-if="counts?.leadMinutes === 0">（按上课时刻提醒）</span>
        </span>
      </div>

      <p
        v-if="staleCount > 0"
        class="error-text"
      >
        有 {{ staleCount }} 个事件的文件版本比库内更旧，导入会跳过它们，库内规则与历史触发记录都不变。
      </p>
      <p
        v-if="silentCount > 0"
        class="muted"
      >
        有 {{ silentCount }} 个事件算不出未来触发时刻（重复规则已结束，或全部落在排除日上），导入后不会为它们产生提醒。
      </p>

      <div
        v-if="rows.length > 0"
        class="table-scroll"
      >
        <table
          class="schedule-table"
          data-testid="ics-table"
        >
          <caption>逐事件判定：文件版本与库内版本比较后的结果、下一次触发时刻与重复规则。</caption>
          <thead>
            <tr>
              <th scope="col">
                事件
              </th>
              <th scope="col">
                判定（文件版本 → 库内版本）
              </th>
              <th scope="col">
                下次触发
              </th>
              <th scope="col">
                规则
              </th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="item in rows"
              :key="item.uid"
            >
              <!-- 摘要与 UID 都来自外部文件，属不可信文本，一律文本插值。 -->
              <td>
                {{ item.summary }}
                <span class="schedule-table__code">{{ item.uid }}</span>
              </td>
              <td>
                {{ SCHEDULE_IMPORT_ACTION_LABELS[item.action] }}
                <span class="muted">{{ item.sequence }} → {{ item.currentSequence ?? '—' }}</span>
              </td>
              <td>
                {{ formatDateTime(item.nextTriggerAt) }}
                <span
                  v-if="item.followingTriggerAt !== null"
                  class="muted"
                >再下次 {{ formatDateTime(item.followingTriggerAt) }}</span>
                <span
                  v-else-if="item.nextTriggerAt === null"
                  class="muted"
                >不触发</span>
              </td>
              <td>
                <span class="schedule-table__code">{{ item.rrule }}</span>
                <span class="muted">{{ item.timezone }}</span>
                <span
                  v-if="item.exdates.length > 0"
                  class="muted"
                >排除日 {{ item.exdates.length }} 天</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>

    <p
      v-if="schedule.result !== null"
      class="reminders__created"
      role="status"
      data-testid="ics-result"
    >
      {{ schedule.result.idempotentReplay
        ? '这是同一幂等键的重放结果，服务端没有再次写库；计数为首次导入的结果。'
        : '服务端已按判定写入，提醒看板已刷新。' }}
      <span class="muted">（trace {{ schedule.resultTraceId }}）</span>
    </p>

    <p
      v-if="schedule.previewTraceId !== null"
      class="muted"
    >
      本次预览的 trace {{ schedule.previewTraceId }}
    </p>
  </BaseCard>
</template>
