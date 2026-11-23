<script setup lang="ts">
import { computed, ref } from 'vue';

import AppAlert from '../../components/ui/AppAlert.vue';
import BaseButton from '../../components/ui/BaseButton.vue';
import BaseCard from '../../components/ui/BaseCard.vue';
import BaseField from '../../components/ui/BaseField.vue';
import EmptyState from '../../components/ui/EmptyState.vue';
import SkeletonBlock from '../../components/ui/SkeletonBlock.vue';
import { useAdminMemoriesStore } from '../../stores/adminMemories';
import type { MemoryItem } from '../../types/memories';
import { EMPTY_STATE_TEXT, formatDateTime } from '../../utils/display';

const store = useAdminMemoriesStore();

const userIdValue = ref('');
const filterError = ref<string | null>(null);
/** 行内两步确认：删除不可逆，先问一次再执行。 */
const confirmingDelete = ref<string | null>(null);
/** 最近一次成功查询的用户；删除与重读都锚定它，而不是输入框里可能已改动的值。 */
const queriedUserId = ref<string | null>(null);

const items = computed(() => store.items ?? []);

const UUID_PATTERN = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

async function query(): Promise<void> {
  const value = userIdValue.value.trim();
  if (!value) {
    filterError.value = '请先填写要查询的用户 ID。';
    return;
  }
  if (!UUID_PATTERN.test(value)) {
    filterError.value = '用户 ID 必须是 UUID 形态（与用户管理页的账号 ID 一致）。';
    return;
  }
  filterError.value = null;
  confirmingDelete.value = null;
  queriedUserId.value = value;
  await store.fetch(value);
}

function askDelete(item: MemoryItem): void {
  confirmingDelete.value = item.key;
}

async function confirmDelete(item: MemoryItem): Promise<void> {
  confirmingDelete.value = null;
  if (queriedUserId.value !== null) {
    await store.remove(queriedUserId.value, item.key);
  }
}
</script>

<template>
  <BaseCard
    title="长期记忆"
    :level="1"
  >
    <details class="page-note">
      <summary>这些记忆从哪里来</summary>
      <p>
        记忆只由用户本人在问答里显式下达「记住：…」指令写入：数据面先对事实做 PII 复检，
        残留敏感信息一律拒写，落库的只有掩码后的文本。每用户上限 50 条（溢出淘汰最旧）、
        保留 90 天；问答时作为不可信参考资料注入，不改变工具与引用规则。
      </p>
      <p>
        管理面只有读与删，没有写入口。删除立即生效：该用户下一次问答不再注入这条记忆。
        数据面未启用记忆功能时本页会如实报错，而不是显示空清单。
      </p>
    </details>

    <div class="admin__toolbar">
      <BaseField
        label="用户 ID"
        :error="filterError"
      >
        <input
          v-model="userIdValue"
          type="text"
          placeholder="UUID"
          @keyup.enter="query"
        >
      </BaseField>
      <BaseButton
        :disabled="store.loading"
        @click="query"
      >
        {{ store.loading ? '读取中…' : '查询' }}
      </BaseButton>
    </div>

    <AppAlert
      :detail="store.detail"
      :code="store.code"
      :trace-id="store.traceId"
    />

    <p
      v-if="store.notice !== null"
      class="admin-controls__result"
      role="status"
    >
      {{ store.notice }}
    </p>

    <SkeletonBlock
      v-if="store.loading && !store.loaded"
      label="记忆加载中"
    />
  </BaseCard>

  <BaseCard
    v-if="store.loaded"
    title="记忆清单"
  >
    <EmptyState
      v-if="items.length === 0"
      :text="EMPTY_STATE_TEXT.memoriesNone"
    />

    <div
      v-else
      class="table-scroll"
    >
      <table class="memory-table">
        <caption>该用户的长期记忆；文本为掩码后的事实，按记录时间倒序</caption>
        <thead>
          <tr>
            <th scope="col">
              记忆内容
            </th>
            <th scope="col">
              记录时间
            </th>
            <th scope="col">
              操作
            </th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="item in items"
            :key="item.key"
          >
            <td>{{ item.text }}</td>
            <td>{{ formatDateTime(item.createdAt) }}</td>
            <td>
              <div class="admin__toolbar">
                <BaseButton
                  v-if="confirmingDelete !== item.key"
                  variant="danger"
                  :disabled="store.submitting"
                  @click="askDelete(item)"
                >
                  删除
                </BaseButton>
              </div>
              <div
                v-if="confirmingDelete === item.key"
                class="admin-confirm"
              >
                <p class="error-text">
                  确认删除这条记忆？删除后该用户的下一次问答不再参考它，且不可恢复。
                </p>
                <div class="admin__toolbar">
                  <BaseButton
                    variant="danger"
                    :disabled="store.submitting"
                    @click="confirmDelete(item)"
                  >
                    {{ store.submitting ? '提交中…' : '确认删除' }}
                  </BaseButton>
                  <BaseButton
                    :disabled="store.submitting"
                    @click="confirmingDelete = null"
                  >
                    取消
                  </BaseButton>
                </div>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <p class="muted">
      每用户至多 50 条、保留 90 天，均由数据面强制执行；本页只显示服务端事实，不做本地截断。
    </p>
  </BaseCard>
</template>