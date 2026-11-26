<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import AppAlert from '../components/ui/AppAlert.vue';
import BaseButton from '../components/ui/BaseButton.vue';
import BaseCard from '../components/ui/BaseCard.vue';
import BaseField from '../components/ui/BaseField.vue';
import EmptyState from '../components/ui/EmptyState.vue';
import SkeletonBlock from '../components/ui/SkeletonBlock.vue';
import UploadPanel from '../components/UploadPanel.vue';
import { useDocumentsStore } from '../stores/documents';
import { useUploadsStore } from '../stores/uploads';
import { DOCUMENT_STATUSES, DOCUMENT_TITLE_MAX_LENGTH } from '../types/documents';
import type { DocumentListItemData, DocumentStatus } from '../types/documents';
import { EMPTY_STATE_TEXT, STATUS_LABELS, VECTOR_CONSISTENCY_LABELS, formatBytes, formatDateTime } from '../utils/display';

const documents = useDocumentsStore();
const uploads = useUploadsStore();

const statusValue = ref('');
const queryValue = ref('');
const tagValue = ref('');

/** 编辑草稿是视图本地状态：Store 只认服务端返回，不把「还没提交的想法」写进状态里。 */
const editingId = ref<string | null>(null);
const titleDraft = ref('');
const tagsDraft = ref('');
const pendingDeleteId = ref<string | null>(null);

/** store 的串行锁锁的是整页，不只是被点的那一行：在途期间所有写操作按钮一起禁用。 */
const idle = computed(() => documents.busyId === null);

/** 下拉框的值来自字符串，必须按枚举校验后才作为过滤条件发出去，非法值等价于「全部」。 */
function parseFilter(value: string): DocumentStatus | null {
  return DOCUMENT_STATUSES.find((status) => status === value) ?? null;
}

async function reload(): Promise<void> {
  await documents.loadFirst();
}

async function applyFilters(): Promise<void> {
  await documents.setFilters({
    status: parseFilter(statusValue.value),
    q: queryValue.value,
    tag: tagValue.value,
  });
  editingId.value = null;
  pendingDeleteId.value = null;
}

async function clearFilters(): Promise<void> {
  statusValue.value = '';
  queryValue.value = '';
  tagValue.value = '';
  await applyFilters();
}

function startEdit(doc: DocumentListItemData): void {
  editingId.value = doc.documentId;
  titleDraft.value = doc.title ?? doc.fileName;
  tagsDraft.value = doc.tags.join(', ');
}

function cancelEdit(): void {
  editingId.value = null;
}

/** 逗号（含全角）分隔的标签草稿；空白项直接丢掉，交给 store 做归一化与重复校验。 */
function parseTags(raw: string): string[] {
  return raw
    .split(/[,，]/)
    .map((tag) => tag.trim())
    .filter((tag) => tag.length > 0);
}

async function saveTitle(doc: DocumentListItemData): Promise<void> {
  if (await documents.rename(doc.documentId, titleDraft.value)) {
    editingId.value = null;
  }
}

async function saveTags(doc: DocumentListItemData): Promise<void> {
  if (await documents.replaceTags(doc.documentId, parseTags(tagsDraft.value))) {
    editingId.value = null;
  }
}

/** 软删除不可从本页撤销，因此确认语要把代价说清楚：向量侧也会一并删除。 */
function askDelete(doc: DocumentListItemData): void {
  pendingDeleteId.value = doc.documentId;
}

async function confirmDelete(doc: DocumentListItemData): Promise<void> {
  await documents.remove(doc.documentId);
  pendingDeleteId.value = null;
}

function cancelDelete(): void {
  pendingDeleteId.value = null;
}

function isBusy(doc: DocumentListItemData): boolean {
  return documents.busyId === doc.documentId;
}

onMounted(() => {
  void reload();
});

// 离开页面后不允许再有定时器打状态接口，在途分片也一并取消。
onBeforeUnmount(() => {
  uploads.cancelAll();
});

// 有文档到达 INDEXED（计数上升）就刷新首页；下降来自 clearFinished/删除，
// 不该把翻到第 3 页的用户拉回第 1 页并发起多余整页重取（C-10）。
watch(() => uploads.indexedCount, (n, o) => {
  if (n > o) {
    void reload();
  }
});
</script>

<template>
  <div class="ink-documents">
  <header class="ink-section-head ink-reveal">
    <span class="ink-section-head__no">叁</span>
    <h2>文档架</h2>
    <p class="ink-section-head__aside">每一份资料都有状态、索引版本与出处；书脊可悬停翻检。</p>
  </header>

  <BaseCard
    title="文档"
    :level="1"
    class="ink-reveal"
  >
    <UploadPanel />
  </BaseCard>

  <BaseCard
    title="文档列表"
    class="document-list"
  >
    <div class="document-list__toolbar">
      <BaseField label="状态筛选">
        <select
          v-model="statusValue"
          @change="applyFilters"
        >
          <option value="">
            全部
          </option>
          <option
            v-for="status in DOCUMENT_STATUSES"
            :key="status"
            :value="status"
          >
            {{ STATUS_LABELS[status] }}
          </option>
        </select>
      </BaseField>
      <BaseField label="搜索文件名或标题">
        <input
          v-model="queryValue"
          type="search"
          placeholder="不检索正文，只按元数据"
          @keyup.enter="applyFilters"
        >
      </BaseField>
      <BaseField label="按标签">
        <input
          v-model="tagValue"
          type="text"
          placeholder="单个标签名"
          @keyup.enter="applyFilters"
        >
      </BaseField>
      <BaseButton
        :disabled="documents.loading"
        @click="applyFilters"
      >
        查询
      </BaseButton>
      <BaseButton
        :disabled="documents.loading"
        @click="clearFilters"
      >
        清空条件
      </BaseButton>
      <BaseButton
        :disabled="documents.loading"
        @click="reload"
      >
        {{ documents.loading ? '加载中…' : '刷新' }}
      </BaseButton>
    </div>

    <AppAlert
      :detail="documents.detail"
      :trace-id="documents.traceId"
    />

    <div
      v-if="documents.items.length > 0"
      class="table-scroll"
    >
      <table class="document-table">
        <caption>已上传文档的解析状态、索引版本与本页可执行的操作。</caption>
        <thead>
          <tr>
            <th scope="col">
              文档
            </th>
            <th scope="col">
              大小
            </th>
            <th scope="col">
              状态
            </th>
            <th scope="col">
              分块
            </th>
            <th scope="col">
              标签
            </th>
            <th scope="col">
              索引版本
            </th>
            <th scope="col">
              创建时间
            </th>
            <th scope="col">
              操作
            </th>
          </tr>
        </thead>
        <tbody>
          <template
            v-for="doc in documents.items"
            :key="doc.documentId"
          >
            <tr>
              <!-- 标题与文件名都是用户可控文本，只做文本插值。 -->
              <td>
                {{ doc.title ?? doc.fileName }}
                <span
                  v-if="doc.title !== null"
                  class="muted"
                >{{ doc.fileName }}</span>
              </td>
              <td class="document-table__size">
                {{ formatBytes(doc.sizeBytes) }}
              </td>
              <td>
                <!-- 状态是这张表唯一需要「一眼分辨」的列：data-status 只驱动颜色与状态点，文案仍取自 display.ts。 -->
                <span
                  class="doc-status"
                  :data-status="doc.status"
                >{{ STATUS_LABELS[doc.status] }}</span>
                <span
                  v-if="doc.errorCode !== null"
                  class="muted"
                >{{ doc.errorCode }}</span>
              </td>
              <td>{{ doc.chunkCount }}</td>
              <td>
                <span
                  v-for="tag in doc.tags"
                  :key="tag"
                  class="governance__chip"
                >{{ tag }}</span>
                <span
                  v-if="doc.tags.length === 0"
                  class="muted"
                >—</span>
              </td>
              <td>{{ doc.indexVersion }}</td>
              <td>{{ formatDateTime(doc.createdAt) }}</td>
              <td class="document-table__actions">
                <BaseButton @click="documents.toggleStatusView(doc.documentId)">
                  {{ documents.statusViewId === doc.documentId ? '收起详情' : '详情' }}
                </BaseButton>
                <BaseButton
                  :disabled="!idle"
                  @click="startEdit(doc)"
                >
                  编辑
                </BaseButton>
                <BaseButton
                  v-if="doc.status === 'FAILED' || doc.status === 'DEAD'"
                  :disabled="!idle"
                  @click="documents.retryParse(doc.documentId)"
                >
                  {{ isBusy(doc) ? '处理中…' : '重试解析' }}
                </BaseButton>
                <BaseButton
                  v-if="doc.status === 'INDEXED'"
                  :disabled="!idle"
                  @click="documents.reindex(doc.documentId)"
                >
                  {{ isBusy(doc) ? '处理中…' : '重建索引' }}
                </BaseButton>
                <BaseButton
                  variant="danger"
                  :disabled="!idle"
                  @click="askDelete(doc)"
                >
                  删除
                </BaseButton>
              </td>
            </tr>

            <tr
              v-if="documents.statusViewId === doc.documentId"
              class="document-table__expanded"
            >
              <td colspan="8">
                <SkeletonBlock
                  v-if="documents.statusLoading"
                  label="索引详情加载中"
                  :rows="4"
                />
                <dl
                  v-else-if="documents.statusView !== null"
                  class="document-table__detail"
                >
                  <div>
                    <dt>状态</dt>
                    <dd>{{ STATUS_LABELS[documents.statusView.status] }}（重试 {{ documents.statusView.retryCount }}/{{ documents.statusView.maxRetry }}）</dd>
                  </div>
                  <div>
                    <dt>当前索引版本</dt>
                    <dd>{{ documents.statusView.indexVersion }}</dd>
                  </div>
                  <div>
                    <dt>重建在途版本</dt>
                    <dd>{{ documents.statusView.pendingIndexVersion ?? '无' }}</dd>
                  </div>
                  <div>
                    <dt>MySQL 分块 / 向量行数</dt>
                    <dd>{{ documents.statusView.chunkCount }} / {{ documents.statusView.vectorRows ?? '未确认' }}</dd>
                  </div>
                  <div>
                    <dt>行数对照</dt>
                    <dd>{{ VECTOR_CONSISTENCY_LABELS[documents.statusView.vectorConsistency] }}</dd>
                  </div>
                  <div>
                    <dt>向量确认时刻</dt>
                    <dd>{{ formatDateTime(documents.statusView.vectorConfirmedAt) }}</dd>
                  </div>
                  <div>
                    <dt>可检索时刻</dt>
                    <dd>{{ formatDateTime(documents.statusView.indexedAt) }}</dd>
                  </div>
                  <div v-if="documents.statusView.errorCode !== null">
                    <dt>错误</dt>
                    <dd>
                      {{ documents.statusView.errorCode }}
                      <span class="muted">{{ documents.statusView.errorMessage ?? '' }}</span>
                    </dd>
                  </div>
                </dl>
                <p class="muted">
                  行数对照是「最后一次确认」的结果，不是跨库实时比对；删除的即时事实是本行已从列表消失。
                </p>
              </td>
            </tr>

            <tr
              v-if="editingId === doc.documentId"
              class="document-table__expanded"
            >
              <td colspan="8">
                <div class="document-list__toolbar">
                  <BaseField label="展示标题">
                    <input
                      v-model="titleDraft"
                      type="text"
                      :maxlength="DOCUMENT_TITLE_MAX_LENGTH"
                    >
                  </BaseField>
                  <BaseButton
                    :disabled="!idle"
                    @click="saveTitle(doc)"
                  >
                    保存标题
                  </BaseButton>
                  <BaseField label="标签（逗号分隔，整集替换）">
                    <input
                      v-model="tagsDraft"
                      type="text"
                    >
                  </BaseField>
                  <BaseButton
                    :disabled="!idle"
                    @click="saveTags(doc)"
                  >
                    保存标签
                  </BaseButton>
                  <BaseButton @click="cancelEdit">
                    取消
                  </BaseButton>
                </div>
                <p class="muted">
                  标题不超过 120 字；标签最多 20 个、单个不超过 24 字，提交后按归一化结果去重校验。
                </p>
              </td>
            </tr>

            <tr
              v-if="pendingDeleteId === doc.documentId"
              class="document-table__expanded"
            >
              <td colspan="8">
                <p class="error-text">
                  确认删除「{{ doc.title ?? doc.fileName }}」？删除后该文档立即从可检索范围消失，
                  其向量也会异步删除，本期不提供撤销入口。
                </p>
                <BaseButton
                  variant="danger"
                  :disabled="!idle"
                  @click="confirmDelete(doc)"
                >
                  {{ isBusy(doc) ? '处理中…' : '确认删除' }}
                </BaseButton>
                <BaseButton @click="cancelDelete">
                  取消
                </BaseButton>
              </td>
            </tr>
          </template>
        </tbody>
      </table>
    </div>
    <SkeletonBlock
      v-else-if="documents.loading && !documents.loaded"
      label="文档列表加载中"
    />
    <EmptyState
      v-else-if="documents.loaded"
      :text="documents.hasFilter ? EMPTY_STATE_TEXT.documentsFiltered : EMPTY_STATE_TEXT.documentsNone"
    />

    <BaseButton
      v-if="documents.canLoadMore"
      @click="documents.loadMore()"
    >
      加载更多
    </BaseButton>
    <p
      v-else-if="documents.items.length > 0"
      class="muted"
    >
      已显示全部 {{ documents.items.length }} 条。
    </p>
  </BaseCard>
  </div>
</template>
