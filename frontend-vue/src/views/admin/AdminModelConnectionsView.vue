<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';

import ModelConnectionWizard from '../../components/admin/ModelConnectionWizard.vue';
import AppAlert from '../../components/ui/AppAlert.vue';
import BaseButton from '../../components/ui/BaseButton.vue';
import BaseCard from '../../components/ui/BaseCard.vue';
import EmptyState from '../../components/ui/EmptyState.vue';
import SkeletonBlock from '../../components/ui/SkeletonBlock.vue';
import { useAdminModelConnectionsStore } from '../../stores/adminModelConnections';
import type { ModelConnection } from '../../types/modelConnections';
import { EMPTY_STATE_TEXT, formatDateTime } from '../../utils/display';
import { hostOf, probeStatusLabel } from '../../utils/modelConnection';

const store = useAdminModelConnectionsStore();

const creating = ref(false);
/** 行内两步确认：只挡删除这一个不可逆方向，其余操作可直接执行。 */
const confirmingDelete = ref<string | null>(null);

const items = computed(() => store.items ?? []);
const active = computed(() => store.activeConnection);

function startCreate(): void {
  confirmingDelete.value = null;
  creating.value = true;
  void store.loadPresets();
}

function closeWizard(): void {
  creating.value = false;
}

async function onConnected(): Promise<void> {
  creating.value = false;
  await store.fetch();
}

async function refresh(): Promise<void> {
  confirmingDelete.value = null;
  await store.fetch();
}

async function toggleActive(connection: ModelConnection): Promise<void> {
  confirmingDelete.value = null;
  await store.setActive(connection.id, !connection.active);
}

async function toggleEnabled(connection: ModelConnection): Promise<void> {
  confirmingDelete.value = null;
  await store.setEnabled(connection, !connection.enabled);
}

async function reprobe(connection: ModelConnection): Promise<void> {
  confirmingDelete.value = null;
  await store.reprobe(connection.id);
}

function askDelete(connection: ModelConnection): void {
  confirmingDelete.value = connection.id;
}

async function confirmDelete(connection: ModelConnection): Promise<void> {
  confirmingDelete.value = null;
  await store.remove(connection.id);
}

onMounted(() => {
  void store.load();
});
</script>

<template>
  <BaseCard
    title="模型连接"
    :level="1"
  >
    <details class="page-note">
      <summary>这些连接怎么生效</summary>
      <p>
        每条连接是一个 OpenAI 兼容端点加一个模型名。API Key 以 AES-256-GCM 密文存储，
        绑定到这条连接与本租户，保存后不再回显；本页只显示「已配置 / 未配置」。
        设为生效后，网关把密文发布给数据面，数据面在每次问答时解密并调用它——
        <strong>切换模型不需要重启任何服务</strong>。没有生效连接时，问答回退到部署配置的模型。
      </p>
      <p>
        每租户至多一条生效连接，由数据库唯一键保证。删除连接会一并删除其密文，不留副本。
      </p>
    </details>

    <div class="admin__toolbar">
      <BaseButton
        :disabled="store.loading"
        @click="refresh"
      >
        {{ store.loading ? '读取中…' : '重新读取' }}
      </BaseButton>
      <BaseButton
        :disabled="store.submitting || creating"
        @click="startCreate"
      >
        新建连接
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
      label="模型连接加载中"
    />
  </BaseCard>

  <BaseCard
    v-if="creating"
    title="新建模型连接"
  >
    <ModelConnectionWizard
      :presets="store.presets"
      :presets-loading="store.presetsLoading"
      @connected="onConnected"
      @cancel="closeWizard"
    />
  </BaseCard>

  <BaseCard
    v-if="store.loaded"
    title="连接清单"
  >
    <p
      v-if="active"
      class="muted"
    >
      当前生效：{{ active.name }}（{{ active.model }}）。问答立即使用它。
    </p>
    <p
      v-else
      class="muted"
    >
      当前没有生效连接，问答使用部署配置的模型。
    </p>

    <EmptyState
      v-if="items.length === 0"
      :text="EMPTY_STATE_TEXT.modelConnectionsNone"
    />

    <div
      v-else
      class="table-scroll"
    >
      <table class="model-connection-table">
        <caption>本租户的模型连接；凭据列只表达是否已配置，不显示任何密钥材料</caption>
        <thead>
          <tr>
            <th scope="col">
              名称
            </th>
            <th scope="col">
              模型
            </th>
            <th scope="col">
              端点主机
            </th>
            <th scope="col">
              凭据
            </th>
            <th scope="col">
              探测
            </th>
            <th scope="col">
              状态
            </th>
            <th scope="col">
              操作
            </th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="connection in items"
            :key="connection.id"
          >
            <td>{{ connection.name }}</td>
            <td class="mono-id">
              {{ connection.model }}
            </td>
            <td class="mono-id">
              {{ hostOf(connection.baseUrl) }}
            </td>
            <td>{{ connection.hasCredential ? '已配置' : '未配置' }}</td>
            <td>
              {{ probeStatusLabel(connection.probeStatus) }}
              <span class="muted">{{ formatDateTime(connection.lastProbedAt) }}</span>
            </td>
            <td>
              <span
                v-if="connection.active"
                class="governance__chip governance__chip--ok"
              >生效中</span>
              <span
                v-else-if="!connection.enabled"
                class="governance__chip"
              >已停用</span>
              <span
                v-else
                class="governance__chip"
              >备用</span>
            </td>
            <td>
              <div class="admin__toolbar">
                <BaseButton
                  :disabled="store.submitting || !connection.enabled"
                  @click="toggleActive(connection)"
                >
                  {{ connection.active ? '取消生效' : '设为生效' }}
                </BaseButton>
                <BaseButton
                  variant="link"
                  :disabled="store.submitting"
                  @click="reprobe(connection)"
                >
                  重新探测
                </BaseButton>
                <BaseButton
                  variant="link"
                  :disabled="store.submitting"
                  @click="toggleEnabled(connection)"
                >
                  {{ connection.enabled ? '停用' : '启用' }}
                </BaseButton>
                <BaseButton
                  v-if="confirmingDelete !== connection.id"
                  variant="danger"
                  :disabled="store.submitting"
                  @click="askDelete(connection)"
                >
                  删除
                </BaseButton>
              </div>
              <div
                v-if="confirmingDelete === connection.id"
                class="admin-confirm"
              >
                <p class="error-text">
                  确认删除「{{ connection.name }}」？其 API Key 密文会一并删除且不可恢复；
                  若它是生效连接，问答将回退到部署配置的模型。
                </p>
                <div class="admin__toolbar">
                  <BaseButton
                    variant="danger"
                    :disabled="store.submitting"
                    @click="confirmDelete(connection)"
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
      写操作按值幂等：重复提交同一个值得到同一状态，每次调用都会追加一条管理审计事件。
      两个管理员同时写入时服务端是后写覆盖，界面检测不到这种覆盖。
    </p>
  </BaseCard>
</template>
