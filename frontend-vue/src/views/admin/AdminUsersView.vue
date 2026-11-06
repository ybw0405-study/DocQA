<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';

import AppAlert from '../../components/ui/AppAlert.vue';
import BaseButton from '../../components/ui/BaseButton.vue';
import BaseCard from '../../components/ui/BaseCard.vue';
import BaseField from '../../components/ui/BaseField.vue';
import EmptyState from '../../components/ui/EmptyState.vue';
import SkeletonBlock from '../../components/ui/SkeletonBlock.vue';
import { useAdminUsersStore } from '../../stores/adminUsers';
import { useAuthStore } from '../../stores/auth';
import type { UserRole, UserStatus } from '../../types/api';
import { USER_ROLES, USER_STATUSES } from '../../types/api';
import type { UserSummary } from '../../types/users';
import { EMPTY_STATE_TEXT, USER_ROLE_LABELS, USER_STATUS_LABELS, formatDateTime } from '../../utils/display';

const users = useAdminUsersStore();
const auth = useAuthStore();

const statusValue = ref('');
const roleValue = ref('');

/** 编辑草稿与确认态都是视图本地状态：store 只认服务端回显，未提交的勾选不进状态。 */
const editingId = ref<string | null>(null);
const roleDraft = ref<UserRole[]>([]);
const confirmDisableId = ref<string | null>(null);
const result = ref<string | null>(null);

/** 读写共用一把忙碌旗：翻页时不让人对着看不见的当前值改角色。 */
const busy = computed(() => users.loading || users.submitting);

const editingRow = computed(() => users.items.find((item) => item.userId === editingId.value) ?? null);

/** 两条自锁护栏的判据只有「这一行是不是调用者自己」，而这个身份只来自 `/auth/me` 的 `userId`。 */
const isEditingSelf = computed(() => editingRow.value !== null && editingRow.value.userId === auth.userId);

/** 下拉框的值来自字符串，必须按枚举校验后才作为过滤条件发出去，非法值等价于「不限」。 */
function parseStatus(value: string): UserStatus | null {
  return USER_STATUSES.find((status) => status === value) ?? null;
}

function parseRole(value: string): UserRole | null {
  return USER_ROLES.find((role) => role === value) ?? null;
}

/**
 * 已勾选的角色什么情况下不给取消：摘掉自己的 ADMIN（管理面会自我关闭）、以及取消最后一个角色
 * （空集合服务端按 422 拒绝）。本地只挡这两条，其余判定留给服务端——契约写着「至少持有 `USER`」
 * 而 Java 侧只要求非空，这条差异已登记为待确认事项，界面按实现的那一条说话。
 */
function roleLockReason(role: UserRole): string | null {
  if (!roleDraft.value.includes(role)) {
    return null;
  }
  if (role === 'ADMIN' && isEditingSelf.value) {
    return '不能摘掉自己的管理员角色：那会让管理面对你自己关闭。';
  }
  if (roleDraft.value.length === 1) {
    return '至少要为账号保留一个角色：空集合会被服务端按 422 拒绝。';
  }
  return null;
}

function isRoleLocked(role: UserRole): boolean {
  return roleLockReason(role) !== null;
}

function isSelf(row: UserSummary): boolean {
  return row.userId === auth.userId;
}

function statusChip(status: UserStatus): string {
  return status === 'ACTIVE' ? 'governance__chip governance__chip--ok' : 'governance__chip governance__chip--warn';
}

function roleText(row: UserSummary): string {
  return row.roles.map((role) => USER_ROLE_LABELS[role]).join('、');
}

function startEdit(row: UserSummary): void {
  confirmDisableId.value = null;
  result.value = null;
  editingId.value = row.userId;
  roleDraft.value = [...row.roles];
}

function cancelEdit(): void {
  editingId.value = null;
  roleDraft.value = [];
}

function toggleRole(role: UserRole, event: Event): void {
  const checked = (event.target as HTMLInputElement).checked;
  // 按 USER_ROLES 的固定顺序重排勾选结果：请求体里的集合顺序不该跟着点击顺序漂移。
  roleDraft.value = USER_ROLES.filter((item) => (item === role ? checked : roleDraft.value.includes(item)));
}

async function saveRoles(): Promise<void> {
  const row = editingRow.value;
  if (row === null) {
    return;
  }
  // 草稿在提交后就被清空，回执要引用的是这一份提交前的快照。
  const submitted = [...roleDraft.value];
  if (await users.saveRoles(row.userId, submitted)) {
    cancelEdit();
    result.value = `已把 ${row.username} 的角色整集替换为「${roleTextFrom(submitted)}」。`
      + '角色变更不吊销已签发的访问令牌：新角色的生效延迟上限就是这张令牌的剩余有效期。'
      + goneNote(row);
  }
}

function roleTextFrom(roles: UserRole[]): string {
  return roles.map((role) => USER_ROLE_LABELS[role]).join('、');
}

/** 改完不再匹配当前筛选条件的行会由 store 移出列表，这一条必须说出口，否则界面就像凭空少了一行。 */
function goneNote(row: UserSummary): string {
  return users.items.some((item) => item.userId === row.userId)
    ? ''
    : '这一行已不再匹配当前筛选条件，因此从列表移除；换条件或清空筛选后重新查询才会再看到它。';
}

function askDisable(row: UserSummary): void {
  cancelEdit();
  result.value = null;
  confirmDisableId.value = row.userId;
}

function cancelDisable(): void {
  confirmDisableId.value = null;
}

async function submitStatus(row: UserSummary, status: UserStatus): Promise<void> {
  confirmDisableId.value = null;
  if (await users.setStatus(row.userId, status)) {
    result.value = status === 'DISABLED'
      ? `已停用 ${row.username}：它的刷新令牌家族即刻吊销，在途的访问令牌一并被拒，该账号即刻失去登录能力。`
      : `已启用 ${row.username}：可以重新登录，但此前已吊销的凭据不会复活。`
    result.value += goneNote(row);
  }
}

async function applyFilters(): Promise<void> {
  cancelEdit();
  cancelDisable();
  result.value = null;
  await users.applyFilters({ status: parseStatus(statusValue.value), role: parseRole(roleValue.value) });
}

async function clearFilters(): Promise<void> {
  statusValue.value = '';
  roleValue.value = '';
  await applyFilters();
}

async function reload(): Promise<void> {
  cancelEdit();
  cancelDisable();
  result.value = null;
  await users.loadFirst();
}

onMounted(() => {
  void users.loadFirst();
});
</script>

<template>
  <BaseCard
    title="用户管理"
    :level="1"
  >
    <details class="page-note">
      <summary>这一页能做什么、不能做什么</summary>
      <p>
        列表只含账号的定位信息，任何口令、令牌摘要都不在这些字段里。2.5.0 不提供建号与改名入口：
        账号只能由用户自行注册产生，这里能做的是角色整集替换与停用／启用。
      </p>
      <p>
        角色筛选的语义是「持有该角色即命中」，不是角色集合相等；两个条件都在服务端按每一页重新求值，
        换条件等于换一页，已拼接的后续行会先清空。列表按注册时间倒序。
      </p>
    </details>

    <div class="admin__toolbar">
      <BaseField label="账号状态">
        <select
          v-model="statusValue"
          :disabled="busy"
          @change="applyFilters"
        >
          <option value="">
            不限
          </option>
          <option
            v-for="status in USER_STATUSES"
            :key="status"
            :value="status"
          >
            {{ USER_STATUS_LABELS[status] }}
          </option>
        </select>
      </BaseField>
      <BaseField label="角色">
        <select
          v-model="roleValue"
          :disabled="busy"
          @change="applyFilters"
        >
          <option value="">
            不限
          </option>
          <option
            v-for="role in USER_ROLES"
            :key="role"
            :value="role"
          >
            {{ USER_ROLE_LABELS[role] }}
          </option>
        </select>
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
        {{ users.loading ? '加载中…' : '刷新' }}
      </BaseButton>
    </div>

    <AppAlert
      :detail="users.detail"
      :code="users.code"
      :trace-id="users.traceId"
    />

    <p
      v-if="result !== null"
      class="admin-users__result"
      role="status"
    >
      {{ result }}
    </p>

    <div
      v-if="users.items.length > 0"
      class="table-scroll"
    >
      <table class="users-table">
        <caption>账号的定位信息，以及这一行可以执行的角色与状态变更。</caption>
        <thead>
          <tr>
            <th scope="col">
              用户
            </th>
            <th scope="col">
              状态
            </th>
            <th scope="col">
              角色
            </th>
            <th scope="col">
              注册时间
            </th>
            <th scope="col">
              最近登录
            </th>
            <th scope="col">
              操作
            </th>
          </tr>
        </thead>
        <tbody>
          <template
            v-for="row in users.items"
            :key="row.userId"
          >
            <tr>
              <!-- username 与 userId 都来自契约字段，前者只用于展示，写入键只有后者。 -->
              <td>
                <span class="users-table__name">
                  {{ row.username }}
                  <span
                    v-if="isSelf(row)"
                    class="users-table__self"
                  >当前登录的账号</span>
                </span>
                <span class="mono-id">{{ row.userId }}</span>
              </td>
              <td>
                <span :class="statusChip(row.status)">{{ USER_STATUS_LABELS[row.status] }}</span>
              </td>
              <td>{{ roleText(row) }}</td>
              <td>{{ formatDateTime(row.createdAt) }}</td>
              <td>{{ formatDateTime(row.lastLoginAt) }}</td>
              <td class="users-table__actions">
                <BaseButton
                  :disabled="busy"
                  @click="startEdit(row)"
                >
                  改角色
                </BaseButton>
                <BaseButton
                  v-if="row.status === 'ACTIVE' && !isSelf(row)"
                  variant="danger"
                  :disabled="busy"
                  @click="askDisable(row)"
                >
                  停用
                </BaseButton>
                <BaseButton
                  v-if="row.status === 'ACTIVE' && isSelf(row)"
                  disabled
                  title="停用不适用于当前登录的账号：服务端会拒绝这条请求。"
                >
                  停用
                </BaseButton>
                <!--
                  这句话对读屏与检索仍然成立，但不再占一格宽度：原因挂在按钮的 title 上，
                  视觉上由「灰掉的停用」本身表达，重复一遍会把整行高度顶开。
                -->
                <span
                  v-if="row.status === 'ACTIVE' && isSelf(row)"
                  class="visually-hidden"
                >停用不适用于当前登录的账号：服务端会拒绝这条请求。</span>
                <BaseButton
                  v-if="row.status === 'DISABLED'"
                  :disabled="busy"
                  @click="submitStatus(row, 'ACTIVE')"
                >
                  启用
                </BaseButton>
              </td>
            </tr>

            <tr
              v-if="editingId === row.userId"
              class="users-table__expanded"
            >
              <td colspan="6">
                <fieldset class="users-table__roles">
                  <legend>角色（整集替换）</legend>
                  <label
                    v-for="role in USER_ROLES"
                    :key="role"
                  >
                    <input
                      type="checkbox"
                      :checked="roleDraft.includes(role)"
                      :disabled="busy || isRoleLocked(role)"
                      @change="toggleRole(role, $event)"
                    >
                    {{ USER_ROLE_LABELS[role] }}
                  </label>
                  <p
                    v-for="role in USER_ROLES.filter((item) => isRoleLocked(item))"
                    :key="`lock-${role}`"
                    class="muted"
                  >
                    {{ USER_ROLE_LABELS[role] }}：{{ roleLockReason(role) }}
                  </p>
                </fieldset>
                <div class="admin__toolbar">
                  <BaseButton
                    :disabled="busy"
                    @click="saveRoles"
                  >
                    {{ users.submitting ? '提交中…' : '保存角色' }}
                  </BaseButton>
                  <BaseButton @click="cancelEdit">
                    取消
                  </BaseButton>
                </div>
                <p class="muted">
                  提交是全量替换：勾到的那一组会整体覆盖该账号原有的角色，勾没勾都随请求发出。
                  界面只挡「一个角色都不勾」和「摘掉当前登录账号的管理员角色」这两条自锁，
                  其余判定由服务端给出；重复提交同一组值得到同一结果，
                  两个管理员同时写入时服务端后写覆盖，界面检测不到，因此这里不声称存在版本冲突。
                </p>
              </td>
            </tr>

            <tr
              v-if="confirmDisableId === row.userId"
              class="users-table__expanded"
            >
              <td colspan="6">
                <div class="admin-confirm">
                  <p class="error-text">
                    确认停用 {{ row.username }}？停用会即刻吊销该账号的全部刷新令牌家族，并把它的访问令牌
                    一并拒掉：该账号即刻失去登录能力，正在进行的问答也会在下一次请求时失败。
                    重新启用不会复活这些已吊销的凭据，对方必须重新登录。
                  </p>
                  <div class="admin__toolbar">
                    <BaseButton
                      variant="danger"
                      :disabled="users.submitting"
                      @click="submitStatus(row, 'DISABLED')"
                    >
                      {{ users.submitting ? '提交中…' : '确认停用' }}
                    </BaseButton>
                    <BaseButton
                      :disabled="users.submitting"
                      @click="cancelDisable"
                    >
                      取消
                    </BaseButton>
                  </div>
                </div>
              </td>
            </tr>
          </template>
        </tbody>
      </table>
    </div>
    <SkeletonBlock
      v-else-if="users.loading && !users.loaded"
      label="用户列表加载中"
    />
    <EmptyState
      v-else-if="users.loaded"
      :text="users.hasFilter ? EMPTY_STATE_TEXT.usersFiltered : EMPTY_STATE_TEXT.usersNone"
    />

    <BaseButton
      v-if="users.canLoadMore"
      @click="users.loadMore()"
    >
      加载更多
    </BaseButton>
    <p
      v-else-if="users.items.length > 0"
      class="muted"
    >
      本页已加载 {{ users.items.length }} 行。契约不返回总数，因此这个数字不是「一共有多少个账号」。
    </p>
  </BaseCard>
</template>
