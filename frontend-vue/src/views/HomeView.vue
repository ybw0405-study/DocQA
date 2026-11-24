<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';

import { getAiUsage } from '../api/admin';
import { changePassword } from '../api/auth';
import { ApiError } from '../api/errors';
import AppAlert from '../components/ui/AppAlert.vue';
import BaseButton from '../components/ui/BaseButton.vue';
import BaseCard from '../components/ui/BaseCard.vue';
import BaseField from '../components/ui/BaseField.vue';
import { useRetryWindow } from '../composables/useRetryWindow';
import { useAuthStore } from '../stores/auth';
import { useConversationsStore } from '../stores/conversations';
import { useDocumentsStore } from '../stores/documents';
import { useRemindersStore } from '../stores/reminders';
import type { UsageDayPoint, UsageSummary } from '../types/admin';
import { PASSWORD_MAX_LENGTH, PASSWORD_MIN_LENGTH, PASSWORD_PATTERN } from '../types/api';
import { CURRENT_PASSWORD_MAX_LENGTH } from '../types/users';
import {
  IDENTITY_ROLES_NOTE,
  IDENTITY_STATUS_NOTE,
  IDENTITY_TOKEN_TTL_NOTE,
  REMINDER_STATUS_LABELS,
  STATUS_LABELS,
  USER_ROLE_LABELS,
  USER_STATUS_LABELS,
  formatDateTime,
} from '../utils/display';

const auth = useAuthStore();
const router = useRouter();

const documents = useDocumentsStore();
const conversations = useConversationsStore();
const reminders = useRemindersStore();

/*
 * 首页只取每个列表的第一页，用来回答「我现在手头有什么」。
 * 三处都吞掉异常：某一路挂了不该让整页变成一片错误提示，各自的页面会给出完整报错。
 */
const overviewLoaded = ref(false);

/*
 * 用量带只对管理员有意义：`/admin/ai/usage` 需要 ADMIN 角色，非管理员调用只会拿 403。
 * 所以这里先判角色再决定要不要发请求，不为「一定有内容」硬塞一个空壳。
 */
const usage = ref<UsageSummary | null>(null);
const usageLoaded = ref(false);

/** 近 7 个 UTC 自然日。窗口超过 31 天服务端直接 400，这里不试探边界。 */
const USAGE_WINDOW_DAYS = 7;

async function loadUsage(): Promise<void> {
  if (!auth.isAdmin) {
    usageLoaded.value = true;
    return;
  }
  const to = new Date();
  const from = new Date(to.getTime() - (USAGE_WINDOW_DAYS - 1) * 24 * 60 * 60 * 1000);
  try {
    usage.value = await getAiUsage({
      from: from.toISOString(),
      to: to.toISOString(),
      bucket: 'day',
      topUsers: 1,
    });
  } catch {
    // 用量是首页的附加信息，读失败不该把「最近活动」一起拖成错误页。
    usage.value = null;
  } finally {
    usageLoaded.value = true;
  }
}

/**
 * 服务端只返回**有执行记录**的日期，所以 byDay 可能只有一两个点。
 * 时间轴不能缺格：按窗口补齐 7 天，缺的那天补 0，否则七列里只有一根柱子立着。
 */
const usageDays = computed<UsageDayPoint[]>(() => {
  const summary = usage.value;
  if (summary === null) {
    return [];
  }
  const known = new Map(summary.byDay.map((day) => [day.date, day]));
  const end = new Date(summary.to).getTime();
  const days: UsageDayPoint[] = [];
  for (let i = USAGE_WINDOW_DAYS - 1; i >= 0; i -= 1) {
    const key = new Date(end - i * 24 * 60 * 60 * 1000).toISOString().slice(0, 10);
    days.push(known.get(key) ?? { date: key, questions: 0, tokens: 0, failed: 0 });
  }
  return days;
});

/** 柱高按窗口内峰值归一：绝对刻度会随用量增长把早期的柱子压成一条线。 */
const usagePeak = computed(() => Math.max(1, ...usageDays.value.map((day) => day.questions)));

function barHeight(questions: number): string {
  return `${String(Math.round((questions / usagePeak.value) * 100))}%`;
}

function percentText(part: number, total: number): string {
  return total === 0 ? '—' : `${String(Math.round((part / total) * 100))}%`;
}

function latencyText(value: number | null): string {
  // null 是「窗口内没有执行」，不是 0ms；两者在面板上必须能分开读。
  return value === null ? '—' : `${String(Math.round(value))} ms`;
}

onMounted(async () => {
  await Promise.allSettled([
    documents.loaded ? Promise.resolve() : documents.loadFirst(),
    conversations.listLoaded ? Promise.resolve() : conversations.loadListFirst(),
    reminders.listLoaded ? Promise.resolve() : reminders.loadListFirst(),
  ]);
  overviewLoaded.value = true;
  await loadUsage();
});

/** 首页只露最近三条：这里是「回看一下」，完整的翻页与筛选在各自主页面。 */
const recentDocuments = computed(() => documents.items.slice(0, 3));
const recentConversations = computed(() => conversations.items.slice(0, 3));
const upcomingReminders = computed(() =>
  reminders.items
    .filter((item) => item.nextTriggerAt !== null)
    .slice(0, 3),
);

const currentPassword = ref('');
const newPassword = ref('');
const submitting = ref(false);
const reloading = ref(false);

/** 429 的等待秒数由服务端给定，本地时钟只活在视图层：本页没有 store，跨路由保留也没有意义。 */
const retryAfterSeconds = ref<number | null>(null);
const { secondsLeft, waiting } = useRetryWindow(retryAfterSeconds);

const errorDetail = ref<string | null>(null);
const errorCode = ref<string | null>(null);
const errorTraceId = ref<string | null>(null);
const currentError = ref<string | null>(null);
const newError = ref<string | null>(null);
const identityError = ref<string | null>(null);

const roleText = computed(() =>
  auth.roles.length === 0 ? '未回显角色' : auth.roles.map((role) => USER_ROLE_LABELS[role]).join('、'),
);
const statusText = computed(() => (auth.status === null ? '未读取到' : USER_STATUS_LABELS[auth.status]));
const tokenTtlText = computed(() =>
  auth.accessTokenExpiresIn === null ? '未读取到' : `剩余 ${String(auth.accessTokenExpiresIn)} 秒`,
);

const canSubmit = computed(
  () =>
    !submitting.value
    && !waiting.value
    && currentPassword.value.length > 0
    && newPassword.value.length > 0,
);

function resetMessages(): void {
  errorDetail.value = null;
  errorCode.value = null;
  errorTraceId.value = null;
  currentError.value = null;
  newError.value = null;
  // 每次提交先归零，下一次 429 才是一次「null → 秒数」的变化，倒数窗口才会重新起算。
  retryAfterSeconds.value = null;
}

/**
 * 只预检新口令：当前口令在契约上的约束是「非空即合法」，
 * 本地再套一次强度策略就会把「旧口令打错了」误报成「格式不符」。
 */
function checkNewPassword(): boolean {
  if (!PASSWORD_PATTERN.test(newPassword.value)) {
    newError.value = `新口令至少 ${PASSWORD_MIN_LENGTH} 位，且同时包含字母与数字。`;
    return false;
  }
  if (newPassword.value === currentPassword.value) {
    newError.value = '新口令不能与当前口令相同。';
    return false;
  }
  return true;
}

async function reload(): Promise<void> {
  reloading.value = true;
  identityError.value = null;
  try {
    await auth.loadCurrentUser();
  } catch (error) {
    identityError.value = error instanceof ApiError
      ? `${error.detail}（code ${error.code}）`
      : '未能读到服务端身份，请稍后重试。';
  } finally {
    reloading.value = false;
  }
}

async function submit(): Promise<void> {
  if (!canSubmit.value) {
    return;
  }
  resetMessages();
  if (!checkNewPassword()) {
    return;
  }
  submitting.value = true;
  try {
    await changePassword({ currentPassword: currentPassword.value, newPassword: newPassword.value });
    // 契约在这里返回 204 且不含任何令牌：服务端已吊销该用户全部刷新凭据并推进吊销水位，
    // 因此本地会话必须随即清空，静默续期只会拿到被拒绝的刷新请求。
    currentPassword.value = '';
    newPassword.value = '';
    await auth.signOut();
    // 提示语本体不进 URL：只带一个固定键，登录页据此渲染 display.ts 的单点文案（与 `redirect` 查询键同先例）。
    await router.replace({ name: 'login', query: { notice: 'password-changed' } });
  } catch (error) {
    if (error instanceof ApiError) {
      errorDetail.value = error.detail;
      errorCode.value = error.code;
      errorTraceId.value = error.traceId;
      currentError.value = error.fieldMessage('currentPassword') ?? null;
      newError.value = error.fieldMessage('newPassword') ?? null;
      retryAfterSeconds.value = error.retryAfterSeconds;
    } else {
      errorDetail.value = '修改口令失败，请稍后重试。';
    }
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <!--
    首页先回答「我现在手头有什么」，再谈账户设置。
    之前这一屏只有身份五字段，一进来像在看一份接口回包。
  -->
  <BaseCard
    title="最近活动"
    :level="1"
    class="workbench"
  >
    <div class="workbench__grid">
      <section class="workbench__lane">
        <p class="workbench__head">
          <span class="workbench__name">文档</span>
          <span class="workbench__count">{{ documents.items.length }}</span>
        </p>
        <ul class="workbench__list">
          <li
            v-for="doc in recentDocuments"
            :key="doc.documentId"
          >
            <span
              class="doc-status"
              :data-status="doc.status"
            >{{ STATUS_LABELS[doc.status] }}</span>
            <span class="workbench__label">{{ doc.title ?? doc.fileName }}</span>
          </li>
        </ul>
        <RouterLink
          class="workbench__more"
          to="/documents"
        >
          打开文档
        </RouterLink>
      </section>

      <section class="workbench__lane">
        <p class="workbench__head">
          <span class="workbench__name">会话</span>
          <span class="workbench__count">{{ conversations.items.length }}</span>
        </p>
        <ul class="workbench__list">
          <li
            v-for="item in recentConversations"
            :key="item.conversationId"
          >
            <span class="workbench__label">{{ item.title }}</span>
            <span class="workbench__meta">{{ item.lastMessageAt === null ? '尚未发言' : formatDateTime(item.lastMessageAt) }}</span>
          </li>
        </ul>
        <RouterLink
          class="workbench__more"
          to="/ask"
        >
          继续提问
        </RouterLink>
      </section>

      <section class="workbench__lane">
        <p class="workbench__head">
          <span class="workbench__name">待触发提醒</span>
          <span class="workbench__count">{{ reminders.items.length }}</span>
        </p>
        <ul class="workbench__list">
          <li
            v-for="item in upcomingReminders"
            :key="item.reminderId"
          >
            <span class="workbench__label">{{ item.title }}</span>
            <span class="workbench__meta">{{ REMINDER_STATUS_LABELS[item.status] }} · {{ formatDateTime(item.nextTriggerAt) }}</span>
          </li>
        </ul>
        <RouterLink
          class="workbench__more"
          to="/reminders"
        >
          查看提醒
        </RouterLink>
      </section>
    </div>
  </BaseCard>

  <!--
    用量带只给管理员：普通账号没有 `/admin/ai/usage` 的读权限，
    与其显示一张永远读不出来的卡片，不如整块不出现。
  -->
  <BaseCard
    v-if="auth.isAdmin"
    title="AI 用量 · 近 7 天"
    :level="1"
    class="usage"
  >
    <p
      v-if="!usageLoaded"
      class="muted"
    >
      正在读取用量。
    </p>
    <template v-else-if="usage !== null">
      <div class="usage__stats">
        <div class="usage__stat">
          <span class="usage__value">{{ usage.totals.questions }}</span>
          <span class="usage__label">问答次数</span>
          <span class="usage__note">成功率 {{ percentText(usage.totals.success, usage.totals.questions) }}</span>
        </div>
        <div class="usage__stat">
          <span class="usage__value">{{ usage.totals.tokens.toLocaleString('en-US') }}</span>
          <span class="usage__label">消耗 token</span>
          <span class="usage__note">缓存命中 {{ usage.totals.cacheHits }} 次</span>
        </div>
        <div class="usage__stat">
          <span class="usage__value">{{ latencyText(usage.totals.avgLatencyMs) }}</span>
          <span class="usage__label">平均延迟</span>
          <span class="usage__note">历史均值，非压测结论</span>
        </div>
        <div class="usage__stat">
          <span class="usage__value">{{ usage.totals.budgetExhausted }}</span>
          <span class="usage__label">预算耗尽</span>
          <span class="usage__note">失败 {{ usage.totals.failed }} 次</span>
        </div>
      </div>

      <ol class="usage__bars">
        <li
          v-for="day in usageDays"
          :key="day.date"
          class="usage__bar-slot"
        >
          <span class="usage__bar-track">
            <span
              class="usage__bar"
              :style="{ height: barHeight(day.questions) }"
              :data-empty="day.questions === 0 ? 'true' : undefined"
            />
          </span>
          <span class="usage__tick">{{ day.date.slice(5) }}</span>
          <span class="usage__bar-value">{{ day.questions }}</span>
        </li>
      </ol>
    </template>
    <p
      v-else
      class="muted"
    >
      用量暂时读不出来，其余内容不受影响。
    </p>
  </BaseCard>

  <BaseCard
    title="账户"
    :level="1"
  >
    <details class="page-note">
      <summary>这些字段从哪来</summary>
      <p>
        本页只显示服务端回给的字段：用户标识、用户名、账号状态与角色都来自本次身份读取，前端不自行推断，也不留存任何口令。
      </p>
    </details>

    <div class="admin__toolbar">
      <BaseButton
        :disabled="reloading"
        @click="reload"
      >
        {{ reloading ? '读取中…' : '重新读取' }}
      </BaseButton>
    </div>

    <AppAlert :detail="identityError" />

    <dl class="admin-state">
      <div>
        <dt>用户标识</dt>
        <dd>{{ auth.userId ?? '未取到，请退出后重新登录。' }}</dd>
      </div>
      <div>
        <dt>用户名</dt>
        <dd>{{ auth.username ?? '未取到，请退出后重新登录。' }}</dd>
      </div>
      <div>
        <dt>账号状态</dt>
        <dd>{{ statusText }}</dd>
      </div>
      <div>
        <dt>角色</dt>
        <dd>{{ roleText }}</dd>
      </div>
      <div>
        <dt>本令牌剩余有效期</dt>
        <dd>{{ tokenTtlText }}</dd>
      </div>
    </dl>

    <!-- 三条口径注脚是「这份快照为什么可能过期」的解释，核对的人需要，日常看账户的人不需要。 -->
    <details class="page-note">
      <summary>字段口径与陈旧性</summary>
      <p>{{ IDENTITY_STATUS_NOTE }}</p>
      <p>{{ IDENTITY_ROLES_NOTE }}</p>
      <p>{{ IDENTITY_TOKEN_TTL_NOTE }}</p>
    </details>
  </BaseCard>

  <BaseCard title="修改口令">
    <form @submit.prevent="submit">
      <BaseField
        label="当前口令"
        :error="currentError"
      >
        <!-- 契约上 currentPassword 只有 minLength: 1 与 maxLength: 72，本页因此不做强度预检。 -->
        <input
          v-model="currentPassword"
          name="currentPassword"
          type="password"
          :maxlength="CURRENT_PASSWORD_MAX_LENGTH"
          autocomplete="current-password"
          required
          data-testid="current-password"
        >
      </BaseField>
      <BaseField
        label="新口令"
        :error="newError"
      >
        <input
          v-model="newPassword"
          name="newPassword"
          type="password"
          :maxlength="PASSWORD_MAX_LENGTH"
          :minlength="PASSWORD_MIN_LENGTH"
          :pattern="PASSWORD_PATTERN.source"
          autocomplete="new-password"
          required
          data-testid="new-password"
        >
        <template #hint>
          <span class="muted">至少 {{ PASSWORD_MIN_LENGTH }} 位，需同时包含字母与数字。</span>
        </template>
      </BaseField>

      <AppAlert
        :detail="errorDetail"
        :code="errorCode"
        :trace-id="errorTraceId"
        :fields-shown="currentError !== null || newError !== null"
      />

      <p
        v-if="waiting"
        class="muted"
      >
        限流等待 {{ secondsLeft }} 秒。
      </p>

      <BaseButton
        submit
        :disabled="!canSubmit"
      >
        {{ submitting ? '提交中…' : '修改口令' }}
      </BaseButton>
    </form>

    <details class="page-note">
      <summary>口令变更的契约约束</summary>
      <p>
        两个口令字段在契约上都是 writeOnly：服务端只收不发，本页不读取它们、也不写入任何浏览器存储。
        当前口令缺失或不正确统一返回 401，服务端刻意不区分这两种情况；
        这与登录共用同一个限流闸（60 秒窗口内累计 5 次失败），等待秒数由服务端给出。
        修改成功后该用户的全部登录凭据即刻失效，本页会退出并回到登录页。
      </p>
    </details>
  </BaseCard>
</template>
