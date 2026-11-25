<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import NotificationPanel from '../components/NotificationPanel.vue';
import ScheduleImportPanel from '../components/ScheduleImportPanel.vue';
import AppAlert from '../components/ui/AppAlert.vue';
import BaseButton from '../components/ui/BaseButton.vue';
import BaseCard from '../components/ui/BaseCard.vue';
import BaseField from '../components/ui/BaseField.vue';
import EmptyState from '../components/ui/EmptyState.vue';
import SkeletonBlock from '../components/ui/SkeletonBlock.vue';
import { useRetryWindow } from '../composables/useRetryWindow';
import { allowedTargets, useRemindersStore } from '../stores/reminders';
import type { ReminderListItemData, ReminderStatus } from '../types/reminders';
import {
  LEAD_MINUTES_MAX,
  LEAD_MINUTES_MIN,
  REMINDER_CREATE_KINDS,
  REMINDER_DESCRIPTION_MAX_LENGTH,
  REMINDER_STATUSES,
  REMINDER_TIMEZONE_MAX_LENGTH,
  REMINDER_TITLE_MAX_LENGTH,
  RRULE_MAX_LENGTH,
  RRULE_PATTERN,
} from '../types/reminders';
import {
  EMPTY_STATE_TEXT,
  REMINDER_ACTION_LABELS,
  REMINDER_KIND_LABELS,
  REMINDER_STATUS_LABELS,
  classStartAt,
  formatDateTime,
  formatLeadMinutes,
} from '../utils/display';

const reminders = useRemindersStore();

const { secondsLeft, waiting } = useRetryWindow(() => reminders.retryAfterSeconds);

/**
 * 终态两步确认的第一击。契约 规定 `COMPLETED` / `CANCELLED` 没有出边，
 * 误点只能靠「重建一条」挽回，所以这两击之间必须让确认文案与「不可回退」同时出现在这一行上。
 */
const pendingTarget = ref<{ reminderId: string; status: ReminderStatus } | null>(null);

const submitLabel = computed(() => {
  if (reminders.creating) {
    return '创建中…';
  }
  return reminders.pendingKey === null ? '创建提醒' : '重试同一请求';
});

function isTerminal(status: ReminderStatus): boolean {
  return status === 'COMPLETED' || status === 'CANCELLED';
}

function isPending(reminderId: string, status: ReminderStatus): boolean {
  const pending = pendingTarget.value;
  return pending !== null && pending.reminderId === reminderId && pending.status === status;
}

/** 按钮动词取自目标状态；`ACTIVE` 因此读作「恢复」而不是「设为进行中」。 */
function buttonLabel(reminderId: string, status: ReminderStatus): string {
  const verb = REMINDER_ACTION_LABELS[status];
  if (isPending(reminderId, status)) {
    return `确认${verb}？`;
  }
  return isTerminal(status) ? `${verb}（不可回退）` : verb;
}

function onStatusClick(item: ReminderListItemData, status: ReminderStatus): void {
  if (isTerminal(status) && !isPending(item.reminderId, status)) {
    pendingTarget.value = { reminderId: item.reminderId, status };
    return;
  }
  pendingTarget.value = null;
  void reminders.updateStatus(item.reminderId, status);
}

function cancelStatus(): void {
  pendingTarget.value = null;
}

/** 表单默认给一条周重复规则，只作为输入起点；提交前仍要经服务端 `RruleParser` 判定。 */
const rruleHint = computed(() =>
  RRULE_PATTERN.test(reminders.form.rrule) ? '重复规则由 Java 按 RRULE、时区与 EXDATE 展开。' : '形如 FREQ=WEEKLY;BYDAY=MO;UNTIL=20261231T235959Z。',
);

function eventValue(event: Event): string {
  const target = event.target;
  if (target instanceof HTMLInputElement || target instanceof HTMLSelectElement || target instanceof HTMLTextAreaElement) {
    return target.value;
  }
  return '';
}

function onTitle(event: Event): void {
  reminders.patchForm({ title: eventValue(event) });
}

function onDescription(event: Event): void {
  reminders.patchForm({ description: eventValue(event) });
}

function onTimezone(event: Event): void {
  reminders.patchForm({ timezone: eventValue(event) });
}

function onDueAt(event: Event): void {
  reminders.patchForm({ dueAt: eventValue(event) });
}

function onLeadMinutes(event: Event): void {
  reminders.patchForm({ leadMinutes: eventValue(event) });
}

function onRrule(event: Event): void {
  reminders.patchForm({ rrule: eventValue(event) });
}

function onDtstart(event: Event): void {
  reminders.patchForm({ dtstart: eventValue(event) });
}

function onFilter(event: Event): void {
  void reminders.updateStatusFilter(eventValue(event));
}

onMounted(() => {
  void reminders.loadListFirst();
});
</script>

<template>
  <BaseCard
    title="提醒"
    :level="1"
    class="reminders-board"
  >
    <!--
      这段是状态机与时间语义的实现说明，核对规则的人需要，扫看板的人不需要：
      折进 details，正文让位给「哪条提醒什么时候响」。
    -->
    <details class="page-note">
      <summary>状态机与时间语义</summary>
      <p>
        一条重复提醒只存一行，看板每行是一条规则本身；下一次触发时间由服务端按 RRULE、时区与 EXDATE 算出。
        2.3.0 起每行可暂停、恢复、完成或取消：暂停保留「本来下次是几点」，恢复由服务端按当前时刻重算
        下一次触发且错过的触发不补发，完成与取消是终态、会把下次触发置空且不可回退。
      </p>
    </details>

    <label class="reminders__filter">
      <span class="reminders__filter-label">状态过滤</span>
      <select
        data-testid="filter"
        :value="reminders.statusFilter ?? ''"
        :disabled="reminders.listLoading"
        @change="onFilter"
      >
        <option value="">
          全部
        </option>
        <option
          v-for="status in REMINDER_STATUSES"
          :key="status"
          :value="status"
        >
          {{ REMINDER_STATUS_LABELS[status] }}
        </option>
      </select>
    </label>

    <SkeletonBlock
      v-if="reminders.listLoading && !reminders.listLoaded"
      label="提醒看板加载中"
    />

    <div
      v-for="group in reminders.groups"
      :key="group.heading"
      class="reminders__group"
    >
      <h2>{{ group.heading }}</h2>
      <p class="muted">
        {{ group.note }}
      </p>
      <ul class="reminders__list">
        <li
          v-for="item in group.items"
          :key="item.reminderId"
          class="reminders__item"
        >
          <p class="reminders__head">
            <!-- 标题、备注与 RRULE 都是服务端回来的不可信文本，一律文本插值。 -->
            <span class="reminders__title">{{ item.title }}</span>
            <span
              class="reminders__status"
              :data-status="item.status"
            >{{ REMINDER_STATUS_LABELS[item.status] }}</span>
            <span class="reminders__kind">{{ REMINDER_KIND_LABELS[item.kind] }}</span>
          </p>
          <p class="reminders__times">
            <span class="reminders__label">下次触发</span>
            <span class="reminders__when">{{ formatDateTime(item.nextTriggerAt) }}</span>
            <span
              v-if="item.kind === 'COURSE' && item.nextTriggerAt !== null"
              class="reminders__lead"
            >
              上课 {{ formatDateTime(classStartAt(item.nextTriggerAt, item.leadMinutes)) }}
              · {{ formatLeadMinutes(item.leadMinutes) }}
            </span>
            <span class="reminders__label">上次触发</span>
            <span class="reminders__past">{{ formatDateTime(item.lastTriggeredAt) }}</span>
          </p>
          <p
            v-if="item.description !== null"
            class="reminders__desc"
          >
            {{ item.description }}
          </p>
          <!--
            RRULE 原文是契约字段，做语义翻译就会与 Java 的实现产生第二套解释，因此不翻译；
            但它也不该在扫读时占一行——折进「重复规则」里，需要核对规则的人自己展开。
          -->
          <details class="reminders__rule">
            <summary>
              重复规则
              <span class="reminders__tz">{{ item.timezone }}</span>
              <span
                v-if="item.exdates.length > 0"
                class="reminders__tz"
              >排除日 {{ item.exdates.length }} 天</span>
            </summary>
            <p class="reminders__rrule">
              {{ item.rrule }}
            </p>
          </details>
          <p class="reminders__actions">
            <BaseButton
              v-for="target in allowedTargets(item.status)"
              :key="target"
              :data-testid="`status-${target}`"
              :disabled="reminders.updatingId !== null"
              @click="onStatusClick(item, target)"
            >
              {{ buttonLabel(item.reminderId, target) }}
            </BaseButton>
            <BaseButton
              v-if="pendingTarget !== null && pendingTarget.reminderId === item.reminderId"
              data-testid="status-cancel"
              @click="cancelStatus"
            >
              放弃
            </BaseButton>
            <span
              v-if="allowedTargets(item.status).length === 0"
              class="muted"
            >终态，不可回退；需要再次提醒就重建一条或重新导入课表。</span>
            <span
              v-if="reminders.updatingId === item.reminderId"
              class="muted"
            >提交中…</span>
          </p>
        </li>
      </ul>
    </div>

    <EmptyState
      v-if="reminders.listLoaded && reminders.items.length === 0"
      :text="EMPTY_STATE_TEXT.remindersFiltered"
    />
    <BaseButton
      v-if="reminders.canLoadMore"
      @click="reminders.loadListMore()"
    >
      加载更多提醒
    </BaseButton>
  </BaseCard>

  <BaseCard title="新建提醒">
    <fieldset class="reminders__kinds">
      <legend>提醒类型</legend>
      <label
        v-for="kind in REMINDER_CREATE_KINDS"
        :key="kind"
      >
        <input
          type="radio"
          name="reminder-kind"
          :value="kind"
          :checked="reminders.form.kind === kind"
          :disabled="reminders.creating"
          @change="reminders.patchForm({ kind })"
        >
        {{ REMINDER_KIND_LABELS[kind] }}
      </label>
    </fieldset>
    <p class="muted">
      课程提醒不在这里创建：它由下面的课表导入产生，看板因此能区分「一节每周的课」与「一条自定义重复提醒」。
    </p>

    <BaseField label="标题">
      <input
        data-testid="title"
        :value="reminders.form.title"
        :maxlength="REMINDER_TITLE_MAX_LENGTH"
        :disabled="reminders.creating"
        @input="onTitle"
      >
    </BaseField>
    <BaseField label="备注（可留空）">
      <textarea
        data-testid="description"
        :value="reminders.form.description"
        :maxlength="REMINDER_DESCRIPTION_MAX_LENGTH"
        rows="2"
        :disabled="reminders.creating"
        @input="onDescription"
      />
    </BaseField>
    <BaseField label="IANA 时区">
      <input
        data-testid="timezone"
        :value="reminders.form.timezone"
        :maxlength="REMINDER_TIMEZONE_MAX_LENGTH"
        :disabled="reminders.creating"
        @input="onTimezone"
      >
    </BaseField>
    <p class="muted">
      时间输入按本机时区解释后转成 UTC 提交；默认时区取本机 IANA 名称，可手工改写。
    </p>

    <template v-if="reminders.form.kind === 'DEADLINE'">
      <BaseField label="作业截止时间">
        <input
          data-testid="due-at"
          type="datetime-local"
          step="1"
          :value="reminders.form.dueAt"
          :disabled="reminders.creating"
          @input="onDueAt"
        >
      </BaseField>
      <BaseField :label="`提前提醒分钟数（${LEAD_MINUTES_MIN}–${LEAD_MINUTES_MAX}）`">
        <input
          data-testid="lead-minutes"
          type="number"
          :min="LEAD_MINUTES_MIN"
          :max="LEAD_MINUTES_MAX"
          step="1"
          :value="reminders.form.leadMinutes"
          :disabled="reminders.creating"
          @input="onLeadMinutes"
        >
      </BaseField>
      <p class="muted">
        服务端把截止提醒落成一条一次性规则，下一次触发等于截止时间减去提前分钟数。
      </p>
    </template>

    <template v-else>
      <BaseField label="重复规则 RRULE">
        <input
          data-testid="rrule"
          :value="reminders.form.rrule"
          :maxlength="RRULE_MAX_LENGTH"
          :disabled="reminders.creating"
          @input="onRrule"
        >
      </BaseField>
      <p class="muted">
        {{ rruleHint }}
      </p>
      <BaseField label="首次触发基准时间">
        <input
          data-testid="dt-start"
          type="datetime-local"
          step="1"
          :value="reminders.form.dtstart"
          :disabled="reminders.creating"
          @input="onDtstart"
        >
      </BaseField>
      <p class="muted">
        排除日（EXDATE）由课表导入链路产生，手工表单不提交该字段。
      </p>
    </template>

    <BaseButton
      data-testid="submit"
      :disabled="reminders.creating || waiting"
      @click="reminders.submitReminder()"
    >
      {{ submitLabel }}
    </BaseButton>
    <p
      v-if="waiting"
      class="muted"
    >
      限流等待 {{ secondsLeft }} 秒。
    </p>

    <p
      v-if="reminders.createdSummary !== null"
      class="reminders__created"
      role="status"
    >
      已创建「{{ reminders.createdSummary.title }}」，{{ reminders.createdSummary.idempotentReplay ? '这是同一幂等键的重放结果' : '服务端已写入新规则' }}；
      下次触发 {{ formatDateTime(reminders.createdSummary.nextTriggerAt) }}。
      <span class="muted">（trace {{ reminders.createdSummary.traceId }}）</span>
    </p>

    <AppAlert
      :detail="reminders.detail"
      :code="reminders.code"
      :trace-id="reminders.traceId"
    />
  </BaseCard>

  <!-- 课表导入落库的就是看板上的课程类规则，与看板同页才能立刻看到写入结果。 -->
  <ScheduleImportPanel />

  <!-- 通知是「已经响过的提醒」，与看板同页才能就地标记已读，并按 reminderId 对照看板行的提前量。 -->
  <NotificationPanel />
</template>
