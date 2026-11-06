<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';

import AppAlert from '../../components/ui/AppAlert.vue';
import BaseButton from '../../components/ui/BaseButton.vue';
import BaseCard from '../../components/ui/BaseCard.vue';
import BaseField from '../../components/ui/BaseField.vue';
import EmptyState from '../../components/ui/EmptyState.vue';
import SkeletonBlock from '../../components/ui/SkeletonBlock.vue';
import { useAdminQuotasStore } from '../../stores/adminQuotas';
import { useAdminUsersStore } from '../../stores/adminUsers';
import { QUOTA_LIMIT_MAX, QUOTA_LIMIT_MIN } from '../../types/admin';
import type { AiQuota } from '../../types/admin';
import { EMPTY_STATE_TEXT, formatDateTime } from '../../utils/display';

const quotas = useAdminQuotasStore();
const users = useAdminUsersStore();

/**
 * 列表接口只返回**已设置覆盖行**的用户，所以「一条都没有」时这一页永远没有可编辑的行。
 * 没有新建入口就等于这个功能根本用不了：因此补一个按需展开的写入表单。
 * 用户下拉懒加载——页面初次进入不该为了一个没打开的表单多打一次 /admin/users。
 */
const creating = ref(false);
const newUserId = ref('');

function startCreate(): void {
  creating.value = true;
  editingId.value = null;
  newUserId.value = '';
  questionDraft.value = '';
  tokenDraft.value = '';
  result.value = null;
  if (!users.loaded) {
    void users.loadFirst();
  }
}

async function saveNew(): Promise<void> {
  if (newUserId.value === '') {
    return;
  }
  if (
    await quotas.saveQuota(newUserId.value, {
      dailyQuestionLimit: limitValue(questionDraft.value),
      dailyTokenLimit: limitValue(tokenDraft.value),
    })
  ) {
    creating.value = false;
    result.value = '覆盖已写入。该用户下一次问答起按新上限计量。';
  }
}

const editingId = ref<string | null>(null);
const questionDraft = ref('');
const tokenDraft = ref('');
const result = ref<string | null>(null);

/** 契约区间用字符串呈现一次即可，两条字段旁的提示共用同一句。 */
const RANGE_HINT = `${String(QUOTA_LIMIT_MIN)}–${String(QUOTA_LIMIT_MAX)} 的整数，留空表示该维度不设上限。`;

/**
 * 视图这一层必须先挡住「填了但不是整数」的输入：`Number('abc')` 是 NaN，
 * 而 `JSON.stringify(NaN)` 会把它写成 `null`——乱填就会被服务端读成「该维度不设上限」，
 * 这是一次静默的数据改写。契约区间由 store 把关，这里的判据是文本可否安全转成整数，两件事不重叠。
 */
const UNSIGNED_INTEGER = /^\d+$/;

function rangeError(raw: string): string | null {
  const trimmed = raw.trim();
  if (trimmed === '') {
    return null;
  }
  if (!UNSIGNED_INTEGER.test(trimmed)) {
    return RANGE_HINT;
  }
  const value = Number(trimmed);
  return value >= QUOTA_LIMIT_MIN && value <= QUOTA_LIMIT_MAX ? null : RANGE_HINT;
}

const questionError = computed(() => rangeError(questionDraft.value));
const tokenError = computed(() => rangeError(tokenDraft.value));

/** 空串是「不设上限」这个明确语义，不是「没填」，所以归一成 null 而不是 NaN。 */
function limitValue(raw: string): number | null {
  const trimmed = raw.trim();
  return trimmed === '' ? null : Number(trimmed);
}

function startEdit(quota: AiQuota): void {
  editingId.value = quota.userId;
  questionDraft.value = quota.dailyQuestionLimit === null ? '' : String(quota.dailyQuestionLimit);
  tokenDraft.value = quota.dailyTokenLimit === null ? '' : String(quota.dailyTokenLimit);
}

function cancelEdit(): void {
  editingId.value = null;
}

const canSave = computed(
  () => !quotas.submitting && questionError.value === null && tokenError.value === null,
);

async function save(): Promise<void> {
  const userId = editingId.value;
  if (userId === null) {
    return;
  }
  const cleared = questionDraft.value.trim() === '' && tokenDraft.value.trim() === '';
  if (await quotas.saveQuota(userId, { dailyQuestionLimit: limitValue(questionDraft.value), dailyTokenLimit: limitValue(tokenDraft.value) })) {
    editingId.value = null;
    result.value = cleared
      ? '两个上限都留空，该用户的个性化覆盖已清除；服务端为此追加的是清除类审计事件。'
      : '覆盖已写入，列表已重取第一页——排序键是最近写入时刻，刚改过的行本来就会换到最前面。';
  }
}

async function reload(): Promise<void> {
  result.value = null;
  editingId.value = null;
  await quotas.loadFirst();
}

onMounted(() => {
  void quotas.loadFirst();
});
</script>

<template>
  <BaseCard
    title="AI 日配额"
    :level="1"
  >
    <details class="page-note">
      <summary>日配额的口径</summary>
      <p>
        日配额是业务约束而不是安全边界：问答次数是硬闸，token 是软闸，两者都按 UTC 自然日计量。
        列表只包含已设置覆盖行的用户，不在列表里表示不设上限，不表示「查不到这个人」。
      </p>
    </details>
    <div class="admin__toolbar">
      <BaseButton
        :disabled="quotas.loading"
        @click="startCreate"
      >
        新建覆盖
      </BaseButton>
      <BaseButton
        :disabled="quotas.loading"
        @click="reload"
      >
        {{ quotas.loading ? '加载中…' : '刷新' }}
      </BaseButton>
    </div>

    <div
      v-if="creating"
      class="admin-quotas__create"
    >
      <BaseField label="用户">
        <select v-model="newUserId">
          <option value="">
            选择账号
          </option>
          <option
            v-for="user in users.items"
            :key="user.userId"
            :value="user.userId"
          >
            {{ user.username }}
          </option>
        </select>
        <template #hint>
          <span class="muted">只列出第一页；目标账号不在列表里时，请先到「用户」页按状态或角色筛出来。</span>
        </template>
      </BaseField>
      <BaseField label="每日问答上限">
        <input
          v-model="questionDraft"
          type="number"
          min="1"
          placeholder="留空不设上限"
        >
        <template #hint>
          <span class="muted">{{ RANGE_HINT }}</span>
        </template>
      </BaseField>
      <BaseField label="每日 token 上限">
        <input
          v-model="tokenDraft"
          type="number"
          min="1"
          placeholder="留空不设上限"
        >
        <template #hint>
          <span class="muted">两个上限都留空等于清除覆盖，不是「设为 0」。</span>
        </template>
      </BaseField>
      <div class="admin-quotas__create-actions">
        <BaseButton
          :disabled="!canSave || newUserId === ''"
          @click="saveNew"
        >
          {{ quotas.submitting ? '写入中…' : '写入覆盖' }}
        </BaseButton>
        <BaseButton @click="creating = false">
          取消
        </BaseButton>
      </div>
    </div>

    <AppAlert
      :detail="quotas.detail"
      :code="quotas.code"
      :trace-id="quotas.traceId"
    />

    <p
      v-if="result !== null"
      class="admin-quotas__result"
      role="status"
    >
      {{ result }}
    </p>

    <div
      v-if="quotas.items.length > 0"
      class="table-scroll"
    >
      <table class="quota-table">
        <caption>已设置个性化日配额的用户，以及这一行可以执行的编辑。</caption>
        <thead>
          <tr>
            <th scope="col">
              用户
            </th>
            <th scope="col">
              问答上限
            </th>
            <th scope="col">
              token 上限
            </th>
            <th scope="col">
              最近写入
            </th>
            <th scope="col">
              操作
            </th>
          </tr>
        </thead>
        <tbody>
          <template
            v-for="quota in quotas.items"
            :key="quota.userId"
          >
            <tr>
              <!-- username 只用于展示，写入键只有 userId；两者都来自契约字段。 -->
              <td>
                {{ quota.username ?? '—' }}
                <span class="muted">{{ quota.userId }}</span>
              </td>
              <td>{{ quota.dailyQuestionLimit ?? '不设上限' }}</td>
              <td>{{ quota.dailyTokenLimit ?? '不设上限' }}</td>
              <td>
                {{ formatDateTime(quota.updatedAt) }}
                <span class="muted">{{ quota.updatedBy ?? '写入者未记录' }}</span>
              </td>
              <td class="quota-table__actions">
                <BaseButton
                  :disabled="quotas.submitting"
                  @click="startEdit(quota)"
                >
                  编辑
                </BaseButton>
              </td>
            </tr>

            <tr
              v-if="editingId === quota.userId"
              class="quota-table__expanded"
            >
              <td colspan="5">
                <div class="admin__toolbar">
                  <BaseField
                    label="每日问答上限"
                    :error="questionError"
                  >
                    <input
                      v-model="questionDraft"
                      type="text"
                      :placeholder="RANGE_HINT"
                    >
                  </BaseField>
                  <BaseField
                    label="每日 token 上限"
                    :error="tokenError"
                  >
                    <input
                      v-model="tokenDraft"
                      type="text"
                      :placeholder="RANGE_HINT"
                    >
                  </BaseField>
                  <BaseButton
                    :disabled="!canSave"
                    @click="save"
                  >
                    {{ quotas.submitting ? '提交中…' : '保存' }}
                  </BaseButton>
                  <BaseButton @click="cancelEdit">
                    取消
                  </BaseButton>
                </div>
                <p class="muted">
                  提交是全量替换：两个上限都会随请求发出，留空即该维度不设上限，两项都留空即清除这一行。
                  重复提交同一个值得到同一结果；两个管理员同时写入时服务端后写覆盖，界面检测不到，因此这里不声称存在版本冲突。
                </p>
              </td>
            </tr>
          </template>
        </tbody>
      </table>
    </div>
    <SkeletonBlock
      v-else-if="quotas.loading && !quotas.loaded"
      label="配额列表加载中"
    />
    <EmptyState
      v-else-if="quotas.loaded"
      :text="EMPTY_STATE_TEXT.quotasNone"
    />

    <BaseButton
      v-if="quotas.canLoadMore"
      @click="quotas.loadMore()"
    >
      加载更多
    </BaseButton>
    <p
      v-else-if="quotas.items.length > 0"
      class="muted"
    >
      本页已加载 {{ quotas.items.length }} 行。契约不返回总数，因此这个数字不是「一共有多少行」。
    </p>
  </BaseCard>
</template>
