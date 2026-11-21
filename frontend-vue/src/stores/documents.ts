import { defineStore } from 'pinia';
import {
  deleteDocument,
  getDocumentStatus,
  listDocuments,
  renameDocument,
  reindexDocument,
  replaceDocumentTags,
  retryDocumentParse,
} from '../api/documents';
import { ApiError } from '../api/errors';
import { newIdempotencyKey } from '../api/http';
import {
  DOCUMENT_QUERY_MAX_LENGTH,
  DOCUMENT_TAG_FILTER_MAX_LENGTH,
  DOCUMENT_TAG_MAX_LENGTH,
  DOCUMENT_TAGS_MAX,
  DOCUMENT_TITLE_MAX_LENGTH,
} from '../types/documents';
import type { DocumentListItemData, DocumentStatus, DocumentStatusData } from '../types/documents';
import { isStaleCursor, PAGE_LIMIT } from './failureFields';

/** 只在拿不到 ProblemDetail 时用（断网、超时）；服务端给了 detail 就原样转述。 */
const LIST_FAILURE_HINT = '文档列表加载失败，请稍后重试。';
const OPERATION_FAILURE_HINT = '这次操作未完成，请稍后重试。';
/** 详情没取到不影响列表本身，措辞不要把整页说成故障。 */
const STATUS_VIEW_FAILURE_HINT = '索引详情读取失败，列表仍可正常操作。';

/**
 * 三个过滤条件是 AND 关系；游标不编码过滤条件，因此任何条件变化都必须回到第一页。
 * 空串一律归一化为 null：契约上 `q` 与 `tag` 的 `minLength` 都是 1，发空串会 400 而不是「不过滤」。
 */
export interface DocumentsFilter {
  status: DocumentStatus | null;
  q: string | null;
  tag: string | null;
}

export interface DocumentsState {
  items: DocumentListItemData[];
  nextCursor: string | null;
  loading: boolean;
  loaded: boolean;
  filter: DocumentsFilter;
  /** 行操作串行化：同一时刻只允许一个在途写操作，按钮上的文档由它决定。 */
  busyId: string | null;
  /**
   * 按需拉取的状态视图。列表投影没有 `vectorRows`/`vectorConsistency`/`pendingIndexVersion`，
   * 这三项又正是「删了还能不能问到」「重建有没有在途」的唯一凭据，只能走状态接口单独取。
   */
  statusView: DocumentStatusData | null;
  statusViewId: string | null;
  statusLoading: boolean;
  /**
   * 第一页取数的代号：条件切换必须立刻反映新条件，不能被在途的旧条件请求挤掉，
   * 所以旧代号的响应一律丢弃，只有最新代号允许落地。
   */
  fetchSeq: number;
  detail: string | null;
  traceId: string | null;
}

const EMPTY_FILTER: DocumentsFilter = { status: null, q: null, tag: null };

function describeFailure(target: Pick<DocumentsState, 'detail' | 'traceId'>, error: unknown, fallback: string): void {
  if (error instanceof ApiError) {
    target.detail = error.detail;
    target.traceId = error.traceId;
    return;
  }
  target.detail = fallback;
  target.traceId = null;
}

/** 与服务端标签归一化口径一致：去首尾空白 → 连续空白折叠为单空格 → 小写。 */
function normalizeTag(raw: string): string {
  return raw.trim().replace(/\s+/g, ' ').toLowerCase();
}

/**
 * 本地预校验镜像契约 `DocumentTagsUpdateRequest` 的三条拒绝规则，越界时不浪费一次往返。
 * 归一化后重复要报错而不是静默去重：静默去重会让用户以为标签已经挂上，实际少了一个。
 */
function validateTags(tags: string[]): string | null {
  if (tags.length > DOCUMENT_TAGS_MAX) {
    return `最多 ${String(DOCUMENT_TAGS_MAX)} 个标签，超出的不会被保留。`;
  }
  const seen = new Set<string>();
  for (const raw of tags) {
    const normalized = normalizeTag(raw);
    if (normalized.length === 0) {
      return '标签不能是空白；不需要它就把它删掉。';
    }
    if (normalized.length > DOCUMENT_TAG_MAX_LENGTH) {
      return `单个标签不能超过 ${String(DOCUMENT_TAG_MAX_LENGTH)} 个字符。`;
    }
    if (seen.has(normalized)) {
      return `标签「${normalized}」重复，请合并后再提交。`;
    }
    seen.add(normalized);
  }
  return null;
}

/**
 * 状态视图比列表行更权威，但列表行的 `contentType` 与 `sizeBytes` 不在状态视图里，
 * 所以是「按共有字段回填这一行」，不是整行替换。
 */
function applyStatusToRow(row: DocumentListItemData, data: DocumentStatusData): DocumentListItemData {
  return {
    ...row,
    fileName: data.fileName,
    title: data.title,
    tags: [...data.tags],
    status: data.status,
    retryCount: data.retryCount,
    maxRetry: data.maxRetry,
    errorCode: data.errorCode,
    indexVersion: data.indexVersion,
    chunkCount: data.chunkCount,
    indexedAt: data.indexedAt,
    updatedAt: data.updatedAt,
  };
}

/** 越界值按截断处理而不是拒绝：搜索框里粘进长文本时，静默截断比报错更符合直觉。 */
function trimTo(value: string, max: number): string | null {
  const trimmed = value.trim();
  if (trimmed.length === 0) {
    return null;
  }
  return trimmed.slice(0, max);
}

export const useDocumentsStore = defineStore('documents', {
  state: (): DocumentsState => ({
    items: [],
    nextCursor: null,
    loading: false,
    loaded: false,
    filter: { ...EMPTY_FILTER },
    busyId: null,
    statusView: null,
    statusViewId: null,
    statusLoading: false,
    fetchSeq: 0,
    detail: null,
    traceId: null,
  }),

  getters: {
    canLoadMore: (state): boolean => !state.loading && state.nextCursor !== null,

    hasFilter: (state): boolean =>
      state.filter.status !== null || state.filter.q !== null || state.filter.tag !== null,
  },

  actions: {
    async loadFirst(): Promise<void> {
      if (this.loading) {
        return;
      }
      await this.fetchFirstPage();
    },

    /**
     * 取第一页，不受 `loading` 守卫约束：切换过滤条件时必须真正取回新条件的结果，
     * 让还在途的旧条件请求去挤掉它只会让列表和筛选栏各说各话。过期响应按代号丢弃。
     */
    async fetchFirstPage(): Promise<void> {
      const seq = ++this.fetchSeq;
      this.loading = true;
      try {
        const data = await listDocuments(this.pageQuery());
        if (seq !== this.fetchSeq) {
          return;
        }
        this.items = data.items;
        this.nextCursor = data.nextCursor;
        this.closeStatusView();
        this.detail = null;
        this.traceId = null;
      } catch (error) {
        if (seq !== this.fetchSeq) {
          return;
        }
        describeFailure(this, error, LIST_FAILURE_HINT);
      } finally {
        if (seq === this.fetchSeq) {
          this.loading = false;
          this.loaded = true;
        }
      }
    },

    async loadMore(): Promise<void> {
      const cursor = this.nextCursor;
      if (!this.canLoadMore || cursor === null) {
        return;
      }
      const seq = this.fetchSeq;
      this.loading = true;
      try {
        const data = await listDocuments({ ...this.pageQuery(), cursor });
        if (seq !== this.fetchSeq) {
          return;
        }
        this.items = [...this.items, ...data.items];
        this.nextCursor = data.nextCursor;
        this.detail = null;
        this.traceId = null;
      } catch (error) {
        if (seq !== this.fetchSeq) {
          return;
        }
        // keyset 游标由服务端签发，两次翻页之间排序键变化或游标被篡改都会 400；回到首页比停在坏游标上更诚实。
        if (isStaleCursor(error)) {
          this.nextCursor = null;
          this.loading = false;
          await this.fetchFirstPage();
          this.detail = '翻页游标已失效，列表已回到第一页。';
          return;
        }
        describeFailure(this, error, LIST_FAILURE_HINT);
      } finally {
        if (seq === this.fetchSeq) {
          this.loading = false;
        }
      }
    },

    pageQuery(): { status?: DocumentStatus; q?: string; tag?: string; limit: number } {
      return {
        status: this.filter.status ?? undefined,
        q: this.filter.q ?? undefined,
        tag: this.filter.tag ?? undefined,
        limit: PAGE_LIMIT,
      };
    },

    /** 三个条件合并提交；切换任何一个都清空已累积的页并回到第一页，即使上一页请求还在途。 */
    async setFilters(next: DocumentsFilter): Promise<void> {
      this.filter = {
        status: next.status,
        q: trimTo(next.q ?? '', DOCUMENT_QUERY_MAX_LENGTH),
        tag: trimTo(next.tag ?? '', DOCUMENT_TAG_FILTER_MAX_LENGTH),
      };
      this.items = [];
      this.nextCursor = null;
      await this.fetchFirstPage();
    },

    resetFilters(): Promise<void> {
      return this.setFilters({ ...EMPTY_FILTER });
    },

    /**
     * 五个写操作共用的执行壳：串行锁、每次调用一个新的幂等键、失败回显 ProblemDetail。
     * 返回 null 表示「没有发生」——被锁挡住或调用失败，两种情况都由 `detail`/`busyId` 说明。
     */
    async runDocumentOperation<T>(documentId: string, call: (idempotencyKey: string) => Promise<T>): Promise<T | null> {
      if (this.busyId !== null) {
        return null;
      }
      this.busyId = documentId;
      this.detail = null;
      this.traceId = null;
      try {
        return await call(newIdempotencyKey());
      } catch (error) {
        describeFailure(this, error, OPERATION_FAILURE_HINT);
        return null;
      } finally {
        this.busyId = null;
      }
    },

    /** 只改展示标题；空标题在服务端会 400，这里先拦下。 */
    async rename(documentId: string, rawTitle: string): Promise<boolean> {
      const title = rawTitle.trim();
      if (title.length === 0 || title.length > DOCUMENT_TITLE_MAX_LENGTH) {
        return this.rejectLocal(`标题不能为空，且不能超过 ${String(DOCUMENT_TITLE_MAX_LENGTH)} 个字符。`);
      }
      const data = await this.runDocumentOperation(documentId, (key) => renameDocument(documentId, title, key));
      if (data === null) {
        return false;
      }
      this.replaceRow(documentId, data);
      return true;
    },

    /** 整集替换语义：提交的就是最终标签全集，空数组即清空。 */
    async replaceTags(documentId: string, tags: string[]): Promise<boolean> {
      const violation = validateTags(tags);
      if (violation !== null) {
        return this.rejectLocal(violation);
      }
      const normalized = tags.map(normalizeTag);
      const data = await this.runDocumentOperation(documentId, (key) => replaceDocumentTags(documentId, normalized, key));
      if (data === null) {
        return false;
      }
      this.replaceRow(documentId, data);
      return true;
    },

    /** 204 只承诺 MySQL 侧已不可见，因此本地直接移除该行；向量侧的收敛不在本页可见范围内。 */
    async remove(documentId: string): Promise<boolean> {
      const deleted = await this.runDocumentOperation(documentId, async (key) => {
        await deleteDocument(documentId, key);
        return true as const;
      });
      if (deleted !== true) {
        return false;
      }
      this.items = this.items.filter((doc) => doc.documentId !== documentId);
      if (this.statusViewId === documentId) {
        this.closeStatusView();
      }
      return true;
    },

    /** 契约只允许 `FAILED`/`DEAD` 手动重试；对进行中的解析重试只会重复烧 embedding。 */
    async retryParse(documentId: string): Promise<boolean> {
      const row = this.findRow(documentId);
      if (row === null || (row.status !== 'FAILED' && row.status !== 'DEAD')) {
        return this.rejectLocal('只有解析失败或已进入死信的文档才能重试。');
      }
      const data = await this.runDocumentOperation(documentId, (key) => retryDocumentParse(documentId, key));
      if (data === null) {
        return false;
      }
      await this.settleAfterTransition(documentId, data);
      return true;
    },

    /**
     * 重建期间 `status` 保持 `INDEXED`，而 `pendingIndexVersion` 不在列表投影里，
     * 所以「已有重建在途」这一条前端判不了，交给服务端 409。
     */
    async reindex(documentId: string): Promise<boolean> {
      const row = this.findRow(documentId);
      if (row === null || row.status !== 'INDEXED') {
        return this.rejectLocal('只有已可检索的文档才能重建索引。');
      }
      const data = await this.runDocumentOperation(documentId, (key) => reindexDocument(documentId, key));
      if (data === null) {
        return false;
      }
      this.replaceRow(documentId, data);
      return true;
    },

    /** 展开/收起状态视图；同一文档重复点击不会重复发请求。 */
    async toggleStatusView(documentId: string): Promise<void> {
      if (this.statusViewId === documentId) {
        this.closeStatusView();
        return;
      }
      if (this.statusLoading) {
        return;
      }
      this.statusLoading = true;
      this.detail = null;
      this.traceId = null;
      try {
        this.statusView = await getDocumentStatus(documentId);
        this.statusViewId = documentId;
      } catch (error) {
        describeFailure(this, error, STATUS_VIEW_FAILURE_HINT);
        if (error instanceof ApiError && error.status === 404) {
          this.closeStatusView();
        }
      } finally {
        this.statusLoading = false;
      }
    },

    closeStatusView(): void {
      this.statusView = null;
      this.statusViewId = null;
      this.statusLoading = false;
    },

    findRow(documentId: string): DocumentListItemData | null {
      return this.items.find((doc) => doc.documentId === documentId) ?? null;
    },

    replaceRow(documentId: string, data: DocumentStatusData): void {
      this.items = this.items.map((doc) => (doc.documentId === documentId ? applyStatusToRow(doc, data) : doc));
      // 五个写操作返回的就是完整状态视图，面板恰好展开在这一行时不必再发一次 GET。
      if (this.statusViewId === documentId) {
        this.statusView = data;
      }
    },

    rejectLocal(detail: string): false {
      this.detail = detail;
      this.traceId = null;
      return false;
    },

    /**
     * 重试会把 `FAILED` 变成 `PARSING`：在状态过滤下回填就留下一行与筛选条件不符的记录，
     * 因此过滤生效时改为重新拉第一页，让这一行按服务端口径消失。
     * 这里不能走 `loadFirst`：写操作刚刚改变了服务端状态，在途的旧读请求可能早于它，被守卫挡掉就白跳了一次。
     */
    async settleAfterTransition(documentId: string, data: DocumentStatusData): Promise<void> {
      if (this.filter.status === null || this.filter.status === data.status) {
        this.replaceRow(documentId, data);
        return;
      }
      await this.fetchFirstPage();
    },
  },
});
