<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import AppAlert from './ui/AppAlert.vue';
import BaseButton from './ui/BaseButton.vue';
import BaseCard from './ui/BaseCard.vue';
import EmptyState from './ui/EmptyState.vue';
import SkeletonBlock from './ui/SkeletonBlock.vue';
import { useNotificationsStore } from '../stores/notifications';
import { useRemindersStore } from '../stores/reminders';
import type { NotificationData } from '../types/notifications';
import { NOTIFICATION_IDS_MAX } from '../types/notifications';
import { EMPTY_STATE_TEXT, REMINDER_KIND_LABELS, classStartAt, formatDateTime, formatLeadMinutes } from '../utils/display';

const notifications = useNotificationsStore();
const reminders = useRemindersStore();

/** 勾选只存 ID：重取第一页后已经不存在的行不该留下幽灵勾选，所以落地时按当前行集收敛。 */
const selectedIds = ref<string[]>([]);

const selected = computed(() => {
  const chosen = new Set(selectedIds.value);
  return notifications.items.filter((item) => chosen.has(item.notificationId) && item.readAt === null);
});

const canMarkSelected = computed(() => selected.value.length > 0 && !notifications.marking);

const canMarkAll = computed(() => !notifications.marking && !notifications.listLoading);

const selectionOverflow = computed(() => selectedIds.value.length > NOTIFICATION_IDS_MAX);

/** 勾选集合已经全变成已读、但服务端 `updated` 也可能是 0（越权或列表过期），两种都要显式说明。 */
const markNote = computed(() => {
  const updated = notifications.lastUpdated;
  if (updated === null) {
    return null;
  }
  return updated === 0
    ? '服务端没有把任何一条改为已读：这一批要么已经是已读，要么不属于当前账号。'
    : `已标记 ${String(updated)} 条为已读；未读数只统计已加载页，契约不提供总数。`;
});

function onToggle(notificationId: string, event: Event): void {
  const target = event.target as HTMLInputElement;
  selectedIds.value = target.checked
    ? [...new Set([...selectedIds.value, notificationId])]
    : selectedIds.value.filter((id) => id !== notificationId);
}

function clearSelection(): void {
  selectedIds.value = [];
}

async function markSelected(): Promise<void> {
  const ids = selected.value.map((item) => item.notificationId);
  if (await notifications.markRead(ids)) {
    clearSelection();
  }
}

async function markAll(): Promise<void> {
  if (await notifications.markAllRead()) {
    clearSelection();
  }
}

async function onUnreadOnly(event: Event): Promise<void> {
  const target = event.target as HTMLInputElement;
  clearSelection();
  await notifications.setUnreadOnly(target.checked);
}

async function onLoadMore(): Promise<void> {
  await notifications.loadListMore();
  clearSelection();
}

/**
 * 通知行没有 `leadMinutes`，契约要求用看板行的提前量做**显示层**换算（D-72）。
 * 看板行不在已加载页时不猜提前量，退回只说触发时刻，措辞与换算过的口径明确不同。
 */
function leadOf(item: NotificationData): number | null {
  if (item.kind !== 'COURSE') {
    return null;
  }
  const row = reminders.items.find((candidate) => candidate.reminderId === item.reminderId) ?? null;
  return row === null ? null : row.leadMinutes;
}

function triggerText(item: NotificationData): string {
  const lead = leadOf(item);
  if (lead === null) {
    return `触发时刻 ${formatDateTime(item.plannedTriggerAt)}`;
  }
  return `上课 ${formatDateTime(classStartAt(item.plannedTriggerAt, lead))} · ${formatLeadMinutes(lead)}`;
}

onMounted(() => {
  void notifications.loadListFirst();
});
</script>

<template>
  <BaseCard
    title="通知"
    class="notifications"
  >
    <p class="muted">
      一条通知等于一次已经响过的提醒，不是规则本身；提醒被完成或取消后历史通知仍然保留。
      标题与备注是触发当时的快照，不随提醒后续改名而变。本页两个接口都不进限流桶，
      所以这里没有限流等待；未读数只统计已加载页，契约不提供总数与未读数接口。
    </p>

    <label class="notifications__filter">
      <input
        data-testid="notifications-unread-only"
        type="checkbox"
        :checked="notifications.unreadOnly"
        :disabled="notifications.marking"
        @change="onUnreadOnly"
      >
      只看未读
    </label>
    <span
      v-if="notifications.unreadOnly"
      class="muted"
    >
      过滤条件每页重新求值，不是快照：边翻页边标已读，已加载的行会退出结果集，列表随之「变短」。
    </span>

    <div class="notifications__toolbar">
      <span class="notifications__count">本页未读 {{ notifications.unreadCount }} 条</span>
      <BaseButton
        data-testid="notifications-mark-selected"
        :disabled="!canMarkSelected || selectionOverflow"
        @click="markSelected"
      >
        标记所选（{{ selected.length }}）
      </BaseButton>
      <BaseButton
        data-testid="notifications-mark-all"
        :disabled="!canMarkAll"
        @click="markAll"
      >
        全部已读
      </BaseButton>
    </div>
    <p
      v-if="selectionOverflow"
      class="error-text"
    >
      一次最多标记 {{ NOTIFICATION_IDS_MAX }} 条，其余请用「全部已读」。
    </p>

    <p
      v-if="notifications.marking"
      class="muted"
    >
      标记中…
    </p>
    <SkeletonBlock
      v-if="notifications.listLoading && !notifications.listLoaded"
      label="通知列表加载中"
    />
    <p
      v-if="markNote !== null"
      class="notifications__note"
      role="status"
      data-testid="notifications-note"
    >
      {{ markNote }}
    </p>

    <ul class="notifications__list">
      <li
        v-for="item in notifications.items"
        :key="item.notificationId"
        class="notifications__item"
        :class="{ 'notifications__item--unread': item.readAt === null }"
      >
        <label class="notifications__pick">
          <input
            type="checkbox"
            :checked="selectedIds.includes(item.notificationId)"
            :disabled="item.readAt !== null || notifications.marking"
            @change="onToggle(item.notificationId, $event)"
          >
          <span class="visually-hidden">选择该条通知</span>
        </label>
        <div class="notifications__body">
          <p class="notifications__head">
            <!-- 标题与备注都是服务端回来的不可信文本，一律文本插值。 -->
            <span class="notifications__title">{{ item.title }}</span>
            <span class="muted">{{ REMINDER_KIND_LABELS[item.kind] }}</span>
            <span
              v-if="item.readAt === null"
              class="notifications__badge"
            >未读</span>
            <span
              v-else
              class="muted"
            >已读</span>
          </p>
          <p class="notifications__times">
            <span>{{ triggerText(item) }}</span>
            <span class="muted">落库 {{ formatDateTime(item.deliveredAt) }}</span>
          </p>
          <p
            v-if="item.description !== null"
            class="notifications__desc"
          >
            {{ item.description }}
          </p>
        </div>
      </li>
    </ul>

    <!--
      空态只在「取数成功且确实为空」时出现：首屏请求失败时 `listLoaded` 也会被置真
      （`fetchFirstPage` 的 finally 保证加载骨架必须收起），若只判 `listLoaded && items.length === 0`，
      失败页会同时出现「还没有通知」与错误条，等于告诉使用者「成功但没有数据」（S10-M3）。
    -->
    <EmptyState
      v-if="notifications.listLoaded && notifications.items.length === 0 && notifications.detail === null"
      :text="notifications.unreadOnly ? EMPTY_STATE_TEXT.notificationsUnread : EMPTY_STATE_TEXT.notificationsNone"
    />
    <BaseButton
      v-if="notifications.canLoadMore"
      @click="onLoadMore"
    >
      加载更多通知
    </BaseButton>

    <AppAlert
      :detail="notifications.detail"
      :code="notifications.code"
      :trace-id="notifications.traceId"
      data-testid="notifications-error"
    />
  </BaseCard>
</template>
